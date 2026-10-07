package com.zalexdev.stryker.engine;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.stryker.terminal.bridge.StrykerLog;

public final class UmlEngine implements GuestEngine {

    private static final String TAG = "UmlEngine";

    public static final int SSH_PORT = RootlessPaths.HOST_SSH_PORT;

    private final Context app;
    private volatile Process process;
    private volatile Thread consolePump;
    private volatile String lastError = "";
    private volatile boolean stopRequested;
    private volatile long lastGuestOk;
    private static final long GUEST_FRESH_MS = 15_000;
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "stryker-uml");
                t.setDaemon(true);
                return t;
            });

    public UmlEngine(Context context) {
        this.app = context.getApplicationContext();
    }

    public File kernel()  { return new File(nativeDir(), "libuml.so"); }
    public File stub()    { return new File(nativeDir(), "libstub.so"); }
    public File umnet()   { return new File(nativeDir(), "libumnet.so"); }
    public File passt()   { return new File(nativeDir(), "libpasst.so"); }

    public File usbServer() { return new File(nativeDir(), "libumusb.so"); }

    private File nativeDir() {
        return new File(app.getApplicationInfo().nativeLibraryDir);
    }

    private static final int BOOT_PING_TIMEOUT_MS = 15_000;

    public File base()    { return new File(app.getFilesDir(), "uml"); }
    public File console() { return new File(base(), "console.log"); }

    public File tempDir()  { return new File(base(), "tmp"); }

    String fallbackTempDir() {
        String[] vars = {"TMPDIR", "TMP", "TEMP"};
        for (String v : vars) {
            String value = System.getenv(v);
            if (value != null && !value.trim().isEmpty() && new File(value).isDirectory()) {
                return null;
            }
        }
        for (String candidate : new String[]{"/dev/shm", "/tmp"}) {
            File d = new File(candidate);
            if (d.isDirectory() && d.canWrite()) return null;
        }
        String shm = writableTmpfs();
        if (shm != null) return shm;
        File tmp = tempDir();
        tmp.mkdirs();
        return tmp.isDirectory() ? tmp.getAbsolutePath() : null;
    }

    private static String writableTmpfs() {
        for (String[] m : mounts()) {
            if (!"tmpfs".equals(m[1])) continue;
            File d = new File(m[0]);
            if (!d.isDirectory() || !d.canWrite()) continue;
            File probe = new File(d, "stryker-uml");
            if (probe.isDirectory() || probe.mkdirs()) return probe.getAbsolutePath();
        }
        return null;
    }

    private static List<String[]> mounts() {
        List<String[]> out = new ArrayList<>();
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/mounts"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length >= 3) out.add(new String[]{f[1], f[2]});
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static String fsTypeOf(String path) {
        String type = null;
        int best = -1;
        for (String[] m : mounts()) {
            String mount = m[0];
            String prefix = mount.endsWith("/") ? mount : mount + "/";
            if (!path.equals(mount) && !path.startsWith(prefix)) continue;
            if (mount.length() > best) {
                best = mount.length();
                type = m[1];
            }
        }
        return type;
    }

    String memoryBackingHint() {
        String dir = fallbackTempDir();
        if (dir == null) {
            for (String v : new String[]{"TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(v);
                if (value != null && !value.trim().isEmpty() && new File(value).isDirectory()) {
                    dir = value;
                    break;
                }
            }
        }
        if (dir == null) {
            for (String c : new String[]{"/dev/shm", "/tmp"}) {
                File d = new File(c);
                if (d.isDirectory() && d.canWrite()) { dir = c; break; }
            }
        }
        if (dir == null) return null;
        String type = fsTypeOf(dir);
        if ("tmpfs".equals(type)) return null;
        return "the kernel backs guest memory with a file in " + dir + ", which is on "
                + (type == null ? "a filesystem" : type) + " rather than tmpfs, and only tmpfs "
                + "supports MADV_REMOVE. This device gives apps no writable tmpfs, so UML cannot "
                + "run here; the QEMU engine does not need it.";
    }

    public File rootfs() { return RootlessPaths.rootfs(app); }

    @Override
    public EngineType type() {
        return EngineType.UML;
    }

    @Override
    public String displayName() {
        return "User Mode Linux";
    }

    @Override
    public List<Artifact> requiredArtifacts() {
        List<Artifact> out = new ArrayList<>();
        out.add(new Artifact("bundled", "Kernel (ships in the app)", kernel(), false));
        out.add(new Artifact("rootfs", "Debian image", rootfs(), true));
        return out;
    }

    @Override
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (!kernel().canExecute()) out.add("libuml.so (the kernel)");
        if (!stub().exists())       out.add("libstub.so (the address-space stub)");
        if (!umnet().canExecute())  out.add("libumnet.so (networking)");
        if (!passt().canExecute())  out.add("libpasst.so (networking)");
        if (!rootfs().isFile())     out.add("rootfs.img (the Debian image)");
        return out;
    }

    @Override
    public boolean isInstalled() {
        return missing().isEmpty();
    }

    @Override
    public int sshPort() {
        return SSH_PORT;
    }

    @Override
    public boolean supports(Capability capability) {
        switch (capability) {
            case SECCOMP:
            case DISK_RESIZE:
            case USB_PASSTHROUGH:
                return true;
            default:
                return false;
        }
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public String guestPrompt() {
        return "";
    }

    @Override
    public ArrayList<String> exec(String command) {
        if (!isReady() && !startBlocking(null)) {
            GuestExec.logToStore("the UML guest is not running — start it from the dashboard");
            return new ArrayList<>();
        }
        return GuestExec.run(command);
    }

    @Override
    public GuestExec.Session openStream(String command) throws IOException {
        if (!isReady()) startBlocking(null);
        return GuestExec.openJob(command);
    }

    @Override
    public File shareDir() {
        File preferred = RootlessEngine.get(app).resolveShareDir();
        if (preferred != null) return preferred;
        File fallback = new File(base(), "share");
        fallback.mkdirs();
        return fallback;
    }

    private volatile UmlUsb usb;
    private volatile boolean usbDriverOk;

    private static final long WLAN_APPEAR_TIMEOUT_MS = 45_000;

    @Override
    public GuestUsb usb() {
        UmlUsb u = usb;
        if (u == null) {
            synchronized (this) {
                u = usb;
                if (u == null) usb = u = new UmlUsb(app, this);
            }
        }
        return u;
    }

    @Override
    public boolean ensureUsbWifiAttached() {
        if (!isReady() && !startBlocking(null)) return false;
        GuestUsb u = usb();
        int candidates = u.pickWifiDevices().size();
        if (candidates == 0) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: nothing that looks like a WiFi adapter is plugged "
                    + "into the phone");
            return false;
        }
        int count = u.attachAllWifiDongles(20_000);
        if (count <= 0) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: no adapter could be passed into the guest");
            return false;
        }
        if (candidates > 1) {
            GuestExec.logToStore("USB adapters: " + count + " of " + candidates
                    + " passed into the guest");
        }
        return awaitGuestWlan(WLAN_APPEAR_TIMEOUT_MS, count);
    }

    @Override
    public boolean usbDriverOk() {
        return usbDriverOk;
    }

    private boolean awaitGuestWlan(long timeoutMs, int expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        java.util.List<String> ifs;
        while (true) {
            ifs = GuestExec.wirelessInterfaces();
            if (ifs.size() >= Math.max(expected, 1)) break;
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        if (ifs.isEmpty()) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: DRIVER MISSING \u2014 the dongle is in the guest but "
                    + "'iw dev' shows no interface after " + (timeoutMs / 1000) + "s. The device "
                    + "node is still usable by a userspace driver; for an in-kernel one, install "
                    + "the firmware for this chipset from the Terminal.");
            return false;
        }
        usbDriverOk = true;
        if (ifs.size() < expected) {
            GuestExec.logToStore("USB adapters: only " + ifs.size() + " of " + expected
                    + " bound a driver \u2014 guest exposes " + ifs);
        } else {
            GuestExec.logToStore("USB adapter: driver OK \u2014 guest exposes " + ifs);
        }
        return true;
    }

    private static final String SHIM_PATH = "/usr/local/lib/stryker/systemctl";

    private void ensureSystemctlShim() {
        try {
            java.util.List<String> have = GuestExec.run(
                    "[ -x " + SHIM_PATH + " ] && [ -L /usr/local/sbin/systemctl ] "
                            + "&& echo __HAVE__ || echo __NEED__");
            for (String l : have) {
                if (l != null && l.trim().equals("__HAVE__")) return;
            }
            java.io.File staged = new java.io.File(shareDir(), "systemctl-shim");
            try (java.io.InputStream in = app.getAssets().open("systemctl-shim");
                 java.io.OutputStream out = new java.io.FileOutputStream(staged)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            GuestExec.run("mkdir -p /usr/local/lib/stryker && "
                    + "sed 's/\r$//' " + guestSharePath() + "/systemctl-shim > " + SHIM_PATH
                    + " && chmod 0755 " + SHIM_PATH
                    + " && ln -sf " + SHIM_PATH + " /usr/local/sbin/systemctl");
            staged.delete();
            GuestExec.logToStore("installed a systemctl that works without systemd "
                    + "(this guest's init is not systemd, so services start through it)");
        } catch (Exception e) {
            StrykerLog.w(TAG, "could not install the systemctl shim: " + e.getMessage());
        }
    }

    private volatile Thread portMirror;
    private final java.util.Set<Integer> mirrored =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());

    private static final long MIRROR_PERIOD_MS = 10_000;

    private void startPortMirror() {
        if (portMirror != null) return;
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && isRunning()) {
                try {
                    syncPorts();
                    Thread.sleep(MIRROR_PERIOD_MS);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    try { Thread.sleep(MIRROR_PERIOD_MS); } catch (InterruptedException e) { return; }
                }
            }
        }, "uml-port-mirror");
        t.setDaemon(true);
        t.start();
        portMirror = t;
    }

    private void stopPortMirror() {
        Thread t = portMirror;
        portMirror = null;
        if (t != null) t.interrupt();
        synchronized (mirrored) {
            for (Integer port : mirrored) GuestSsh.unforwardLocalPort(port);
            mirrored.clear();
        }
    }

    private void syncPorts() {
        if (!isReady()) return;
        java.util.Set<Integer> live = new java.util.HashSet<>();
        for (String line : GuestExec.run(
                "ss -ltnH 2>/dev/null | awk '{print $4}' | sed 's/.*://' | sort -un")) {
            if (line == null) continue;
            String v = line.trim();
            if (v.isEmpty()) continue;
            try {
                int port = Integer.parseInt(v);
                if (port <= 1024 || port > 65535) continue;
                if (port == RootlessPaths.GUEST_SSH_PORT || port == SSH_PORT) continue;
                live.add(port);
            } catch (NumberFormatException ignored) {
            }
        }

        for (int port : live) {
            if (mirrored.contains(port)) continue;
            if (GuestSsh.forwardLocalPort(port, port)) {
                mirrored.add(port);
                GuestExec.logToStore("guest port " + port + " is now reachable at 127.0.0.1:" + port);
            } else {
                mirrored.add(port);
            }
        }
        java.util.List<Integer> gone = new ArrayList<>();
        synchronized (mirrored) {
            for (Integer port : mirrored) if (!live.contains(port)) gone.add(port);
        }
        for (int port : gone) {
            GuestSsh.unforwardLocalPort(port);
            mirrored.remove(port);
        }
    }

    @Override
    public String guestSharePath() {
        return "/host";
    }

    @Override
    public boolean forwardPort(int hostPort, int guestPort) {
        if (!isRunning()) return false;
        return GuestSsh.forwardLocalPort(hostPort, guestPort);
    }

    @Override
    public boolean unforwardPort(int hostPort) {
        return GuestSsh.unforwardLocalPort(hostPort);
    }

    @Override
    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    @Override
    public boolean isReady() {
        return isRunning() && GuestExec.ping(1000);
    }

    @Override
    public State status() {
        if (!isRunning()) return State.STOPPED;
        boolean fresh = System.currentTimeMillis() - lastGuestOk < GUEST_FRESH_MS;
        return fresh && lastGuestOk >= GuestSsh.lastLossAt() ? State.READY : State.BOOTING;
    }

    @Override
    public State statusBlocking() {
        if (!isRunning()) return State.STOPPED;
        if (GuestExec.ping(1500)) {
            lastGuestOk = System.currentTimeMillis();
            return State.READY;
        }
        return State.BOOTING;
    }

    @Override
    public java.util.List<String> consoleTail(int lines) {
        java.util.List<String> tail = new ArrayList<>();
        try (java.io.BufferedReader r =
                     new java.io.BufferedReader(new java.io.FileReader(console()))) {
            String line;
            while ((line = r.readLine()) != null) {
                tail.add(line);
                if (tail.size() > lines) tail.remove(0);
            }
        } catch (Exception ignored) {
        }
        return tail;
    }

    @Override
    public void startAsync() {
        executor.submit(() -> startBlocking(null));
    }

    @Override
    public boolean stopAndWait(long timeoutMs) {
        stop();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (isRunning() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return !isRunning();
    }

    @Override
    public boolean startBlocking(BootListener listener) {
        stopRequested = false;
        StrykerLog.i(TAG, "startBlocking: installed=" + isInstalled() + " running=" + isRunning());
        try {
            if (isRunning() && GuestExec.ping(BOOT_PING_TIMEOUT_MS)) {
                ensureSystemctlShim();
                startPortMirror();
                if (listener != null) listener.onBooted();
                return true;
            }
            List<String> gaps = missing();
            if (!gaps.isEmpty()) {
                return fail(listener, "not installed: " + android.text.TextUtils.join(", ", gaps));
            }
            File share = shareDir();
            com.zalexdev.stryker.utils.Core prefs = new com.zalexdev.stryker.utils.Core(app);
            int ramMb = prefs.getInt(VmSpecs.K_RAM, 0);
            int cpus = prefs.getInt(VmSpecs.K_CPUS, 0);
            if (ramMb <= 0) ramMb = 2048;

            start(share, ramMb, cpus, true);
            if (listener != null) listener.onBootLine("UML kernel started");

            for (int i = 0; i < 150 && !stopRequested; i++) {
                if (!isRunning()) {
                    Integer code = exitCode();
                    logConsoleTail();
                    return fail(listener, "the guest exited"
                            + (code != null ? " (" + code + ")" : "") + ": " + lastConsoleProblem());
                }
                if (GuestSsh.guestReported()) {
                    if (listener != null && i % 5 == 0) listener.onBootLine("guest up, opening ssh");
                    if (GuestExec.ping(BOOT_PING_TIMEOUT_MS)) {
                        lastError = "";
                        lastGuestOk = System.currentTimeMillis();
                        ensureSystemctlShim();
                        startPortMirror();
                        if (listener != null) listener.onBooted();
                        return true;
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
            if (stopRequested) return false;
            return fail(listener, "the guest did not answer within 150s — "
                    + lastConsoleProblem());
        } catch (Exception e) {
            StrykerLog.w(TAG, "start failed", e);
            return fail(listener, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void logConsoleTail() {
        List<String> tail = consoleTail(12);
        if (tail.isEmpty()) return;
        StringBuilder sb = new StringBuilder("UML engine: last lines of the guest console:");
        for (String l : tail) sb.append("\n  ").append(l);
        GuestExec.logToStore(sb.toString());
    }

    private boolean fail(BootListener listener, String reason) {
        if (reason != null && reason.contains("MADV_REMOVE")) {
            String hint = memoryBackingHint();
            if (hint != null) reason = reason + " — " + hint;
        }
        lastError = reason;
        StrykerLog.w(TAG, "boot failed: " + reason);
        GuestExec.logToStore("UML engine: " + reason);
        if (listener != null) listener.onFailed(reason);
        return false;
    }

    private String lastConsoleProblem() {
        try {
            List<String> tail = new ArrayList<>();
            try (java.io.BufferedReader r =
                         new java.io.BufferedReader(new java.io.FileReader(console()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    tail.add(line);
                    if (tail.size() > 200) tail.remove(0);
                }
            }
            return BootDiagnosis.reason(tail, VmBootStage.detect(tail)) + hostNotes(tail);
        } catch (Exception e) {
            return "see " + console().getName();
        }
    }

    private static boolean looksLikeAnExplanation(String l) {
        if (l == null || l.trim().isEmpty()) return false;
        if (l.contains("Kernel panic") || l.contains("Aborted")) return true;
        String lower = l.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cannot") || lower.contains("couldn't") || lower.contains("could not")
                || lower.contains("failed") || lower.contains("error")
                || lower.contains("denied") || lower.contains("no such")
                || lower.contains("address already in use") || lower.contains("unable to");
    }

    private String hostNotes(List<String> tail) {
        StringBuilder sb = new StringBuilder();
        for (String l : tail) {
            if (l == null) continue;
            if (l.contains("Userspace mode:") || l.contains("tempdir")
                    || l.startsWith("Checking that seccomp")) {
                sb.append("\n  ").append(l.trim());
            }
        }
        return sb.toString();
    }

    public synchronized void start(File shareDir, int memoryMb, int cpus, boolean seccomp)
            throws IOException {
        if (isRunning()) return;

        List<String> gaps = missing();
        if (!gaps.isEmpty()) {
            throw new IOException("the UML engine cannot start, missing: " + String.join(", ", gaps));
        }
        if (shareDir == null) {
            throw new IOException("the UML engine needs a share directory: it is how the app's ssh "
                    + "key reaches the guest and how the guest's host key comes back");
        }
        int reaped = reapStrayGuests(4000);
        if (reaped > 0) {
            GuestExec.logToStore("cleared " + reaped
                    + " guest process(es) left over from a previous run before starting");
        }

        base().mkdirs();
        shareDir.mkdirs();
        new File(shareDir, "firmware").mkdirs();

        GuestSsh.configure(app, shareDir, SSH_PORT);
        try {
            GuestSsh.publishPublicKey();
        } catch (Exception e) {
            String why = e.getMessage() != null && !e.getMessage().isEmpty()
                    ? e.getMessage() : e.getClass().getName();
            throw new IOException("could not place the ssh key in the share: " + why, e);
        }

        String execProblem = NativeExec.check(app, umnet(), passt(), kernel(), stub());
        if (execProblem != null) {
            GuestExec.logToStore(execProblem);
            throw new IOException(execProblem);
        }

        List<String> cmd = buildCommand(shareDir, memoryMb, cpus, seccomp);
        StrykerLog.i(TAG, "starting: " + String.join(" ", cmd));
        GuestExec.logToStore("UML engine starting, share " + shareDir.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(base());
        String ownTmp = fallbackTempDir();
        if (ownTmp != null) {
            pb.environment().put("TMPDIR", ownTmp);
            GuestExec.logToStore("no host tempdir: guest memory will live in " + ownTmp);
        }
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException(NativeExec.explain(umnet(), e), e);
        }
        process = p;
        startConsolePump(p);
    }

    private List<String> buildCommand(File share, int memoryMb, int cpus, boolean seccomp) {
        List<String> cmd = new ArrayList<>();

        cmd.add(umnet().getAbsolutePath());
        cmd.add("--passt");
        cmd.add(passt().getAbsolutePath());
        cmd.add("--fwd");
        cmd.add("127.0.0.1/" + SSH_PORT + ":" + RootlessPaths.GUEST_SSH_PORT);
        cmd.add("--");

        cmd.add(kernel().getAbsolutePath());
        cmd.add("mem=" + Math.max(memoryMb, 512) + "M");
        cmd.add("panic=-1");
        cmd.add("con=null");
        cmd.add("con0=fd:0,fd:1");
        cmd.add("stub_exe=" + stub().getAbsolutePath());
        cmd.add("seccomp=" + (seccomp ? "auto" : "off"));
        if (cpus > 0) cmd.add("ncpus=" + cpus);
        cmd.add("ubd0=" + rootfs().getAbsolutePath());
        cmd.add("root=/dev/ubda");
        cmd.add("rw");
        cmd.add("init=/stryker-init");
        cmd.add("stryker.share=" + share.getAbsolutePath());
        cmd.add("firmware_class.path=/host/firmware");
        return cmd;
    }

    private void startConsolePump(final Process p) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                         new java.io.InputStreamReader(p.getInputStream()));
                 java.io.PrintWriter out = new java.io.PrintWriter(
                         new java.io.FileWriter(console(), false))) {
                String line;
                while ((line = in.readLine()) != null) {
                    out.println(line);
                    out.flush();
                    if (line.startsWith("STRYKER_BOOT") || line.startsWith("STRYKER_INIT")) {
                        StrykerLog.i(TAG, line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "uml-console");
        t.setDaemon(true);
        t.start();
        consolePump = t;
    }

    private int reapStrayGuests(long graceMs) {
        java.util.List<Integer> pids = findGuestPids();
        if (pids.isEmpty()) return 0;

        StrykerLog.i(TAG, "reaping " + pids.size() + " stray guest process(es): " + pids);
        for (int pid : pids) {
            android.os.Process.sendSignal(pid, 15);
        }
        long deadline = System.currentTimeMillis() + graceMs;
        while (System.currentTimeMillis() < deadline && !findGuestPids().isEmpty()) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        java.util.List<Integer> left = findGuestPids();
        for (int pid : left) {
            StrykerLog.w(TAG, "guest pid " + pid + " ignored SIGTERM, killing");
            android.os.Process.killProcess(pid);
        }
        deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && !findGuestPids().isEmpty()) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return pids.size();
    }

    private java.util.List<Integer> findGuestPids() {
        java.util.List<Integer> out = new ArrayList<>();
        String marker = nativeDir().getAbsolutePath();
        File proc = new File("/proc");
        String[] entries = proc.list();
        if (entries == null) return out;
        for (String entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == android.os.Process.myPid()) continue;
            try (java.io.FileInputStream in =
                         new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                if (n <= 0) continue;
                String cmd = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8)
                        .replace('\0', ' ');
                if (cmd.contains(marker)
                        && (cmd.contains("libuml.so") || cmd.contains("libumnet.so")
                            || cmd.contains("libpasst.so"))) {
                    out.add(pid);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    @Override
    public synchronized void stop() {
        stopRequested = true;
        stopPortMirror();
        UmlUsb u = usb;
        if (u != null) u.detachAll();
        usbDriverOk = false;
        GuestSsh.disconnect();
        Process p = process;
        process = null;
        if (p == null) return;
        try {
            GuestExec.run("nohup poweroff >/dev/null 2>&1 &");
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < 30 && p.isAlive(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        if (p.isAlive()) p.destroy();
        for (int i = 0; i < 10 && p.isAlive(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        if (p.isAlive()) p.destroyForcibly();
        reapStrayGuests(4000);
        Thread t = consolePump;
        if (t != null) t.interrupt();
        consolePump = null;
    }

    public Integer exitCode() {
        Process p = process;
        if (p == null || p.isAlive()) return null;
        return p.exitValue();
    }
}
