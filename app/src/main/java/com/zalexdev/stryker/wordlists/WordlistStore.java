package com.zalexdev.stryker.wordlists;

import android.content.Context;
import android.net.Uri;

import com.zalexdev.stryker.utils.Core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class WordlistStore {

    public String guestDir() {
        return core.guestShare() + "/wordlists";
    }

    private static final String INDEX_FILE = ".stryker-index.json";
    private static final int SAMPLE_LINES = 40;

    private final Core core;

    public WordlistStore(Context context) {
        this(new Core(context));
    }

    public WordlistStore(Core core) {
        this.core = core;
    }

    public File dir() {
        File d = new File(core.getShareRoot() + "/wordlists");
        if (!d.exists())
            d.mkdirs();
        return d;
    }

    public String guestPath(Wordlist wl) {
        return guestDir() + "/" + wl.getName();
    }

    public String guestPathQuoted(Wordlist wl) {
        return quoteForShell(guestPath(wl));
    }

    public static String quoteForShell(String path) {
        String escaped = path
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("`", "\\`");
        return "\"" + escaped + "\"";
    }

    public String reachablePathQuoted(Wordlist wl) {
        if (wl == null) return null;
        String name = wl.getName();
        String found = probe(candidates(name));
        if (found != null) return quoteForShell(found);

        File src = wl.file;
        File dst = new File(dir(), name);
        if (src != null && src.isFile() && !src.getAbsolutePath().equals(dst.getAbsolutePath())) {
            if (copy(src, dst)) {
                found = probe(candidates(name));
                if (found != null) return quoteForShell(found);
            }
        }
        return null;
    }

    public String reachablePath(Wordlist wl) {
    String quoted = reachablePathQuoted(wl);
    if (quoted != null && quoted.length() >= 2
        && quoted.startsWith("\"") && quoted.endsWith("\"")) {
        return quoted.substring(1, quoted.length() - 1);
    }
    return quoted;
    }
    
    private java.util.List<String> candidates(String name) {
        java.util.LinkedHashSet<String> dirs = new java.util.LinkedHashSet<>();
        dirs.add(core.guestShare() + "/wordlists");
        dirs.add("/sdcard/Stryker/wordlists");
        dirs.add("/host/wordlists");
        try {
            File ext = core.context.getExternalFilesDir(null);
            if (ext != null) {
                dirs.add(ext.getAbsolutePath() + "/Stryker/wordlists");
                dirs.add("/sdcard/Android/data/" + core.context.getPackageName()
                        + "/files/Stryker/wordlists");
            }
        } catch (Throwable ignored) {
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String d : dirs) out.add(d + "/" + name);
        return out;
    }

    private String probe(java.util.List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            sb.append("[ -f ").append(quoteForShell(p)).append(" ] && { echo __WL__")
                    .append(p).append("; exit 0; }; ");
        }
        sb.append("true");
        try {
            for (String line : core.customChrootCommand(sb.toString())) {
                if (line == null) continue;
                int at = line.indexOf("__WL__");
                if (at >= 0) {
                    String hit = line.substring(at + 6).trim();
                    if (!hit.isEmpty()) return hit;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean copy(File src, File dst) {
        try {
            dst.getParentFile().mkdirs();
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public List<Wordlist> list() {
        List<Wordlist> out = new ArrayList<>();
        File[] files = dir().listFiles();
        if (files == null) return out;

        JSONObject index = readIndex();
        boolean indexChanged = false;

        for (File f : files) {
            if (!f.isFile() || f.isHidden() || f.getName().startsWith(".")) continue;
            Wordlist wl = new Wordlist(f);
            JSONObject entry = index.optJSONObject(f.getName());
            if (entry != null) {
                wl.category = WordlistCategory.byId(entry.optString("category", null));
                wl.origin = Wordlist.Origin.byId(entry.optString("origin", null));
                wl.note = entry.optString("note", "");
                if (entry.optLong("size", -1) == f.length()
                        && entry.optLong("modified", -1) == f.lastModified()) {
                    wl.lines = entry.optLong("lines", -1);
                }
            } else {
                wl.category = detect(f);
                writeEntry(index, wl);
                indexChanged = true;
            }
            out.add(wl);
        }

        if (indexChanged) saveIndex(index);
        Collections.sort(out, (a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    public List<Wordlist> list(WordlistCategory... categories) {
        List<Wordlist> all = list();
        if (categories == null || categories.length == 0) return all;
        List<Wordlist> out = new ArrayList<>();
        for (Wordlist wl : all) {
            for (WordlistCategory c : categories) {
                if (wl.category == c) {
                    out.add(wl);
                    break;
                }
            }
        }
        return out;
    }

    public Wordlist byName(String name) {
        for (Wordlist wl : list()) {
            if (wl.getName().equals(name)) return wl;
        }
        return null;
    }

    public boolean exists(String name) {
        return new File(dir(), name).exists();
    }

    public List<String> preview(Wordlist wl, int max) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = reader(wl.file)) {
            String line;
            while (out.size() < max && (line = r.readLine()) != null) out.add(line);
        } catch (IOException ignored) {
        }
        return out;
    }

    public long countLines(Wordlist wl) {
        long n = 0;
        try (BufferedReader r = reader(wl.file)) {
            while (r.readLine() != null) n++;
        } catch (IOException e) {
            return -1;
        }
        wl.lines = n;
        wl.sizeBytes = wl.file.length();
        wl.modified = wl.file.lastModified();
        JSONObject index = readIndex();
        writeEntry(index, wl);
        saveIndex(index);
        return n;
    }

    public Wordlist create(String name, WordlistCategory category, Wordlist.Origin origin) {
        File f = new File(dir(), uniqueName(name));
        Wordlist wl = new Wordlist(f);
        wl.category = category == null ? WordlistCategory.OTHER : category;
        wl.origin = origin;
        return wl;
    }

    public BufferedWriter writer(Wordlist wl, boolean append) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(wl.file, append), "UTF-8"), 1 << 16);
    }

    public void finish(Wordlist wl, long lines) {
        wl.lines = lines;
        wl.sizeBytes = wl.file.length();
        wl.modified = wl.file.lastModified();
        JSONObject index = readIndex();
        writeEntry(index, wl);
        saveIndex(index);
    }

    public Wordlist write(String name, WordlistCategory category, Wordlist.Origin origin,
                          Iterable<String> lines) {
        Wordlist wl = create(name, category, origin);
        long n = 0;
        try (BufferedWriter w = writer(wl, false)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
                n++;
            }
        } catch (IOException e) {
            wl.file.delete();
            return null;
        }
        finish(wl, n);
        return wl;
    }

    public Wordlist importFrom(Uri uri, String name, WordlistCategory category) {
        Wordlist wl = create(name, category, Wordlist.Origin.IMPORTED);
        try (InputStream in = core.getContext().getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(wl.file)) {
            if (in == null) return null;
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        } catch (IOException | SecurityException e) {
            wl.file.delete();
            return null;
        }
        if (category == null) wl.category = detect(wl.file);
        finish(wl, -1);
        return wl;
    }

    public boolean rename(Wordlist wl, String newName) {
        String target = ensureExtension(newName, wl.getName());
        if (target.equals(wl.getName())) return true;
        File dest = new File(dir(), target);
        if (dest.exists()) return false;
        String old = wl.getName();
        if (!wl.file.renameTo(dest)) return false;
        JSONObject index = readIndex();
        index.remove(old);
        Wordlist moved = new Wordlist(dest);
        moved.category = wl.category;
        moved.origin = wl.origin;
        moved.note = wl.note;
        moved.lines = wl.lines;
        writeEntry(index, moved);
        saveIndex(index);
        return true;
    }

    public boolean delete(Wordlist wl) {
        boolean gone = wl.file.delete();
        if (gone) {
            JSONObject index = readIndex();
            index.remove(wl.getName());
            saveIndex(index);
        }
        return gone;
    }

    public Wordlist duplicate(Wordlist wl) {
        Wordlist copy = create(wl.getDisplayName() + "-copy" + extensionOf(wl.getName()),
                wl.category, wl.origin);
        try (InputStream in = new FileInputStream(wl.file);
             OutputStream out = new FileOutputStream(copy.file)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        } catch (IOException e) {
            copy.file.delete();
            return null;
        }
        copy.note = wl.note;
        finish(copy, wl.lines);
        return copy;
    }

    public void setCategory(Wordlist wl, WordlistCategory category) {
        wl.category = category;
        JSONObject index = readIndex();
        writeEntry(index, wl);
        saveIndex(index);
    }

    public void setNote(Wordlist wl, String note) {
        wl.note = note == null ? "" : note;
        JSONObject index = readIndex();
        writeEntry(index, wl);
        saveIndex(index);
    }

    public String uniqueName(String requested) {
        String name = sanitize(requested);
        if (!name.contains(".")) name = name + ".txt";
        if (!new File(dir(), name).exists()) return name;
        String base = name.substring(0, name.lastIndexOf('.'));
        String ext = name.substring(name.lastIndexOf('.'));
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i + ext;
            if (!new File(dir(), candidate).exists()) return candidate;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    public static String sanitize(String name) {
        if (name == null) return "wordlist.txt";
        String s = name.trim().replace('\\', '-').replace('/', '-');
        s = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        s = s.replaceAll("^[.]+", "");
        if (s.isEmpty()) s = "wordlist";
        if (s.length() > 96) s = s.substring(0, 96);
        return s;
    }

    private static String ensureExtension(String name, String fallbackFrom) {
        String s = sanitize(name);
        if (s.contains(".")) return s;
        return s + extensionOf(fallbackFrom);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".txt";
    }

    private JSONObject readIndex() {
        File f = new File(dir(), INDEX_FILE);
        if (!f.exists()) return new JSONObject();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = reader(f)) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (IOException e) {
            return new JSONObject();
        }
        try {
            return new JSONObject(sb.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private void saveIndex(JSONObject index) {
        File f = new File(dir(), INDEX_FILE);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(f), "UTF-8"))) {
            w.write(index.toString());
        } catch (IOException ignored) {
        }
    }

    private void writeEntry(JSONObject index, Wordlist wl) {
        try {
            JSONObject e = new JSONObject();
            e.put("category", wl.category.id);
            e.put("origin", wl.origin.id);
            e.put("note", wl.note);
            e.put("lines", wl.lines);
            e.put("size", wl.file.length());
            e.put("modified", wl.file.lastModified());
            index.put(wl.getName(), e);
        } catch (JSONException ignored) {
        }
    }

    private WordlistCategory detect(File f) {
        WordlistCategory byName = WordlistCategory.detectFromName(f.getName());
        if (byName != WordlistCategory.OTHER) return byName;
        List<String> sample = new ArrayList<>();
        try (BufferedReader r = reader(f)) {
            String line;
            while (sample.size() < SAMPLE_LINES && (line = r.readLine()) != null) sample.add(line);
        } catch (IOException ignored) {
        }
        return WordlistCategory.detectFromSample(sample);
    }

    private static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16);
    }

    public long totalBytes() {
        long total = 0;
        File[] files = dir().listFiles();
        if (files != null) {
            for (File f : files) if (f.isFile() && !f.getName().startsWith(".")) total += f.length();
        }
        return total;
    }

    public String describeFolder() {
        List<Wordlist> all = list();
        return String.format(Locale.US, "%d lists · %s", all.size(), Wordlist.humanBytes(totalBytes()));
    }
}
