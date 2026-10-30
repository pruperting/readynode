package uk.plumridge.readynode;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import fi.iki.elonen.NanoHTTPD;

/** Seed mode against stand-ins for OsmAnd, Kiwix and F-Droid, and packs to and from a folder. */
public class SeedTest {
    /** Plays OsmAnd, Kiwix and F-Droid. */
    static class Internet extends NanoHTTPD {
        final Map<String, byte[]> files = new HashMap<>();
        int hits;

        Internet(int port) { super("127.0.0.1", port); }

        @Override
        public Response serve(IHTTPSession s) {
            hits++;
            String key = s.getUri() + (s.getQueryParameterString() == null ? "" : "?" + s.getQueryParameterString());
            byte[] b = files.get(key);
            if (b == null) b = files.get(key + "/");                 // NanoHTTPD drops a trailing slash
            if (b == null) b = files.get(s.getUri());                // NanoHTTPD keeps a stale query string on reused connections
            if (b == null) b = files.get(s.getUri() + "/");
            if (b == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no " + key);
            String range = s.getHeaders().get("range");
            if (range != null && range.startsWith("bytes=-")) {
                int n = Math.min(b.length, Integer.parseInt(range.substring(7)));
                return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "application/octet-stream",
                        new ByteArrayInputStream(Arrays.copyOfRange(b, b.length - n, b.length)), n);
            }
            return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", new ByteArrayInputStream(b), b.length);
        }
    }

