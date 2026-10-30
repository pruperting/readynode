package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies a ReadyNet onto this phone, from any ReadyNet server: another ReadyNode phone, a seed, or any other server
 * that answers /api/net/manifest and /net/... You choose the sections; only new or changed files are downloaded.
 *
 * Checksums: when the source gives a file's SHA-256, the download is checked against it and thrown away if it doesn't
 * match; a file already here with the same size but a different date is checked by its checksum instead of being
 * copied again.
 *
 * Removals, so a sync can never wipe out your copy by accident or by someone else's doing, and every source is
 * treated the same:
 *  - files in sections you've unticked (to free space);
 *  - files the ReadyNet they came from no longer has (each file remembers which ReadyNet it came from, so one with
 *    less than you can never make you remove anything it didn't give you);
 *  - and only after {@link Removals#allow} says yes (the app asks you, showing how many and how big).
 * Only files sync put here itself (listed in its index) are ever removed: your own uploads are never touched.
 *
 * A file being replaced stays in place until its new copy has fully arrived and checked out.
 *
 * Pure Java, so it's tested on a computer against real ReadyNet servers.
 */
public class Syncer {
    public interface Opener { HttpURLConnection open(URL url) throws IOException; }
    public interface Progress { void update(String message, long done, long total); }

    /** Asked before anything is removed. unticked: in sections you unticked; gone: dropped by the ReadyNet they came from. */
    public interface Removals { boolean allow(int unticked, int gone, long bytes, List<String> examples); }

    public static final Removals ALWAYS = (u, g, b, e) -> true;

    public static class Result {
        public int downloaded, kept, removed, failed, keptBack;
        public long bytes;
        public String from = "", kind = "", id = "", lastError = "";
    }

    /** What a ReadyNet offers, per section, before you choose. */
    public static class Offer {
        public String name = "", kind = "", id = "";
        public final Map<String, long[]> sections = new LinkedHashMap<>();   // section -> {files, bytes}
        JSONArray files;
    }

    private final File root, index;
    private final Opener opener;
    private volatile boolean cancelled;

    /** root: this phone's ReadyNet folder. index: where to remember what sync put there (outside root). */
    public Syncer(File root, File index, Opener opener) {
        this.root = root;
        this.index = index;
        this.opener = opener;
    }

    public void cancel() { cancelled = true; }

    static String base(String host) {
        host = host.trim().replaceAll("/+$", "");
        if (host.endsWith("/readynet")) host = host.substring(0, host.length() - "/readynet".length());
        return host.startsWith("http") ? host : "http://" + host;
    }

    public Offer offer(String host) throws Exception {
        JSONObject m = new JSONObject(get(base(host) + "/api/net/manifest"));
        Offer o = new Offer();
        o.name = m.optString("name", "ReadyNet");
        o.kind = m.optString("kind", "node");
        o.id = m.optString("id", "");
        o.files = m.optJSONArray("files");
        if (o.files == null) o.files = new JSONArray();
        for (String s : NodeServer.SECTIONS) o.sections.put(s, new long[2]);
        for (int i = 0; i < o.files.length(); i++) {
            JSONObject f = o.files.getJSONObject(i);
            long[] t = o.sections.get(f.optString("section"));
            if (t != null) { t[0]++; t[1] += f.optLong("size"); }
        }
        return o;
    }

    /**
     * How far behind this phone's copy is, without copying anything: {files, bytes} that a sync of these sections
     * would fetch. Quick (no checksums), so a file whose date changed counts even if its content didn't.
     */
    public long[] behind(Offer offer, Set<String> sections) throws Exception {
        JSONObject old = index.exists() ? new JSONObject(read(index)) : new JSONObject();
        long[] out = new long[2];
        for (int i = 0; i < offer.files.length(); i++) {
            JSONObject f = offer.files.getJSONObject(i);
            String path = clean(f.optString("path")), sec = f.optString("section");
            if (path == null || !sections.contains(sec) || !path.startsWith(sec + "/")) continue;
            long size = f.optLong("size"), mtime = f.optLong("mtime");
            String sha = sha(f);
            JSONObject prev = old.optJSONObject(path);
            File dest = new File(root, path);
            boolean have = prev != null && prev.optLong("size") == size && dest.isFile() && dest.length() == size
                    && (prev.optLong("mtime") == mtime || !sha.isEmpty() && sha.equals(prev.optString("sha256")));
            if (!have) { out[0]++; out[1] += size; }
        }
        return out;
    }

    public Result run(String host, Set<String> sections, Removals removals, Progress progress) throws Exception {
        String base = base(host);
        progress.update("Asking what there is to copy…", 0, 0);
        Offer offer = offer(host);
        Result res = new Result();
        res.from = offer.name;
        res.kind = offer.kind;
        res.id = offer.id;

        root.mkdirs();
        JSONObject old = index.exists() ? new JSONObject(read(index)) : new JSONObject();
        JSONObject now = new JSONObject();
        Set<String> offered = new HashSet<>();

        // work out what's needed first, and check there's room for it
        long need = 0, total = 0;
        JSONArray todo = new JSONArray();
        for (int i = 0; i < offer.files.length(); i++) {
            JSONObject f = offer.files.getJSONObject(i);
            String path = clean(f.optString("path")), sec = f.optString("section");
            if (path == null || !sections.contains(sec) || !path.startsWith(sec + "/")) continue;
            offered.add(path);
            long size = f.optLong("size"), mtime = f.optLong("mtime");
            String sha = sha(f);
            File dest = new File(root, path);
            JSONObject prev = old.optJSONObject(path);
            total += size;
            boolean sameMeta = prev != null && prev.optLong("size") == size && prev.optLong("mtime") == mtime
                    && dest.isFile() && dest.length() == size;
            boolean shaAgrees = sha.isEmpty() || prev == null || prev.optString("sha256").isEmpty() || sha.equals(prev.optString("sha256"));
            if (sameMeta && shaAgrees) {
                res.kept++;
                JSONObject e = new JSONObject(prev.toString());
                if (!sha.isEmpty()) e.put("sha256", sha);
                if (!e.has("from") && !offer.id.isEmpty()) e.put("from", offer.id);   // from before sources were noted
                now.put(path, e);
                continue;
            }
            if (dest.isFile() && dest.length() == size) {
                // here already but the dates differ (or it isn't ours yet): check the content rather than copy again
                boolean same = !sha.isEmpty() ? sha.equals(fileSha(dest))
                        : prev == null && Math.abs(dest.lastModified() / 1000 - mtime) <= 2;
                if (same) {
                    res.kept++;
                    if (mtime > 0) dest.setLastModified(mtime * 1000);
                    // remember it as sync's own if it was, or if its checksum proves it's the source's file (so a
                    // lost index doesn't mean checking it again every time); never claim files in Files, which may be yours
                    if (prev != null || !sha.isEmpty() && !sec.equals("Files")) now.put(path, entry(size, mtime, sec, sha, offer.id));
                    continue;
                }
            }
            todo.put(f);
            need += size;                                      // the old copy stays until the new one is complete
        }
        long free = root.getUsableSpace();
        if (need > free - (200L << 20)) {
            throw new IOException("not enough room on this phone: that needs " + NodeServer.human(need) + ", but only "
                    + NodeServer.human(free) + " is free. Untick a section and try again");
        }

        long done = total - sumSizes(todo);
        for (int i = 0; i < todo.length() && !cancelled; i++) {
            JSONObject f = todo.getJSONObject(i);
            String path = clean(f.optString("path"));
            long size = f.optLong("size"), mtime = f.optLong("mtime");
            String sha = sha(f);
            File dest = new File(root, path);
            progress.update("Copying " + path, done, total);
            try {
                download(base + "/net/" + NodeServer.enc(path), dest, size, sha);
                if (mtime > 0) dest.setLastModified(mtime * 1000);
                res.downloaded++;
                res.bytes += size;
                now.put(path, entry(size, mtime, f.optString("section"), sha, offer.id));
            } catch (IOException e) {
                res.failed++;
                res.lastError = path + ": " + e.getMessage();
                JSONObject prev = old.optJSONObject(path);
                if (prev != null) now.put(path, prev);
            }
            done += size;
            // remember progress as we go, so a cancelled or interrupted sync never forgets what it copied
            if (i % 20 == 19) write(index, merged(old, now).toString(1));
        }
        if (cancelled) {
            write(index, merged(old, now).toString(1));
            throw new IOException("Sync cancelled");
        }

        // what could be removed: only things sync put here, in unticked sections, or dropped by the ReadyNet they came from
        List<String> unticked = new ArrayList<>(), gone = new ArrayList<>();
        long bytes = 0;
        for (Iterator<String> it = old.keys(); it.hasNext(); ) {
            String p = it.next();
            if (now.has(p) || clean(p) == null) continue;
            JSONObject prev = old.getJSONObject(p);
            String sec = prev.optString("section", p.contains("/") ? p.substring(0, p.indexOf('/')) : "");
            File f = new File(root, p);
            if (!sections.contains(sec)) { unticked.add(p); bytes += f.length(); }
            else if (!offer.id.isEmpty() && offer.id.equals(prev.optString("from")) && !offered.contains(p)) { gone.add(p); bytes += f.length(); }
        }
        boolean remove = (unticked.isEmpty() && gone.isEmpty()) || removals.allow(unticked.size(), gone.size(), bytes,
                examples(unticked, gone));
        for (Iterator<String> it = old.keys(); it.hasNext(); ) {
            String p = it.next();
            if (now.has(p)) continue;
            if (remove && (unticked.contains(p) || gone.contains(p))) {
                File f = new File(root, p);
                if (f.delete()) res.removed++;
                pruneEmpty(f.getParentFile());
            } else {
                if (unticked.contains(p) || gone.contains(p)) res.keptBack++;
                now.put(p, old.getJSONObject(p));              // keep remembering it
            }
        }
        write(index, now.toString(1));
        progress.update("Done", total, total);
        return res;
    }

    private static List<String> examples(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>();
        for (String p : a) if (out.size() < 5) out.add(p);
        for (String p : b) if (out.size() < 5) out.add(p);
        return out;
    }

    private static JSONObject entry(long size, long mtime, String section, String sha, String from) throws Exception {
        JSONObject e = new JSONObject().put("size", size).put("mtime", mtime).put("section", section);
        if (from != null && !from.isEmpty()) e.put("from", from);
        if (!sha.isEmpty()) e.put("sha256", sha);
        return e;
    }

    private static String sha(JSONObject f) {
        String s = f.optString("sha256", "").toLowerCase();
        return s.matches("[0-9a-f]{64}") ? s : "";
    }

    private static long sumSizes(JSONArray a) throws Exception {
        long n = 0;
        for (int i = 0; i < a.length(); i++) n += a.getJSONObject(i).optLong("size");
        return n;
    }

    private static JSONObject merged(JSONObject old, JSONObject now) throws Exception {
        JSONObject m = new JSONObject(old.toString());
        for (Iterator<String> it = now.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, now.get(k)); }
        return m;
    }

    /** Keep paths inside root: no absolute paths, no "..", no hidden names, and only in a ReadyNet section. */
    static String clean(String p) {
        if (p == null) return null;
        p = p.replace('\\', '/');
        StringBuilder b = new StringBuilder();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..") || seg.startsWith(".")) return null;
            if (b.length() > 0) b.append('/');
            b.append(seg);
        }
        String out = b.toString();
        for (String s : NodeServer.SECTIONS) if (out.startsWith(s + "/")) return out;
        return null;
    }

    static class HttpError extends IOException {
        final int code;
        HttpError(int code) { super("HTTP " + code); this.code = code; }
    }

    private String get(String url) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new HttpError(code); }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    /** Download to a .part file, check size (and checksum if given), then swap it in place in one step. */
    private void download(String url, File dest, long expected, String sha) throws IOException {
        dest.getParentFile().mkdirs();
        File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new HttpError(code); }
        MessageDigest md = sha.isEmpty() ? null : sha256();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            copy(in, out, md);
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (part.length() != expected) { part.delete(); throw new IOException("Incomplete download"); }
        if (md != null && !sha.equals(hex(md.digest()))) {
            part.delete();
            throw new IOException("didn't match its checksum, so it wasn't kept");
        }
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            part.delete();
            throw new IOException("Couldn't save " + dest.getName() + " (the old copy is still there)");
        }
    }

    private void copy(InputStream in, OutputStream out, MessageDigest md) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancelled) throw new IOException("Sync cancelled");
            out.write(buf, 0, n);
            if (md != null) md.update(buf, 0, n);
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String fileSha(File f) {
        MessageDigest md = sha256();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } catch (IOException e) {
            return "";
        }
        return hex(md.digest());
    }

    static String hex(byte[] d) {
        StringBuilder h = new StringBuilder();
        for (byte x : d) h.append(String.format("%02x", x));
        return h.toString();
    }

    private void pruneEmpty(File dir) {
        while (dir != null && !dir.equals(root) && dir.getParentFile() != null && !dir.getParentFile().equals(root)) {
            String[] left = dir.list();
            if (left == null || left.length > 0 || !dir.delete()) return;
            dir = dir.getParentFile();
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
