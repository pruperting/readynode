package uk.plumridge.readynode;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.util.List;

/** News passed from phone to phone, search, and the battery estimate. */
public class RelayTest {
    static final Syncer.Opener NET = u -> (HttpURLConnection) u.openConnection();

    static final class Phone {
        final String id, name;
        final Broadcasts casts;
        final NodeServer server;
        final int port;
        final File root;

        Phone(String id, String name, int port, boolean receive) throws Exception {
            this.id = id;
            this.name = name;
            this.port = port;
            root = NodeTest.phone("Maps/Wales.obf", "map");
            casts = new Broadcasts(new File(root.getParentFile(), "broadcasts.json"), () -> id, () -> name);
            casts.setReceiving(receive);
            server = new NodeServer("127.0.0.1", port, root, n -> new FileInputStream(new File(NodeTest.SITE, n)),
                    new NewsStore(new File(root.getParentFile(), "news.json")), () -> name, null, "test");
            server.setId(() -> id);
            server.setBroadcasts(casts);
            server.setShared(true);
            server.start(5000, true);
        }

        /** This phone is on the other one's hotspot, with ReadyNode open. */
        Relay.Result meet(Phone other) {
            Relay.Result r = Relay.exchange("127.0.0.1:" + other.port, NET, casts, id, name);
            assertEquals(r.error, "", r.error);
            return r;
        }

        String sees(String fromName) throws Exception {
            for (JSONObject c : casts.visible()) if (c.getJSONObject("from").getString("name").equals(fromName)) return c.getString("text");
            return null;
        }
    }

    @Test
    public void broadcastsTravelFromPhoneToPhone() throws Exception {
        NodeServer.relayIntervalMs = 0;          // the test phones all share one address
        Phone a = new Phone("aaaa0000aaaa0000", "Ann's phone", 18091, true), b = new Phone("bbbb0000bbbb0000", "Ben's phone", 18092, true),
                c = new Phone("cccc0000cccc0000", "Cat's phone", 18093, true), d = new Phone("dddd0000dddd0000", "Dan's phone", 18095, false);
        try {
            a.casts.broadcast("Bridge on Station Road is closed");
            b.casts.broadcast("Shop on the green has bread");

            // Ben joins Ann's hotspot: they swap broadcasts both ways
            Relay.Result r = b.meet(a);
            assertEquals(1, r.got);
            assertEquals(1, r.gave);
            assertEquals("Bridge on Station Road is closed", b.sees("Ann's phone"));
            assertEquals("Shop on the green has bread", a.sees("Ben's phone"));

            // Cat meets only Ben, and gets Ann's broadcast through him
            assertEquals(2, c.meet(b).got);
            JSONObject viaBen = null;
            for (JSONObject x : c.casts.visible()) if (x.getJSONObject("from").getString("name").equals("Ann's phone")) viaBen = x;
            assertEquals("Ben's phone", viaBen.getString("via"));
            assertEquals("meeting again brings nothing new", 0, c.meet(b).got);

            // one broadcast per ReadyNode: Ann's new one replaces her old one everywhere, as phones meet
            a.casts.broadcast("Bridge open again");
            b.meet(a);
            c.meet(b);
            assertEquals("Bridge open again", c.sees("Ann's phone"));
            long fromAnn = 0;
            for (JSONObject x : c.casts.visible()) if (x.getJSONObject("from").getString("id").equals(a.id)) fromAnn++;
            assertEquals(1, fromAnn);

            // the News page and simple view show them
            JSONObject doc = new JSONObject(NodeTest.get(c.port, "/api/net/news").body);
            assertEquals(2, doc.getJSONArray("broadcasts").length());
            assertTrue(NodeTest.get(c.port, "/readynet/simple/").body.contains("Bridge open again"));

            // Ann stops: it goes from Ben's and Cat's too
            a.casts.stop();
            b.meet(a);
            c.meet(b);
            assertNull(c.sees("Ann's phone"));
            assertNull(a.casts.mine());

            // Cat mutes Ben: hidden, not passed on, and stays out until unmuted
            c.casts.mute(b.id, b.name);
            assertNull(c.sees("Ben's phone"));
            b.casts.broadcast("Buy my stuff");
            assertEquals(0, c.meet(b).got);
            assertNull(c.sees("Ben's phone"));
            c.casts.unmute(b.id);
            assertEquals(1, c.meet(b).got);
            assertEquals("Buy my stuff", c.sees("Ben's phone"));

            // Dan has receiving off: he takes nothing in, refuses what others hand over, but still gives his own
            d.casts.broadcast("Dan's broadcast");
            r = d.meet(c);
            assertEquals(0, r.got);
            assertEquals(1, r.gave);
            assertTrue(d.casts.visible().size() == 1 && d.sees("Dan's phone") != null);
            assertEquals(403, NodeTest.post(d.port, "/api/net/news/relay", new JSONObject().put("id", c.id).put("name", c.name)
                    .put("broadcasts", c.casts.forOthers()).toString()).code);
            r = c.meet(d);
            assertFalse("Dan doesn't take broadcasts", r.theyTake);
            assertEquals(1, new JSONObject(NodeTest.get(d.port, "/api/net/news").body).getJSONArray("broadcasts").length());

            NodeServer.relayIntervalMs = 10_000;
            NodeTest.post(a.port, "/api/net/news/relay", new JSONObject().put("id", "eeee0000eeee0000").put("name", "x").put("broadcasts", new JSONArray()).toString());
            assertEquals("too soon from the same address", 429, NodeTest.post(a.port, "/api/net/news/relay",
                    new JSONObject().put("id", "eeee0000eeee0000").put("name", "x").put("broadcasts", new JSONArray()).toString()).code);
        } finally {
            NodeServer.relayIntervalMs = 10_000;
            a.server.stop();
            b.server.stop();
            c.server.stop();
            d.server.stop();
        }
    }