    static byte[] zip(String... nameThenContent) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            for (int i = 0; i + 1 < nameThenContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameThenContent[i]));
                z.write(nameThenContent[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static byte[] gz(String s) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(s.getBytes(StandardCharsets.UTF_8)); }
        return bo.toByteArray();
    }

    @Test
    public void seedsAPhoneFromTheInternetThenItSeedsOthers() throws Exception {
        Internet net = new Internet(18101);
        String base = "http://127.0.0.1:18101";
        net.files.put("/get_indexes?gzip=true", gz("<osmand_regions>"
                + "<region type=\"map\" name=\"Gb_england_south-west-england_europe_2.obf.zip\" targetsize=\"180.5\" date=\"01.10.2026\" timestamp=\"111\"/>"
                + "<region type=\"map\" name=\"Gb_wales_europe_2.obf.zip\" targetsize=\"120\" date=\"01.10.2026\" timestamp=\"222\"/>"
                + "<region type=\"map\" name=\"France_bretagne_europe_2.obf.zip\" targetsize=\"300\" timestamp=\"333\"/>"
                + "<region type=\"map\" name=\"World_basemap_mini_2.obf.zip\" targetsize=\"60\" timestamp=\"444\"/></osmand_regions>"));
        net.files.put("/download?standard=yes&file=Gb_wales_europe_2.obf.zip", zip("Gb_wales_europe_2.obf", "WALES MAP"));
        net.files.put("/download?standard=yes&file=World_basemap_mini_2.obf.zip", zip("readme.txt", "x", "World_basemap_mini_2.obf", "WORLD"));
        net.files.put("/zim/other/", ("<a href=\"zimgit-water_en_2024-01.zim\">old</a> <a href=\"zimgit-water_en_2024-08.zim\">new</a>").getBytes());
        net.files.put("/zim/other/zimgit-water_en_2024-08.zim", "WATER LIBRARY".getBytes());
        // an app with one APK per processor: the suggested one is for PCs, the other runs on phones
        net.files.put("/api/v1/packages/net.osmand.plus", ("{\"suggestedVersionCode\":43,\"packages\":[{\"versionCode\":43,\"versionName\":\"5.0\"},"
                + "{\"versionCode\":42,\"versionName\":\"5.0\"},{\"versionCode\":30,\"versionName\":\"4.0\"}]}").getBytes());
        net.files.put("/repo/net.osmand.plus_43.apk", zip("lib/x86_64/libx.so", "pc"));
        net.files.put("/repo/net.osmand.plus_42.apk", zip("lib/arm64-v8a/libx.so", "phone"));
        net.files.put("/api/v1/packages/org.ligi.survivalmanual", "{\"suggestedVersionCode\":7,\"packages\":[{\"versionCode\":7,\"versionName\":\"1\"}]}".getBytes());
        net.files.put("/repo/org.ligi.survivalmanual_7.apk", zip("classes.dex", "manual"));
        net.files.put("/release/kiwix-android/", "<a href=\"org.kiwix.kiwixmobile.standalone-3.9.1.apk\"></a><a href=\"org.kiwix.kiwixmobile.standalone-3.10.0.apk\"></a>".getBytes());
        net.files.put("/release/kiwix-android/org.kiwix.kiwixmobile.standalone-3.10.0.apk", zip("classes.dex", "kiwix"));
        net.start(5000, true);
        JSONObject pack = new JSONObject()
                .put("maps", new JSONObject().put("prefixes", new JSONArray().put("Gb_").put("World_basemap_mini")))
                .put("libraries", new JSONArray().put(new JSONObject().put("path", "other/zimgit-water_en").put("section", "Survival library").put("name", "Water").put("mb", 20))
                        .put(new JSONObject().put("path", "../evil").put("section", "Knowledge")))
                .put("apps", new JSONArray().put(new JSONObject().put("id", "net.osmand.plus").put("name", "OsmAnd (offline maps)").put("tags", new JSONArray().put("maps")))
                        .put(new JSONObject().put("id", "org.ligi.survivalmanual").put("name", "Survival Manual").put("tags", new JSONArray().put("survival"))))
                .put("kiwix", new JSONObject().put("name", "Kiwix (offline library reader)").put("tags", new JSONArray().put("knowledge")));
        File root = NodeTest.phone("Survival library/zimgit-water_en_2024-01.zim", "old water");
        File state = new File(root.getParentFile(), "seed-state.json");
        Syncer.Opener o = u -> (HttpURLConnection) u.openConnection();
        Seeder s = new Seeder(root, state, pack, o, base, base, base);
        try {
            List<Seeder.Region> regions = s.regions();
            assertEquals("only the prefixes in the pack", 3, regions.size());
            Seeder.Region sw = regions.stream().filter(r -> r.file.startsWith("Gb_england_south")).findFirst().get();
            assertEquals("South West England (England)", sw.name);
            assertEquals((long) (180.5 * 1024 * 1024), sw.bytes);
            assertEquals("Wales", Seeder.regionName("Gb_wales_europe_2"));

            Seeder.Result r = s.run(new HashSet<>(Arrays.asList("Gb_wales_europe_2", "World_basemap_mini_2")), true, true,
                    Arrays.asList("arm64-v8a", "armeabi-v7a"), (m, d, t) -> {});
            assertEquals(String.join("; ", r.problems), 0, r.failed);
            assertEquals("WALES MAP", NodeTest.read(new File(root, "Maps/Gb_wales_europe_2.obf")));
            assertEquals("unzipped, the .obf only", "WORLD", NodeTest.read(new File(root, "Maps/World_basemap_mini_2.obf")));
            assertEquals("WATER LIBRARY", NodeTest.read(new File(root, "Survival library/zimgit-water_en_2024-08.zim")));
            assertFalse("the older library version goes", new File(root, "Survival library/zimgit-water_en_2024-01.zim").exists());
            assertTrue("the phone build, not the PC one", NodeTest.read(new File(root, "Apps/OsmAnd (offline maps).apk")).contains("arm64-v8a"));
            assertTrue(new File(root, "Apps/Kiwix (offline library reader).apk").isFile());
            JSONObject apps = new JSONObject(NodeTest.read(new File(root, "Apps/apps.json"))).getJSONObject("apps");
            assertEquals("maps", apps.getJSONObject("OsmAnd (offline maps).apk").getJSONArray("tags").getString(0));
            assertTrue(apps.has("Kiwix (offline library reader).apk"));
            for (File f : new File(root, "Maps").listFiles()) assertFalse("no leftovers", f.getName().endsWith(".part"));

            // again: nothing downloaded twice
            int before = net.hits;
            r = s.run(new HashSet<>(Arrays.asList("Gb_wales_europe_2", "World_basemap_mini_2")), true, true, Arrays.asList("arm64-v8a"), (m, d, t) -> {});
            assertEquals(0, r.downloaded);
            assertTrue(r.upToDate >= 5);
            assertTrue("only the lists were fetched again", net.hits - before <= 6);

            // a map that's gone from OsmAnd is reported, not fatal
            r = s.run(new HashSet<>(Arrays.asList("Gb_atlantis_europe_2")), false, false, Arrays.asList("arm64-v8a"), (m, d, t) -> {});
            assertEquals(1, r.failed);

            // the seeded phone is now a complete ReadyNet: another phone syncs everything from it, with no internet
            NodeServer seed = NodeTest.server(18102, root, "Seed phone", new File(root.getParentFile(), "news.json"));
            seed.start(5000, true);
            try {
                File other = NodeTest.phone();
                Syncer.Result sr = NodeTest.syncer(other).run("127.0.0.1:18102", NodeTest.ALL_BUT_FILES, Syncer.ALWAYS, (m, d, t) -> {});
                assertEquals(0, sr.failed);
                assertEquals("WALES MAP", NodeTest.read(new File(other, "Maps/Gb_wales_europe_2.obf")));
                assertTrue(new File(other, "Apps/apps.json").isFile());
            } finally {
                seed.stop();
            }
        } finally {
            net.stop();
        }
    }

    @Test
    public void packsGoOutToAFolderAndBackIn() throws Exception {
        File phone = NodeTest.phone("Maps/Wales.obf", "wales", "Survival library/Guides/WATER.md", "# Water", "Knowledge/wikimed.zim", "zim",
                "Apps/OsmAnd.apk", "apk", "Files/Media/mine.jpg", "private", "Maps/.hidden", "x");
        File card = Files.createTempDirectory("sdcard").toFile();
        Packs.Result out = Packs.export(phone, new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps")),
                Packs.folder(card), "Rupert's phone", (m, d, t) -> {});
        assertEquals(4, out.copied);
        assertEquals("wales", NodeTest.read(new File(card, "ReadyNet pack/Maps/Wales.obf")));
        assertFalse("Files aren't packed", new File(card, "ReadyNet pack/Files").exists());
        assertFalse("hidden files aren't packed", new File(card, "ReadyNet pack/Maps/.hidden").exists());
        JSONObject about = new JSONObject(NodeTest.read(new File(card, "ReadyNet pack/readynet-pack.json")));
        assertEquals(4, about.getJSONArray("files").length());
        assertEquals("making it again skips what's there", 4, Packs.export(phone, new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps")),
                Packs.folder(card), "x", (m, d, t) -> {}).skipped);

        // a stray file that isn't in a section, or tries to escape, is ignored
        new File(card, "ReadyNet pack/Films").mkdirs();
        Files.write(new File(card, "ReadyNet pack/Films/film.mp4").toPath(), "no".getBytes());

        File fresh = NodeTest.phone();
        Packs.Result in = Packs.importFrom(Packs.folder(card), fresh, (m, d, t) -> {});      // the folder it's in works too
        assertEquals(4, in.copied);
        assertEquals("# Water", NodeTest.read(new File(fresh, "Survival library/Guides/WATER.md")));
        assertFalse(new File(fresh, "Films").exists());
        assertEquals("again: all skipped", 4, Packs.importFrom(Packs.folder(new File(card, "ReadyNet pack")), fresh, (m, d, t) -> {}).skipped);
        try {
            Packs.importFrom(Packs.folder(Files.createTempDirectory("empty").toFile()), fresh, (m, d, t) -> {});
            fail("an empty folder isn't a pack");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("no ReadyNet pack"));
        }
    }
}
