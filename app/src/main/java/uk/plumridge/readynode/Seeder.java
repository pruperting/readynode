package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Seed mode: fill this phone's ReadyNet from the internet, so it becomes a complete ReadyNet that others can sync from
 * with no internet at all. Everything comes from the original sources, as listed in pack.json:
 *  - offline maps from OsmAnd (download.osmand.net), the regions you choose, unzipped as they download;
 *  - Kiwix libraries (download.kiwix.org), always the newest version, replacing older ones;
 *  - open-source Android apps from F-Droid (the build for this phone's processor), and Kiwix's own reader app.
 * Only new or updated things are downloaded. A download stays a hidden .part file until it's complete.
 * (The guides and this app itself come from inside the app: see SeedActivity.)
 *
 * Pure Java, so it's tested on a computer against stand-ins for those servers.
 */
public class Seeder {
    public static final String OSMAND = "https://download.osmand.net", KIWIX = "https://download.kiwix.org", FDROID = "https://f-droid.org";

    public static class Region {
        public String file, name, date = "", timestamp = "";
        public long bytes;
    }

    public static class Result {
        public int downloaded, upToDate, failed;
        public long bytes;
        public final List<String> problems = new ArrayList<>();
    }

    private final File root, stateFile;
    private final JSONObject pack;
    private final Syncer.Opener opener;
    private final String osmand, kiwix, fdroid;
    private volatile boolean cancelled;

    public Seeder(File root, File stateFile, JSONObject pack, Syncer.Opener opener) { this(root, stateFile, pack, opener, OSMAND, KIWIX, FDROID); }

    Seeder(File root, File stateFile, JSONObject pack, Syncer.Opener opener, String osmand, String kiwix, String fdroid) {
        this.root = root;
        this.stateFile = stateFile;
        this.pack = pack;
        this.opener = opener;
        this.osmand = osmand;
        this.kiwix = kiwix;
        this.fdroid = fdroid;
    }

    public void cancel() { cancelled = true; }

    // ------------------------------------------------------------------ maps
    private static final Pattern REGION = Pattern.compile("<region\\b[^>]*>"), ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    /** The map regions on offer (from OsmAnd's own list), for the prefixes in pack.json. */
    public List<Region> regions() throws Exception {
        byte[] raw = bytes(osmand + "/get_indexes?gzip=true");
        String xml;
        if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) { xml = new String(readAll(in), StandardCharsets.UTF_8); }
        } else xml = new String(raw, StandardCharsets.UTF_8);
        JSONArray prefixes = pack.optJSONObject("maps") == null ? new JSONArray() : pack.getJSONObject("maps").optJSONArray("prefixes");
        List<Region> out = new ArrayList<>();
        Matcher m = REGION.matcher(xml);
        while (m.find()) {
            Map<String, String> a = new HashMap<>();
            Matcher am = ATTR.matcher(m.group());
            while (am.find()) a.put(am.group(1), am.group(2));
            String n = a.getOrDefault("name", "");
            if (!n.endsWith(".obf.zip")) continue;
            String file = n.substring(0, n.length() - ".obf.zip".length());
            boolean wanted = false;
            if (prefixes != null) for (int i = 0; i < prefixes.length(); i++) if (file.startsWith(prefixes.optString(i))) wanted = true;
            if (!wanted || !file.matches("[A-Za-z0-9_.-]+")) continue;
            Region r = new Region();
            r.file = file;
            r.name = regionName(file);
            r.date = a.getOrDefault("date", "");
            r.timestamp = a.getOrDefault("timestamp", "");
            try { r.bytes = (long) (Double.parseDouble(a.getOrDefault("targetsize", "0")) * 1024 * 1024); } catch (NumberFormatException ignored) { }
            out.add(r);
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** "Gb_england_south-west-england_europe_2" → "South West England (England)". */
    static String regionName(String file) {
        if (file.startsWith("World_basemap")) return "World overview (zoomed out)";
        String s = file.replaceAll("_europe_\\d+$", "").replaceAll("_\\d+$", "").replaceFirst("^Gb_", "");
        String[] parts = s.split("_");
        String last = title(parts[parts.length - 1]);
        return parts.length > 1 ? last + " (" + title(parts[0]) + ")" : last;
    }

    private static String title(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split("[-\\s]+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.equals("of") || w.equals("and") || w.equals("the") ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ the whole run
    /**
     * Download what's chosen. regions: map files (from regions()); libraries / apps: everything in pack.json's lists.
     * abis: this phone's processor types, best first (Build.SUPPORTED_ABIS).
     */
    public Result run(Set<String> regions, boolean libraries, boolean apps, List<String> abis, Syncer.Progress progress) throws Exception {
        Result res = new Result();
        JSONObject state = stateFile.exists() ? new JSONObject(new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        root.mkdirs();
        if (!regions.isEmpty()) {
            Map<String, Region> on = new HashMap<>();
            for (Region r : regions()) on.put(r.file, r);
            JSONObject seen = obj(state, "maps");
            for (String f : regions) {
                if (cancelled) break;
                Region r = on.get(f);
                if (r == null) { problem(res, f + ": not in OsmAnd's list any more"); continue; }
                File obf = new File(root, "Maps/" + f + ".obf");
                if (obf.isFile() && r.timestamp.equals(seen.optString(f))) { res.upToDate++; continue; }
                try {
                    progress.update("Map: " + r.name, 0, r.bytes);
                    long n = unzipFrom(osmand + "/download?standard=yes&file=" + f + ".obf.zip", ".obf", obf, r.name, progress);
                    seen.put(f, r.timestamp);
                    res.downloaded++;
                    res.bytes += n;
                    save(state);
                } catch (IOException e) { problem(res, r.name + ": " + e.getMessage()); }
            }
        }
        if (libraries && !cancelled) libraries(res, state, progress);
        if (apps && !cancelled) apps(res, state, abis, progress);
        save(state);
        if (cancelled) throw new IOException("Cancelled");
        return res;
    }

    // ------------------------------------------------------------------ libraries (Kiwix)
    private void libraries(Result res, JSONObject state, Syncer.Progress progress) throws Exception {
        JSONArray libs = pack.optJSONArray("libraries");
        Map<String, String> listings = new HashMap<>();
        if (libs != null) for (int i = 0; i < libs.length() && !cancelled; i++) {
            JSONObject l = libs.getJSONObject(i);
            String path = l.getString("path"), name = l.optString("name", path), section = l.optString("section", "Knowledge");
            if (!path.matches("[a-z0-9_]+/[A-Za-z0-9_.-]+") || !(section.equals("Survival library") || section.equals("Knowledge"))) continue;
            String folder = path.substring(0, path.indexOf('/')), prefix = path.substring(path.indexOf('/') + 1);
            try {
                if (!listings.containsKey(folder)) listings.put(folder, new String(bytes(kiwix + "/zim/" + folder + "/"), StandardCharsets.UTF_8));
                Matcher m = Pattern.compile("href=\"(" + Pattern.quote(prefix) + "_(\\d{4}-\\d{2})\\.zim)\"").matcher(listings.get(folder));
                TreeSet<String> found = new TreeSet<>((a, b) -> a.substring(a.length() - 11).compareTo(b.substring(b.length() - 11)));
                while (m.find()) found.add(m.group(1));
                if (found.isEmpty()) { problem(res, name + ": not on Kiwix any more"); continue; }
                String newest = found.last();
                File out = new File(root, section + "/" + newest);
                if (out.isFile()) res.upToDate++;
                else {
                    progress.update("Library: " + name, 0, 0);
                    long n = download(kiwix + "/zim/" + folder + "/" + newest, out, name, progress);
                    res.downloaded++;
                    res.bytes += n;
                }
                for (String sec : new String[]{"Survival library", "Knowledge"}) {        // older versions go
                    File[] all = new File(root, sec).listFiles();
                    if (all != null) for (File f : all)
                        if (f.getName().matches(Pattern.quote(prefix) + "_\\d{4}-\\d{2}\\.zim") && !f.equals(out)) f.delete();
                }
            } catch (IOException e) { problem(res, name + ": " + e.getMessage()); }
        }
    }

    // ------------------------------------------------------------------ apps (F-Droid, and Kiwix's reader)
    private void apps(Result res, JSONObject state, List<String> abis, Syncer.Progress progress) throws Exception {
        JSONObject seen = obj(state, "apps");
        JSONObject meta = new JSONObject();
        JSONArray list = pack.optJSONArray("apps");
        if (list != null) for (int i = 0; i < list.length() && !cancelled; i++) {
            JSONObject a = list.getJSONObject(i);
            String pkg = a.getString("id"), name = fileName(a.optString("name", pkg));
            if (!pkg.matches("[A-Za-z0-9_.]+")) continue;
            File out = new File(root, "Apps/" + name + ".apk");
            File old = new File(root, "Apps/" + a.optString("name", pkg).replace("/", "-") + ".apk");    // 3.0 kept ":" in names
            if (!old.equals(out) && old.isFile() && !out.exists() && !old.renameTo(out)) old.delete();
            meta.put(out.getName(), new JSONObject().put("name", a.optString("name", pkg)).put("tags", a.optJSONArray("tags") == null ? new JSONArray() : a.getJSONArray("tags")));
            try {
                JSONObject info = new JSONObject(new String(bytes(fdroid + "/api/v1/packages/" + pkg), StandardCharsets.UTF_8));
                long suggested = info.getLong("suggestedVersionCode");
                JSONObject was = seen.optJSONObject(pkg);
                if (was != null && was.optLong("suggested") == suggested && out.isFile()) { res.upToDate++; continue; }
                long code = phoneBuild(pkg, info, abis);
                progress.update("App: " + name, 0, 0);
                long n = download(fdroid + "/repo/" + pkg + "_" + code + ".apk", out, name, progress);
                seen.put(pkg, new JSONObject().put("suggested", suggested).put("code", code));
                res.downloaded++;
                res.bytes += n;
                save(state);
            } catch (Exception e) { problem(res, name + ": " + e.getMessage()); }
        }
        JSONObject kw = pack.optJSONObject("kiwix");
        if (kw != null && !cancelled) {
            String name = fileName(kw.optString("name", "Kiwix"));
            File out = new File(root, "Apps/" + name + ".apk");
            meta.put(out.getName(), new JSONObject().put("name", name).put("tags", kw.optJSONArray("tags") == null ? new JSONArray() : kw.getJSONArray("tags")));
            try {
                String page = new String(bytes(kiwix + "/release/kiwix-android/"), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("href=\"(org\\.kiwix\\.kiwixmobile\\.standalone-v?(\\d+)\\.(\\d+)\\.(\\d+)\\.apk)\"").matcher(page);
                String best = null;
                long bestV = -1;
                while (m.find()) {
                    long v = Long.parseLong(m.group(2)) * 1_000_000L + Long.parseLong(m.group(3)) * 1000L + Long.parseLong(m.group(4));
                    if (v > bestV) { bestV = v; best = m.group(1); }
                }
                if (best == null) problem(res, name + ": not found on Kiwix's site");
                else if (best.equals(state.optString("kiwixApk")) && out.isFile()) res.upToDate++;
                else {
                    progress.update("App: " + name, 0, 0);
                    res.bytes += download(kiwix + "/release/kiwix-android/" + best, out, name, progress);
                    res.downloaded++;
                    state.put("kiwixApk", best);
                }
            } catch (IOException e) { problem(res, name + ": " + e.getMessage()); }
        }
        // names and tags for the apps that are here, so ReadyNet shows each on the right pages (keeping other entries)
        File appsJson = new File(root, "Apps/apps.json");
        JSONObject doc = new JSONObject();
        try { if (appsJson.isFile()) doc = new JSONObject(new String(Files.readAllBytes(appsJson.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        JSONObject all = doc.optJSONObject("apps") == null ? new JSONObject() : doc.getJSONObject("apps");
        for (java.util.Iterator<String> it = meta.keys(); it.hasNext(); ) {           // Android's org.json has no getNames
            String k = it.next();
            if (new File(root, "Apps/" + k).isFile()) all.put(k, meta.get(k));
        }
        Syncer.write(appsJson, new JSONObject().put("apps", all).toString(1));
    }

    /**
     * F-Droid's suggested version can be a build for PCs (x86) when an app has one APK per processor type. Pick the
     * build this phone can run (or one that runs everywhere), reading each APK's list of native code from its end.
     */
    long phoneBuild(String pkg, JSONObject info, List<String> abis) throws Exception {
        long code = info.getLong("suggestedVersionCode");
        JSONArray pk = info.optJSONArray("packages");
        if (pk == null) return code;
        String vname = null;
        for (int i = 0; i < pk.length(); i++) if (pk.getJSONObject(i).getLong("versionCode") == code) vname = pk.getJSONObject(i).optString("versionName");
        List<Long> builds = new ArrayList<>();
        for (int i = 0; i < pk.length(); i++) {
            JSONObject p = pk.getJSONObject(i);
            if (vname != null && vname.equals(p.optString("versionName")) && !builds.contains(p.getLong("versionCode"))) builds.add(p.getLong("versionCode"));
        }
        if (builds.size() <= 1) return code;
        builds.sort((a, b) -> Long.compare(b, a));
        for (long c : builds) {
            Set<String> types = cpuTypes(fdroid + "/repo/" + pkg + "_" + c + ".apk");
            if (types.isEmpty()) return c;
            for (String abi : abis) if (types.contains(abi)) return c;
        }
        return code;
    }

    private static final Pattern LIB = Pattern.compile("lib/([a-z0-9_-]+)/");

    /** The processor types an APK has native code for, from the zip directory at its end. */
    Set<String> cpuTypes(String url) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Range", "bytes=-3000000");
        int code = c.getResponseCode();
        if (code != 200 && code != 206) { c.disconnect(); throw new Syncer.HttpError(code); }
        String tail;
        try (InputStream in = c.getInputStream()) { tail = new String(readAll(in), StandardCharsets.ISO_8859_1); } finally { c.disconnect(); }
        Set<String> out = new TreeSet<>();
        Matcher m = LIB.matcher(tail);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ------------------------------------------------------------------ plumbing
    private HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ReadyNode-seed/1");
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new Syncer.HttpError(code); }
        return c;
    }

    private byte[] bytes(String url) throws IOException {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream()) { return readAll(in); } finally { c.disconnect(); }
    }

    /**
     * Download to a hidden .part file, checking there's room, then put it in place. Returns its size. A dropped or
     * stalled connection (a slow mirror, the phone's Wi-Fi napping) carries on from where it got to, a few times over.
     */
    private long download(String url, File dest, String label, Syncer.Progress progress) throws IOException {
        dest.getParentFile().mkdirs();
        File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
        part.delete();
        long total = -1;
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES && !cancelled; attempt++) {
            if (attempt > 0) {
                progress.update(label + ": connection dropped, carrying on (try " + (attempt + 1) + " of " + RETRIES + ")…", part.length(), total);
                try { Thread.sleep(Math.min(30000, 3000L * attempt)); } catch (InterruptedException e) { throw new IOException("Cancelled"); }
            }
            long have = part.isFile() ? part.length() : 0;
            HttpURLConnection c;
            int code;
            try {
                c = opener.open(new URL(url));
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "ReadyNode-seed/1");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                code = c.getResponseCode();
            } catch (IOException e) { last = e; continue; }
            try {
                if (code == 416 && total > 0 && have == total) break;                // it had all arrived
                if (code == 200) have = 0;                                         // the server started again from the top
                else if (code != 206 || have == 0) throw new Syncer.HttpError(code);
                long len = c.getContentLengthLong();
                if (total < 0 && len >= 0) { total = have + len; room(total, dest); }
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, have > 0)) {
                    copy(in, out, label, have, total, progress);
                }
                if (total > 0 && part.length() < total) { last = new IOException("the connection dropped"); continue; }   // ended early, quietly
                last = null;
                break;
            } catch (Syncer.HttpError e) {
                part.delete();
                throw e;
            } catch (IOException e) {
                if (cancelled || (e.getMessage() != null && e.getMessage().startsWith("not enough room"))) { part.delete(); throw e; }
                last = e;
            } finally {
                c.disconnect();
            }
        }
        if (last != null || cancelled) { part.delete(); throw last != null ? last : new IOException("Cancelled"); }
        long n = part.length();
        if (total > 0 && n != total) { part.delete(); throw new IOException("incomplete download"); }
        move(part, dest);
        return n;
    }

    static final int RETRIES = 6;

    /** A file name that every card and stick can hold: FAT and exFAT refuse \\ / : * ? " < > | */
    static String fileName(String s) {
        String n = s.replace(": ", " - ").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        return n.isEmpty() ? "file" : n;
    }

    /** Download a zip and keep just the one file in it ending in ext, unzipping as it arrives (no copy of the zip). */
    private long unzipFrom(String url, String ext, File dest, String label, Syncer.Progress progress) throws IOException {
        for (int attempt = 1; ; attempt++) {           // unzipped as it streams, so a dropped connection starts that map again
            try {
                return unzipOnce(url, ext, dest, label, progress);
            } catch (Syncer.HttpError e) {
                throw e;
            } catch (IOException e) {
                if (cancelled || attempt >= 3 || (e.getMessage() != null && (e.getMessage().startsWith("not enough room") || e.getMessage().startsWith("the download had no")))) throw e;
                progress.update(label + ": connection dropped, starting it again (try " + (attempt + 1) + " of 3)…", 0, 0);
                try { Thread.sleep(5000L * attempt); } catch (InterruptedException x) { throw new IOException("Cancelled"); }
            }
        }
    }

    private long unzipOnce(String url, String ext, File dest, String label, Syncer.Progress progress) throws IOException {
        HttpURLConnection c = connect(url);
        long total = c.getContentLengthLong();
        dest.getParentFile().mkdirs();
        room(total * 3, dest);                                   // maps unzip to roughly two to three times the zip
        File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
        long n = -1;
        try (ZipInputStream z = new ZipInputStream(c.getInputStream())) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory() || !e.getName().endsWith(ext)) continue;
                try (OutputStream out = new FileOutputStream(part)) { n = copy(z, out, label, 0, 0, progress); }
                break;
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (n < 0) { part.delete(); throw new IOException("the download had no " + ext + " file in it"); }
        move(part, dest);
        return n;
    }

    private void room(long need, File dest) throws IOException {
        long free = dest.getParentFile().getUsableSpace();
        if (need > 0 && need > free - (200L << 20))
            throw new IOException("not enough room: needs " + NodeServer.human(need) + ", " + NodeServer.human(free) + " free");
    }

    /** Copies, reporting progress from "from" bytes in (a resumed download); returns the bytes copied this time. */
    private long copy(InputStream in, OutputStream out, String label, long from, long total, Syncer.Progress progress) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = from, shown = from;
        int k;
        while ((k = in.read(buf)) > 0) {
            if (cancelled) throw new IOException("Cancelled");
            out.write(buf, 0, k);
            done += k;
            if (done - shown > (2 << 20)) { shown = done; progress.update(label + ": " + NodeServer.human(done) + (total > 0 ? " of " + NodeServer.human(total) : ""), done, total); }
        }
        return done - from;
    }

    private static void move(File part, File dest) throws IOException {
        try {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static JSONObject obj(JSONObject parent, String key) throws Exception {
        JSONObject o = parent.optJSONObject(key);
        if (o == null) { o = new JSONObject(); parent.put(key, o); }
        return o;
    }

    private void save(JSONObject state) throws Exception { Syncer.write(stateFile, state.toString(1)); }

    private static void problem(Result res, String p) {
        res.failed++;
        res.problems.add(p);
    }

    /** Roughly how big the chosen download is, for the screen (libraries and apps from pack.json's estimates). */
    public long estimate(List<Region> chosen, boolean libraries, boolean apps) {
        long n = 0;
        for (Region r : chosen) n += r.bytes;
        JSONArray libs = pack.optJSONArray("libraries");
        if (libraries && libs != null) for (int i = 0; i < libs.length(); i++) n += libs.optJSONObject(i).optLong("mb") << 20;
        if (apps) n += 750L << 20;
        return n;
    }
}
