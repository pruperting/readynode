package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ReadyNet packs: this phone's ReadyNet as one file (a ".rdynd"), for when Wi-Fi between phones isn't an option:
 * an SD card, a USB-C stick, a laptop, or sent phone to phone like any other file. Inside it's an ordinary zip:
 * readynet-pack.json first (what's in it), then the files under their ReadyNet paths.
 *
 * - Files that shrink are compressed (maps by about 45%); ones that don't (Wikipedia-style libraries are already
 *   compressed) are stored as they are, so nothing wastes time.
 * - Over {@link #PART_LIMIT} it's split into parts, "… part 1 of 2.rdynd", each complete in itself: memory cards and
 *   sticks are usually FAT32, which can't hold a file of 4 GB or more. Parts can be copied in in any order.
 * - Written straight to the card and unpacked straight onto the phone: neither end needs room for a second copy.
 * - Copying in skips files already here at the same size, so an interrupted copy just carries on.
 *
 * Pure Java; the folder it's written to is behind {@link Tree}, so it's tested with ordinary folders and used with
 * Android's storage picker.
 */
public final class Packs {
    private Packs() {}

    public static final String EXT = ".rdynd", ABOUT = "readynet-pack.json";
    public static final long PART_LIMIT = 3_800_000_000L;               // under FAT32's 4 GB, with room for the zip's own bits
    /** What a pack carries: everything a phone syncs except other people's shared files. */
    public static final List<String> SECTIONS = Arrays.asList("Maps", "Survival library", "Knowledge", "Apps");

    /** A folder somewhere: an ordinary folder in tests, a folder picked with Android's storage picker on a phone. */
    public interface Tree {
        Tree child(String name, boolean create) throws IOException;    // a sub-folder; null if it isn't there and !create
        List<String[]> list() throws IOException;                       // {name, "d" or "f", size}
        InputStream read(String name) throws IOException;
        OutputStream write(String name) throws IOException;             // a new file (replacing one of that name)
        void delete(String name) throws IOException;
        void rename(String from, String to) throws IOException;
    }

    public static class Result {
        public int copied, skipped, failed, parts;
        public long bytes, packed;
        public final List<String> names = new ArrayList<>();
        public String lastError = "";
        /** Copying in: of all the files in this pack's whole set (every part), how many this phone now has, and the
         *  parts that still have files missing (empty when the set is complete). Older packs don't say: setFiles = -1. */
        public int setFiles = -1, setHave;
        public final List<Integer> partsNeeded = new ArrayList<>();
    }

    // ------------------------------------------------------------------ make: phone → .rdynd file(s)
    public static Result export(File root, Tree into, String madeBy, Syncer.Progress progress) throws Exception {
        return export(root, into, madeBy, PART_LIMIT, progress);
    }

    static Result export(File root, Tree into, String madeBy, long partLimit, Syncer.Progress progress) throws Exception {
        List<String[]> files = new ArrayList<>();                         // {path, size}
        for (String sec : SECTIONS) walk(new File(root, sec), sec, files);
        if (files.isEmpty()) throw new IOException("there's nothing on this phone to pack yet: seed or sync first");

        List<List<String[]>> parts = new ArrayList<>();                  // fill each part up to the limit, in order
        long inPart = 0, total = 0;
        for (String[] f : files) {
            long size = Long.parseLong(f[1]);
            if (parts.isEmpty() || (inPart > 0 && inPart + size > partLimit)) { parts.add(new ArrayList<>()); inPart = 0; }
            parts.get(parts.size() - 1).add(f);
            inPart += size;
            total += size;
        }
        String base = "ReadyNet " + Seeder.fileName(madeBy) + " " + new SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(new Date());
        Result r = new Result();
        r.parts = parts.size();
        JSONArray set = new JSONArray();                                  // every part lists the whole set, and which part has what
        for (int p = 0; p < parts.size(); p++)
            for (String[] f : parts.get(p)) set.put(new JSONArray().put(f[0]).put(Long.parseLong(f[1])).put(p + 1));
        long done = 0;
        byte[] buf = new byte[256 * 1024];
        for (int p = 0; p < parts.size(); p++) {
            String name = base + (parts.size() > 1 ? " part " + (p + 1) + " of " + parts.size() : "") + EXT;
            JSONArray list = new JSONArray();
            for (String[] f : parts.get(p)) list.put(new JSONObject().put("path", f[0]).put("size", Long.parseLong(f[1])));
            JSONObject about = new JSONObject().put("readynet", 1).put("made", NewsStore.now()).put("by", madeBy)
                    .put("part", p + 1).put("parts", parts.size()).put("files", list).put("set", set);
            into.delete(name);
            Counting count = new Counting(into.write(name));
            boolean ok = false;
            try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(count, 1 << 20))) {
                z.setLevel(Deflater.BEST_COMPRESSION);
                z.putNextEntry(new ZipEntry(ABOUT));
                z.write(about.toString(1).getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                for (String[] f : parts.get(p)) {
                    File src = new File(root, f[0]);
                    long size = Long.parseLong(f[1]);
                    String label = (parts.size() > 1 ? "Part " + (p + 1) + " of " + parts.size() + ": " : "") + f[0];
                    progress.update(label, done, total);
                    z.setLevel(shrinks(src) ? 6 : Deflater.NO_COMPRESSION);
                    ZipEntry e = new ZipEntry(f[0]);
                    e.setTime(src.lastModified());
                    z.putNextEntry(e);
                    long shown = done;
                    try (InputStream in = new FileInputStream(src)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            z.write(buf, 0, n);
                            done += n;
                            if (done - shown > (8 << 20)) { shown = done; progress.update(label, done, total); }
                        }
                    }
                    z.closeEntry();
                    r.copied++;
                    r.bytes += size;
                }
                z.finish();
                ok = true;
            } finally {
                if (!ok) try { into.delete(name); } catch (IOException ignored) { }    // half a pack is no use to anyone
            }
            r.packed += count.n;
            r.names.add(name);
        }
        progress.update("Done", total, total);
        return r;
    }

    /**
     * Is this file worth compressing? Tries eight pieces spread through it, since files vary inside (an app's code
     * shrinks, its pictures don't). Only a file that's incompressible throughout, like a Wikipedia library, is stored.
     */
    static boolean shrinks(File f) throws IOException {
        long len = f.length();
        if (len < 1 << 20) return true;
        int piece = 128 * 1024;
        byte[] in = new byte[piece];
        byte[] out = new byte[64 * 1024];
        long packed = 0, read = 0;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            for (int i = 0; i < 8; i++) {
                raf.seek((len - piece) * i / 7);
                raf.readFully(in);
                Deflater d = new Deflater(1);
                d.setInput(in);
                d.finish();
                while (!d.finished()) packed += d.deflate(out);
                d.end();
                read += piece;
            }
        }
        return packed < read * 0.97;
    }

    // ------------------------------------------------------------------ copy in: .rdynd → phone
    /** What a pack says it holds (its readynet-pack.json), or an IOException if it isn't a ReadyNet pack. */
    public static JSONObject about(InputStream in) throws IOException {
        ZipInputStream z = new ZipInputStream(new BufferedInputStream(in, 1 << 16));
        return readAbout(z);
    }

    private static JSONObject readAbout(ZipInputStream z) throws IOException {
        ZipEntry e;
        try {
            e = z.getNextEntry();
        } catch (IOException | IllegalArgumentException x) {
            throw new IOException("that isn't a ReadyNet pack");
        }
        if (e == null || !ABOUT.equals(e.getName())) throw new IOException("that isn't a ReadyNet pack");
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = z.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > (64 << 20)) throw new IOException("that isn't a ReadyNet pack"); }
            JSONObject a = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            if (a.optInt("readynet") != 1 || a.optJSONArray("files") == null) throw new IOException("that isn't a ReadyNet pack this app understands");
            return a;
        } catch (org.json.JSONException x) {
            throw new IOException("that isn't a ReadyNet pack");
        }
    }

    public static Result importFrom(InputStream source, File root, Syncer.Progress progress) throws Exception {
        ZipInputStream z = new ZipInputStream(new BufferedInputStream(source, 1 << 20));
        JSONObject about = readAbout(z);
        Map<String, Long> listed = new HashMap<>();
        JSONArray files = about.getJSONArray("files");
        long total = 0, need = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String p = f.optString("path");
            long size = f.optLong("size", -1);
            if (!safe(p) || size < 0) continue;                             // never anything outside ReadyNet's sections
            listed.put(p, size);
            total += size;
            File dest = new File(root, p);
            if (!(dest.isFile() && dest.length() == size)) need += size;
        }
        root.mkdirs();
        long free = root.getUsableSpace();
        if (need > free - (200L << 20))
            throw new IOException("not enough room on this phone: the pack needs " + NodeServer.human(need) + ", " + NodeServer.human(free) + " is free");
        String part = about.optInt("parts", 1) > 1 ? "Part " + about.optInt("part") + " of " + about.optInt("parts") + ": " : "";
        Result r = new Result();
        r.parts = about.optInt("parts", 1);
        long done = 0;
        byte[] buf = new byte[256 * 1024];
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            String p = e.getName();
            Long size = listed.remove(p);
            if (e.isDirectory() || size == null) continue;                   // unlisted or unsafe: ignored
            File dest = new File(root, p);
            if (dest.isFile() && dest.length() == size) { r.skipped++; done += size; continue; }
            File tmp = new File(dest.getParentFile(), "." + dest.getName() + ".part");
            try {
                dest.getParentFile().mkdirs();
                long got = 0, shown = 0;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        got += n;
                        if (got > size) throw new IOException("bigger than the pack says");
                        out.write(buf, 0, n);
                        if (got - shown > (8 << 20)) { shown = got; progress.update(part + p, done + got, total); }
                    }
                }
                if (got != size) throw new IOException("incomplete in the pack");
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                r.copied++;
                r.bytes += size;
            } catch (IOException x) {
                tmp.delete();
                if (x instanceof java.util.zip.ZipException || x instanceof java.io.EOFException) throw new IOException("the pack is damaged or cut short (" + p + ")");
                r.failed++;
                r.lastError = p + ": " + x.getMessage();
                if (x.getMessage() != null && x.getMessage().contains("ENOSPC")) throw new IOException("this phone ran out of room");
            }
            done += size;
        }
        if (!listed.isEmpty()) {
            r.failed += listed.size();
            r.lastError = "the pack is missing " + listed.size() + " file(s) it lists: it may have been cut short";
        }
        checkSet(about, root, r);
        progress.update("Done", total, total);
        return r;
    }

    /** After copying a part in: how much of the whole set is here now, and which parts are still needed. */
    static void checkSet(JSONObject about, File root, Result r) {
        JSONArray set = about.optJSONArray("set");
        if (set == null) return;
        java.util.TreeSet<Integer> need = new java.util.TreeSet<>();
        r.setFiles = 0;
        for (int i = 0; i < set.length(); i++) {
            JSONArray e = set.optJSONArray(i);
            if (e == null || !safe(e.optString(0))) continue;
            r.setFiles++;
            File f = new File(root, e.optString(0));
            if (f.isFile() && f.length() == e.optLong(1)) r.setHave++;
            else need.add(e.optInt(2));
        }
        r.partsNeeded.addAll(need);
    }

    /** What's left to copy in for a pack's set, from its readynet-pack.json alone (for the question before copying). */
    public static Result setStatus(JSONObject about, File root) {
        Result r = new Result();
        r.parts = about.optInt("parts", 1);
        checkSet(about, root, r);
        return r;
    }

    /** A path inside one of the packed sections, with no tricks. */
    static boolean safe(String p) {
        String c = Syncer.clean(p);
        if (c == null || !c.equals(p) || !p.contains("/")) return false;
        for (String seg : p.split("/")) if (seg.startsWith(".")) return false;
        return SECTIONS.contains(p.substring(0, p.indexOf('/')));
    }

    // ------------------------------------------------------------------ helpers
    private static void walk(File dir, String rel, List<String[]> out) {
        File[] all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".part")) continue;
            if (f.isDirectory()) { if (!n.equals(NodeServer.NOT_FOR_PHONES)) walk(f, rel + "/" + n, out); }
            else out.add(new String[]{rel + "/" + n, String.valueOf(f.length())});
        }
    }

    private static final class Counting extends FilterOutputStream {
        long n;
        Counting(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); n++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); n += len; }
    }

    /** A Tree over an ordinary folder (tests, and anything with a real path). */
    public static Tree folder(File dir) {
        return new Tree() {
            public Tree child(String name, boolean create) {
                File c = new File(dir, name);
                if (!c.isDirectory()) { if (!create || !c.mkdirs()) return null; }
                return folder(c);
            }

            public List<String[]> list() {
                List<String[]> out = new ArrayList<>();
                File[] all = dir.listFiles();
                if (all != null) for (File f : all) out.add(new String[]{f.getName(), f.isDirectory() ? "d" : "f", String.valueOf(f.length())});
                return out;
            }

            public InputStream read(String name) throws IOException { return new FileInputStream(new File(dir, name)); }

            public OutputStream write(String name) throws IOException { return new FileOutputStream(new File(dir, name)); }

            public void delete(String name) { new File(dir, name).delete(); }

            public void rename(String from, String to) throws IOException {
                if (!new File(dir, from).renameTo(new File(dir, to))) throw new IOException("couldn't rename " + from);
            }
        };
    }
}
