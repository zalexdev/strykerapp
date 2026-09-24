package com.zalexdev.stryker.utils;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.content.Context.VIBRATOR_SERVICE;
import static android.content.Context.WIFI_SERVICE;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Environment.getExternalStorageDirectory;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.zalexdev.stryker.BuildConfig;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.Credentials;
import com.zalexdev.stryker.custom.Device;
import com.zalexdev.stryker.custom.Exploit;
import com.zalexdev.stryker.custom.Module;
import com.zalexdev.stryker.custom.Site;
import com.zalexdev.stryker.engine.Apt;
import com.zalexdev.stryker.engine.EngineType;
import com.zalexdev.stryker.engine.GuestCore;
import com.zalexdev.stryker.engine.RootlessEngine;
import com.zalexdev.stryker.logger.LogTool;
import com.zalexdev.stryker.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;


public class Core {

    public final static String EXECUTE = "/data/data/com.zalexdev.stryker/files/chroot_exec ";
    public final static String BUSYBOX = "/data/data/com.zalexdev.stryker/files/busybox ";
    public final static String SHELL = "bash";
    public final static String CHROOT_ROOT = "/data/stryker/release";

    /** Marker written after a successful chroot install. The name IS the rootfs generation:
     *  "4.0" is the old Alpine tree, "6.0" the Debian one. */
    public final static String CHROOT_MARKER_VERSION = "6.0";
    public final static String CHROOT_MARKER = CHROOT_ROOT + "/" + CHROOT_MARKER_VERSION;
    private final static String[] LEGACY_CHROOT_MARKERS = {"4.0"};
    public final static String HIDDEN_MAC = "XX:XX:XX:XX:XX:XX";
    public final String versionName = BuildConfig.VERSION_NAME;
    public final int versionInt = BuildConfig.VERSION_CODE;
    public HashMap<String, String> vendorDB = new HashMap<String, String>();
    private final SharedPreferences preferences;
    public Context context;
    public Process process;
    public MonitorManager monitorManager;
    public Logger logger;
    public SQLiteDatabase db;
    public SQLiteDatabase dbCodename;
    public SQLiteDatabase dbAdapters;
    public Core(Context context) {

        SharedPreferences preferences1;
        this.context = context;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    preferences1 =  EncryptedSharedPreferences.create(
                    "touchMeAndGetPhoneReset",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            e.printStackTrace();
            try {
                String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
                    preferences1 =  EncryptedSharedPreferences.create(
                    "touchMeAndGetPhoneReset",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ); } catch (GeneralSecurityException | IOException ex) {
            ex.printStackTrace();
            preferences1 = PreferenceManager.getDefaultSharedPreferences(context);
        }}
        preferences = preferences1;
        logger = new Logger();
        monitorManager = new MonitorManager(this);
    }

    public Context getContext() {
        return context;
    }

    public int connectWiFi2(String ssid, String psk){
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = String.format("\"%s\"", ssid);
        wifiConfig.preSharedKey = String.format("\"%s\"",psk);
        WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
        int netId = wifiManager.addNetwork(wifiConfig);
        wifiManager.disconnect();
        wifiManager.enableNetwork(netId, true);
        wifiManager.reconnect();
        return netId;
    }

    public void deleteWifi(int netid){
        WifiManager wifiManager = (WifiManager)context.getSystemService(WIFI_SERVICE);
        wifiManager.removeNetwork(netid);
    }

    public void saveNetwork(String bssid, String psk, String pin, String ssid){
        ArrayList<String> nw = new ArrayList<>();
        nw.add(psk);
        nw.add(pin);
        nw.add(ssid);
        nw.add(bssid);
        putListString(bssid,nw);
        addSavedNetwork(bssid);
    }
    public ArrayList<ArrayList<String>> getSavedNetworks(){
        ArrayList<String> bssids = getListString("bssids");
        ArrayList<ArrayList<String>> networks = new ArrayList<>();
        for (String bssid : bssids) {
            networks.add(getListString(bssid));
        }
        return networks;
    }

    public void addSavedNetwork(String bssid){
        ArrayList<String> bssids = getListString("bssids");
        bssids.add(bssid);
        putListString("bssids",bssids);
    }
    public void removeSavedNetwork(String bssid){
        ArrayList<String> bssids = getListString("bssids");
        bssids.remove(bssid);
        putListString("bssids",bssids);
        remove(bssid);
    }

    public void copyToClipBoard(String s){
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Stryker", s);
        clipboard.setPrimaryClip(clip);
    }
    public ArrayList<String> getNetwork(String bssid){
        return getListString(bssid);
    }


    public int getInt(String key) {
        return preferences.getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        return preferences.getInt(key, defaultValue);
    }


    public String getString(String key) {
        return preferences.getString(key, "");
    }
    public ArrayList<String> getListString(String key) {
        return new ArrayList<>(Arrays.asList(TextUtils.split(preferences.getString(key, ""), "‚‗‚")));
    }


    public boolean getBoolean(String key) {
        if (preferences != null){
            return preferences.getBoolean(key, false);}else{
            return false;
        }
    }

    public void putInt(String key, int value) {
        isNull(key);
        preferences.edit().putInt(key, value).apply();
    }


    public void putString(String key, String value) {
        isNull(key);
        preferences.edit().putString(key, value).apply();
    }

    public void putListString(String key, ArrayList<String> stringList) {
        isNull(key);
        String[] myStringList = stringList.toArray(new String[stringList.size()]);
        preferences.edit().putString(key, TextUtils.join("‚‗‚", myStringList)).apply();
    }

    public void putBoolean(String key, boolean value) {
        isNull(key);
        preferences.edit().putBoolean(key, value).apply();
    }

    public void remove(String key) {
        preferences.edit().remove(key).apply();
    }

