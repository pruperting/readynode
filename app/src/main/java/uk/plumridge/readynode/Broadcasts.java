package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Broadcasts: one short message per ReadyNode that travels from phone to phone, to every ReadyNode that has "Receive
 * broadcasts" turned on. Separate from a ReadyNet's own News (which only people joined to it see).
 *
 *  - Each ReadyNode has at most one broadcast. Posting a new one replaces it everywhere, as phones meet; stopping it
 *    removes it everywhere the same way. So one ReadyNode can never flood the others.
 *  - Receiving is off until you turn it on. Off: this phone neither takes nor passes on other people's broadcasts
 *    (it still hands over its own, to phones that take them).
 *  - Mute a ReadyNode and its broadcast is hidden and not passed on, until you unmute it.
 *  - No replies travel: replies stay on the ReadyNet where they were written.
 *  - Received broadcasts go 30 days after they arrived; at most 100 are kept.
 *  - A ReadyNet server that doesn't take part (no "broadcasts" in its news) is skipped.
 *
 * Kept in broadcasts.json: {receive, mine, received, muted}. On the wire each broadcast is
 * {from: {id, name}, v, t, text, removed?, via?}: v is the sender's own counter (higher is newer).
 * Pure Java, so it's tested on a computer.
 */
public class Broadcasts {
    static final int MAX_TEXT = 500, MAX_KEPT = 100, MAX_NAME = 40;
    static final long KEEP_MS = 30L * 24 * 3600 * 1000;
    static final String ID = "[0-9a-f]{6,32}", TIME = "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d";

    private final File file;
    private final Supplier<String> me, myName;

    public Broadcasts(File file, Supplier<String> me, Supplier<String> myName) {
        this.file = file;
        this.me = me;
        this.myName = myName;
    }

    // ------------------------------------------------------------------ settings
    public synchronized boolean receiving() { return read().optBoolean("receive", false); }

    public synchronized void setReceiving(boolean on) throws Exception {
        JSONObject d = read();
        d.put("receive", on);
        write(d);
    }

    // ------------------------------------------------------------------ this phone's own broadcast
    /** This phone's broadcast now, or null if it isn't broadcasting. */
    public synchronized JSONObject mine() {
        JSONObject m = read().optJSONObject("mine");
        return m == null || m.optBoolean("removed") ? null : m;
    }

    /** Broadcast this (replacing any earlier one). */
    public synchronized void broadcast(String text) throws Exception {
        text = NewsStore.cut(text, MAX_TEXT);
        if (text.isEmpty()) throw new IllegalArgumentException("write something first");
        JSONObject d = read();
        d.put("mine", new JSONObject().put("v", next(d)).put("t", NewsStore.now()).put("text", text));
        write(d);
    }

    /** Stop broadcasting: the broadcast goes from other phones too, as they meet. */
    public synchronized void stop() throws Exception {
        JSONObject d = read();
        if (d.optJSONObject("mine") == null) return;
        d.put("mine", new JSONObject().put("v", next(d)).put("t", NewsStore.now()).put("text", "").put("removed", true));
        write(d);
    }

    /** Always newer than the last one, even if the phone's clock has gone back. */
    private static long next(JSONObject d) {
        JSONObject m = d.optJSONObject("mine");
        long last = m == null ? 0 : m.optLong("v");
        return Math.max(System.currentTimeMillis() / 1000, last + 1);
    }

    // ------------------------------------------------------------------ what other ReadyNodes see and get
    /**
     * What this phone hands over and shows on its ReadyNet: its own broadcast (or the news that it stopped), plus,
     * when receiving, the ones it carries (muted ones left out). Includes stopped ones, so the stop travels too.
     */
    public synchronized JSONArray forOthers() throws Exception {
        JSONObject d = read();
        JSONArray out = new JSONArray();
        JSONObject m = d.optJSONObject("mine");
        if (m != null) {
            JSONObject e = new JSONObject(m.toString()).put("from", new JSONObject().put("id", me.get()).put("name", NewsStore.cut(myName.get(), MAX_NAME)));
            out.put(e);
        }
        if (!d.optBoolean("receive")) return out;
        JSONArray r = d.optJSONArray("received");
        if (r != null) for (int i = 0; i < r.length(); i++) {
            JSONObject e = new JSONObject(r.getJSONObject(i).toString());
            e.remove("got");
            out.put(e);
        }
        return out;
    }

