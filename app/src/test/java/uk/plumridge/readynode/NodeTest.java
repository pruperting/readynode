package uk.plumridge.readynode;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The phone's ReadyNet server and sync, on a computer:
 *  - phone to phone: two NodeServers, one syncing from the other;
 *  - from another ReadyNet server: set READYNET_URL (a running server) and READYNET_NET (its ReadyNet folder) to run.
 */
public class NodeTest {
    static final File SITE = new File("../site");
    static final Set<String> ALL_BUT_FILES = new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps"));

    static NodeServer server(int port, File root, String name, File news) {
        NodeServer s = new NodeServer("127.0.0.1", port, root, n -> new FileInputStream(new File(SITE, n)), new NewsStore(news),
                () -> name, new File(root.getParentFile(), "self.apk"), "test");
        String id = String.format("%016x", port);
        s.setId(() -> id);
        return s;
    }

    static File phone(String... files) throws Exception {
        File base = Files.createTempDirectory("phone").toFile(), root = new File(base, "readynet");
        for (int i = 0; i + 1 < files.length; i += 2) {
            File f = new File(root, files[i]);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), files[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        root.mkdirs();
        Files.write(new File(base, "self.apk").toPath(), "APK".getBytes());
        return root;
    }

    static Syncer syncer(File root) {
        return new Syncer(root, new File(root.getParentFile(), "sync-index.json"), u -> (HttpURLConnection) u.openConnection());
    }

    // ------------------------------------------------------------------ the server answers what the website needs
    @Test
    public void serverRoutes() throws Exception {
        File root = phone("Maps/Gb_wales_europe_2.obf", "map", "Knowledge/Not for phones/big.zim", "big",
                "Apps/apps.json", "{}", "Files/Books/plan.pdf", "pdf", "Maps/.hidden", "x");
        byte[] audio = new byte[10000];
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) i;
        new File(root, "Files/Media").mkdirs();
        Files.write(new File(root, "Files/Media/Opening #1 & more.mp3").toPath(), audio);
        Files.write(new File(root.getParentFile(), "kit.json").toPath(), "{}".getBytes());       // a private file outside the copy
        NodeServer srv = server(18081, root, "Rupert's phone", new File(root.getParentFile(), "news.json"));
        srv.start(5000, true);
        try {
            assertEquals("/readynet/", get(18081, "/").headers.get("Location").get(0));
            assertEquals("/readynet/", get(18081, "/generate_204").headers.get("Location").get(0));   // captive-portal checks
            Resp page = get(18081, "/readynet/");
            assertEquals(200, page.code);
            assertTrue(page.body.contains("readynet.js"));
            assertEquals(200, get(18081, "/readynet/readynet.js").code);
            assertEquals(404, get(18081, "/readynet/..%2F..%2Fsecret").code);

            JSONObject info = new JSONObject(get(18081, "/api/net/info").body);
            assertEquals("Rupert's phone", info.getString("name"));
            assertEquals("node", info.getString("kind"));
            assertEquals(1, info.getJSONObject("sections").getInt("Maps"));
            assertEquals("apps.json isn't counted", 0, info.getJSONObject("sections").getInt("Apps"));

            JSONArray ls = new JSONArray(get(18081, "/api/net/ls/Files/Media").body);
            assertEquals("Opening #1 & more.mp3", ls.getJSONObject(0).getString("name"));
            assertEquals(10000, ls.getJSONObject(0).getLong("size"));
            assertEquals(404, get(18081, "/api/net/ls/..").code);

            Resp full = get(18081, "/net/Files/Media/Opening%20%231%20%26%20more.mp3");
            assertEquals(10000, full.bytes.length);
            Resp part = get(18081, "/net/Files/Media/Opening%20%231%20%26%20more.mp3", "Range", "bytes=100-199");
            assertEquals(206, part.code);
            assertEquals((byte) 100, part.bytes[0]);
            assertEquals("bytes 100-199/10000", part.headers.get("Content-Range").get(0));
            assertTrue(get(18081, "/net/Maps/Gb_wales_europe_2.obf?dl=1").headers.get("Content-Disposition").get(0).contains("attachment"));
            assertEquals(404, get(18081, "/net/Maps/.hidden").code);
            assertEquals(404, get(18081, "/net/../kit.json").code);
            assertEquals("APK", get(18081, "/readynode.apk").body);

            JSONArray files = new JSONObject(get(18081, "/api/net/manifest").body).getJSONArray("files");
            String all = files.toString();
            assertTrue(all.contains("Maps/Gb_wales_europe_2.obf"));
            assertFalse("Not for phones never offered", all.contains("big.zim"));
            assertFalse("hidden never offered", all.contains(".hidden"));

            assertEquals("no Ready Kit or family pages in ReadyNode", 303, get(18081, "/family/kit").code);

            // news and replies
            new NewsStore(new File(root.getParentFile(), "news.json")).post("Rupert", "Water's off till 6");
            JSONArray items = new JSONObject(get(18081, "/api/net/news").body).getJSONArray("items");
            String id = items.getJSONObject(0).getString("id");
            Resp r = post(18081, "/api/net/news/reply", "{\"id\":\"" + id + "\",\"who\":\"Ann\",\"text\":\"Thanks!\",\"t\":\"2026-10-28 16:00\"}");
            assertEquals(201, r.code);
            assertEquals(429, post(18081, "/api/net/news/reply", "{\"id\":\"" + id + "\",\"text\":\"again\"}").code);
            JSONObject rep = new JSONObject(get(18081, "/api/net/news").body).getJSONArray("items").getJSONObject(0).getJSONArray("replies").getJSONObject(0);
            assertEquals("Ann", rep.getString("who"));
            assertEquals("2026-10-28 16:00", rep.getString("t"));
            assertEquals(400, post(18081, "/api/net/news/reply", "not json").code);
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------ client mode answers this phone only
    @Test
    public void clientModeOnlyAnswersThisPhone() throws Exception {
        String lan = null;
        for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
            for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && ni.isUp()) lan = a.getHostAddress();
        Assume.assumeTrue("needs a non-loopback address", lan != null);
        File root = phone("Maps/Wales.obf", "map");
        NodeServer srv = new NodeServer(null, 18084, root, n -> new FileInputStream(new File(SITE, n)),
                new NewsStore(new File(root.getParentFile(), "news.json")), () -> "P", null, "test");
        srv.start(5000, true);
        try {
            assertEquals("this phone itself", 200, get(18084, "/api/net/info").code);
            assertEquals("others, in client mode", 403, getFrom(lan, 18084, "/api/net/info").code);
            srv.setShared(true);
            assertEquals("others, in server mode", 200, getFrom(lan, 18084, "/api/net/info").code);
            assertTrue(srv.everyone());
            srv.setShared(false);
            assertEquals(403, getFrom(lan, 18084, "/api/net/info").code);
        } finally {
            srv.stop();
        }
    }

    static Resp getFrom(String host, int port, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        Resp r = new Resp();
        r.code = c.getResponseCode();
        return r;
    }

    @Test
    public void newsStore() throws Exception {
        File f = new File(Files.createTempDirectory("news").toFile(), "news.json");
        NewsStore n = new NewsStore(f);
        n.post("me", "one");
        n.post("me", "two");
        JSONArray items = n.items();
        assertEquals("newest first", "two", items.getJSONObject(0).getString("text"));
        String id = items.getJSONObject(1).getString("id");
        assertNull(n.reply(id, "x", "hi", null, "1.1.1.1"));
        assertEquals("404", n.reply("nope", "x", "hi", null, "2.2.2.2")[0]);
        String rid = n.items().getJSONObject(1).getJSONArray("replies").getJSONObject(0).getString("id");
        assertTrue(n.delete(id, rid));
        assertEquals(0, n.items().getJSONObject(1).getJSONArray("replies").length());
        assertTrue(n.delete(id, null));
        assertEquals(1, n.items().length());
        try { n.post("me", "   "); fail("empty post"); } catch (IllegalArgumentException expected) { }
    }

    // ------------------------------------------------------------------ phone to phone
    @Test
    public void syncPhoneToPhone() throws Exception {
        File a = phone("Maps/Wales.obf", "wales map", "Survival library/Guides/WATER.md", "# Water", "Knowledge/wikimed.zim", "zim",
                "Apps/OsmAnd.apk", "apk", "Apps/apps.json", "{}", "Files/Books/their-plan.pdf", "pdf");
        NodeServer host = server(18082, a, "Phone A", new File(a.getParentFile(), "news.json"));
        host.start(5000, true);
        try {
            File b = phone("Files/Media/my-photo.jpg", "mine");
            Syncer s = syncer(b);
            Syncer.Offer offer = s.offer("127.0.0.1:18082/readynet/");
            assertEquals("Phone A", offer.name);
            assertEquals("node", offer.kind);
            assertEquals(2, offer.sections.get("Apps")[0]);

            Syncer.Result r = s.run("127.0.0.1:18082", ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
            assertEquals(5, r.downloaded);
            assertEquals("wales map", read(new File(b, "Maps/Wales.obf")));
            assertFalse("their shared files not copied unless ticked", new File(b, "Files/Books/their-plan.pdf").exists());
            assertEquals("same date as on phone A", new File(a, "Maps/Wales.obf").lastModified() / 1000, new File(b, "Maps/Wales.obf").lastModified() / 1000);

            r = s.run("127.0.0.1:18082", ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
            assertEquals("nothing re-downloaded", 0, r.downloaded);

            // phone A drops a file it gave us: we're asked first, and "keep" keeps it
            File wikimed = new File(a, "Knowledge/wikimed.zim");
            byte[] wm = Files.readAllBytes(wikimed.toPath());
            long wmt = wikimed.lastModified();
            assertTrue(wikimed.delete());
            int[] dropped = {-1};
            r = s.run("127.0.0.1:18082", ALL_BUT_FILES, (u, g, by, ex) -> { dropped[0] = g; return false; }, (m, d, t) -> {});
            assertEquals("asked about the file phone A dropped", 1, dropped[0]);
            assertEquals(0, r.removed);
            assertTrue(new File(b, "Knowledge/wikimed.zim").exists());
            Files.write(wikimed.toPath(), wm);
            wikimed.setLastModified(wmt);

            // tick their files too, then untick Knowledge: it's removed from this phone, my own files stay
            Set<String> some = new HashSet<>(Arrays.asList("Maps", "Survival library", "Apps", "Files"));
            int[] asked = {-1};
            r = s.run("127.0.0.1:18082", some, (u, g, by, ex) -> { asked[0] = u; return false; }, (m, d, t) -> {});
            assertEquals("asked about the unticked section", 1, asked[0]);
            assertEquals("said no: kept", 0, r.removed);
            assertEquals(1, r.keptBack);
            assertTrue(new File(b, "Knowledge/wikimed.zim").exists());
            r = s.run("127.0.0.1:18082", some, Syncer.ALWAYS, (m, d, t) -> {});
            assertTrue(new File(b, "Files/Books/their-plan.pdf").exists());
            assertEquals(1, r.removed);
            assertFalse(new File(b, "Knowledge/wikimed.zim").exists());
            assertTrue("Knowledge folder kept", new File(b, "Knowledge").isDirectory());
            assertTrue("my own file never touched", new File(b, "Files/Media/my-photo.jpg").exists());

            // and phone B can now serve it all on to phone C
            NodeServer bSrv = server(18083, b, "Phone B", new File(b.getParentFile(), "news.json"));
            bSrv.start(5000, true);
            try {
                File c = phone();
                Syncer.Result rc = syncer(c).run("127.0.0.1:18083", new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps", "Files")),
                        Syncer.ALWAYS, (m, d, t) -> {});
                assertEquals(0, rc.failed);
                assertEquals("# Water", read(new File(c, "Survival library/Guides/WATER.md")));
                assertTrue(new File(c, "Files/Media/my-photo.jpg").exists());

                // a ReadyNet that never gave phone C a file can't make it remove it, however little it has
                File e = phone();
                NodeServer eSrv = server(18087, e, "Phone E (empty)", new File(e.getParentFile(), "news.json"));
                eSrv.start(5000, true);
                try {
                    int[] asked2 = {0};
                    Syncer.Result re = syncer(c).run("127.0.0.1:18087", new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps", "Files")),
                            (u, g, by, ex) -> { asked2[0] += u + g; return true; }, (m, d, t) -> {});
                    assertEquals(0, asked2[0]);
                    assertEquals(0, re.removed);
                    assertTrue(new File(c, "Maps/Wales.obf").exists());
                } finally {
                    eSrv.stop();
                }
            } finally {
                bSrv.stop();
            }
        } finally {
            host.stop();
        }
    }

    @Test
    public void cleanKeepsToSections() {
        assertNull(Syncer.clean("../etc/passwd"));
        assertNull(Syncer.clean("Maps/../../x"));
        assertNull(Syncer.clean("Maps/.index.json"));
        assertNull("not a ReadyNet section", Syncer.clean("Films/x.mkv"));
        assertEquals("Maps/Wales.obf", Syncer.clean("/Maps//./Wales.obf"));
        assertEquals("Books", Node.folderFor("x.epub"));
        assertEquals("Apps", Node.folderFor("x.APK"));
        assertEquals("Media", Node.folderFor("x.mp4"));
    }

    // ------------------------------------------------------------------ from another ReadyNet server
    @Test
    public void syncFromServer() throws Exception {
        String url = System.getenv("READYNET_URL"), net = System.getenv("READYNET_NET");
        Assume.assumeTrue("set READYNET_URL and READYNET_NET to run", url != null && net != null);
        File root = phone("Files/Media/mine.mp3", "mine");
        Syncer s = syncer(root);
        Syncer.Offer o = s.offer(url);
        assertFalse("the server has an id", o.id.isEmpty());
        Syncer.Result r = s.run(url, ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
        assertEquals(r.lastError, 0, r.failed);
        assertTrue(r.downloaded >= 3);
        assertTrue(new File(root, "Survival library/Guides/WATER.md").exists());
        assertFalse("Not for phones stays there", new File(root, "Knowledge/Not for phones").exists());
        String index = read(new File(root.getParentFile(), "sync-index.json"));
        assertTrue("checksums remembered", index.contains("sha256"));

        // this phone re-serves the checksums it verified, so the next phone can check them too
        NodeServer me = server(18085, root, "Me", new File(root.getParentFile(), "news.json"));
        me.setHashIndex(new File(root.getParentFile(), "sync-index.json"));
        me.start(5000, true);
        try {
            assertTrue(get(18085, "/api/net/manifest").body.contains("sha256"));
        } finally {
            me.stop();
        }

        // the server drops a file it gave us: we're asked; "keep" keeps it, "remove" removes it
        File gone = new File(net, "Maps/Gb_wales_europe_2.obf");
        byte[] keep = Files.readAllBytes(gone.toPath());
        long mt = gone.lastModified();
        assertTrue(gone.delete());
        try {
            r = s.run(url, ALL_BUT_FILES, (u, g, by, ex) -> false, (m, d, t) -> {});
            assertEquals("said keep: nothing removed", 0, r.removed);
            assertTrue(new File(root, "Maps/Gb_wales_europe_2.obf").exists());
            int[] gones = {-1};
            r = s.run(url, ALL_BUT_FILES, (u, g, by, ex) -> { gones[0] = g; return true; }, (m, d, t) -> {});
            assertEquals(1, gones[0]);
            assertEquals(1, r.removed);
            assertFalse(new File(root, "Maps/Gb_wales_europe_2.obf").exists());
            assertTrue(new File(root, "Files/Media/mine.mp3").exists());
        } finally {
            Files.write(gone.toPath(), keep);
            gone.setLastModified(mt);
        }

        // a file arrives damaged (doesn't match the server's checksum): refused, and the phone's old copy stays
        File guide = new File(net, "Survival library/Guides/WATER.md");
        byte[] good = Files.readAllBytes(guide.toPath());
        long gmt = guide.lastModified();
        File mine = new File(root, "Survival library/Guides/WATER.md");
        Files.write(mine.toPath(), "my old copy".getBytes());                // make the phone want a fresh copy
        mine.setLastModified(1000);
        byte[] bad = good.clone();
        bad[0] ^= 1;                                                         // same size, same date, wrong content
        Files.write(guide.toPath(), bad);
        guide.setLastModified(gmt);
        try {
            new File(root.getParentFile(), "sync-index.json").delete();
            r = s.run(url, ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
            assertEquals(1, r.failed);
            assertTrue(r.lastError, r.lastError.contains("checksum"));
            assertEquals("my old copy", read(mine));
        } finally {
            Files.write(guide.toPath(), good);
            guide.setLastModified(gmt);
        }

        // a server that takes no part in broadcasts: its news has none, and the phone simply skips it
        JSONObject boxNews = new JSONObject(fetch(Syncer.base(url) + "/api/net/news"));
        assertFalse(boxNews.has("broadcasts"));
        Broadcasts bc = new Broadcasts(new File(root.getParentFile(), "casts.json"), () -> "abcdef0123456789", () -> "Test phone");
        bc.setReceiving(true);
        bc.broadcast("hello");
        Relay.Result rr = Relay.exchange(url, u -> (HttpURLConnection) u.openConnection(), bc, "abcdef0123456789", "Test phone");
        assertEquals(rr.error, "", rr.error);
        assertEquals(0, rr.got + rr.gave);
        // search on the server answers the same shape as the phone's
        JSONObject hits = new JSONObject(fetch(Syncer.base(url) + "/api/net/search?q=water"));
        assertTrue(hits.getJSONArray("hits").length() > 0);
        assertTrue(hits.getJSONArray("hits").getJSONObject(0).has("guide"));
        // and the phone can tell how far behind it is without copying anything
        assertEquals("the damaged guide is still to fetch", 1, s.behind(s.offer(url), ALL_BUT_FILES)[0]);
        s.run(url, ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
        assertEquals(0, s.behind(s.offer(url), ALL_BUT_FILES)[0]);
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    public void simpleViewForOldBrowsers() throws Exception {
        File root = phone("Maps/Wales.obf", "map", "Survival library/Guides/Water.md", "# Water\n<b>boil</b> it");
        NodeServer srv = server(18086, root, "Old phone test", new File(root.getParentFile(), "news.json"));
        srv.start(5000, true);
        try {
            String home = get(18086, "/readynet/simple/").body;
            assertTrue(home.contains("Offline maps") && home.contains("Old phone test") && !home.contains("<script"));
            String maps = get(18086, "/readynet/simple/Maps/").body;
            assertTrue(maps.contains("/net/Maps/Wales.obf?dl=1"));
            String guide = get(18086, "/readynet/simple/Survival%20library/Guides/Water.md").body;
            assertTrue(guide.contains("&lt;b&gt;boil&lt;/b&gt; it"));
            assertEquals(302, get(18086, "/readynet/simple/Maps/Wales.obf").code);
            assertEquals(404, get(18086, "/readynet/simple/..%2F..%2Fkit.json").code);
            assertTrue(get(18086, "/readynet/").body.contains("readynetStarted"));
        } finally {
            srv.stop();
        }
    }

    // ------------------------------------------------------------------ tiny HTTP client
    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static class Resp {
        int code;
        String body;
        byte[] bytes;
        Map<String, List<String>> headers;
    }

    static Resp get(int port, String path, String... header) throws Exception { return req(port, "GET", path, null, header); }

    static Resp post(int port, String path, String json) throws Exception { return req(port, "POST", path, json, "Content-Type", "application/json"); }

    static Resp req(int port, String method, String path, String body, String... header) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        for (int i = 0; i + 1 < header.length; i += 2) c.setRequestProperty(header[i], header[i + 1]);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        r.headers = c.getHeaderFields();
        InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) { in.transferTo(out); in.close(); }
        r.bytes = out.toByteArray();
        r.body = new String(r.bytes, StandardCharsets.UTF_8);
        return r;
    }
}
