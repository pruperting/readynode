package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;

/**
 * This phone's ReadyNet: the ReadyNet website (from the app's assets), answering the ReadyNet addresses over this
 * phone's copy in {@code root}. Other phones sync from it, and any other ReadyNet server answers the same addresses,
 * so phones can sync from those too. Pure Java (no Android classes), so it's tested on a computer.
 *
 * It listens on every address from the start (so it never has to restart when you switch modes), but in client
 * mode it only answers this phone itself: anyone else is told it isn't sharing.
 */
public class NodeServer extends NanoHTTPD {
    public static final int PORT = 8080;
    static final long NODE_MAX = 2048L << 20;      // phones skip files over 2 GB
    static final String[] SECTIONS = {"Maps", "Survival library", "Knowledge", "Apps", "Files"};
    /** A folder of bigger versions a server keeps for browsing and downloading, but never offers to phones to sync. */
    static final String NOT_FOR_PHONES = "Not for phones";

    /** Where the website's files come from: the app's assets on a phone, a folder in tests. */
    public interface Site { InputStream open(String name) throws IOException; }

    private final File root;
    private final Site site;
    private final NewsStore news;
    private final Supplier<String> name;
    private final File selfApk;
    private final String version;

    public NodeServer(String host, int port, File root, Site site, NewsStore news, Supplier<String> name,
                      File selfApk, String version) {
        super(host, port);
        this.root = root;
        this.site = site;
        this.news = news;
        this.name = name;
        this.selfApk = selfApk;
        this.version = version;
    }

    private volatile boolean shared;
    private volatile File hashIndex;                                   // sync's index: checksums of what it copied
    private volatile Supplier<String> id = () -> "";
    private volatile Supplier<JSONObject> power = () -> null;

    /** Re-serve the checksums sync verified, so phones syncing from this one can check them too. */
    public void setHashIndex(File f) { hashIndex = f; }

    public void setId(Supplier<String> s) { id = s; }

    /** This phone's battery, for the badge on its ReadyNet: {"pct":78,"charging":false}. */
    public void setPower(Supplier<JSONObject> p) { power = p; }

    /** Server mode on (answer everyone) or off (answer this phone only). */
    public void setShared(boolean on) { shared = on; }

    public boolean everyone() { return shared; }

