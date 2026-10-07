package com.zalexdev.stryker.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.zalexdev.stryker.netdetect.ChipsetDb;
import com.zalexdev.stryker.netdetect.ChipsetInfo;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.stryker.terminal.bridge.StrykerLog;

public final class RootlessEngine implements GuestEngine {

    private static final String TAG = "RootlessEngine";
    private static final int BOOT_TIMEOUT_MS = 150_000;

    private static final int BOOT_PING_TIMEOUT_MS = 15_000;
    private static final String PROMPT_MARK = "__STRYKER_ID__";

    private static volatile RootlessEngine instance;

    private final Context app;
    private final ExecutorService qemuExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stryker-qemu");
        t.setDaemon(true);
        return t;
    });

    private volatile Process qemuProcess;
    private volatile Process dyingProcess;
    private volatile boolean booted;
    private volatile long lastGuestOk;
    private static final long GUEST_FRESH_MS = 15_000;
    private volatile File shareInUse;
    private volatile boolean shareActive;
    private volatile boolean usbDriverOk;

    private static final long WLAN_APPEAR_TIMEOUT_MS = 45_000;
    private final Object bootMarkLock = new Object();
    private volatile String lastError = "";
    private volatile String guestPrompt = "";
    private volatile boolean lastBootUsedFallback;
    private volatile boolean autoFallback = true;
    private volatile boolean stopRequested;
    private volatile QmpClient qmp;

    private static final String NETDEV_ID = "net0";
    private volatile UsbPassthroughManager usb;

    public interface BootListener extends GuestEngine.BootListener {
    }

    private RootlessEngine(Context context) {
        this.app = context.getApplicationContext();
    }

    public static RootlessEngine get(Context context) {
        if (instance == null) {
            synchronized (RootlessEngine.class) {
                if (instance == null) instance = new RootlessEngine(context);
            }
        }
        return instance;
    }

    @Override
    public EngineType type() {
        return EngineType.ROOTLESS;
    }

    @Override
    public String displayName() {
        return "Rootless VM (QEMU)";
    }

    @Override
    public java.util.List<Artifact> requiredArtifacts() {
        java.util.List<Artifact> out = new java.util.ArrayList<>();
        out.add(new Artifact("bundled", "QEMU (ships in the app)", RootlessPaths.qemuBin(app), false));
        out.add(new Artifact("bundled", "libslirp (ships in the app)", RootlessPaths.libslirp(app), false));
        out.add(new Artifact("kernel", "Kernel", RootlessPaths.kernel(app), false));
        out.add(new Artifact("initrd", "Initrd", RootlessPaths.initrd(app), false));
        out.add(new Artifact("rootfs", "Debian image", RootlessPaths.rootfs(app), true));
        return out;
    }

    @Override
    public java.util.List<String> missing() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Artifact a : requiredArtifacts()) {
            if (!a.target.exists() || a.target.length() == 0) out.add(a.label);
        }
        return out;
    }

    @Override
    public File shareDir() {
        return resolveShareDir();
    }

    @Override
    public int sshPort() {
        return RootlessPaths.HOST_SSH_PORT;
    }

    @Override
    public boolean supports(Capability capability) {
        switch (capability) {
            case DISK_TUNING:
            case TCG_TUNING:
            case CPU_MODEL:
            case SAFE_PROFILE:
            case DISK_RESIZE:
            case USB_PASSTHROUGH:
                return true;
            case SECCOMP:
            default:
                return false;
        }
    }

    public boolean isInstalled() {
        return RootlessPaths.qemuBin(app).canExecute()
                && RootlessPaths.libslirp(app).exists()
                && RootlessPaths.kernel(app).exists()
                && RootlessPaths.initrd(app).exists()
                && RootlessPaths.rootfs(app).exists();
    }

    public boolean isRunning() {
        Process p = qemuProcess;
        return p != null && isAlive(p);
    }

    public boolean isReady() {
        if (!isRunning() || !booted) return false;
        if (!GuestExec.ping(1000)) return false;
        lastGuestOk = System.currentTimeMillis();
        return true;
    }


    public synchronized boolean startBlocking(GuestEngine.BootListener listener) {
        if (isReady()) { if (listener != null) listener.onBooted(); return true; }
        if (EngineType.active(new Core(app)) != EngineType.ROOTLESS) {
            GuestEngine up = Engines.running(app);
            if (up != null && up != this) {
                lastError = "another guest (" + up.displayName() + ") is already running";
                GuestExec.logToStore(lastError + " — not starting the VM on top of it");
                if (listener != null) listener.onFailed(lastError);
                return false;
            }
        }
        if (isRunning() && booted) {
            for (int i = 0; i < 5; i++) {
                if (GuestExec.ping(2000)) {
                    if (listener != null) listener.onBooted();
                    return true;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
            lastError = "VM is running but the guest command server stopped responding";
            GuestExec.logToStore(lastError + " — not rebooting; restart the VM from the dashboard if it persists");
            if (listener != null) listener.onFailed(lastError);
            return false;
        }
        if (!isInstalled()) {
            lastError = "Rootless artifacts not installed";
            if (listener != null) listener.onFailed(lastError);
            return false;
        }
        lastBootUsedFallback = false;
        stopRequested = false;
        String reason = attemptBoot(listener);
        if (reason == null) { lastError = ""; return true; }

        Core prefs = prefs();
        if (autoFallback && prefs != null && !VmSpecs.safeBoot(prefs)) {
            lastError = reason;
            note(listener, "Boot failed (" + reason + ") — retrying with a safe profile");
            GuestExec.logToStore("VM boot failed (" + reason + "), falling back to the safe profile "
                    + "(aio=threads, cache=writeback, legacy CPU, no virtio-rng, no fast boot)");
            VmSpecs.setSafeBoot(prefs, true);
            lastBootUsedFallback = true;
            killAndAwait(12_000);
            String second = attemptBoot(listener);
            if (second == null) {
                lastError = "";
                VmSpecs.setSafeBoot(prefs, false);
                GuestExec.logToStore("VM booted with the safe profile — slower, but working. "
                        + "Restart the VM to retry the normal one.");
                return true;
            }
            VmSpecs.setSafeBoot(prefs, false);
            lastBootUsedFallback = false;
            lastError = second;
            if (listener != null) listener.onFailed(second);
            return false;
        }

        lastError = reason;
        if (listener != null) listener.onFailed(reason);
        return false;
    }

    private String attemptBoot(GuestEngine.BootListener listener) {
        try {
            killAndAwait(12_000);
            clearStaleSockets();
            ensureExecutable();
            autoGrowDisk();
            VmProbe.ensureCpuProfileVerified(app, prefs());
            List<String> cmd = buildCommand();
            StrykerLog.i(TAG, "QEMU: " + join(cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(RootlessPaths.base(app));
            pb.environment().put("LD_LIBRARY_PATH",
                    app.getApplicationInfo().nativeLibraryDir
                            + ":" + RootlessPaths.base(app).getAbsolutePath()
                            + ":/system/lib64:/vendor/lib64");
            pb.redirectErrorStream(true);

            final Process proc = pb.start();
            qemuProcess = proc;
            booted = false;

            new Thread(() -> pumpBootLog(proc, listener), "stryker-qemu-log").start();

            long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
            boolean consoleTried = false;
            while (System.currentTimeMillis() < deadline) {
                if (stopRequested) return "stopped";
                if (!isAlive(proc)) {
                    return describeExit(proc);
                }
                if (GuestExec.ping(BOOT_PING_TIMEOUT_MS) && guestShellReady()) {
                    markBooted();
                    if (listener != null) listener.onBooted();
                    return null;
                }
                if (!consoleTried && VmBootStage.detect(tailLog(120)) >= VmBootStage.AGENT) {
                    consoleTried = true;
                    note(listener, "Guest is up but the agent is not answering — starting it");
                    new Thread(this::bootstrapAgentOverConsole, "stryker-agent-bootstrap").start();
                }
                sleep(1000);
            }
            java.util.List<String> tail = tailLog(200);
            int stage = VmBootStage.detect(tail);
            return "Boot timed out after " + (BOOT_TIMEOUT_MS / 1000) + "s — "
                    + BootDiagnosis.reason(tail, stage);
        } catch (java.io.IOException e) {
            StrykerLog.e(TAG, "start failed", e);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            return msg.startsWith("Android will not") || msg.contains("is not on disk")
                    || msg.contains("is empty")
                    ? msg
                    : NativeExec.explain(RootlessPaths.qemuBin(app), e);
        } catch (Exception e) {
            StrykerLog.e(TAG, "start failed", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    @Override
    public String guestPrompt() {
        return guestPrompt == null ? "" : guestPrompt;
    }

    private boolean guestShellReady() {
        String saw = null;
        try {
            ArrayList<String> out = GuestExec.run(
                    "printf '" + PROMPT_MARK + "%s@%s\\n' \"$(id -un 2>/dev/null)\" \"$(hostname 2>/dev/null)\"");
            for (String l : out) {
                if (l == null) continue;
                int at = l.indexOf(PROMPT_MARK);
                if (at < 0) continue;
                saw = l.substring(at + PROMPT_MARK.length()).trim();
                if (saw.startsWith("root@")) {
                    guestPrompt = saw;
                    return true;
                }
            }
        } catch (Throwable t) {
            saw = t.getClass().getSimpleName();
        }
        if (!java.util.Objects.equals(saw, lastReadinessMiss)) {
            lastReadinessMiss = saw;
            StrykerLog.w(TAG, "guest answers but does not look like root yet: "
                    + (saw == null ? "no marker in the reply" : "\"" + saw + "\""));
        }
        return false;
    }

    private String lastReadinessMiss;

    public boolean usedSafeFallback() {
        return lastBootUsedFallback;
    }

    public void setAutoFallback(boolean enabled) {
        autoFallback = enabled;
    }

    private Core prefs() {
        try {
            return new Core(app);
        } catch (Throwable t) {
            return null;
        }
    }

    private void note(GuestEngine.BootListener listener, String message) {
        if (listener != null) listener.onBootLine(message);
    }

    private void autoGrowDisk() {
        try {
            File img = RootlessPaths.rootfs(app);
            if (!img.exists()) return;
            if (!VmSpecs.shouldAutoGrow(app)) return;
            long target = VmSpecs.autoDiskTargetBytes(app);
            long before = img.length();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
                raf.setLength(target);
                raf.getFD().sync();
            }
            if (img.length() != target) return;
            new Core(app).putBoolean(VmSpecs.K_RESIZE_PENDING, true);
            GuestExec.logToStore("VM disk grew " + (before / VmSpecs.GB) + " GB -> "
                    + (target / VmSpecs.GB) + " GB to match free storage");
        } catch (Throwable t) {
            StrykerLog.w(TAG, "autoGrowDisk: " + t.getMessage());
        }
    }

    private void reclaimFreedSpace() {
        try {
            GuestExec.run("command -v fstrim >/dev/null 2>&1 && fstrim / 2>&1 || true");
        } catch (Throwable t) {
            StrykerLog.w(TAG, "fstrim: " + t.getMessage());
        }
    }

    private void clearStaleSockets() {
        deleteQuietly(RootlessPaths.qmpSock(app));
        deleteQuietly(RootlessPaths.serialSock(app));
        deleteQuietly(RootlessPaths.termSock(app));
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists())
                f.delete();
        } catch (Throwable ignored) {
        }
    }

    private void killAndAwait(long timeoutMs) {
        Process live = qemuProcess;
        Process dying = dyingProcess;
        if (live == null && (dying == null || !isAlive(dying))) {
            dyingProcess = null;
            return;
        }
        if (live != null) teardownRunning();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Process p = dyingProcess;
            if (p == null || !isAlive(p)) {
                dyingProcess = null;
                return;
            }
            sleep(200);
        }
        Process p = dyingProcess;
        if (p != null && isAlive(p)) {
            destroyForcibly(p);
            sleep(600);
        }
        if (p == null || !isAlive(p)) dyingProcess = null;
    }

    private String lastLogProblem() {
        try {
            java.util.List<String> tail = tailLog(40);
            for (int i = tail.size() - 1; i >= 0; i--) {
                String l = tail.get(i);
                if (l == null) continue;
                String lower = l.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("qemu-system") || lower.contains("error")
                        || lower.contains("failed") || lower.contains("not supported")
                        || lower.contains("invalid") || lower.contains("could not")
                        || lower.contains("couldn't") || lower.contains("cannot")
                        || lower.contains("denied") || lower.contains("no such")) {
                    return l.length() > 160 ? l.substring(0, 160) : l;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String describeExit(Process proc) {
        int code = safeExit(proc);
        String how = code >= 128 && code < 160
                ? "killed by " + signalName(code - 128)
                : "exited with code " + code;

        String problem = lastLogProblem();
        java.util.List<String> tail = tailLog(12);
        if (problem.isEmpty()) {
            problem = tail.isEmpty()
                    ? "QEMU wrote nothing before dying — it did not get as far as starting the machine"
                    : "the boot log ends at: " + tail.get(tail.size() - 1);
        }

        if (!tail.isEmpty()) {
            StringBuilder sb = new StringBuilder("QEMU " + how + ". Last lines of the boot log:");
            for (String l : tail) sb.append(System.lineSeparator()).append("  ").append(l);
            GuestExec.logToStore(sb.toString());
        }
        return "QEMU " + how + ": " + problem;
    }

    private static String signalName(int sig) {
        switch (sig) {
            case 4:  return "SIGILL (the CPU refused an instruction QEMU emitted)";
            case 6:  return "SIGABRT (QEMU aborted itself)";
            case 7:  return "SIGBUS";
            case 9:  return "SIGKILL (Android killed it — usually out of memory)";
            case 11: return "SIGSEGV (crash)";
            case 15: return "SIGTERM";
            default: return "signal " + sig;
        }
    }

    public void startAsync() {
        qemuExecutor.submit(() -> startBlocking(null));
    }

    public void stop() {
        stopRequested = true;
        teardownRunning();
    }

    private void teardownRunning() {
        booted = false;
        guestPrompt = "";
        final UsbPassthroughManager oldUsb = usb;
        final QmpClient oldQmp = qmp;
        usb = null;
        qmp = null;
        final Process p = qemuProcess;
        qemuProcess = null;
        if (p != null) dyingProcess = p;
        new Thread(() -> {
            try { if (oldUsb != null) oldUsb.detachAll(); } catch (Throwable ignored) {}
            try { if (oldQmp != null) oldQmp.powerdown(); } catch (Throwable ignored) {}
            try { if (oldQmp != null) oldQmp.close(); } catch (Throwable ignored) {}
            if (p == null) return;
            sleep(2500);
            if (isAlive(p)) p.destroy();
            sleep(1500);
            if (isAlive(p)) destroyForcibly(p);
        }, "stryker-qemu-stop").start();
    }

    public boolean stopAndWait(long timeoutMs) {
        stop();
        killAndAwait(timeoutMs);
        clearStaleSockets();
        Process p = dyingProcess;
        return p == null || !isAlive(p);
    }

    public boolean hardRestart(GuestEngine.BootListener listener) {
        stopAndWait(20_000);
        return startBlocking(listener);
    }


    public enum ResizeResult { OK, ALREADY_THAT_SIZE, SHRINK_UNSUPPORTED, VM_STILL_RUNNING, IMAGE_MISSING, IO_ERROR }

    public synchronized ResizeResult resizeDisk(long targetBytes) {
        File img = RootlessPaths.rootfs(app);
        if (!img.exists()) return ResizeResult.IMAGE_MISSING;
        long current = img.length();
        if (targetBytes == current) return ResizeResult.ALREADY_THAT_SIZE;
        if (targetBytes < current) return ResizeResult.SHRINK_UNSUPPORTED;

        if (!stopAndWait(20_000)) return ResizeResult.VM_STILL_RUNNING;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
            raf.setLength(targetBytes);
            raf.getFD().sync();
        } catch (Exception e) {
            StrykerLog.e(TAG, "resizeDisk failed", e);
            return ResizeResult.IO_ERROR;
        }
        if (img.length() != targetBytes) {
            StrykerLog.e(TAG, "resizeDisk: image is " + img.length() + " after asking for " + targetBytes);
            return ResizeResult.IO_ERROR;
        }
        try {
            Core prefs = new Core(app);
            prefs.putInt(VmSpecs.K_DISK_GB, (int) Math.round(targetBytes / (double) VmSpecs.GB));
            prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, true);
        } catch (Throwable ignored) {}
        return ResizeResult.OK;
    }

    private void maybeResizeFilesystem() {
        try {
            Core prefs = new Core(app);
            if (!prefs.getBoolean(VmSpecs.K_RESIZE_PENDING)) return;
            long imageBytes = VmSpecs.currentDiskBytes(app);
            long fits = VmSpecs.fittingDiskBytes(app);
            if (imageBytes > fits + VmSpecs.DISK_GROW_STEP_BYTES) {
                prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                GuestExec.logToStore("VM image claims " + (imageBytes / VmSpecs.GB)
                        + " GB but only " + (fits / VmSpecs.GB)
                        + " GB fits on this device — skipping the filesystem expansion. "
                        + "Pick a size that fits under Settings if you need a bigger disk.");
                return;
            }
            GuestExec.logToStore("expanding VM disk filesystem (resize2fs /dev/vda)…");
            ArrayList<String> out = GuestExec.run(
                    "command -v resize2fs >/dev/null 2>&1 && echo __HAS_RESIZE2FS__ || echo __NO_RESIZE2FS__; "
                    + "echo __BEFORE__; df -k / | tail -n 1; "
                    + "resize2fs /dev/vda 2>&1 || resize2fs -f /dev/vda 2>&1; "
                    + "echo __AFTER__; df -k / | tail -n 1; echo __RESIZE_DONE__");

            boolean hasTool = false;
            boolean done = false;
            boolean nothingToDo = false;
            long before = -1L;
            long after = -1L;
            int marker = 0;
            for (String l : out) {
                if (l == null) continue;
                if (l.contains("__HAS_RESIZE2FS__")) hasTool = true;
                if (l.contains("__NO_RESIZE2FS__")) hasTool = false;
                if (l.contains("__RESIZE_DONE__")) done = true;
                if (l.toLowerCase(java.util.Locale.ROOT).contains("nothing to do")) nothingToDo = true;
                if (l.contains("__BEFORE__")) { marker = 1; continue; }
                if (l.contains("__AFTER__")) { marker = 2; continue; }
                long blocks = dfBlocks(l);
                if (blocks <= 0) continue;
                if (marker == 1 && before < 0) before = blocks;
                else if (marker == 2 && after < 0) after = blocks;
            }

            boolean grew = before > 0 && after > before;
            if (grew || nothingToDo) {
                prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                GuestExec.logToStore(grew
                        ? "VM disk filesystem expanded to " + (after / 1024L) + " MB"
                        : "VM disk filesystem already fills the image");
                return;
            }
            if (!hasTool) {
                GuestExec.logToStore("disk grown, but resize2fs is missing in the guest — "
                        + "run 'apt-get install -y e2fsprogs' in the terminal, the grow retries on the next boot");
                return;
            }
            GuestExec.logToStore("resize2fs did not expand the filesystem"
                    + (done ? "" : " (command did not finish)") + " — retrying on the next boot");
        } catch (Throwable t) {
            StrykerLog.w(TAG, "maybeResizeFilesystem: " + t.getMessage());
        }
    }

    private static final String ATH9K_HTC_FW = "/lib/firmware/ath9k_htc/htc_9271-1.4.0.fw";

    private void ensureWifiFirmware() {
        try {
            ArrayList<String> have = GuestExec.run(
                    "[ -f " + ATH9K_HTC_FW + " ] && echo __FW_OK__ || echo __FW_MISSING__");
            for (String l : have) {
                if (l != null && l.contains("__FW_OK__")) return;
            }
            GuestExec.logToStore("guest is missing " + ATH9K_HTC_FW
                    + " — installing firmware-ath9k-htc (ath9k_htc dongles fail with "
                    + "\"Target is unresponsive\" without it)");
            GuestExec.run("export DEBIAN_FRONTEND=noninteractive; "
                    + "apt-get install -y --no-install-recommends firmware-ath9k-htc wireless-regdb "
                    + ">/dev/null 2>&1; true");
            ArrayList<String> after = GuestExec.run(
                    "[ -f " + ATH9K_HTC_FW + " ] && echo __FW_OK__ || echo __FW_MISSING__");
            for (String l : after) {
                if (l != null && l.contains("__FW_OK__")) {
                    GuestExec.logToStore("ath9k_htc firmware installed — replug the dongle to retry");
                    return;
                }
            }
            GuestExec.logToStore("could not install firmware-ath9k-htc (no network in the VM?)");
        } catch (Throwable ignored) {
        }
    }

    private void ensureKernelModules() {
        ensureWifiFirmware();
        try {
            GuestExec.run("mkdir -p /etc/modules-load.d; "
                    + "{ echo loop; echo squashfs; echo overlay; } > /etc/modules-load.d/stryker.conf 2>/dev/null; true");
            if (guestHasModules()) {
                GuestExec.run("modprobe loop >/dev/null 2>&1; modprobe squashfs >/dev/null 2>&1; "
                        + "modprobe overlay >/dev/null 2>&1; true");
                return;
            }
            File initrd = RootlessPaths.initrd(app);
            File share = resolveShareDir();
            if (!initrd.exists() || share == null) return;
            File staged = new File(share, ".initrd.img");
            GuestExec.logToStore("guest has no /lib/modules — unpacking kernel modules from the initrd");
            try (InputStream in = new java.io.FileInputStream(initrd);
                 java.io.OutputStream out = new java.io.FileOutputStream(staged)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
            }
            GuestExec.run("command -v cpio >/dev/null 2>&1 || "
                    + "(export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends cpio >/dev/null 2>&1); "
                    + "rm -rf /tmp/stryker-ird; mkdir -p /tmp/stryker-ird; cd /tmp/stryker-ird; "
                    + "(cpio -idm < /sdcard/Stryker/.initrd.img || busybox cpio -idm < /sdcard/Stryker/.initrd.img) >/dev/null 2>&1; "
                    + "if [ -d /tmp/stryker-ird/lib/modules ]; then mkdir -p /lib/modules; "
                    + "cp -a /tmp/stryker-ird/lib/modules/. /lib/modules/; depmod -a >/dev/null 2>&1; "
                    + "echo __MODULES_DEPLOYED__; fi; rm -rf /tmp/stryker-ird");
            staged.delete();
            ArrayList<String> res = GuestExec.run(
                    "modprobe loop >/dev/null 2>&1; modprobe squashfs >/dev/null 2>&1; "
                    + "losetup -f >/dev/null 2>&1 && echo __LOOP_OK__ || echo __LOOP_NO__");
            for (String l : res) {
                if (l != null && l.contains("__LOOP_OK__")) {
                    GuestExec.logToStore("loop devices are available in the guest");
                    return;
                }
            }
            GuestExec.logToStore("loop still unavailable after loading modules");
        } catch (Throwable t) {
            StrykerLog.w(TAG, "ensureKernelModules: " + t.getMessage());
        }
    }

    private boolean guestHasModules() {
        ArrayList<String> out = GuestExec.run(
                "[ -d \"/lib/modules/$(uname -r)/kernel\" ] && echo __HAS_MODULES__ || echo __NO_MODULES__");
        for (String l : out) {
            if (l != null && l.trim().equals("__HAS_MODULES__")) return true;
        }
        return false;
    }

    private static long dfBlocks(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return -1L;
            for (int i = 1; i < parts.length; i++) {
                try {
                    return Long.parseLong(parts[i]);
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }


    @Override
    public ArrayList<String> exec(String command) {
        if (!isReady() && !startBlocking(null)) {
            GuestExec.logToStore("VM is not running — start it from the dashboard, then retry");
            return new ArrayList<>();
        }
        return GuestExec.run(command);
    }

    @Override
    public GuestExec.Session openStream(String command) throws java.io.IOException {
        if (!isReady()) startBlocking(null);
        return GuestExec.openJob(command);
    }


    private static final String CORE_MARKER = "/CORE/PixieWps/pixie.py";
    private static final String STAGED_CORE = ".stryker-guest-core.tar";

    private GuestEngine guestInUse() {
        GuestEngine up = Engines.running(app);
        return up != null ? up : Engines.active(new Core(app));
    }

    public synchronized boolean ensureGuestCore() {
        GuestEngine active = guestInUse();
        if (!active.isReady() && !active.startBlocking(null)) return false;
        ArrayList<String> chk = GuestExec.run("[ -f " + CORE_MARKER + " ] && "
                + "cat " + GuestCore.VERSION_FILE + " 2>/dev/null || echo __NO__");
        for (String l : chk) {
            if (l != null && GuestCore.VERSION.equals(l.trim())) return true;
        }
        return deployGuestCore();
    }

    private static String unpackAndVerify(String tarPath) {
        return "tar xf " + tarPath + " -C / 2>&1; "
                + "chmod 0755 /usr/local/sbin/stryker-ptyd /usr/local/sbin/stryker-agentd 2>/dev/null; "
                + "echo __AGENT_BYTES__$(wc -c < /usr/local/sbin/stryker-agentd 2>/dev/null || echo 0); "
                + "if [ -s /usr/local/sbin/stryker-agentd ] && [ -x /usr/local/sbin/stryker-agentd ] "
                + "&& [ -f " + CORE_MARKER + " ]; then echo __DEPLOYED__; else echo __FAIL__; fi";
    }

    private java.io.File stageGuestCore(java.io.File shareDir) throws java.io.IOException {
        java.io.File staged = new java.io.File(shareDir, STAGED_CORE);
        GuestCorePackage.extractTo(app, staged);
        return staged;
    }

    public boolean deployGuestCore() {
        try {
            GuestEngine active = guestInUse();
            java.io.File shareDir = active.shareDir();
            if (shareDir == null) return false;
            java.io.File staged = stageGuestCore(shareDir);
            ArrayList<String> res = GuestExec.run(
                    unpackAndVerify(active.guestSharePath() + "/" + STAGED_CORE));
            for (String l : res) {
                if (l != null && l.trim().startsWith("__AGENT_BYTES__")) {
                    GuestExec.logToStore("guest core deployed, agent is "
                            + l.trim().substring("__AGENT_BYTES__".length()) + " bytes");
                }
            }
            staged.delete();
            boolean ok = false;
            for (String l : res) if (l != null && l.trim().equals("__DEPLOYED__")) ok = true;
            if (ok) restartGuestAgent();
            return ok;
        } catch (Exception e) {
            StrykerLog.w(TAG, "deployGuestCore failed: " + e.getMessage());
            return false;
        }
    }

    public boolean bootstrapAgentOverConsole() {
        String sock = RootlessPaths.serialSock(app).getAbsolutePath();
        if (!new File(sock).exists()) return false;

        GuestExec.logToStore("guest agent unreachable — bootstrapping it over the serial console");
        File staged = null;
        if (shareActive && shareInUse != null) {
            try {
                staged = stageGuestCore(shareInUse);
            } catch (Exception e) {
                StrykerLog.w(TAG, "staging guest core for console bootstrap failed: " + e.getMessage());
                staged = null;
            }
        }

        StringBuilder cmd = new StringBuilder();
        if (staged != null) {
            cmd.append(unpackAndVerify("/sdcard/Stryker/" + STAGED_CORE)).append("; ");
        }
        cmd.append("command -v socat >/dev/null 2>&1 || (export DEBIAN_FRONTEND=noninteractive; ")
           .append("apt-get install -y --no-install-recommends socat >/dev/null 2>&1); ")
           .append("(systemctl restart stryker-agent.service >/dev/null 2>&1 ")
           .append("|| (pkill -f stryker-agentd >/dev/null 2>&1; ")
           .append("setsid /usr/local/sbin/stryker-agentd >/dev/null 2>&1 &)); ")
           .append("sleep 3; ss -ltn 2>/dev/null | grep -q ':1050' && echo __AGENT_UP__ || echo __AGENT_DOWN__");

        boolean up = false;
        for (String l : GuestConsole.run(cmd.toString(), sock, 180_000)) {
            if (l != null && l.contains("__AGENT_UP__")) up = true;
        }
        if (staged != null) {
            staged.delete();
        }
        if (up) {
            GuestExec.logToStore("guest agent started from the console");
            return true;
        }
        for (String l : GuestConsole.run(
                "printf 'agentd_bytes=%s socat=%s\\n' "
                + "\"$(wc -c < /usr/local/sbin/stryker-agentd 2>/dev/null || echo missing)\" "
                + "\"$(command -v socat >/dev/null 2>&1 && echo yes || echo NO)\"", sock, 20_000)) {
            if (l != null && l.startsWith("agentd_bytes=")) {
                GuestExec.logToStore("console bootstrap failed — guest reports " + l.trim());
            }
        }
        return false;
    }

    private static final int AGENT_PORT_TRIES = 40;

    private static final String AGENT_PORT_PROBE =
            "{ ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null; } | grep -q ':1052' "
            + "&& echo __UP__ || echo __NO__";

    private static final String PTYD_STATUS = "/tmp/stryker-ptyd.status";

    private String guestAgentPortReason() {
        for (String l : GuestExec.run("cat " + PTYD_STATUS + " 2>/dev/null")) {
            if (l != null && !l.trim().isEmpty()) return l.trim();
        }
        return "the agent left no status, so it probably never ran";
    }

    private void restartGuestAgent() {
        GuestExec.run("(systemctl restart stryker-agent.service >/dev/null 2>&1 "
                + "|| (pkill -f stryker-agentd >/dev/null 2>&1; "
                + "pkill -f stryker-ptyd >/dev/null 2>&1; "
                + "setsid /usr/local/sbin/stryker-agentd >/dev/null 2>&1 &)) &");
        for (int i = 0; i < AGENT_PORT_TRIES; i++) {
            for (String l : GuestExec.run(AGENT_PORT_PROBE)) {
                if (l != null && l.trim().equals("__UP__")) return;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        String why = guestAgentPortReason();
        StrykerLog.w(TAG, "guest agent restarted but port 1052 never came up: " + why);
        GuestExec.logToStore("guest agent: port 1052 never came up — " + why);
    }

    @Override
    public synchronized boolean ensureUsbWifiAttached() {
        if (!isReady() && !startBlocking(null)) return false;
        if (usb == null) return false;
        int candidates = usb.pickWifiDevices().size();
        int count = usb.attachAllWifiDongles(20_000);
        if (count <= 0) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: no adapter could be passed into the VM");
            return false;
        }
        if (candidates > 1) {
            GuestExec.logToStore("USB adapters: " + count + " of " + candidates + " passed into the VM");
        }
        return awaitGuestWlan(WLAN_APPEAR_TIMEOUT_MS, count);
    }

    public java.util.List<String> guestWifiInterfaces() {
        return guestWlanInterfaces();
    }

    @Override
    public boolean usbDriverOk() {
        return usbDriverOk;
    }

    private static java.util.List<String> guestWlanInterfaces() {
        return GuestExec.wirelessInterfaces();
    }

    private boolean awaitGuestWlan(long timeoutMs, int expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        java.util.List<String> ifs = java.util.Collections.emptyList();
        while (true) {
            ifs = guestWlanInterfaces();
            if (ifs.size() >= Math.max(expected, 1)) break;
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        if (ifs.isEmpty()) {
            usbDriverOk = false;
            String hint = chipsetDriverHint();
            GuestExec.logToStore("USB adapter: DRIVER MISSING — the dongle is attached to the VM but "
                    + "'iw dev' shows no interface after " + (timeoutMs / 1000) + "s."
                    + (hint != null ? " Detected: " + hint + (hint.endsWith(".") ? "" : ".") : "")
                    + " Install the driver or firmware for this chipset from the Terminal, then retry.");
            return false;
        }
        usbDriverOk = true;
        if (ifs.size() < expected) {
            GuestExec.logToStore("USB adapters: only " + ifs.size() + " of " + expected
                    + " bound a driver — guest exposes " + ifs
                    + ". The missing one needs its driver/firmware installed from the Terminal.");
        } else {
            GuestExec.logToStore("USB adapter: driver OK — guest exposes " + ifs);
        }
        return true;
    }

    private String chipsetDriverHint() {
        if (usb == null) return null;
        List<UsbDevice> picks = usb.pickWifiDevices();
        if (picks == null || picks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : picks) {
            if (!usb.isAttached(d)) continue;
            ChipsetInfo info = ChipsetDb.lookup(
                    String.format(java.util.Locale.ENGLISH, "%04x", d.getVendorId() & 0xFFFF),
                    String.format(java.util.Locale.ENGLISH, "%04x", d.getProductId() & 0xFFFF));
            if (info == null || info.kind != ChipsetInfo.Kind.WIFI) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(info.displayName()).append(" (driver ").append(info.driver).append(")");
            if (info.notes != null && !info.notes.isEmpty()) {
                sb.append(" — ").append(info.notes);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public UsbPassthroughManager usb() { return usb; }
    public QmpClient qmp() { return qmp; }

    @Override
    public boolean forwardPort(int hostPort, int guestPort) {
        QmpClient c = qmp;
        if (c == null || !c.isConnected()) return false;
        unforwardPort(hostPort);
        return c.hostfwdAdd(NETDEV_ID + " tcp:" + RootlessPaths.HOST_LOOPBACK + ":"
                + hostPort + "-:" + guestPort);
    }

    @Override
    public String guestSharePath() {
        return "/sdcard/Stryker";
    }

    @Override
    public boolean unforwardPort(int hostPort) {
        QmpClient c = qmp;
        if (c == null || !c.isConnected()) return false;
        return c.hostfwdRemove(NETDEV_ID + " tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + hostPort);
    }


    @Override
    public java.util.List<String> consoleTail(int lines) {
        return tailLog(lines);
    }

    @Override
    public GuestEngine.State status() {
        if (!isRunning()) return GuestEngine.State.STOPPED;
        if (!booted) return GuestEngine.State.BOOTING;
        boolean fresh = System.currentTimeMillis() - lastGuestOk < GUEST_FRESH_MS;
        return fresh && lastGuestOk >= GuestSsh.lastLossAt()
                ? GuestEngine.State.READY : GuestEngine.State.BOOTING;
    }

    @Override
    public GuestEngine.State statusBlocking() {
        if (isRunning()) {
            if (GuestExec.ping(1500)) {
                lastGuestOk = System.currentTimeMillis();
                markBooted();
                return GuestEngine.State.READY;
            }
            return GuestEngine.State.BOOTING;
        }
        if (GuestExec.ping(1500)) {
            lastGuestOk = System.currentTimeMillis();
            return GuestEngine.State.READY;
        }
        return GuestEngine.State.STOPPED;
    }

    private void markBooted() {
        synchronized (bootMarkLock) {
            lastGuestOk = System.currentTimeMillis();
            if (booted) return;
            booted = true;
        }
        connectControl();
        qemuExecutor.submit(() -> {
            try {
                maybeResizeFilesystem();
                reclaimFreedSpace();
                ensureKernelModules();
            } catch (Throwable t) {
                StrykerLog.w(TAG, "post-boot maintenance failed: " + t.getMessage());
            }
        });
    }

    public boolean usbAttached() {
        return usb != null && usb.hasAttached();
    }

    public java.util.List<String> tailLog(int maxLines) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        File log = RootlessPaths.serialLog(app);
        if (!log.exists() || log.length() == 0) log = RootlessPaths.bootLog(app);
        if (!log.exists()) return out;
        java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(log)))) {
            String line;
            while ((line = br.readLine()) != null) {
                ring.addLast(line);
                if (ring.size() > maxLines) ring.removeFirst();
            }
        } catch (Exception ignored) {}
        out.addAll(ring);
        return out;
    }

    private void connectControl() {
        try {
            qmp = new QmpClient(RootlessPaths.qmpSock(app).getAbsolutePath());
            if (qmp.connect()) {
                usb = new UsbPassthroughManager(app, qmp);
            } else {
                StrykerLog.w(TAG, "QMP connect failed — USB passthrough unavailable");
            }
        } catch (Exception e) {
            StrykerLog.w(TAG, "control connect failed: " + e.getMessage());
        }
    }


    private List<String> buildCommand() {
        int cpus = VmSpecs.DEFAULT_CPUS, ramMb = VmSpecs.DEFAULT_RAM_MB;
        boolean usbEnabled = true, shareEnabled = true, rngEnabled = true, mttcg = true;
        boolean ioThread = true, fastBoot = true;
        String cacheMode = "writeback", aioMode = "threads";
        String cpuModel = "max,sve=off,pmu=off,pauth=off";
        int tbSize = 512;
        Core prefs = null;
        try {
            prefs = new Core(app);
            cpus = VmSpecs.effectiveCpus(app, prefs);
            ramMb = VmSpecs.effectiveRamMb(app, prefs);
            usbEnabled = VmSpecs.usbEnabled(prefs);
            shareEnabled = VmSpecs.shareEnabled(prefs);
            rngEnabled = VmSpecs.rngEnabled(prefs);
            mttcg = VmSpecs.mttcg(prefs);
            cacheMode = VmSpecs.cacheMode(prefs);
            aioMode = VmSpecs.aioMode(prefs);
            tbSize = VmSpecs.tbSizeMb(app, prefs, ramMb);
            cpuModel = VmSpecs.cpuModel(prefs);
            ioThread = VmSpecs.ioThread(prefs);
            fastBoot = VmSpecs.fastBoot(prefs);
        } catch (Throwable ignored) {}

        String base = RootlessPaths.base(app).getAbsolutePath();
        List<String> a = new ArrayList<>();
        a.add(RootlessPaths.qemuBin(app).getAbsolutePath());

        a.add("-nodefaults");
        a.add("-M"); a.add("virt,gic-version=3");

        File kvm = new File("/dev/kvm");
        if (kvm.exists() && kvm.canWrite()) {
            a.add("-cpu"); a.add("host");
            a.add("-accel"); a.add("kvm");
        } else {
            a.add("-cpu"); a.add(cpuModel);
            a.add("-accel"); a.add("tcg,thread=" + (mttcg ? "multi" : "single") + ",tb-size=" + tbSize);
        }
        a.add("-smp"); a.add(cpus + ",sockets=1,cores=" + cpus + ",threads=1");
        a.add("-m");   a.add(String.valueOf(ramMb));

        a.add("-kernel"); a.add(RootlessPaths.kernel(app).getAbsolutePath());
        a.add("-initrd"); a.add(RootlessPaths.initrd(app).getAbsolutePath());
        a.add("-append"); a.add(kernelCmdline(fastBoot));

        a.add("-drive"); a.add("file=" + RootlessPaths.rootfs(app).getAbsolutePath()
                + ",if=none,id=drive0,format=raw,cache=" + cacheMode + ",aio=" + aioMode
                + ",discard=unmap,detect-zeroes=unmap");
        if (ioThread) {
            a.add("-object"); a.add("iothread,id=io0");
            a.add("-device"); a.add("virtio-blk-pci,drive=drive0,iothread=io0");
        } else {
            a.add("-device"); a.add("virtio-blk-pci,drive=drive0");
        }

        a.add("-netdev"); a.add("user,id=net0,ipv6=off"
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_SSH_PORT
                + "-:" + RootlessPaths.GUEST_SSH_PORT);
        a.add("-device"); a.add("virtio-net-pci,netdev=net0,romfile=");

        if (usbEnabled) {
            a.add("-device"); a.add("qemu-xhci,id=usbhc0,p2=8,p3=8");
        }

        if (rngEnabled) { a.add("-device"); a.add("virtio-rng-pci"); }

        shareInUse = null;
        shareActive = false;
        if (shareEnabled) {
            File share = pickShareDir();
            if (share != null) {
                shareInUse = share;
                shareActive = true;
                a.add("-fsdev"); a.add("local,id=fsdev0,security_model=none,path=" + share.getAbsolutePath());
                a.add("-device"); a.add("virtio-9p-pci,fsdev=fsdev0,mount_tag=strykershare");
            } else {
                StrykerLog.w(TAG, "9p share dir unavailable — booting without /sdcard share");
            }
        }
        if (!shareActive) {
            GuestExec.logToStore("VM is booting WITHOUT the /sdcard capture share — handshakes and "
                    + "reports written inside the guest will not be visible to the app");
        }

        if (shareActive && shareInUse != null) {
            try {
                GuestSsh.configure(app, shareInUse, RootlessPaths.HOST_SSH_PORT);
                GuestSsh.publishPublicKey();
            } catch (Exception e) {
                GuestExec.logToStore("could not place the app's ssh key in the share ("
                        + e.getMessage() + ") — the guest will boot but cannot be reached");
            }
        } else {
            GuestExec.logToStore("no share, so no ssh key exchange is possible: the guest will "
                    + "boot but the app will not be able to run anything in it");
        }

        a.add("-chardev"); a.add("socket,id=serial0,path=" + RootlessPaths.serialSock(app).getAbsolutePath()
                + ",server=on,wait=off,logfile=" + RootlessPaths.serialLog(app).getAbsolutePath());
        a.add("-serial"); a.add("chardev:serial0");
        a.add("-device"); a.add("virtio-serial-pci");
        a.add("-chardev"); a.add("socket,id=term0,path=" + RootlessPaths.termSock(app).getAbsolutePath()
                + ",server=on,wait=off");
        a.add("-device"); a.add("virtconsole,chardev=term0,name=org.stryker.term");

        a.add("-display"); a.add("none");
        a.add("-qmp"); a.add("unix:" + RootlessPaths.qmpSock(app).getAbsolutePath() + ",server,nowait");
        return a;
    }

    private static String kernelCmdline(boolean fastBoot) {
        StringBuilder sb = new StringBuilder("root=/dev/vda rw rootwait rootflags=noatime "
                + "console=ttyAMA0 loglevel=4 net.ifnames=0 mitigations=off stryker.rootless=1");
        if (fastBoot) {
            sb.append(" init_on_alloc=0 init_on_free=0 audit=0 nokaslr")
              .append(" rcupdate.rcu_expedited=1 rcupdate.rcu_normal_after_boot=1")
              .append(" cryptomgr.notests random.trust_bootloader=on");
        }
        return sb.toString();
    }


    private void ensureExecutable() throws java.io.IOException {
        String problem = NativeExec.check(app,
                RootlessPaths.qemuBin(app), RootlessPaths.libslirp(app));
        if (problem != null) {
            GuestExec.logToStore(problem);
            throw new java.io.IOException(problem);
        }
    }

    public File resolveShareDir() {
        if (isRunning()) return shareActive ? shareInUse : null;
        return pickShareDir();
    }

    public boolean shareActive() {
        return !isRunning() || shareActive;
    }

    private File pickShareDir() {
        if (hasStorageAccess()) {
            File pub = new File(android.os.Environment.getExternalStorageDirectory(), "Stryker");
            if ((pub.isDirectory() || pub.mkdirs()) && pub.canWrite()) {
                return withSubdirs(pub);
            }
        }
        File ext = app.getExternalFilesDir(null);
        if (ext != null) {
            File s = new File(ext, "Stryker");
            if (s.isDirectory() || s.mkdirs()) return withSubdirs(s);
        }
        return null;
    }

    private boolean hasStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try { return android.os.Environment.isExternalStorageManager(); }
            catch (Throwable t) { return false; }
        }
        try {
            return app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private static File withSubdirs(File base) {
        new File(base, "hs").mkdirs();
        new File(base, "captured").mkdirs();
        new File(base, "reports").mkdirs();
        return base;
    }

    private void pumpBootLog(Process proc, GuestEngine.BootListener listener) {
        File log = RootlessPaths.bootLog(app);
        try (InputStream in = proc.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in));
             FileWriter fw = new FileWriter(log, false)) {
            String line;
            while ((line = br.readLine()) != null) {
                fw.write(line); fw.write("\n"); fw.flush();
                if (listener != null) listener.onBootLine(line);
            }
        } catch (Exception ignored) {}
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    private static int safeExit(Process p) {
        try { return p.exitValue(); } catch (Exception e) { return -1; }
    }

    private static void destroyForcibly(Process p) {
        try { p.getClass().getMethod("destroyForcibly").invoke(p); }
        catch (Throwable t) { p.destroy(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p).append(' ');
        return sb.toString().trim();
    }
}
