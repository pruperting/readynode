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
        final java.util.Set<String> dropOnce = new HashSet<>();            // send half, then cut the connection, the first time
        final List<String> resumed = new java.util.ArrayList<>();
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
            if (range != null && range.matches("bytes=\\d+-")) {
                int from = Integer.parseInt(range.substring(6, range.length() - 1));
                resumed.add(s.getUri() + "@" + from);
                return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "application/octet-stream",
                        new ByteArrayInputStream(Arrays.copyOfRange(b, from, b.length)), b.length - from);
            }
            if (dropOnce.remove(s.getUri())) {
                byte[] all = b;
                java.io.InputStream half = new java.io.InputStream() {
                    int i;
                    @Override public int read() throws java.io.IOException {
                        if (i >= all.length / 2) throw new java.io.IOException("cut");
                        return all[i++] & 0xff;
                    }
                };
                return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", half, b.length);
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
        byte[] water = new byte[1 << 20];
        new java.util.Random(2).nextBytes(water);
        net.files.put("/zim/other/zimgit-water_en_2024-08.zim", water);
        net.dropOnce.add("/zim/other/zimgit-water_en_2024-08.zim");
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
            assertArrayEquals("cut halfway, then carried on", water, Files.readAllBytes(new File(root, "Survival library/zimgit-water_en_2024-08.zim").toPath()));
            assertEquals("it asked for the rest, not the whole thing again", 1, net.resumed.size());
            assertTrue(net.resumed.toString(), net.resumed.get(0).startsWith("/zim/other/zimgit-water_en_2024-08.zim@") && !net.resumed.get(0).endsWith("@0"));
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
    public void packsAreOneFileAndComeBackIn() throws Exception {
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < 20000; i++) map.append("road ").append(i % 50).append(" junction\n");      // compresses well
        byte[] noise = new byte[300000];
        new java.util.Random(1).nextBytes(noise);                                                   // doesn't compress at all
        File phone = NodeTest.phone("Maps/Wales.obf", map.toString(), "Survival library/Guides/WATER.md", "# Water", "Apps/Markor (notes - write).apk", "apk",
                "Files/Media/mine.jpg", "private", "Maps/.hidden", "x", "Knowledge/Not for phones/big.zim", "too big");
        Files.write(new File(phone, "Knowledge/wikimed.zim").toPath(), noise);
        File card = Files.createTempDirectory("sdcard").toFile();
        Packs.Result out = Packs.export(phone, Packs.folder(card), "Rupert's phone: Pixel", (m, d, t) -> {});
        assertEquals(4, out.copied);
        assertEquals(1, out.parts);
        String[] made = card.list();
        assertEquals(1, made.length);
        assertTrue(made[0], made[0].matches("ReadyNet Rupert's phone - Pixel \\d{4}-\\d{2}-\\d{2}\\.rdynd"));
        File pack = new File(card, made[0]);
        assertTrue("the map is compressed, the random library stored: " + pack.length(), pack.length() < noise.length + map.length() / 5);
        assertTrue("stored, not grown", pack.length() < out.bytes);
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(pack)) {
            assertNull("Files aren't packed", z.getEntry("Files/Media/mine.jpg"));
            assertNull("hidden files aren't packed", z.getEntry("Maps/.hidden"));
            assertNull("nor bigger versions phones never take", z.getEntry("Knowledge/Not for phones/big.zim"));
        }

        File fresh = NodeTest.phone();
        try (java.io.InputStream in = new java.io.FileInputStream(pack)) { assertEquals(4, Packs.about(in).getJSONArray("files").length()); }
        Packs.Result in;
        try (java.io.InputStream s = new java.io.FileInputStream(pack)) { in = Packs.importFrom(s, fresh, (m, d, t) -> {}); }
        assertEquals(4, in.copied);
        assertEquals(0, in.failed);
        assertEquals("# Water", NodeTest.read(new File(fresh, "Survival library/Guides/WATER.md")));
        assertEquals(map.toString(), NodeTest.read(new File(fresh, "Maps/Wales.obf")));
        assertArrayEquals(noise, Files.readAllBytes(new File(fresh, "Knowledge/wikimed.zim").toPath()));
        try (java.io.InputStream s = new java.io.FileInputStream(pack)) { assertEquals("again: all skipped", 4, Packs.importFrom(s, fresh, (m, d, t) -> {}).skipped); }

        // cut short: what arrived is kept, and copying it in again carries on
        File cut = new File(card, "cut.rdynd");
        Files.write(cut.toPath(), java.util.Arrays.copyOf(Files.readAllBytes(pack.toPath()), (int) (pack.length() * 2 / 3)));
        File half = NodeTest.phone();
        try (java.io.InputStream s = new java.io.FileInputStream(cut)) { Packs.importFrom(s, half, (m, d, t) -> {}); fail("a cut-short pack says so"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("damaged or cut short")); }
        assertTrue("files before the cut arrived", new File(half, "Maps/Wales.obf").isFile());
        assertFalse("no half files left about", new File(half, "Maps/.Wales.obf.part").exists());

        // not a pack
        File junk = new File(card, "holiday.zip");
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(junk))) { z.putNextEntry(new java.util.zip.ZipEntry("photo.jpg")); z.write(1); }
        for (File f : new File[]{junk, new File(phone, "Survival library/Guides/WATER.md")})
            try (java.io.InputStream s = new java.io.FileInputStream(f)) { Packs.importFrom(s, fresh, (m, d, t) -> {}); fail(f.getName()); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("isn't a ReadyNet pack")); }
    }

    @Test
    public void bigPacksSplitIntoPartsThatEachStandAlone() throws Exception {
        File phone = NodeTest.phone("Maps/A.obf", "aaaa", "Maps/B.obf", "bbbb", "Knowledge/C.zim", "cccc", "Apps/D.apk", "dddd");
        File card = Files.createTempDirectory("sdcard").toFile();
        Packs.Result out = Packs.export(phone, Packs.folder(card), "Sam", 9, (m, d, t) -> {});      // two files to a part
        assertEquals(2, out.parts);
        java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(card.list()));
        java.util.Collections.sort(names);
        assertTrue(names.toString(), names.get(0).endsWith(" part 1 of 2.rdynd") && names.get(1).endsWith(" part 2 of 2.rdynd"));
        File fresh = NodeTest.phone();
        for (int i = names.size() - 1; i >= 0; i--)                                           // any order
            try (java.io.InputStream s = new java.io.FileInputStream(new File(card, names.get(i)))) { assertEquals(2, Packs.importFrom(s, fresh, (m, d, t) -> {}).copied); }
        for (String f : new String[]{"Maps/A.obf", "Maps/B.obf", "Knowledge/C.zim", "Apps/D.apk"}) assertTrue(f, new File(fresh, f).isFile());
    }

    @Test
    public void aPackCantWriteOutsideReadyNet() throws Exception {
        File evil = Files.createTempFile("evil", ".rdynd").toFile();
        org.json.JSONArray files = new org.json.JSONArray();
        String[] paths = {"../../escape.txt", "Maps/../../escape.txt", "Files/Media/x.jpg", "Maps/.hidden", "/etc/x", "Maps/ok.obf"};
        for (String p : paths) files.put(new JSONObject().put("path", p).put("size", 2));
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(evil))) {
            z.putNextEntry(new java.util.zip.ZipEntry(Packs.ABOUT));
            z.write(new JSONObject().put("readynet", 1).put("files", files).toString().getBytes());
            for (String p : paths) { z.putNextEntry(new java.util.zip.ZipEntry(p)); z.write("hi".getBytes()); }
            z.putNextEntry(new java.util.zip.ZipEntry("Apps/unlisted.apk"));
            z.write("hi".getBytes());
        }
        File phone = NodeTest.phone();
        Packs.Result r;
        try (java.io.InputStream s = new java.io.FileInputStream(evil)) { r = Packs.importFrom(s, phone, (m, d, t) -> {}); }
        assertEquals(1, r.copied);
        assertTrue(new File(phone, "Maps/ok.obf").isFile());
        assertFalse(new File(phone.getParentFile(), "escape.txt").exists());
        assertFalse(new File(phone, "Files/Media/x.jpg").exists());
        assertFalse(new File(phone, "Apps/unlisted.apk").exists());
    }

    @Test
    public void appFileNamesWorkOnEveryCard() {
        assertEquals("Markor (notes - write and keep longer notes)", Seeder.fileName("Markor (notes: write and keep longer notes)"));
        assertEquals("a-b-c-d-e", Seeder.fileName("a/b*c?d|e"));
        assertEquals("x", Seeder.fileName("x..."));
    }

    /** With READYNET_SAMPLES set to a folder of real files (a map, libraries, apps): how small does a pack get? */
    @Test
    public void realContentCompression() throws Exception {
        String dir = System.getenv("READYNET_SAMPLES");
        org.junit.Assume.assumeTrue("set READYNET_SAMPLES to run", dir != null);
        File phone = NodeTest.phone();
        for (File f : new File(dir).listFiles()) {
            String n = f.getName(), sec = n.endsWith(".obf") ? "Maps" : n.endsWith(".zim") ? "Knowledge" : n.endsWith(".apk") ? "Apps" : null;
            if (sec == null) continue;
            new File(phone, sec).mkdirs();
            Files.copy(f.toPath(), new File(phone, sec + "/" + n).toPath());
        }
        File card = Files.createTempDirectory("sdcard").toFile();
        long t = System.currentTimeMillis();
        Packs.Result out = Packs.export(phone, Packs.folder(card), "test", (m, d, x) -> {});
        long ms = System.currentTimeMillis() - t;
        System.out.println("REAL: " + out.copied + " files, " + out.bytes + " -> " + out.packed + " bytes (" + (out.packed * 1000 / out.bytes) / 10.0 + "%) in " + ms + " ms");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(new File(card, out.names.get(0)))) {
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                java.util.zip.ZipEntry x = e.nextElement();
                System.out.println("REAL:   " + x.getName() + " " + x.getSize() + " -> " + x.getCompressedSize() + " (" + (x.getCompressedSize() * 1000 / Math.max(1, x.getSize())) / 10.0 + "%)");
            }
        }
        File fresh = NodeTest.phone();
        t = System.currentTimeMillis();
        try (java.io.InputStream s = new java.io.FileInputStream(new File(card, out.names.get(0)))) { assertEquals(out.copied, Packs.importFrom(s, fresh, (m, d, x) -> {}).copied); }
        System.out.println("REAL: copied back in in " + (System.currentTimeMillis() - t) + " ms");
        for (File f : new File(dir).listFiles()) {
            String n = f.getName(), sec = n.endsWith(".obf") ? "Maps" : n.endsWith(".zim") ? "Knowledge" : n.endsWith(".apk") ? "Apps" : null;
            if (sec != null) assertArrayEquals(n, Files.readAllBytes(f.toPath()), Files.readAllBytes(new File(fresh, sec + "/" + n).toPath()));
        }
    }
}
