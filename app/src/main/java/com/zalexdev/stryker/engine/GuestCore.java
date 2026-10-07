package com.zalexdev.stryker.engine;

import android.content.Context;

import com.zalexdev.stryker.utils.Core;

import java.io.File;
import com.stryker.terminal.bridge.StrykerLog;

public final class GuestCore {

    public static final String ASSET = GuestCorePackage.ASSET;
    public static final String MARKER = "/CORE/PixieWps/pixie.py";
    public static final String VERSION_FILE = "/CORE/.version";
    public static final String VERSION = "7";

    private static final String TAG = "GuestCore";
    private static final String STAGED_NAME = "stryker-core.tar";

    private GuestCore() {
    }

    public static boolean ensure(Core core) {
        if (core == null) return false;
        if (core.isRootless()) return core.rootless().ensureGuestCore();
        return ensureChroot(core);
    }

    public static boolean deploy(Core core) {
        if (core == null) return false;
        if (core.isRootless()) return core.rootless().deployGuestCore();
        return deployChroot(core);
    }

    private static boolean ensureChroot(Core core) {
        if (core.checkFile(Core.CHROOT_ROOT + MARKER)
                && VERSION.equals(readVersion(core))) {
            return true;
        }
        return deployChroot(core);
    }

    private static String readVersion(Core core) {
        for (String l : core.customCommand("cat " + Core.CHROOT_ROOT + VERSION_FILE
                + " 2>/dev/null", true)) {
            if (l != null && !l.trim().isEmpty()) return l.trim();
        }
        return "";
    }

    private static boolean deployChroot(Core core) {
        Context ctx = core.context;
        if (ctx == null) return false;
        File staged = new File(ctx.getFilesDir(), STAGED_NAME);
        try {
            GuestCorePackage.extractTo(ctx, staged);
        } catch (Exception e) {
            StrykerLog.w(TAG, "staging failed: " + e.getMessage());
            staged.delete();
            return false;
        }
        core.customCommand("mkdir -p " + Core.CHROOT_ROOT);
        core.customCommand(Core.BUSYBOX + "tar xf " + staged.getAbsolutePath()
                + " -C " + Core.CHROOT_ROOT);
        core.customCommand("chmod -R 0755 " + Core.CHROOT_ROOT + "/CORE "
                + Core.CHROOT_ROOT + "/exploits");
        staged.delete();
        return core.checkFile(Core.CHROOT_ROOT + MARKER);
    }
}
