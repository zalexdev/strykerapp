package com.zalexdev.stryker.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class Slide2 extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private TextView title;
    private TextView rootSub, storageSub, batterySub;
    private ImageView rootStatus, storageStatus, batteryStatus;
    private ProgressBar rootSpinner, storageSpinner, batterySpinner;
    private MaterialButton button;

    private boolean rootChecked = false;
    private boolean rootGranted = false;

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide2, container, false);
        activity = getActivity();
        if (activity == null) return view;
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        title = view.findViewById(R.id.slide_title);
        button = view.findViewById(R.id.login);

        rootSub = view.findViewById(R.id.root_subtitle);
        storageSub = view.findViewById(R.id.storage_subtitle);
        batterySub = view.findViewById(R.id.battery_subtitle);
        rootStatus = view.findViewById(R.id.root_status);
        storageStatus = view.findViewById(R.id.storage_status);
        batteryStatus = view.findViewById(R.id.battery_status);
        rootSpinner = view.findViewById(R.id.root_spinner);
        storageSpinner = view.findViewById(R.id.storage_spinner);
        batterySpinner = view.findViewById(R.id.battery_spinner);

        refreshStatuses();
        button.setOnClickListener(view12 -> tryGrant());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (rootStatus != null && context != null && core != null) refreshStatuses();
    }

    private void tryGrant() {
        setPip(rootSpinner, rootStatus, true, false);
        setPip(storageSpinner, storageStatus, true, false);
        setPip(batterySpinner, batteryStatus, true, false);

        if (core.isRootless()) {
            core.requestAllFilesAccess(activity);
        }

        new Thread(() -> {
            core.checkPermission(activity);
            boolean rootless = core.isRootless();
            boolean rooted = !rootless && core.checkRoot();
            rootChecked = true;
            rootGranted = rooted;

            if (rooted || rootless) {
                if (rooted) {
                    core.customCommand("pm grant com.zalexdev.stryker android.permission.WRITE_EXTERNAL_STORAGE", true);
                    core.customCommand("pm grant com.zalexdev.stryker android.permission.READ_EXTERNAL_STORAGE", true);
                    core.customCommand("dumpsys deviceidle whitelist +com.zalexdev.stryker", true);
                }

                core.putString("vnc_passwd", "stryker");
                if (rooted) {
                    ArrayList<String> interfaces = core.getInterfacesList();
                    if (interfaces.contains("swlan0")) {
                        core.putString("wlan_scan", "swlan0");
                        core.putString("wlan_wifi", "swlan0");
                        core.putString("wlan_deauth", "swlan0");
                        core.putString("wlan_wps", "swlan0");
                    } else {
                        core.putString("wlan_scan", "wlan0");
                        core.putString("wlan_deauth", "wlan0");
                        core.putString("wlan_wifi", "wlan0");
                        core.putString("wlan_wps", "wlan0");
                    }
                } else {
                    core.putString("wlan_scan", "wlan0");
                    core.putString("wlan_deauth", "wlan0");
                    core.putString("wlan_wifi", "wlan0");
                    core.putString("wlan_wps", "wlan0");
                }
                core.putInt("max_par", 3);
                core.remove("installed_modules");
                core.putBoolean("first_open", true);
                core.putBoolean("store_scan", true);
                core.putBoolean("auto_update", true);
                copyAssets();
                core.putBoolean("save_aps", true);
                core.putBoolean("autoScan", true);
                core.putBoolean("dash", true);
                core.putInt("night", 2);
                core.putInt("threads", 100);
                if (rooted) {
                    core.chmodFolder("/data/data/com.zalexdev.stryker/files");
                }

                uiSafe(() -> {
                    refreshStatuses();
                    if (rooted) {
                        boolean alreadyInstalled = core.checkFolder("/data/stryker/release/sdcard/Stryker")
                                && core.checkFile(Core.CHROOT_MARKER);
                        if (alreadyInstalled) {
                            ((AppIntroActivity) activity).jumpToLast();
                            return;
                        }
                    }
                    core.moveNext(mPager);
                });
            } else {
                uiSafe(() -> {
                    refreshStatuses();
                    title.setText(context.getResources().getString(R.string.permissions_is_not_granted));
                    button.setText(context.getResources().getString(R.string.permissions_check_again));
                    button.setIconResource(R.drawable.done);
                });
            }
        }).start();
    }

    private void refreshStatuses() {
        boolean storageOk = storageGranted();
        boolean batteryOk = batteryWhitelisted();

        boolean rootless = core.isRootless();
        applyStatus(rootSpinner, rootStatus, rootSub, rootless || (rootChecked && rootGranted),
                rootless ? "Not required — rootless VM engine" : "Granted — superuser ready",
                "Required to mount the chroot and run privileged tools");
        applyStatus(storageSpinner, storageStatus, storageSub, storageOk,
                "Granted — can read/write storage",
                "Reads/writes wordlists, scans and captured handshakes");
        applyStatus(batterySpinner, batteryStatus, batterySub, batteryOk,
                "Whitelisted — scans survive in background",
                "Keeps long scans alive in the background");
    }

    private void applyStatus(ProgressBar spinner, ImageView icon, TextView subtitle,
                             boolean ok, String subOk, String subPending) {
        spinner.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        if (ok) {
            icon.setImageResource(R.drawable.done);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.green), PorterDuff.Mode.SRC_IN);
            subtitle.setText(subOk);
        } else {
            icon.setImageResource(R.drawable.question);
            icon.setColorFilter(ContextCompat.getColor(context, R.color.grey), PorterDuff.Mode.SRC_IN);
            subtitle.setText(subPending);
        }
    }

    private void setPip(ProgressBar spinner, ImageView icon, boolean working, boolean ok) {
        if (working) {
            spinner.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
        } else {
            spinner.setVisibility(View.GONE);
            icon.setVisibility(View.VISIBLE);
        }
    }

    private boolean storageGranted() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
                    || context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryWhitelisted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations("com.zalexdev.stryker");
        } catch (Throwable t) {
            return false;
        }
    }

    private void copyAssets() {
        AssetManager assetManager = activity.getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            Log.e("Slide2", "Failed to get asset file list.", e);
        }
        if (files == null) return;
        for (String filename : files) {
            if (filename.equals(Core.BUSYBOX_ASSET)) continue;
            if (filename.equals("rootless")) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(filename, AssetManager.ACCESS_STREAMING);
                @SuppressLint("SdCardPath") File outFile = new File("/data/data/com.zalexdev.stryker/files/", filename);
                out = new FileOutputStream(outFile);
                copyFile(in, out);
                out.flush();
                Log.d("Slide2", "Copied asset: " + filename + " size: " + outFile.length());
            } catch (IOException ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
        Core.extractBusybox(activity);
        if (!core.isRootless()) {
            core.customCommand("dos2unix /data/data/com.zalexdev.stryker/files/*.sh", true);
            core.customCommand("dos2unix /data/data/com.zalexdev.stryker/files/*root*", true);
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void uiSafe(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> {
            if (isAdded()) r.run();
        });
    }
}