    /** For showing: live broadcasts only (own first), newest first. */
    public synchronized List<JSONObject> visible() throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONArray all = forOthers();
        for (int i = 0; i < all.length(); i++) if (!all.getJSONObject(i).optBoolean("removed")) out.add(all.getJSONObject(i));
        String id = me.get();
        out.sort((a, b) -> {
            boolean am = a.optJSONObject("from").optString("id").equals(id), bm = b.optJSONObject("from").optString("id").equals(id);
            return am != bm ? (am ? -1 : 1) : b.optString("t").compareTo(a.optString("t"));
        });
        return out;
    }

    // ------------------------------------------------------------------ taking in another ReadyNode's
    /**
     * Take in the broadcasts another ReadyNode handed over (sender: its id and name). Only when receiving. Returns
     * how many new or updated broadcasts it brought.
     */
    public synchronized int merge(JSONArray in, String senderId, String senderName, long nowMs) throws Exception {
        JSONObject d = read();
        if (!d.optBoolean("receive") || in == null) return 0;
        String myId = me.get();
        JSONArray r = d.optJSONArray("received");
        if (r == null) r = new JSONArray();
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject e = r.getJSONObject(i);
            if (nowMs - e.optLong("got") * 1000 <= KEEP_MS) list.add(e);       // older ones have expired
        }
        int added = 0;
        for (int i = 0; i < in.length(); i++) {
            JSONObject e = in.optJSONObject(i);
            JSONObject f = e == null ? null : e.optJSONObject("from");
            if (f == null) continue;
            String id = f.optString("id");
            long v = e.optLong("v");
            if (!id.matches(ID) || v <= 0 || id.equals(myId) || isMuted(d, id)) continue;
            JSONObject have = null;
            for (JSONObject x : list) if (x.getJSONObject("from").optString("id").equals(id)) have = x;
            if (have != null && have.optLong("v") >= v) continue;            // we already have this one, or newer
            boolean removed = e.optBoolean("removed");
            String text = NewsStore.cut(e.optString("text"), MAX_TEXT), t = e.optString("t");
            if (!removed && text.isEmpty()) continue;
            JSONObject n = new JSONObject().put("from", new JSONObject().put("id", id).put("name", NewsStore.cut(f.optString("name"), MAX_NAME)))
                    .put("v", v).put("t", t.matches(TIME) ? t : "").put("text", removed ? "" : text).put("got", nowMs / 1000);
            if (removed) n.put("removed", true);
            if (!id.equals(senderId)) n.put("via", NewsStore.cut(senderName, MAX_NAME));
            if (have != null) list.remove(have);
            list.add(n);
            if (!removed) added++;
        }
        list.sort((a, b) -> Long.compare(b.optLong("got"), a.optLong("got")));
        d.put("received", new JSONArray(list.size() > MAX_KEPT ? list.subList(0, MAX_KEPT) : list));
        write(d);
        return added;
    }

    // ------------------------------------------------------------------ muting
    public synchronized void mute(String id, String name) throws Exception {
        JSONObject d = read();
        if (!isMuted(d, id)) {
            JSONArray m = d.optJSONArray("muted");
            if (m == null) { m = new JSONArray(); d.put("muted", m); }
            m.put(new JSONObject().put("id", id).put("name", NewsStore.cut(name, MAX_NAME)));
        }
        JSONArray r = d.optJSONArray("received"), kept = new JSONArray();
        if (r != null) for (int i = 0; i < r.length(); i++)
            if (!r.getJSONObject(i).getJSONObject("from").optString("id").equals(id)) kept.put(r.get(i));
        d.put("received", kept);
        write(d);
    }

    public synchronized void unmute(String id) throws Exception {
        JSONObject d = read();
        JSONArray m = d.optJSONArray("muted"), kept = new JSONArray();
        if (m != null) for (int i = 0; i < m.length(); i++) if (!m.getJSONObject(i).optString("id").equals(id)) kept.put(m.get(i));
        d.put("muted", kept);
        write(d);
    }

    /** [{id, name}] */
    public synchronized JSONArray muted() {
        JSONArray m = read().optJSONArray("muted");
        return m == null ? new JSONArray() : m;
    }

    private static boolean isMuted(JSONObject d, String id) {
        JSONArray m = d.optJSONArray("muted");
        if (m != null) for (int i = 0; i < m.length(); i++) if (m.optJSONObject(i) != null && m.optJSONObject(i).optString("id").equals(id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ the file
    private JSONObject read() {
        try {
            if (!file.exists()) return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void write(JSONObject d) throws Exception {
        file.getParentFile().mkdirs();
        File tmp = new File(file.getPath() + ".part");
        Files.write(tmp.toPath(), d.toString(1).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("couldn't save broadcasts");
        }
    }
}