    static boolean loopback(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("::ffff:127."));
    }

    // ------------------------------------------------------------------ routing
    @Override
    public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            if (!shared && !loopback(s.getRemoteIpAddress()))
                return text(Response.Status.FORBIDDEN, "text/plain", "This ReadyNode phone isn't sharing right now (it's in client mode).");
            boolean get = s.getMethod() == Method.GET || s.getMethod() == Method.HEAD;
            if (uri.equals("/") || uri.equals("/readynet")) return redirect("/readynet/");
            if ((uri.equals("/readynet/simple") || uri.startsWith("/readynet/simple/")) && get) return simple(strip(uri, "/readynet/simple"), first(s, "q"));
            if (uri.startsWith("/readynet/") && get) return asset(uri.substring("/readynet/".length()));
            if (uri.equals("/api/net/info") && get) return json(info());
            if ((uri.equals("/api/net/ls") || uri.startsWith("/api/net/ls/")) && get) return listing(strip(uri, "/api/net/ls"));
            if (uri.startsWith("/net/") && get) return file(s, safe(strip(uri, "/net")), "1".equals(first(s, "dl")));
            if (uri.equals("/readynode.apk") && get) return file(s, selfApk != null && selfApk.isFile() ? selfApk : null, true, "ReadyNode.apk");
            if (uri.equals("/api/net/news") && get) return json(newsDoc());
            if (uri.equals("/api/net/news/reply") && s.getMethod() == Method.POST) return reply(s);
            if (uri.equals("/api/net/news/relay") && s.getMethod() == Method.POST) return relay(s);
            if (uri.equals("/api/net/search") && get) return json(Search.json(root, first(s, "q")));
            if (uri.equals("/api/net/manifest") && get) return json(manifest());
            if (uri.startsWith("/api/") || uri.startsWith("/net/")) return text(Response.Status.NOT_FOUND, "text/plain", "not found");
            // anything else (including phones' captive-portal checks) goes to ReadyNet's home page
            return redirect("/readynet/");
        } catch (Exception e) {
            return text(Response.Status.INTERNAL_ERROR, "text/plain", "Something went wrong: " + e.getMessage());
        }
    }

    private static String strip(String uri, String prefix) {
        String r = uri.substring(prefix.length());
        while (r.startsWith("/")) r = r.substring(1);
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /** Resolve a path inside root; null if it escapes (../, symlinks), names something hidden, or doesn't exist. */
    File safe(String rel) {
        for (String seg : rel.split("/")) if (seg.startsWith(".") || seg.contains("\\") || seg.contains("\0")) return null;
        try {
            File base = root.getCanonicalFile();
            File f = new File(base, rel).getCanonicalFile();
            if (!f.equals(base) && !f.getPath().startsWith(base.getPath() + File.separator)) return null;
            return f.exists() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the website
    private Response asset(String name) throws IOException {
        if (name.isEmpty()) name = "index.html";
        if (!name.matches("[A-Za-z0-9._-]+") || name.startsWith(".")) return text(Response.Status.NOT_FOUND, "text/plain", "not found");
        InputStream in;
        try { in = site.open(name); } catch (IOException e) { return text(Response.Status.NOT_FOUND, "text/plain", "not found"); }
        byte[] data = readAll(in);
        Response r = newFixedLengthResponse(Response.Status.OK, mime(ext(name)), new java.io.ByteArrayInputStream(data), data.length);
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    // ------------------------------------------------------------------ the API (the same on every ReadyNet server)
    JSONObject info() throws Exception {
        JSONObject counts = new JSONObject();
        for (String sec : SECTIONS) {
            int[] n = {0};
            count(new File(root, sec), n);
            counts.put(sec, n[0]);
        }
        JSONObject out = new JSONObject().put("readynet", 1).put("name", name.get()).put("kind", "node").put("version", version)
                .put("id", id.get()).put("sections", counts).put("app", selfApk != null && selfApk.isFile());
        JSONObject p = power.get();
        if (p != null) out.put("power", p);
        return out;
    }

    private static void count(File dir, int[] n) {
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            String nm = f.getName().toLowerCase(Locale.ROOT);
            if (nm.startsWith(".")) continue;
            if (f.isDirectory()) count(f, n);
            else if (!nm.endsWith(".part") && !nm.endsWith(".json")) n[0]++;
        }
    }

    private Response listing(String rel) throws Exception {
        File dir = rel.isEmpty() ? root : safe(rel);
        if (dir == null || !dir.isDirectory()) return text(Response.Status.NOT_FOUND, "text/plain", "not found");
        JSONArray out = new JSONArray();
        File[] all = dir.listFiles();
        if (all != null) {
            Arrays.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : all) {
                if (f.getName().startsWith(".") || f.getName().endsWith(".part")) continue;
                JSONObject e = new JSONObject().put("name", f.getName()).put("type", f.isDirectory() ? "directory" : "file")
                        .put("mtime", httpDate(f.lastModified()));
                if (f.isFile()) e.put("size", f.length());
                out.put(e);
            }
        }
        return json(out);
    }

    /** Everything another phone may copy from this one: the same rules as every ReadyNet server. */
    JSONObject manifest() throws Exception {
        JSONArray files = new JSONArray();
        long[] total = {0};
        JSONObject hashes = new JSONObject();
        File hi = hashIndex;
        if (hi != null && hi.isFile()) try { hashes = new JSONObject(new String(Files.readAllBytes(hi.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        for (String sec : SECTIONS) walk(new File(root, sec), sec, sec, files, total, hashes);
        return new JSONObject().put("readynet", 1).put("name", name.get()).put("kind", "node").put("id", id.get())
                .put("generated", System.currentTimeMillis() / 1000).put("files", files).put("total", total[0]);
    }

    private static void walk(File dir, String rel, String sec, JSONArray out, long[] total, JSONObject hashes) throws Exception {
        File[] all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith(".") || n.endsWith(".part")) continue;
            if (f.isDirectory()) {
                if (!n.equalsIgnoreCase(NOT_FOR_PHONES)) walk(f, rel + "/" + n, sec, out, total, hashes);
            } else if (f.length() <= NODE_MAX) {
                JSONObject e = new JSONObject().put("path", rel + "/" + n).put("section", sec).put("size", f.length())
                        .put("mtime", f.lastModified() / 1000);
                JSONObject h = hashes.optJSONObject(rel + "/" + n);       // only if it's still exactly what sync checked
                if (h != null && h.optLong("size") == f.length() && h.optLong("mtime") == f.lastModified() / 1000
                        && !h.optString("sha256").isEmpty()) e.put("sha256", h.optString("sha256"));
                out.put(e);
                total[0] += f.length();
            }
        }
    }

    private Response reply(IHTTPSession s) throws Exception {
        Map<String, String> body = new HashMap<>();
        s.parseBody(body);
        String raw = body.get("postData");
        if (raw == null || raw.length() > 16 * 1024) return text(Response.Status.BAD_REQUEST, "text/plain", "send JSON");
        JSONObject b;
        try { b = new JSONObject(raw); } catch (Exception e) { return text(Response.Status.BAD_REQUEST, "text/plain", "send JSON"); }
        String[] err = news.reply(b.optString("id"), b.optString("who"), b.optString("text"), b.optString("t"), s.getRemoteIpAddress());
        if (err == null) return json(new JSONObject().put("ok", true), Response.Status.CREATED);
        return text(status(Integer.parseInt(err[0])), "text/plain", err[1]);
    }

    private final Map<String, Long> lastRelay = new HashMap<>();
    static volatile long relayIntervalMs = 10_000;               // one hand-over per address every 10 seconds

    private volatile Broadcasts broadcasts;

    /** Broadcasts (one per ReadyNode, passed from phone to phone): see Broadcasts and Relay. */
    public void setBroadcasts(Broadcasts b) { broadcasts = b; }

    /** This ReadyNet's own news, and the broadcasts it shows and passes on. */
    JSONObject newsDoc() throws Exception {
        Broadcasts b = broadcasts;
        return new JSONObject().put("id", id.get()).put("name", name.get()).put("items", news.items())
                .put("broadcasts", b == null ? new JSONArray() : b.forOthers());
    }

    /** Another ReadyNode phone hands over its broadcast (and those it carries), if this phone takes them. */
    private Response relay(IHTTPSession s) throws Exception {
        Broadcasts bc = broadcasts;
        if (bc == null || !bc.receiving()) return refuse(Response.Status.FORBIDDEN, "This ReadyNode isn't taking broadcasts.");
        String len = s.getHeaders().get("content-length");
        if (len == null || !len.matches("\\d{1,8}") || Long.parseLong(len) > (2L << 20)) return refuse(Response.Status.BAD_REQUEST, "too big");
        String ip = s.getRemoteIpAddress();
        synchronized (lastRelay) {
            Long last = lastRelay.get(ip);
            if (last != null && System.currentTimeMillis() - last < relayIntervalMs) return refuse(status(429), "wait a few seconds");
            lastRelay.put(ip, System.currentTimeMillis());
        }
        Map<String, String> body = new HashMap<>();
        s.parseBody(body);
        JSONObject in;
        try { in = new JSONObject(body.get("postData")); } catch (Exception e) { return text(Response.Status.BAD_REQUEST, "text/plain", "send JSON"); }
        return json(new JSONObject().put("ok", true).put("added",
                bc.merge(in.optJSONArray("broadcasts"), in.optString("id"), in.optString("name"), System.currentTimeMillis())));
    }

    /** Turn a request away without reading its body, so close the connection rather than leave the body in it. */
    private static Response refuse(Response.IStatus st, String msg) {
        Response r = text(st, "text/plain", msg);
        r.addHeader("Connection", "close");
        r.closeConnection(true);
        return r;
    }

    private static Response.IStatus status(int code) {
        for (Response.Status st : Response.Status.values()) if (st.getRequestStatus() == code) return st;
        return new Response.IStatus() {
            public String getDescription() { return code + " Error"; }
            public int getRequestStatus() { return code; }
        };
    }

    // ------------------------------------------------------------------ files, with seeking (films, music)
    private Response file(IHTTPSession s, File f, boolean download) throws IOException {
        return file(s, f, download, f == null ? "" : f.getName());
    }

    private Response file(IHTTPSession s, File f, boolean download, String fileName) throws IOException {
        if (f == null || !f.isFile()) return text(Response.Status.NOT_FOUND, "text/plain", "not found");
        long len = f.length();
        String mime = mime(ext(fileName));
        String range = s.getHeaders().get("range");
        Response r;
        if (range != null && range.startsWith("bytes=")) {
            long start = 0, end = len - 1;
            String[] p = range.substring(6).split(",")[0].trim().split("-", -1);
            try {
                if (!p[0].isEmpty()) start = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
                if (p[0].isEmpty() && p.length > 1) { start = Math.max(0, len - Long.parseLong(p[1])); end = len - 1; }
            } catch (NumberFormatException ex) { start = 0; end = len - 1; }
            if (start >= len || start > end) {
                r = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "");
                r.addHeader("Content-Range", "bytes */" + len);
                return r;
            }
            end = Math.min(end, len - 1);
            InputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < start) { long n = in.skip(start - skipped); if (n <= 0) break; skipped += n; }
            r = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, in, end - start + 1);
            r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + len);
        } else {
            r = newFixedLengthResponse(Response.Status.OK, mime, new FileInputStream(f), len);
        }
        r.addHeader("Accept-Ranges", "bytes");
        r.addHeader("Last-Modified", httpDate(f.lastModified()));
        if (download) r.addHeader("Content-Disposition", "attachment; filename=\"" + fileName.replace("\"", "") + "\"");
        return r;
    }

    // ------------------------------------------------------------------ the simple view, for old browsers
    // Plain pages with no JavaScript. Every ReadyNet server makes the same ones.
    private static final String[][] TITLES = {{"Maps", "Offline maps"}, {"Survival library", "Survival library"},
            {"Knowledge", "Knowledge"}, {"Apps", "Android apps"}, {"Files", "Files"}};
    private static final String SIMPLE_CSS = "body{font:16px/1.5 sans-serif;margin:0 auto;max-width:760px;padding:0 12px 30px;background:#f3f4f2;color:#1c2422}"
            + "h1{font-size:22px}h2{font-size:15px;text-transform:uppercase;color:#5d6865;margin-top:22px}"
            + ".b{background:#fff;border:1px solid #d8ddda;border-radius:6px;padding:8px 12px;margin:6px 0}"
            + "a{color:#1c2422}.m{color:#5d6865;font-size:13px}pre{white-space:pre-wrap;word-wrap:break-word}";

    private static String title(String rel) {
        for (String[] t : TITLES) if (t[0].equals(rel)) return t[1];
        return rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
    }

    private Response simplePage(String title, String body) {
        String html = "<!DOCTYPE html><html lang=\"en-GB\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>" + SIMPLE_CSS + "</style></head><body><p><b>ReadyNet</b> &middot; " + esc(name.get())
                + " &middot; simple view</p>" + body + "<p class=\"m\">Simple view for older phones. <a href=\"/readynet/\">Full ReadyNet</a>"
                + " &middot; Call 999 if someone is in immediate danger.</p></body></html>";
        Response r = text(Response.Status.OK, "text/html; charset=utf-8", html);
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    private static String lines(String s) { return esc(s).replace("\n", "<br>"); }

    private static String searchForm(String q) {
        return "<form action=\"/readynet/simple/\" method=\"get\"><input name=\"q\" value=\"" + esc(q)
                + "\" placeholder=\"Search guides and files\"> <button>Search</button></form>";
    }

    private Response simple(String rel, String q) throws Exception {
        if (rel.isEmpty() && q != null && !q.trim().isEmpty()) {
            StringBuilder b = new StringBuilder("<p><a href=\"/readynet/simple/\">&larr; Back</a></p><h1>Search</h1>").append(searchForm(q.trim()));
            List<Search.Hit> hits = Search.find(root, q.trim());
            if (hits.isEmpty()) b.append("<div class=\"b\">Nothing matched.</div>");
            for (Search.Hit h : hits) {
                String label = h.title.isEmpty() ? h.path.substring(h.path.lastIndexOf('/') + 1) : h.title;
                b.append("<div class=\"b\"><a href=\"/readynet/simple/").append(enc(h.path)).append("\">").append(esc(label)).append("</a> <span class=\"m\">")
                 .append(esc(h.path.substring(0, h.path.lastIndexOf('/')))).append(" &middot; ").append(human(h.size)).append("</span>");
                if (!h.snippet.isEmpty()) b.append("<br><span class=\"m\">").append(esc(h.snippet)).append("</span>");
                b.append("</div>");
            }
            return simplePage("Search", b.toString());
        }
        if (rel.isEmpty()) {
            StringBuilder b = new StringBuilder("<h1>").append(esc(name.get())).append("</h1>");
            JSONArray items = news.items();
            if (items.length() > 0) {
                b.append("<h2>News</h2>");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject n = items.getJSONObject(i);
                    b.append("<div class=\"b\"><span class=\"m\">").append(esc(n.optString("t") + " " + n.optString("who"))).append("</span><br>")
                     .append(lines(n.optString("text")));
                    JSONArray rs = n.optJSONArray("replies");
                    if (rs != null) for (int j = 0; j < rs.length(); j++) {
                        JSONObject r = rs.getJSONObject(j);
                        b.append("<br><span class=\"m\">&rarr; ").append(esc(r.optString("t") + " " + r.optString("who"))).append(":</span> ")
                         .append(lines(r.optString("text")));
                    }
                    b.append("</div>");
                }
            }
            Broadcasts bc = broadcasts;
            List<JSONObject> casts = bc == null ? new ArrayList<>() : bc.visible();
            if (!casts.isEmpty()) {
                b.append("<h2>Broadcasts from ReadyNodes</h2>");
                for (JSONObject c : casts)
                    b.append("<div class=\"b\"><span class=\"m\">").append(esc(c.optString("t") + " · " + c.getJSONObject("from").optString("name")
                            + (c.optString("via").isEmpty() ? "" : " · via " + c.optString("via")))).append("</span><br>").append(lines(c.optString("text"))).append("</div>");
            }
            b.append(searchForm(""));
            b.append("<h2>Sections</h2>");
            for (String[] t : TITLES)
                b.append("<div class=\"b\"><a href=\"/readynet/simple/").append(enc(t[0])).append("/\">").append(t[1]).append("</a></div>");
            b.append("<h2>The ReadyNode app (Android 8 or newer)</h2><div class=\"b\"><a href=\"/readynode.apk\">Download ReadyNode</a></div>");
            return simplePage("ReadyNet", b.toString());
        }
        File f = safe(rel);
        if (f == null) return text(Response.Status.NOT_FOUND, "text/plain", "not found");
        String parent = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
        if (f.isFile()) {
            String low = f.getName().toLowerCase(Locale.ROOT);
            if (!low.endsWith(".md") && !low.endsWith(".txt")) {
                Response r = newFixedLengthResponse(Response.Status.FOUND, "text/plain", "");
                r.addHeader("Location", "/net/" + enc(rel));
                return r;
            }
            String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return simplePage(f.getName(), "<p><a href=\"/readynet/simple/" + enc(parent) + "/\">&larr; Back</a> &middot; <a href=\"/net/"
                    + enc(rel) + "?dl=1\">Download</a></p><div class=\"b\"><pre>" + esc(t) + "</pre></div>");
        }
        File[] all = f.listFiles();
        List<File> list = new ArrayList<>();
        if (all != null) for (File x : all) {
            String n = x.getName();
            if (n.startsWith(".") || n.endsWith(".part") || n.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            list.add(x);
        }
        list.sort((a, c) -> a.isDirectory() != c.isDirectory() ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(c.getName()));
        StringBuilder b = new StringBuilder("<p><a href=\"/readynet/simple/").append(enc(parent)).append("\">&larr; Back</a></p><h1>")
                .append(esc(title(rel))).append("</h1>");
        for (File x : list) {
            String p = rel + "/" + x.getName();
            if (x.isDirectory()) {
                b.append("<div class=\"b\"><a href=\"/readynet/simple/").append(enc(p)).append("/\">").append(esc(x.getName())).append("/</a></div>");
            } else {
                b.append("<div class=\"b\"><a href=\"/readynet/simple/").append(enc(p)).append("\">").append(esc(x.getName())).append("</a> <span class=\"m\">")
                 .append(human(x.length())).append(" &middot; <a href=\"/net/").append(enc(p)).append("?dl=1\">download</a></span></div>");
            }
        }
        if (list.isEmpty()) b.append("<div class=\"b\">Nothing here yet.</div>");
        return simplePage(title(rel), b.toString());
    }

    // ------------------------------------------------------------------ helpers
    private static Response json(Object o) { return json(o, Response.Status.OK); }

    private static Response json(Object o, Response.IStatus st) {
        Response r = newFixedLengthResponse(st, "application/json", o.toString());
        r.addHeader("Cache-Control", "no-cache");
        return r;
    }

    private static Response text(Response.IStatus st, String mime, String body) {
        return newFixedLengthResponse(st, mime, body);
    }

    private static Response redirect(String to) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "");
        r.addHeader("Location", to);
        return r;
    }

    private static String first(IHTTPSession s, String key) {
        List<String> v = s.getParameters().get(key);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    /** Read a stream to the end (InputStream.readAllBytes needs Android 13). */
    static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** URL-encode each path segment, keeping the slashes. */
    static String enc(String path) {
        StringBuilder b = new StringBuilder();
        for (String p : path.split("/", -1)) {
            if (b.length() > 0) b.append('/');
            try {
                b.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"));   // the Charset version needs Android 13
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return b.toString();
    }

    static String human(long n) {
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return i < 2 ? String.format(Locale.UK, "%.0f %s", v, u[i]) : String.format(Locale.UK, "%.1f %s", v, u[i]);
    }

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i).toLowerCase(Locale.ROOT);
    }

    static String mime(String e) {
        switch (e) {
            case ".html": case ".htm": return "text/html; charset=utf-8";
            case ".css": return "text/css; charset=utf-8";
            case ".js": return "text/javascript; charset=utf-8";
            case ".json": return "application/json";
            case ".mp3": return "audio/mpeg";
            case ".m4a": return "audio/mp4";
            case ".aac": return "audio/aac";
            case ".ogg": case ".opus": return "audio/ogg";
            case ".flac": return "audio/flac";
            case ".wav": return "audio/wav";
            case ".mp4": case ".m4v": return "video/mp4";
            case ".webm": return "video/webm";
            case ".mkv": return "video/x-matroska";
            case ".mov": return "video/quicktime";
            case ".jpg": case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".svg": return "image/svg+xml";
            case ".txt": case ".md": case ".csv": case ".log": case ".srt": return "text/plain; charset=utf-8";
            case ".pdf": return "application/pdf";
            case ".epub": return "application/epub+zip";
            case ".apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }
}
