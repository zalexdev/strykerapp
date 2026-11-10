package com.zalexdev.stryker.hid.configfs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zalexdev.stryker.hid.capability.HidCapabilities;
import com.zalexdev.stryker.hid.capability.HidCapabilityProbe;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class UsbGadgetController {

    public static final String GADGET_NAME = "stryker";
    public static final String GADGET_BASE = "/config/usb_gadget/" + GADGET_NAME;
    private static final String VENDOR_GADGET = "/config/usb_gadget/g1";

    // Android init USB property triggers. Standard across AOSP regardless of the
    // SoC-specific init.*.usb.*.rc file that actually carries them. Setting both
    // to 0 stops init from re-binding the vendor gadget to the controller right
    // after adbd restarts (see Bug 2 in the USB Arsenal report).
    private static final String PROP_SYS_CONFIGFS = "sys.usb.configfs";
    private static final String PROP_VENDOR_CONFIGFS = "vendor.usb.configfs";
    private static final String PROP_ADB_TCP_PORT = "service.adb.tcp.port";
    private static final String ADB_TCP_PORT = "5555";

    // Opt-in: keep ADB reachable over Wi-Fi while our gadget owns the single UDC.
    // There is only one UDC, so USB-ADB (the vendor gadget) cannot coexist with
    // the Stryker gadget; this is off by default to avoid silently opening an
    // unauthenticated network ADB port.
    public static final String PREF_KEEP_ADB_TCP = "arsenal_keep_adb_tcp";
    private static final String PREF_SAVED_SYS_CONFIGFS = "arsenal_saved_sys_configfs";
    private static final String PREF_SAVED_VENDOR_CONFIGFS = "arsenal_saved_vendor_configfs";

    private static final String KEYBOARD_DESC_HEX =
            "05010906a101050719e029e71500250175019508810295017508810195057501050819012905910295017503910195067508150025650507190029658100c0";

    private static final String MOUSE_DESC_HEX =
            "05010902a1010901a10005091901290315002501950375018102950175058103050109300931093815018127950375088106c0c0";

    private final Core core;
    private final HidCapabilityProbe probe;

    public UsbGadgetController(Context context) {
        this.core = new Core(context);
        this.probe = new HidCapabilityProbe(context);
    }

    public HidCapabilities capabilities() {
        return probe.probe();
    }

    @NonNull
    public GadgetState readState() {
        boolean exists = pathExists(GADGET_BASE);
        String udc = read(GADGET_BASE + "/UDC").trim();
        boolean bound = exists && !udc.isEmpty();
        Set<GadgetFunction> linked = EnumSet.noneOf(GadgetFunction.class);
        if (exists) {
            for (GadgetFunction f : GadgetFunction.values()) {
                if (pathExists(GADGET_BASE + "/configs/c.1/" + f.functionDir)) {
                    linked.add(f);
                }
            }
        }
        String msFile = exists && linked.contains(GadgetFunction.MASS_STORAGE)
                ? read(GADGET_BASE + "/functions/mass_storage.0/lun.0/file").trim()
                : null;
        boolean hidNode = (linked.contains(GadgetFunction.HID_KEYBOARD)
                || linked.contains(GadgetFunction.HID_MOUSE))
                && pathExists("/dev/hidg0");
        return new GadgetState(exists, bound, bound ? udc : null, linked, msFile, hidNode);
    }

    public boolean prepare(@NonNull GadgetProfile profile) {
        if (!core.checkRoot()) return false;
        List<String> script = new ArrayList<>();
        script.add("set -e");
        script.add("mkdir -p " + sq(GADGET_BASE));
        script.add("mkdir -p " + sq(GADGET_BASE + "/configs/c.1"));
        script.add("mkdir -p " + sq(GADGET_BASE + "/configs/c.1/strings/0x409"));
        script.add("mkdir -p " + sq(GADGET_BASE + "/strings/0x409"));
        script.add("echo " + sq(profile.idVendor) + " > " + sq(GADGET_BASE + "/idVendor"));
        script.add("echo " + sq(profile.idProduct) + " > " + sq(GADGET_BASE + "/idProduct"));
        script.add("echo 0x0200 > " + sq(GADGET_BASE + "/bcdUSB"));
        script.add("echo " + sq(profile.manufacturer) + " > " + sq(GADGET_BASE + "/strings/0x409/manufacturer"));
        script.add("echo " + sq(profile.productName) + " > " + sq(GADGET_BASE + "/strings/0x409/product"));
        script.add("echo " + sq(profile.serialNumber) + " > " + sq(GADGET_BASE + "/strings/0x409/serialnumber"));
        script.add("echo " + sq(profile.configurationLabel) + " > "
                + sq(GADGET_BASE + "/configs/c.1/strings/0x409/configuration"));
        script.add("echo 120 > " + sq(GADGET_BASE + "/configs/c.1/MaxPower"));

        if (profile.functions.contains(GadgetFunction.HID_KEYBOARD)) {
            script.addAll(prepareHidKeyboard());
        }
        if (profile.functions.contains(GadgetFunction.HID_MOUSE)) {
            script.addAll(prepareHidMouse());
        }
        if (profile.functions.contains(GadgetFunction.MASS_STORAGE)) {
            script.addAll(prepareMassStorage(profile.massStorageImage,
                    profile.massStorageReadOnly, profile.massStorageCdrom,
                    profile.inquiryString));
        }
        if (profile.functions.contains(GadgetFunction.RNDIS)) {
            script.addAll(prepareRndis());
        }
        if (profile.functions.contains(GadgetFunction.ACM)) {
            script.addAll(prepareAcm());
        }
        if (profile.functions.contains(GadgetFunction.ECM)) {
            script.addAll(prepareEcm());
        }

        for (GadgetFunction f : profile.functions) {
            String link = GADGET_BASE + "/configs/c.1/" + f.functionDir;
            script.add("[ -e " + sq(link) + " ] || ln -s "
                    + sq(GADGET_BASE + "/functions/" + f.functionDir) + " " + sq(link));
        }

        return run(script);
    }

    @Nullable
    public String bind() {
        String udc = capabilities().udcName;
        if (udc == null) return null;

        // Remember the current trigger-property values so "Restore vendor" can
        // put them back exactly as they were.
        rememberOriginalTriggerState();

        List<String> script = new ArrayList<>();
        // 1. Freeze Android's USB property triggers BEFORE touching adbd, so init
        //    cannot re-bind the vendor gadget to the controller once adbd comes
        //    back and sets sys.usb.ffs.ready=1 (Bug 2). vendor.usb.configfs must
        //    go first, otherwise the init counter-trigger resets sys.usb.configfs.
        script.add("setprop " + PROP_VENDOR_CONFIGFS + " 0");
        script.add("setprop " + PROP_SYS_CONFIGFS + " 0");
        // 2. Stop adbd so it releases the ffs.adb file descriptor that pins the
        //    vendor gadget; without this, unbinding g1 fails with EBUSY (Bug 1).
        script.add("stop adbd 2>/dev/null || true");
        script.add("sleep 1");
        // 3. Unbind the vendor gadget (now succeeds) then bind ours to the UDC.
        script.add("if [ -s " + sq(VENDOR_GADGET + "/UDC") + " ]; then "
                + "echo > " + sq(VENDOR_GADGET + "/UDC") + " || true; fi");
        script.add("sleep 0.3");
        script.add("echo " + sq(udc) + " > " + sq(GADGET_BASE + "/UDC") + " || true");
        script.add("sleep 0.5");
        // 4. Optionally keep ADB reachable over Wi-Fi (opt-in, default off). The
        //    single UDC means USB-ADB cannot coexist with our gadget.
        if (core.getBoolean(PREF_KEEP_ADB_TCP)) {
            script.add("setprop " + PROP_ADB_TCP_PORT + " " + ADB_TCP_PORT);
            script.add("start adbd 2>/dev/null || true");
        }
        run(script);

        // Authoritative success check: the vendor-gadget unbind and our bind are
        // best-effort (|| true) so the shell never aborts mid-switch, which means
        // the shell's exit status says nothing. Read back the UDC and only report
        // success when our gadget is actually attached to the controller.
        String actual = read(GADGET_BASE + "/UDC").trim();
        if (!actual.isEmpty()) {
            chmodHidNodes();
            return actual;
        }
        return null;
    }

    public boolean unbind() {
        List<String> script = new ArrayList<>();
        // Detach our gadget (no ffs fd pins it, so this always succeeds).
        script.add("if [ -d " + sq(GADGET_BASE) + " ]; then "
                + "echo > " + sq(GADGET_BASE + "/UDC") + " || true; fi");
        script.add("sleep 0.3");
        // Thaw the init triggers we froze during bind so Android can manage USB
        // again, restoring their original values (default 1 when never captured).
        script.add("setprop " + PROP_SYS_CONFIGFS + " " + sq(savedTriggerValue(PREF_SAVED_SYS_CONFIGFS)));
        script.add("setprop " + PROP_VENDOR_CONFIGFS + " " + sq(savedTriggerValue(PREF_SAVED_VENDOR_CONFIGFS)));
        // Re-bind the vendor gadget as a fallback for devices whose init triggers
        // do not fire on their own, then bring adbd back for USB ADB.
        String udc = capabilities().udcName;
        if (udc != null) {
            script.add("if [ -s " + sq(VENDOR_GADGET + "/UDC") + " ]; then : ; "
                    + "elif [ -d " + sq(VENDOR_GADGET) + " ]; then "
                    + "echo " + sq(udc) + " > " + sq(VENDOR_GADGET + "/UDC") + " || true; fi");
        }
        script.add("start adbd 2>/dev/null || true");
        return run(script);
    }

    private void rememberOriginalTriggerState() {
        rememberTrigger(PREF_SAVED_SYS_CONFIGFS, getprop(PROP_SYS_CONFIGFS));
        rememberTrigger(PREF_SAVED_VENDOR_CONFIGFS, getprop(PROP_VENDOR_CONFIGFS));
    }

    private void rememberTrigger(String prefKey, String current) {
        // Never persist our own frozen "0" as the original; otherwise a second
        // Apply before a Restore would make us "restore" the frozen value. A real
        // value (normally "1") is captured; "0"/blank keeps the prior original.
        if (current != null && !current.isEmpty() && !current.equals("0")) {
            core.putString(prefKey, current);
        }
    }

    private String savedTriggerValue(String prefKey) {
        String v = core.getString(prefKey);
        return (v == null || v.isEmpty()) ? "1" : v;
    }

    private String getprop(String name) {
        List<String> out = core.customCommand("getprop " + sq(name), true);
        if (out != null) {
            for (String line : out) {
                if (line != null && !line.trim().isEmpty()) return line.trim();
            }
        }
        return "";
    }

    public boolean teardown() {
        List<String> script = new ArrayList<>();
        script.add("if [ -d " + sq(GADGET_BASE) + " ]; then");
        script.add("  echo > " + sq(GADGET_BASE + "/UDC") + " 2>/dev/null || true");
        script.add("  find " + sq(GADGET_BASE + "/configs/c.1") + " -maxdepth 1 -type l -delete 2>/dev/null || true");
        script.add("  rmdir " + sq(GADGET_BASE + "/configs/c.1/strings/0x409") + " 2>/dev/null || true");
        script.add("  rmdir " + sq(GADGET_BASE + "/configs/c.1") + " 2>/dev/null || true");
        script.add("  for f in " + sq(GADGET_BASE + "/functions") + "/*; do rmdir \"$f\" 2>/dev/null || true; done");
        script.add("  rmdir " + sq(GADGET_BASE + "/strings/0x409") + " 2>/dev/null || true");
        script.add("  rmdir " + sq(GADGET_BASE) + " 2>/dev/null || true");
        script.add("fi");
        return run(script);
    }

    public boolean apply(@NonNull GadgetProfile profile) {
        if (!prepare(profile)) {
            teardown();
            return false;
        }
        String udc = bind();
        if (udc == null) {
            unbind();
            return false;
        }
        return true;
    }

    public boolean swapMassStorageImage(String absoluteImagePath,
                                        boolean readOnly,
                                        boolean cdrom) {
        if (!pathExists(GADGET_BASE + "/functions/mass_storage.0/lun.0")) return false;
        List<String> script = new ArrayList<>();
        script.add("echo " + (readOnly ? "1" : "0") + " > "
                + sq(GADGET_BASE + "/functions/mass_storage.0/lun.0/ro"));
        script.add("echo " + (cdrom ? "1" : "0") + " > "
                + sq(GADGET_BASE + "/functions/mass_storage.0/lun.0/cdrom"));
        script.add("echo " + sq(absoluteImagePath == null ? "" : absoluteImagePath)
                + " > " + sq(GADGET_BASE + "/functions/mass_storage.0/lun.0/file"));
        return run(script);
    }

    public void chmodHidNodes() {
        core.customCommand("chmod 0666 /dev/hidg0 /dev/hidg1 2>/dev/null", true);
    }

    private List<String> prepareHidKeyboard() {
        List<String> s = new ArrayList<>();
        String dir = GADGET_BASE + "/functions/hid.keyboard";
        s.add("mkdir -p " + sq(dir));
        s.add("echo 1 > " + sq(dir + "/protocol"));
        s.add("echo 1 > " + sq(dir + "/subclass"));
        s.add("echo 8 > " + sq(dir + "/report_length"));
        s.add("printf '" + hexToEscaped(KEYBOARD_DESC_HEX) + "' > " + sq(dir + "/report_desc"));
        return s;
    }

    private List<String> prepareHidMouse() {
        List<String> s = new ArrayList<>();
        String dir = GADGET_BASE + "/functions/hid.mouse";
        s.add("mkdir -p " + sq(dir));
        s.add("echo 2 > " + sq(dir + "/protocol"));
        s.add("echo 1 > " + sq(dir + "/subclass"));
        s.add("echo 4 > " + sq(dir + "/report_length"));
        s.add("printf '" + hexToEscaped(MOUSE_DESC_HEX) + "' > " + sq(dir + "/report_desc"));
        return s;
    }

    private List<String> prepareMassStorage(@Nullable String image,
                                            boolean readOnly,
                                            boolean cdrom,
                                            @Nullable String inquiry) {
        List<String> s = new ArrayList<>();
        String dir = GADGET_BASE + "/functions/mass_storage.0";
        s.add("mkdir -p " + sq(dir));
        s.add("echo 1 > " + sq(dir + "/stall"));
        s.add("echo " + (cdrom ? "1" : "0") + " > " + sq(dir + "/lun.0/cdrom"));
        s.add("echo " + (readOnly ? "1" : "0") + " > " + sq(dir + "/lun.0/ro"));
        s.add("echo 0 > " + sq(dir + "/lun.0/nofua"));
        s.add("echo 0 > " + sq(dir + "/lun.0/removable"));
        if (image != null && !image.isEmpty()) {
            s.add("echo " + sq(image) + " > " + sq(dir + "/lun.0/file"));
        }
        if (inquiry != null && !inquiry.isEmpty()) {
            s.add("echo " + sq(inquiry) + " > " + sq(dir + "/lun.0/inquiry_string"));
        }
        return s;
    }

    private List<String> prepareRndis() {
        List<String> s = new ArrayList<>();
        String dir = GADGET_BASE + "/functions/rndis.usb0";
        s.add("mkdir -p " + sq(dir));
        s.add("echo RNDIS > " + sq(dir + "/os_desc/interface.rndis/compatible_id") + " 2>/dev/null || true");
        s.add("echo 5162001 > " + sq(dir + "/os_desc/interface.rndis/sub_compatible_id") + " 2>/dev/null || true");
        return s;
    }

    private List<String> prepareAcm() {
        return java.util.Collections.singletonList(
                "mkdir -p " + sq(GADGET_BASE + "/functions/acm.0"));
    }

    private List<String> prepareEcm() {
        return java.util.Collections.singletonList(
                "mkdir -p " + sq(GADGET_BASE + "/functions/ecm.usb0"));
    }

    private boolean run(List<String> commands) {
        StringBuilder sb = new StringBuilder();
        for (String c : commands) {
            sb.append(c).append('\n');
        }
        sb.append("echo __STRYKER_OK__\n");
        List<String> out = core.customCommand(sb.toString(), true);
        for (String line : out) {
            if (line != null && line.contains("__STRYKER_OK__")) return true;
        }
        return false;
    }

    private boolean pathExists(String path) {
        return contains(core.customCommand(
                "[ -e " + sq(path) + " ] && echo 1 || echo 0", true), "1");
    }

    private String read(String path) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = core.customCommand("cat " + sq(path) + " 2>/dev/null", true);
        for (String line : lines) {
            if (line == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean contains(List<String> list, String needle) {
        if (list == null) return false;
        for (String s : list) {
            if (s != null && s.contains(needle)) return true;
        }
        return false;
    }

    private static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String hexToEscaped(String hex) {
        StringBuilder sb = new StringBuilder(hex.length() * 2);
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            sb.append("\\x").append(hex, i, i + 2);
        }
        return sb.toString();
    }
}
