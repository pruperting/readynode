package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * ReadyNet packs: this phone's ReadyNet copied to or from a folder of ordinary files, for when Wi-Fi between phones
 * isn't an option: an SD card, a USB-C stick, a laptop. A pack is a folder called "ReadyNet pack" holding the
 * sections (Maps, Survival library, Knowledge, Apps, Files) exactly as on a phone, plus readynet-pack.json describing
 * it. Ordinary files, not one big zip: memory cards and USB sticks are usually FAT32, which can't hold a file over
 * 4 GB, and a zip would need room for itself and its contents at once.
 *
 * Both directions skip files already there at the same size, so an interrupted copy just carries on. Pure Java;
 * the folder is behind {@link Tree}, so it's tested with ordinary folders and used with Android's storage picker.
 */
public final class Packs {
    private Packs() {}

    public static final String FOLDER = "ReadyNet pack", ABOUT = "readynet-pack.json";

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
        public int copied, skipped, failed;
        public long bytes;
        public String lastError = "";
    }

    // ------------------------------------------------------------------ export: phone → pack
    public static Result export(File root, Set<String> sections, Tree into, String madeBy, Syncer.Progress progress) throws Exception {
        Tree pack = into.child(FOLDER, true);
        List<String[]> files = new ArrayList<>();                         // {path, size}
        for (String sec : NodeServer.SECTIONS) if (sections.contains(sec)) walk(new File(root, sec), sec, files);
        long total = 0;
        for (String[] f : files) total += Long.parseLong(f[1]);
        Result r = new Result();
        long done = 0;
        JSONArray about = new JSONArray();
        for (String[] f : files) {
            long size = Long.parseLong(f[1]);
            progress.update("Copying " + f[0], done, total);
            try {
                Tree dir = dirFor(pack, f[0], true);
                String name = f[0].substring(f[0].lastIndexOf('/') + 1);
                if (sizeIn(dir, name) == size) r.skipped++;
                else {
                    String part = "." + name + ".part";
                    try (InputStream in = new FileInputStream(new File(root, f[0])); OutputStream out = dir.write(part)) { copy(in, out); }
                    dir.delete(name);
                    dir.rename(part, name);
                    r.copied++;
                    r.bytes += size;
                }
                about.put(new JSONObject().put("path", f[0]).put("size", size));
            } catch (IOException e) {
                r.failed++;
                r.lastError = f[0] + ": " + e.getMessage();
            }
            done += size;
        }
        try (OutputStream out = pack.write(ABOUT)) {
            out.write(new JSONObject().put("readynet", 1).put("made", NewsStore.now()).put("by", madeBy).put("files", about).toString(1)
                    .getBytes(StandardCharsets.UTF_8));
        }
        progress.update("Done", total, total);
        return r;
    }

    // ------------------------------------------------------------------ import: pack → phone
    /** picked: the "ReadyNet pack" folder itself, or the folder it's in. */
    public static Result importFrom(Tree picked, File root, Syncer.Progress progress) throws Exception {
        Tree pack = picked.child(FOLDER, false);
        if (pack == null) pack = picked;
        List<String[]> files = new ArrayList<>();
        for (String sec : NodeServer.SECTIONS) {
            Tree t = pack.child(sec, false);
            if (t != null) walkTree(t, sec, files);
        }
        if (files.isEmpty()) throw new IOException("no ReadyNet pack there: pick the \"" + FOLDER + "\" folder");
        long total = 0;
        for (String[] f : files) total += Long.parseLong(f[1]);
        root.mkdirs();
        long free = root.getUsableSpace();
        Result r = new Result();
        long need = 0;
        for (String[] f : files) {
            File dest = new File(root, f[0]);
            if (!(dest.isFile() && dest.length() == Long.parseLong(f[1]))) need += Long.parseLong(f[1]);
        }
        if (need > free - (200L << 20))
            throw new IOException("not enough room on this phone: the pack needs " + NodeServer.human(need) + ", " + NodeServer.human(free) + " is free");
        long done = 0;
        for (String[] f : files) {
            long size = Long.parseLong(f[1]);
            File dest = new File(root, f[0]);
            progress.update("Copying " + f[0], done, total);
            if (dest.isFile() && dest.length() == size) { r.skipped++; done += size; continue; }
            File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
            try {
                dest.getParentFile().mkdirs();
                Tree dir = dirFor(pack, f[0], false);
                try (InputStream in = dir.read(f[0].substring(f[0].lastIndexOf('/') + 1)); OutputStream out = new FileOutputStream(part)) { copy(in, out); }
                if (part.length() != size) throw new IOException("incomplete copy");
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                r.copied++;
                r.bytes += size;
            } catch (IOException e) {
                part.delete();
                r.failed++;
                r.lastError = f[0] + ": " + e.getMessage();
            }
            done += size;
        }
        progress.update("Done", total, total);
        return r;
    }

    // ------------------------------------------------------------------ helpers
    private static void walk(File dir, String rel, List<String[]> out) {
        File[] all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".part")) continue;
            if (f.isDirectory()) walk(f, rel + "/" + n, out);
            else out.add(new String[]{rel + "/" + n, String.valueOf(f.length())});
        }
    }

    private static void walkTree(Tree t, String rel, List<String[]> out) throws IOException {
        for (String[] e : t.list()) {
            if (e[0].startsWith(".") || e[0].endsWith(".part")) continue;
            String p = rel + "/" + e[0];
            if (Syncer.clean(p) == null || !Syncer.clean(p).equals(p)) continue;      // only safe paths in a section
            if ("d".equals(e[1])) { Tree c = t.child(e[0], false); if (c != null) walkTree(c, p, out); }
            else out.add(new String[]{p, e[2]});
        }
    }

    private static Tree dirFor(Tree pack, String path, boolean create) throws IOException {
        String[] segs = path.split("/");
        Tree t = pack;
        for (int i = 0; i < segs.length - 1; i++) {
            t = t.child(segs[i], create);
            if (t == null) throw new IOException("folder " + segs[i] + " missing");
        }
        return t;
    }

    private static long sizeIn(Tree dir, String name) throws IOException {
        for (String[] e : dir.list()) if (e[0].equals(name) && "f".equals(e[1])) return Long.parseLong(e[2]);
        return -1;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
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