    public boolean contains(String key) {
        return preferences != null && preferences.contains(key);
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    private void isNull(String key) {
        if (key == null) {
            throw new NullPointerException();
        }
    }


    public Logger getLogger() {
        return logger;
    }

    public void toaster(String msg) {
        Toast toast = Toast.makeText(context,
                msg, Toast.LENGTH_SHORT);
        toast.show();
    }
    public void toaster(Activity activity,String msg) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast toast = Toast.makeText(activity,
                        msg, Toast.LENGTH_SHORT);
                toast.show();
            }
        });

    }





    public void vibrate(int mil) {
        if (SDK_INT >= 26) {
            ((Vibrator) context.getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(mil, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            ((Vibrator) context.getSystemService(VIBRATOR_SERVICE)).vibrate(mil);
        }
    }

    /**
     * The path the app process can use to reach {@code path}, or null when it is not somewhere
     * the app can read directly.
     *
     * Three spellings of the same directory are in use across the codebase: the guest/chroot
     * path (/sdcard/Stryker/...), the host path (getStorage() + "Stryker/..."), and getShareRoot().
     * Under root the last two are the same directory, but when rootless the share lives somewhere
     * else entirely, so both of the first two have to be redirected onto the share root.
     */
    public String hostPath(String path) {
        if (path == null || path.isEmpty()) return null;
        final String guestRoot = "/sdcard/Stryker";
        if (path.startsWith(guestRoot)) return getShareRoot() + path.substring(guestRoot.length());
        String legacyRoot = getStorage() + "Stryker";
        if (path.startsWith(legacyRoot)) return getShareRoot() + path.substring(legacyRoot.length());
        if (path.startsWith(getStorage())) return path;
        return null;
    }

    /**
     * Names of the files in {@code parentDir}.
     *
     * Shared storage is listed through the app process in every mode, deliberately. A root shell
     * does not necessarily see /storage/emulated/0 the way the app does — each app gets its own
     * mount namespace and plain `su` stays outside it, so `ls` can come back empty for a folder
     * the user is looking at in a file manager. Listing through the app is what makes the UI
     * agree with the user; only paths the app genuinely cannot read fall through to the shell.
     */
    public ArrayList<String> getListFiles(String parentDir) {
        String host = hostPath(parentDir);
        if (host != null) {
            ArrayList<String> names = new ArrayList<>();
            File[] fs = new File(host).listFiles();
            if (fs != null) for (File f : fs) names.add(f.getName());
            return names;
        }
        return customCommand("ls "+parentDir);
    }


    public void saveExploit(Exploit exploit){
        ArrayList<String> exploits = getListString("exploits");
        exploits.add(parseExploit(exploit));
        putListString("exploits",exploits);
    }
    public String parseExploit(Exploit exploit){
        JSONObject exp = new JSONObject();
        try {
            exp.put("title",exploit.getTitle());
            exp.put("path",exploit.getPath());
            exp.put("pattern",exploit.getSuccesspatern());
            exp.put("lang",exploit.getLang());
            exp.put("args",exploit.getArgs());
            exp.put("issys",exploit.getIssystem());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return exp.toString();
    }

    public Exploit getExploitByTitle(String title){
        Exploit t = new Exploit();
        ArrayList<Exploit> exploits = getExploits();
        for (Exploit e: exploits){
            if (e.getTitle().equals(title)){
                t = e;
                break;
            }
        }
        return t;
    }
    public void updateExploits(){
        customChrootCommand("rm -rf /exploits; mkdir -p /exploits; "
                + "cp -f /sdcard/Stryker/exploits/* /exploits/ 2>/dev/null; "
                + "chmod -R 0755 /exploits", true);
    }

    public void deleteExploit(int id){
        ArrayList<String> exploits = getListString("exploits");
        exploits.remove(id);
        putListString("exploits",exploits);
    }
    public Exploit unparseExploit(String exploitstring){
        Exploit exploit = new Exploit();
        try {
            JSONObject exp = new JSONObject(exploitstring);
            exploit.setTitle(exp.getString("title"));
            exploit.setPath(exp.getString("path"));
            exploit.setSuccesspatern(exp.getString("pattern"));
            exploit.setLang(exp.getString("lang"));
            exploit.setArgs(exp.getString("args"));
            exploit.setIssystem(exp.getBoolean("issys"));
        } catch (JSONException e) {e.printStackTrace();}
        return exploit;
    }
    public ArrayList<Exploit> getExploits(){
        ArrayList<Exploit> list= new ArrayList<>();
        ArrayList<String> exploits = getListString("exploits");
        for (String e : exploits){
            list.add(unparseExploit(e));
        }
        if (!exploits.toString().contains("EternalBlue")){
            Exploit eternal = new Exploit();
            eternal.setTitle("EternalBlue");
            eternal.setPath("eternalscan.py");
            eternal.setArgs(" {IP}");
            eternal.setIssystem(true);
            eternal.setSuccesspatern("VUNLFOUNDED");
            eternal.setLang("Python");
            saveExploit(eternal);
        }
        if (!exploits.toString().contains("SMBGhost")){
            Exploit ghost = new Exploit();
            ghost.setTitle("SMBGhost");
            ghost.setPath("ghostscanner.py");
            ghost.setArgs(" {IP}");
            ghost.setIssystem(true);
            ghost.setSuccesspatern("VUNLFOUNDED");
            ghost.setLang("Python");
            saveExploit(ghost);
        }
        if (!exploits.toString().contains("Bluekeep")){
            Exploit blue = new Exploit();
            blue.setTitle("Bluekeep");
            blue.setPath("bluekeepscan.py");
            blue.setArgs(" {IP}");
            blue.setIssystem(true);
            blue.setSuccesspatern("VULNERABLE");
            blue.setLang("Python");
            saveExploit(blue);
        }
        if (!exploits.toString().contains("CVE-2022-27255")){
            Exploit cve = new Exploit();
            cve.setTitle("CVE-2022-27255");
            cve.setPath("checker.py");
            cve.setArgs(" {IP} {PORT}");
            cve.setIssystem(true);
            cve.setSuccesspatern("Target vulnerable");
            cve.setLang("Python");
            saveExploit(cve);
        }
        return list;
    }

    public static boolean isArm64() {
        if (Build.SUPPORTED_ABIS == null) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }
    public void scale(View v, Float x){
        v.animate().scaleY(x);
        v.animate().scaleX(x);
    }

    @Deprecated
    public String getDeviceNameByPid(String vidPid) {
        if (vidPid == null || !vidPid.contains(":")) return "Unknown";
        String[] parts = vidPid.split(":");
        String raw = com.zalexdev.stryker.netdetect.LegacyDeviceDb.lookupRaw(context, parts[0], parts[1]);
        return raw != null ? raw : "Unknown";
    }

    public String getStorage() {
        return getExternalStorageDirectory().getAbsolutePath() + "/";
    }

    public String getShareRoot() {
        if (isRootless()) {
            File d = rootless().resolveShareDir();
            if (d != null) return d.getAbsolutePath();
        }
        return getStorage() + "Stryker";
    }
    public final static String PIXIE_HEURISTIC_ASSET = "routes.txt";
    public final static String PIXIE_VERIFIED_ASSET = "pixie_verified.txt";

    private static volatile ArrayList<String> pixieHeuristic;
    private static volatile HashSet<String> pixieVerified;

    private static String normalizeModel(String model) {
        if (model == null) return "";
        return model.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private ArrayList<String> readAssetLines(String asset) {
        ArrayList<String> lines = new ArrayList<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(context.getAssets().open(asset)));
            String line;
            while ((line = reader.readLine()) != null) {
                String v = normalizeModel(line);
                if (!v.isEmpty()) lines.add(v);
            }
        } catch (IOException e) {
            logger.writeLine("Cannot read " + asset + ": " + e.getMessage(), 3);
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        }
        return lines;
    }

    private ArrayList<String> heuristicModels() {
        ArrayList<String> cached = pixieHeuristic;
        if (cached == null) {
            cached = readAssetLines(PIXIE_HEURISTIC_ASSET);
            pixieHeuristic = cached;
        }
        return cached;
    }

    private HashSet<String> verifiedModels() {
        HashSet<String> cached = pixieVerified;
        if (cached == null) {
            cached = new HashSet<>(readAssetLines(PIXIE_VERIFIED_ASSET));
            pixieVerified = cached;
        }
        return cached;
    }

    public boolean isPixieVerified(String model) {
        String needle = normalizeModel(model);
        return !needle.isEmpty() && verifiedModels().contains(needle);
    }

    public boolean checkModel(String model){
        String needle = normalizeModel(model);
        if (needle.isEmpty()) return false;
        if (verifiedModels().contains(needle)) return true;
        for (String m : heuristicModels()) {
            if (needle.contains(m)) return true;
        }
        for (String m : getRouters()) {
            String v = normalizeModel(m);
            if (!v.isEmpty() && needle.contains(v)) return true;
        }
        return false;
    }

    public void installApplication(Context context, String filePath) {
        logger.writeLine("Installing update",1);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uriFromFile(context, new File(filePath)), "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            e.printStackTrace();
        }
    }


    private static Uri uriFromFile(Context context, File file) {
        return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file);
    }

    public ArrayList<Module> getModules(){
        return new ArrayList<>();
    }
    public void installModule(String name){
        logger.writeLine("Installing "+name,1);
        ArrayList<String> mods = getListString("installed_modules");
        mods.add(name);
        putListString("installed_modules",mods);
    }
    public boolean checkModule(String name){
        logger.writeLine("Checking if "+name+" is installed",1);
        ArrayList<String> mods = getListString("installed_modules");
        return mods.contains(name);
    }
    public void deleteModule(String name){
        logger.writeLine("Deleting module: "+name,1);
        ArrayList<String> mods = getListString("installed_modules");
        mods.remove(name);
        putListString("installed_modules",mods);
    }

    public String chrootPath(String guestPath){
        if (guestPath == null || guestPath.isEmpty()) return CHROOT_ROOT;
        return CHROOT_ROOT + (guestPath.startsWith("/") ? guestPath : "/" + guestPath);
    }

    public boolean isToolInstalled(String tool){
        switch (tool) {
            case "metasploit": return getBoolean("msf")
                    || guestFileExists("/opt/metasploit-framework/msfconsole",
                                       "/usr/local/bin/msfconsole")
                    || hasBinary("msfconsole");
            case "nuclei": return getBoolean("nuclei")
                    || guestFileExists("/usr/bin/nuclei", "/usr/local/bin/nuclei")
                    || hasBinary("nuclei");
            case "hydra": return getBoolean("hydra")
                    || guestFileExists("/usr/bin/hydra", "/usr/local/bin/hydra")
                    || hasBinary("hydra");
            case "cameradar": return getBoolean("cameradar")
                    || guestFileExists("/usr/bin/cameradar", "/usr/local/bin/cameradar")
                    || hasBinary("cameradar");
            case "searchsploit": return guestFileExists("/opt/exploitdb/searchsploit",
                                       "/usr/local/bin/searchsploit")
                    || hasBinary("searchsploit");
            default: return false;
        }
    }

    public boolean uninstallTool(String tool){
        logger.writeLine("Uninstalling tool: "+tool,1);
        switch (tool) {
            case "metasploit":
                guestRemove("/opt/metasploit-framework /opt/msfpc /usr/local/bin/msfconsole "
                        + "/usr/local/bin/msfvenom /usr/local/bin/msfdb /usr/local/bin/msfd "
                        + "/usr/local/bin/msfrpc /usr/local/bin/msfpc");
                customChrootCommand("snap remove metasploit-framework >/dev/null 2>&1 || true", true);
                putBoolean("msf", false);
                break;
            case "nuclei":
                guestRemove("/usr/bin/nuclei /usr/local/bin/nuclei /root/go/bin/nuclei");
                putBoolean("nuclei", false);
                break;
            case "hydra":
                customChrootCommand(TextUtils.join("; ", Apt.env()) + "; " + Apt.remove("hydra"), true);
                guestRemove("/usr/local/bin/hydra");
                putBoolean("hydra", false);
                break;
            case "cameradar":
                guestRemove("/usr/bin/cameradar /usr/local/bin/cameradar /usr/bin/radar "
                        + "/usr/local/bin/radar");
                putBoolean("cameradar", false);
                break;
            case "searchsploit":
                guestRemove("/opt/exploitdb /usr/local/bin/searchsploit");
                break;
            default:
                return false;
        }
        deleteModule(tool);
        remove("install_status_" + tool);
        return !isToolInstalled(tool);
    }

    public void guestRemove(String spaceSeparatedPaths){
        if (spaceSeparatedPaths == null || spaceSeparatedPaths.trim().isEmpty()) return;
        if (isRootless()) {
            customChrootCommand("rm -rf " + spaceSeparatedPaths, true);
            return;
        }
        StringBuilder cmd = new StringBuilder("rm -rf");
        for (String p : spaceSeparatedPaths.trim().split("\\s+")) {
            if (!p.isEmpty()) cmd.append(' ').append(Apt.shellQuote(chrootPath(p)));
        }
        customCommand(cmd.toString(), true);
    }

    public boolean guestFileExists(String... guestPaths){
        if (guestPaths == null || guestPaths.length == 0) return false;
        StringBuilder test = new StringBuilder();
        for (String p : guestPaths) {
            if (p == null || p.isEmpty()) continue;
            if (test.length() > 0) test.append(" || ");
            test.append("[ -e ").append(Apt.shellQuote(isRootless() ? p : chrootPath(p))).append(" ]");
        }
        if (test.length() == 0) return false;
        String cmd = "{ " + test + "; } && echo " + Apt.INSTALLED_MARK
                + " || echo " + Apt.MISSING_MARK;
        return marked(isRootless() ? customChrootCommand(cmd, true) : customCommand(cmd, true));
    }

    public boolean hasBinary(String bin){
        return marked(customChrootCommand(Apt.hasBinary(bin), true));
    }

    public boolean hasPackage(String pkg){
        return marked(customChrootCommand(Apt.isInstalled(pkg), true));
    }

    private static boolean marked(ArrayList<String> out){
        if (out == null) return false;
        for (String l : out) {
            if (l != null && l.trim().equals(Apt.INSTALLED_MARK)) return true;
        }
        return false;
    }
    public boolean unzip(String zipFile, String targetDirectory)  {
        customCommand(BUSYBOX+"unzip -o "+zipFile+" -d "+targetDirectory);
        return checkFolder(targetDirectory);

    }
    public Boolean mountCore(){
        customMegaCommand("/data/data/com.zalexdev.stryker/files/bootroot");
        boolean mounted = isMounted();
        if (mounted) {
            try { GuestCore.ensure(this); } catch (Throwable ignored) {}
        }
        return mounted;
    }
    /** True when a chroot from before the Debian move is installed. */
    public boolean hasLegacyChroot(){
        if (isRootless()) return false;
        if (checkFile(CHROOT_MARKER)) return false;
        for (String m : LEGACY_CHROOT_MARKERS) {
            if (checkFile(CHROOT_ROOT + "/" + m)) return true;
        }
        return false;
    }

    /**
     * Every mountpoint currently attached at or under {@code path}, read from /proc/mounts.
     *
     * This is the gate in front of every recursive delete under the chroot, and it is
     * deliberately layout-agnostic. isMounted() answers a different question — "is the chroot
     * fully assembled and usable" — and requires a specific set of mounts, so it reports false
     * for a chroot that is only partly attached. A half-torn-down chroot is exactly the state
     * where deleting is most destructive, and it is also what an upgrade from an older Stryker
     * looks like: releases before 4.5R bound the WHOLE /sdcard at <root>/sdcard instead of
     * <root>/sdcard/Stryker, so a readiness check that looks for the current layout sees
     * "not mounted" while the user's entire internal storage is still attached underneath.
     * Anything at or under the path counts here, whatever its shape.
     */
    public ArrayList<String> mountsUnder(String path) {
        ArrayList<String> mounts = new ArrayList<>();
        if (path == null || path.isEmpty()) return mounts;
        String root = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        for (String s : customCommand("cat /proc/mounts", true)) {
            if (s == null) continue;
            String[] parts = s.trim().split("\\s+");
            if (parts.length < 2) continue;
            // /proc/mounts escapes spaces in the target as \040.
            String target = parts[1].replace("\\040", " ");
            if (target.equals(root) || target.startsWith(root + "/")) mounts.add(target);
        }
        return mounts;
    }

    /**
     * True only when /proc/mounts was actually read. Every caller here is about to delete
     * something, so an unreadable mount table has to fail closed: no root, a dead su session or
     * a truncated read would otherwise look identical to "nothing is mounted".
     */
    private boolean mountTableReadable() {
        for (String s : customCommand("cat /proc/mounts", true)) {
            if (s != null && s.contains(" / ")) return true;
        }
        return false;
    }

    /**
     * rm -rf that refuses to run while anything is still mounted at or under the target.
     * The chroot binds real storage inside itself, so deleting across a live bind walks
     * straight through it and destroys the user's data instead of the chroot.
     */
    public boolean safeDeleteTree(String path) {
        if (path == null || path.trim().isEmpty() || path.trim().equals("/")) {
            logger.writeLine("Refusing to delete an empty or root path", 3);
            return false;
        }
        if (!mountTableReadable()) {
            logger.writeLine("Could not read /proc/mounts — refusing to delete " + path, 3);
            return false;
        }
        ArrayList<String> live = mountsUnder(path);
        if (!live.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String m : live) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(m);
            }
            logger.writeLine("Refusing to delete " + path + " — still mounted: " + sb, 3);
            return false;
        }
        customCommand("rm -rf " + path);
        return true;
    }

    /**
     * Unmount and delete the installed rootfs. Refuses to delete while anything is still mounted:
     * the chroot bind-mounts real storage inside itself, so an rm -rf over a live mount would
     * wipe the user's data.
     */
    public boolean purgeChroot(){
        logger.writeLine("Removing the previous chroot", 1);
        unmountCore();
        for (int i = 0; i < 10 && !mountsUnder(CHROOT_ROOT).isEmpty(); i++) {
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!safeDeleteTree(CHROOT_ROOT)) {
            logger.writeLine("Could not unmount the old chroot — refusing to delete it", 3);
            return false;
        }
        boolean gone = !checkFolder(CHROOT_ROOT + "/bin") && !checkFolder(CHROOT_ROOT + "/usr");
        logger.writeLine(gone ? "Previous chroot removed" : "Previous chroot could not be removed",
                gone ? 2 : 3);
        return gone;
    }

    /** Runs killroot and reports whether the chroot is genuinely detached afterwards. */
    public Boolean unmountCore(){
        customMegaCommand("/data/data/com.zalexdev.stryker/files/killroot");
        return mountTableReadable() && mountsUnder(CHROOT_ROOT).isEmpty();
    }
    /** True when the chroot is fully assembled and usable — not a safety check, see mountsUnder. */
    public Boolean isMounted(){
        return isChrootMounted(CHROOT_ROOT);
    }
    private boolean isChrootMounted(String root){
        boolean proc = false, sys = false, dev = false, sdcard = false;
        for (String s : customCommand("cat /proc/mounts", true)) {
            if (s == null) continue;
            if (s.contains(" " + root + "/proc ")) proc = true;
            if (s.contains(" " + root + "/sys ")) sys = true;
            if (s.contains(" " + root + "/dev ")) dev = true;
            if (s.contains(" " + root + "/sdcard/Stryker ")) sdcard = true;
        }
        return proc && sys && dev && sdcard;
    }
    public boolean isOldMounted(){
        return checkFolder("/data/stryker/beta/sdcard/Stryker");
    }
    public boolean ping(String ip, int port,int timeout) {
        try {
            URI uri;
            if(port !=443){uri = URI.create("http://" + ip + ":" + port + "/");}else{uri = URI.create("https://" + ip + "/"); }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(connection::disconnect, timeout + 4000);
            return connection.getResponseCode() >= 200 && !(connection.getResponseCode() == 404) && !(connection.getResponseCode() == 403);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean ping(String ip, int timeout) {
        try {
            URI uri;
            uri = URI.create("https://" + ip + "/");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeout);
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(connection::disconnect, timeout + 4000);
            return connection.getResponseCode() >= 200 && !(connection.getResponseCode() == 404) && !(connection.getResponseCode() == 403);
        } catch (Exception e) {
            return false;
        }
    }

    public void moveNext(ViewPager2 mPager) {
        mPager.setCurrentItem(mPager.getCurrentItem() + 1);
    }


    public void movePrevious(ViewPager mPager) {
        mPager.setCurrentItem(mPager.getCurrentItem() - 1);
    }
    public boolean isInstalledOnSdCard() {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            ApplicationInfo ai = pi.applicationInfo;
            return (ai.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == ApplicationInfo.FLAG_EXTERNAL_STORAGE;
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        return false;
    }


    public void reCreateProcess(){
        try {
            if (process != null) {
                process.getOutputStream().write("exit\nexit\n".getBytes());
                process.getOutputStream().flush();
                process.destroy();}
            process = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** True when the last generateSuProcess() could not spawn su at all. See the note there. */
    private volatile boolean suSpawnFailed = false;

    public boolean suSpawnFailed() { return suSpawnFailed; }

    public Process generateSuProcess(){
        try {
            Process process = Runtime.getRuntime().exec("su");
            suSpawnFailed = false;
            return process;
        } catch (IOException e) {
            // No su on this device. Most callers dereference the returned Process without a null
            // check, so hand back an inert one instead of crashing them — but record the fact.
            // Without that flag a missing su is indistinguishable from a command that simply
            // printed nothing, and every failure downstream invents its own reason for the empty
            // output: that is how "no root" used to surface as "no usable tar".
            suSpawnFailed = true;
            logger.writeLine("su is not available on this device", 3);
            try {
                return Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "exit 1"});
            } catch (IOException ex) {
                return null;
            }
        }
    }


    public boolean checkFile(String path){
        logger.writeLine("Checking file "+path,1);
        if (isRootless()) {
            return new File(path).isFile();
        }
        return customCommand("[ -f " + path + " ] && echo true || echo false").contains("true");
    }

    public boolean checkFolder(String path){
        boolean ok = false;
        logger.writeLine("Checking folder "+path,1);
        if (isRootless()) {
            return new File(path).isDirectory();
        }
        for (String s : customCommand("[ -d " + path + " ] && echo true || echo false")) {
            if (s.contains("true")) {
                ok = true;
                break;
            }
        }
        return ok;
    }
    public boolean checkMagiskNotification(){
        reCreateProcess();
        if (!getBoolean("offed")){
        String cmd = "/data/data/com.zalexdev.stryker/files/sqlite3 /data/adb/magisk.db \"SELECT notification FROM policies WHERE package_name='com.zalexdev.stryker';\"";
        boolean b = Core.contains(customCommand(cmd),"1");
        if (!b) {
            cmd = "/data/data/com.zalexdev.stryker/files/sqlite3 /data/adb/magisk.db \"SELECT notification FROM policies WHERE uid='"+android.os.Process.myUid()+"';\"";
            b = Core.contains(customCommand(cmd),"1");
        }
        return b;}else{
            return false;
        }
    }

    public boolean checkRoot(){
        return contains(customCommand("id"),"uid=0");
    }


    private static final String RC_MARK = "__STRYKER_RC__";
    private static final long IDLE_LIMIT_MS = 10 * 60 * 1000L;

    private ArrayList<String> pumpProcess(Process process, String script, boolean log, String tool,
                                          boolean answerNoOnPrompt) {
        return pumpProcess(process, script, log, tool, answerNoOnPrompt, IDLE_LIMIT_MS);
    }

    private ArrayList<String> pumpProcess(Process process, String script, boolean log, String tool,
                                          boolean answerNoOnPrompt, long idleLimitMs) {
        ArrayList<String> result = new ArrayList<>();
        if (process == null) return result;
        final ArrayList<String> errors = new ArrayList<>();
        Thread errReader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String l;
                while ((l = br.readLine()) != null) {
                    synchronized (errors) { errors.add(l); }
                    if (log) logger.writeLine(l, 3, tool);
                }
            } catch (IOException ignored) {
            }
        }, "stryker-su-stderr");
        errReader.setDaemon(true);
        errReader.start();
        final long[] lastActivity = {android.os.SystemClock.elapsedRealtime()};
        final boolean[] finished = {false};
        final boolean[] timedOut = {false};
        Thread watchdog = new Thread(() -> {
            while (!finished[0]) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                if (finished[0]) return;
                if (android.os.SystemClock.elapsedRealtime() - lastActivity[0] > idleLimitMs) {
                    timedOut[0] = true;
                    process.destroy();
                    return;
                }
            }
        }, "stryker-su-watchdog");
        watchdog.setDaemon(true);
        if (idleLimitMs > 0) watchdog.start();
        boolean sawMark = false;
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write(script.getBytes());
            stdin.flush();
            if (!answerNoOnPrompt) stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            boolean pendingBlank = false;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(RC_MARK)) {
                    sawMark = true;
                    break;
                }
                lastActivity[0] = android.os.SystemClock.elapsedRealtime();
                if (line.isEmpty()) {
                    pendingBlank = true;
                    continue;
                }
                if (pendingBlank) {
                    pendingBlank = false;
                    result.add("");
                    if (log) logger.writeLine("", 2, tool);
                }
                result.add(line);
                if (log) logger.writeLine(line, 2, tool);
                if (answerNoOnPrompt && line.contains("no interfaces assigned")) {
                    if (log) logger.writeLine("No interfaces assigned. Answering no", 3, tool);
                    stdin.write("n\n".getBytes());
                    stdin.flush();
                }
            }
            if (answerNoOnPrompt) {
                try { stdin.close(); } catch (IOException ignored) {}
            }
            if (!sawMark) process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        } finally {
            finished[0] = true;
            watchdog.interrupt();
            try { errReader.join(sawMark ? 300 : 2000); } catch (InterruptedException ignored) {}
            process.destroy();
        }
        if (timedOut[0]) {
            String msg = "command produced no output for " + (IDLE_LIMIT_MS / 60000)
                    + " min and was killed";
            result.add("[E] " + msg);
            logger.writeLine(msg, 3, tool);
        }
        synchronized (errors) { result.addAll(errors); }
        return result;
    }

    private static String terminated(String command) {
        return command + "\nprintf '\\n" + RC_MARK + "%s\\n' \"$?\"\n";
    }

    public String executeCommand(String command){
        String tool = LogTool.classify(command);
        logger.writeLine("Executing command: " + command,1, tool);
        ArrayList<String> lines = pumpProcess(generateSuProcess(),
                terminated(command) + "exit\nexit\n", true, tool, false);
        StringBuilder result = new StringBuilder();
        for (String l : lines) result.append(l).append('\n');
        if (result.length() > 0) result.setLength(result.length() - 1);
        return result.toString();
    }
    public ArrayList<String> customCommand(String command){
        String tool = LogTool.classify(command);
        logger.writeLine("Executing command: " + command,1, tool);
        return pumpProcess(generateSuProcess(), terminated(command) + "exit\nexit\n", true, tool, false);
    }
    public ArrayList<String> customCommand(String command,boolean nolog){
        return pumpProcess(generateSuProcess(), terminated(command) + "exit\n", false, null, false);
    }

    public ArrayList<String> customCommand(String command, long idleLimitMs){
        String tool = LogTool.classify(command);
        logger.writeLine("Executing command: " + command, 1, tool);
        return pumpProcess(generateSuProcess(), terminated(command) + "exit\nexit\n",
                true, tool, false, idleLimitMs);
    }
    public void threadCommand(String cmd){new Thread(() -> customCommand(cmd)).start();}

    public void threadChrootCommand(String cmd){new Thread(() -> customChrootCommand(cmd)).start();}


    public ArrayList<String> customMegaCommand(String command){
        Process process;
        try {
            process = Runtime.getRuntime().exec("su -mm");
        } catch (IOException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
        String tool = LogTool.classify(command);
        logger.writeLine("Executing command: " + command,1, tool);
        return pumpProcess(process, terminated(command), true, tool, false);
    }
    public boolean isRootless() {
        return EngineType.isRootless(this);
    }

    public RootlessEngine rootless() {
        return RootlessEngine.get(context);
    }

    public ArrayList<String> customChrootCommand(String command)  {
        if (isRootless()) {
            String tool = LogTool.classify(command);
            logger.writeLine("Executing rootless command: " + command, 1, tool);
            ArrayList<String> out = rootless().exec(command);
            for (String l : out) logger.writeLine(l, 2, tool);
            return out;
        }
        String tool = LogTool.classify(command);
        logger.writeLine("Executing chroot command: " + command,1, tool);
        return pumpProcess(generateSuProcess(),
                EXECUTE + "'" + SHELL + "'\n" + terminated(command) + "exit\n", true, tool, true);
    }

    public ArrayList<String> customChrootCommand(String command, boolean nolog)  {
        if (isRootless()) {
            return rootless().exec(command);
        }
        return pumpProcess(generateSuProcess(),
                EXECUTE + "'" + SHELL + "'\n" + terminated(command) + "exit\n", false, null, false);
    }

    @Deprecated
    public boolean guestHasBinary(String bin) {
        return hasBinary(bin);
    }

    public static final String BUSYBOX_ASSET = "busybox64";

    public static File busyboxFile(Context ctx) {
        return new File(ctx.getFilesDir(), "busybox");
    }

    public static boolean extractBusybox(Context ctx) {
        return extractBusybox(ctx, false);
    }

    public static boolean extractBusybox(Context ctx, boolean force) {
        File out = busyboxFile(ctx);
        if (!force && out.length() > 0 && out.canExecute()) return true;
        File tmp = new File(out.getParentFile(), "busybox.part");
        try (InputStream in = ctx.getAssets().open(BUSYBOX_ASSET);
             OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            os.flush();
        } catch (IOException e) {
            Log.e("Core", "Failed to extract " + BUSYBOX_ASSET, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return out.length() > 0 && out.canExecute();
        }
        if (tmp.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        try { tmp.setExecutable(true, false); } catch (Exception ignored) {}
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        try { out.setExecutable(true, false); } catch (Exception ignored) {}
        return true;
    }

    public boolean busyboxUsable() {
        for (String l : customCommand(BUSYBOX + "true >/dev/null 2>&1; echo BBEXIT=$?", true)) {
            if (l != null && l.contains("BBEXIT=0")) return true;
        }
        return false;
    }

    public String tarCommand() {
        if (busyboxUsable()) return BUSYBOX + "tar";
        extractBusybox(context, true);
        if (busyboxUsable()) return BUSYBOX + "tar";
        for (String l : customCommand("command -v tar 2>/dev/null", true)) {
            if (l != null && l.trim().startsWith("/")) return l.trim();
        }
        return null;
    }

    /**
     * Why tarCommand() came back null, phrased for the user.
     *
     * Both probes it runs — the busybox smoke test and `command -v tar` — go through a root
     * shell, so on a device with no usable su every one of them returns nothing and the chain
     * reads as "busybox is broken and the system has no tar". That conclusion is wrong: Android
     * has shipped a toybox tar since 6.0, and a working root shell would have found it. Empty
     * output there means no shell ran, so say that instead of blaming tar.
     */
    public String tarFailureReason() {
        if (suSpawnFailed() || !checkRoot()) {
            return "no root access — su did not return a root shell";
        }
        return "no usable tar — busybox could not run and the system provides none";
    }

    public void moveFile(@NonNull String source, @NonNull String destination){
        if (isRootless()) {
            try {
                File src = new File(source);
                File dst = new File(destination);
                if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
                if (!src.renameTo(dst)) {
                    try (InputStream in = new java.io.FileInputStream(src);
                         OutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[8192]; int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    src.delete();
                }
            } catch (Exception e) {
                logger.writeLine("Rootless move failed: " + e.getMessage(), 3);
            }
            return;
        }
        customCommand("mv " + source + " " + destination);
    }
    public void copyFile(@NonNull String source, @NonNull String destination){
        customCommand("cp -R " + source + " " + destination);

    }
    public void deleteFile(@NonNull String file){
        if (isRootless()) {
            deleteRecursively(new File(file));
            return;
        }
        customCommand("rm -rf " + file);
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    public void createFolder(@NonNull String folder){
        if (isRootless()) {
            //noinspection ResultOfMethodCallIgnored
            new File(folder).mkdirs();
            return;
        }
        customCommand("mkdir " + folder);

    }
    public void chmodFolder(@NonNull String folder){
        if (isRootless()) return;
        customCommand("chmod 777 -R " + folder);

    }
    public boolean checkInet(){
        logger.writeLine("Checking internet connection...",1);
        return customCommand("ping -c 1 8.8.8.8 ; echo $?").contains("0");
    }





    public String getVendorByMacFromDB(String mac){
        String vendor = "";
        try {
            if (db == null || !db.isOpen()){
                db = SQLiteDatabase.openDatabase("/data/data/com.zalexdev.stryker/files/vendors.db", null, SQLiteDatabase.OPEN_READONLY);
            }
            Cursor cursor = db.rawQuery("select MacPrefix,VendorName from macvendor where MacPrefix LIKE '%"+mac.substring(0,8).toUpperCase(Locale.ROOT)+"%' COLLATE NOCASE", null);
            if (cursor.moveToFirst()) {
                vendor = cursor.getString(1);
            }
            cursor.close();


        } catch (Exception e) {
            e.printStackTrace();
        }
        return toTitleCase(vendor);
    }
    public String getDeviceByCodeNameFromDB(String codename){
        String model = "";
        try {
            if (dbCodename == null || !dbCodename.isOpen()){
                dbCodename = SQLiteDatabase.openDatabase("/data/data/com.zalexdev.stryker/files/codenames.db", null, SQLiteDatabase.OPEN_READONLY);
            }
            Cursor cursor = dbCodename.rawQuery("SELECT manufacture,model FROM codename WHERE codename = '"+codename+"';", null);

            if (cursor.moveToFirst()) {
                model = cursor.getString(0)+" "+cursor.getString(1).replace(cursor.getString(0),"");
            }
            cursor.close();


        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            return toTitleCase(model);
        } catch (NullPointerException ignored) {
            return model;
        }
    }
    public static String toTitleCase(String givenString) throws NullPointerException {
        String[] arr = givenString.toLowerCase(Locale.ROOT).split(" ");
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (s.length() > 1) {
                sb.append(Character.toUpperCase(s.charAt(0)))
                        .append(s.substring(1)).append(" ");
            }
        }
        return sb.toString();
    }
    public static boolean isNumeric(String string) {


        if(string == null || string.equals("")) {
            return false;
        }else{
            try {
                Integer.parseInt(string);
                return true;
            } catch (NumberFormatException e) {
                return false;

            }}
    }
    public void saveLastNetworkScan(ArrayList<Device> devices){
        ArrayList<String> devs = new ArrayList<>();
        for (Device d : devices){
            devs.add(d.toJSON());
        }
        putListString("last_network_scan",devs);
    }
    public ArrayList<Device> getLastNetworkScan(){
        ArrayList<Device> devices = new ArrayList<>();
        ArrayList<String> devs = getListString("last_network_scan");
        for (String d : devs){
            Device device = new Device();
            device.restoreFromJSON(d);
            devices.add(device);
        }
        return devices;
    }
    public ArrayList<String> getLatestIps(){
        ArrayList<Device> devices = getLastNetworkScan();
        ArrayList<String> ips = new ArrayList<>();
        for (Device d : devices){
            ips.add(d.getIp());}
        return ips;
    }

    public String getLocalIpaddress(){
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        return Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
    }
    public boolean isHideEnabled(){
        return getBoolean("hide");
    }
    public boolean isStoreEnabled(){
        return preferences.getBoolean("save_aps", true);
    }
    public boolean isBannerScanEnabled(){
        return preferences.getBoolean("autoBanner", true);
    }

    public boolean isPixieIfaceDown(){
        return preferences.getBoolean("pixie_iface_down", true);
    }

    public boolean isInternalDeauthEnabled(){
        return preferences.getBoolean("internal_deauth", false);
    }

    public String wpsIfaceDownFlag(){
        return isPixieIfaceDown() ? " --iface-down" : "";
    }

    public void wpsDisableWifiIfEnabled(){
        if (isRootless()) return;
        if (isPixieIfaceDown()) customCommand("svc wifi disable");
    }

    public static boolean contains(ArrayList<String> list, String item){
        for (String s : list){if (s.contains(item)){return true;}}
        return false;
    }

    public static String generateString() {return UUID.randomUUID().toString().replace("-", "");}
    public void checkPermission(Activity activity) {
        if (context.checkSelfPermission(WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{WRITE_EXTERNAL_STORAGE},
                    123
            );
        }
    }

    public boolean hasLocationPermission() {
        try {
            return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public void requestLocationPermission(Activity activity) {
        if (activity == null || hasLocationPermission()) return;
        try {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    124
            );
        } catch (Exception ignored) {
        }
    }

    public void requestAllFilesAccess(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Intent i = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                }
            } else {
                checkPermission(activity);
            }
        } catch (Exception e) {
            try { checkPermission(activity); } catch (Exception ignored) {}
        }
    }
    public void openlink(String url) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        context.startActivity(browserIntent);
    }
    public ArrayList<Site> getSites(){
        ArrayList<Site> sites = new ArrayList<>();
        ArrayList<String> ss = getListString("sites");
        for (String s : ss){
            sites.add(Site.parseItem(s));
        }
        return sites;
    }
    public void changeSiteByPosition(Site site,int pos){
        ArrayList<String> ss = getListString("sites");
        if (ss.size()>pos ){
        ss.remove(pos);
        ss.add(pos,site.getJSON());
        putListString("sites",ss);}
    }
    public void deleteSiteByPosition(int pos){
        ArrayList<String> ss = getListString("sites");
        ss.remove(pos);

        putListString("sites",ss);
    }
    public int getRandomNumber(int min, int max) {
        return (int) ((Math.random() * (max - min)) + min);
    }
    public int addSite(Site site){
        ArrayList<String> ss = getListString("sites");
        ss.add(site.getJSON());
        putListString("sites",ss);
        return ss.indexOf(site.getJSON());
    }
    public String getDeviceId(){
        if (getString("device_id").length() >1){
            return getString("device_id");
        }else{
            String id = Build.HARDWARE+Build.MODEL+getRandomNumber(100,9999);
            id = id.replace(" ","");
            putString("device_id",id);
            return id;
        }
    }

    public void putRouters(ArrayList<String> routers){if (routers.size() > 0){ putListString("routers",routers);}}

    public ArrayList<String> getRouters(){
        return getListString("routers");
    }

    public void disableMagiskNotification() {

                if (contains(customCommand("/data/data/com.zalexdev.stryker/files/sqlite3 "
                        + "/data/adb/magisk.db"
                        + " \"UPDATE policies SET logging='0',notification='0' WHERE package_name='"
                        + "com.zalexdev.stryker"
                        + "';\""), "no such"))
                {customCommand("/data/data/com.zalexdev.stryker/files/sqlite3 "
                                        + "/data/adb/magisk.db"
                                        + " \"UPDATE policies SET logging='0',notification='0' WHERE uid='"
                                        + android.os.Process.myUid()
                                        + "';\"");}
    }


    public ArrayList<String> getInterfacesList(){
        ArrayList<String> result = new ArrayList<>();
        for (String[] p : monitorManager.listInterfaces()){
            result.add(p[0]);
        }

        ArrayList<String> latest = new ArrayList<>();
        for (String s : result){
            if (s.contains("wlan")){
                latest.add(s);
            }
        }
        putListString("interfaces",latest);
        return result;
    }

    public ArrayList<String> rootlessPrepWifi(String iface) {
        String cmd =
            "rfkill unblock all 2>/dev/null || for f in /sys/class/rfkill/*/state; do echo 1 > \"$f\" 2>/dev/null; done; "
          + "pkill wpa_supplicant 2>/dev/null; "
          + "for i in $(iw dev 2>/dev/null | awk '/Interface/{print $2}'); do case \"$i\" in *mon) [ \"$i\" = \"" + iface + "\" ] || iw dev \"$i\" del 2>/dev/null;; esac; done; "
          + "ip link set " + iface + " down 2>/dev/null; "
          + "iw dev " + iface + " set type managed 2>/dev/null; "
          + "ip link set " + iface + " up 2>&1";
        return customChrootCommand(cmd);
    }

    public String getHSInterface(){
        return monitorManager.getHSInterface();
    }
    public String getWPSInterface(){
        return getString("wlan_wps");
    }

    public void setWifiInterface(String iface){
        if (iface == null || iface.isEmpty()) return;
        String previous = getString("wlan_wifi");
        putString("wlan_wifi", iface);
        for (String key : new String[]{"wlan_scan", "wlan_deauth", "wlan_wps"}){
            String current = getString(key);
            if (current == null || current.isEmpty() || current.equals(previous)){
                putString(key, iface);
            }
        }
    }
    public boolean isMonitorModeEnabled(String wlan){
        return monitorManager.isMonitorModeEnabled(wlan);
    }
    public String getDeauthInterface(){
        return monitorManager.getDeauthInterface();
    }

    public Boolean disableMonitorMode(String wlan){
        return monitorManager.disableMonitorMode(wlan);
    }
    public Boolean enableMonitorMode(String wlan){
        return monitorManager.enableMonitorMode(wlan);
    }
    public Boolean enableMonitorMode(String wlan, String channel){
        return monitorManager.enableMonitorMode(wlan,channel);
    }

    public ArrayList<Credentials> getCredentials(){
        ArrayList<String> creds = getListString("creds");
        ArrayList<Credentials> credentialsList = new ArrayList<>();
        for (String cred : creds){
            credentialsList.add(Credentials.fromJson(cred));
        }
        return credentialsList;
    }

    public void addCredentials(Credentials credentials){
        ArrayList<String> credsList = getListString("creds");
        credsList.add(credentials.toJson());
        putListString("creds",credsList);
    }

    public void clearCredentials(){
        putListString("creds",new ArrayList<>());
    }

    public void removeCredentials(int position){
        ArrayList<String> credsList = getListString("creds");
        credsList.remove(position);
        putListString("creds",credsList);
    }

}