    @Test
    public void broadcastsExpireAreCappedAndCheckedOnArrival() throws Exception {
        File dir = Files.createTempDirectory("casts").toFile();
        Broadcasts b = new Broadcasts(new File(dir, "b.json"), () -> "eeee0000eeee0000", () -> "Me");
        JSONArray in = new JSONArray();
        for (int i = 0; i < 120; i++) in.put(new JSONObject().put("from", new JSONObject().put("id", String.format("%016x", i + 1)).put("name", "n" + i))
                .put("v", 5).put("t", "2026-10-29 10:00").put("text", "m" + i));
        assertEquals("not receiving: nothing taken", 0, b.merge(in, "ffff0000ffff0000", "Hall", System.currentTimeMillis()));
        b.setReceiving(true);
        long now = System.currentTimeMillis();
        assertEquals(120, b.merge(in, "ffff0000ffff0000", "Hall", now - 40L * 24 * 3600 * 1000));
        assertEquals(Broadcasts.MAX_KEPT, b.visible().size());
        b.merge(new JSONArray(), "ffff0000ffff0001", "x", now);
        assertEquals("all 40 days old: gone", 0, b.visible().size());
        JSONArray junk = new JSONArray()
                .put(new JSONObject().put("from", new JSONObject().put("id", "../../x").put("name", "a")).put("v", 1).put("text", "a"))
                .put(new JSONObject().put("from", new JSONObject().put("id", "abcdefabcdef").put("name", "a")).put("v", 1).put("text", " "))
                .put(new JSONObject().put("from", new JSONObject().put("id", "abcdefabcdef").put("name", "a")).put("v", 0).put("text", "no counter"))
                .put(new JSONObject().put("from", new JSONObject().put("id", "eeee0000eeee0000").put("name", "fake me")).put("v", 9).put("text", "not mine"));
        assertEquals(0, b.merge(junk, "ffff0000ffff0002", "x", now));
        // an older one never replaces a newer one
        JSONObject f = new JSONObject().put("id", "abcdefabcdef").put("name", "Hall");
        b.merge(new JSONArray().put(new JSONObject().put("from", f).put("v", 10).put("text", "new")), "abcdefabcdef", "Hall", now);
        b.merge(new JSONArray().put(new JSONObject().put("from", f).put("v", 9).put("text", "old")), "abcdefabcdef", "Hall", now);
        assertEquals("new", b.visible().get(0).getString("text"));
        // long broadcasts are cut, and the phone's own counter always goes up
        b.broadcast("x".repeat(900));
        long v1 = b.mine().getLong("v");
        assertEquals(Broadcasts.MAX_TEXT, b.mine().getString("text").length());
        b.broadcast("again");
        assertTrue(b.mine().getLong("v") > v1);
        try { b.broadcast("  "); fail("empty"); } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void searchFindsWordsInsideGuides() throws Exception {
        File root = NodeTest.phone("Survival library/Guides/WATER.md", "# Water: making it safe\n\nBring it to a **rolling boil** for one minute.\n",
                "Survival library/water_filters.pdf", "pdf", "Survival library/Guides/.hidden.md", "rolling boil", "Maps/Wales.obf", "map");
        List<Search.Hit> hits = Search.find(root, "Rolling  BOIL");
        assertEquals(1, hits.size());
        assertEquals("Water: making it safe", hits.get(0).title);
        assertTrue(hits.get(0).snippet, hits.get(0).snippet.contains("rolling boil for one minute"));
        hits = Search.find(root, "water");
        assertEquals(2, hits.size());
        assertEquals("names first, guides first", "Survival library/Guides/WATER.md", hits.get(0).path);
        assertTrue(Search.find(root, "").isEmpty());
        assertTrue(Search.find(root, "zebra").isEmpty());

        NodeServer srv = NodeTest.server(18094, root, "Search test", new File(root.getParentFile(), "news.json"));
        srv.start(5000, true);
        try {
            JSONObject res = new JSONObject(NodeTest.get(18094, "/api/net/search?q=boil").body);
            assertEquals(1, res.getJSONArray("hits").length());
            assertTrue(res.getJSONArray("hits").getJSONObject(0).getBoolean("guide"));
            String simple = NodeTest.get(18094, "/readynet/simple/?q=boil").body;
            assertTrue(simple.contains("Water: making it safe") && !simple.contains("<script"));
            assertTrue(NodeTest.get(18094, "/readynet/simple/").body.contains("name=\"q\""));
        } finally {
            srv.stop();
        }
    }

    @Test
    public void batteryEstimate() {
        String log = "";
        long t = 1_800_000_000L;
        assertNull(Power.estimate(log));
        log = Power.add(log, t, 80, false);
        log = Power.add(log, t + 10, 80, false);                 // too soon: ignored
        assertEquals(1, Power.parse(log).length);
        log = Power.add(log, t + 600, 79, false);
        assertNull("not enough to go on yet", Power.estimate(log));
        log = Power.add(log, t + 3600, 72, false);
        double[] e = Power.estimate(log);
        assertNotNull(e);
        assertEquals(8.0, e[1], 0.01);                            // 8% an hour
        assertEquals(9.0, e[0], 0.01);                            // 72% left at 8% an hour
        assertEquals("About 9 hours", Power.hours(e[0]));
        assertEquals("", Power.add(log, t + 4000, 73, true));   // charging starts afresh
        assertEquals(1, Power.parse(Power.add(log, t + 4000, 90, false)).length);   // it was charged while we weren't looking
        assertEquals("About 45 minutes", Power.hours(0.75));
        assertEquals("More than 2 days", Power.hours(60));
    }
}
