package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * This phone's message board: the owner posts (Settings), anyone on the Wi-Fi replies (ReadyNet's News page).
 * Same shape and limits on every ReadyNet server, so ReadyNet's News page works the same everywhere.
 * Pure Java, so it's tested on a computer.
 */
public class NewsStore {
    static final int MAX_NEWS = 50, MAX_REPLIES = 100, MAX_TEXT = 2000, MAX_REPLY = 1000, MAX_WHO = 40;
    static final long REPLY_INTERVAL_MS = 5000;

    private final File file;
    private final Map<String, Long> lastReply = new HashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    public NewsStore(File file) { this.file = file; }

    public synchronized JSONArray items() {
        try {
            if (!file.exists()) return new JSONArray();
            JSONObject doc = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            JSONArray items = doc.optJSONArray("items");
            return items == null ? new JSONArray() : items;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized void post(String who, String text) throws Exception {
        text = cut(text, MAX_TEXT);
        if (text.isEmpty()) throw new IllegalArgumentException("write something first");
        JSONArray old = items(), out = new JSONArray();
        out.put(new JSONObject().put("id", id()).put("t", now()).put("who", cut(who, MAX_WHO)).put("text", text)
                .put("replies", new JSONArray()));
        for (int i = 0; i < old.length() && out.length() < MAX_NEWS; i++) out.put(old.get(i));
        save(out);
    }

    /** Reply from someone on the Wi-Fi. Returns null if it worked, otherwise what to tell them (and the HTTP code). */
    public synchronized String[] reply(String id, String who, String text, String t, String from) throws Exception {
        text = cut(text, MAX_REPLY);
        if (text.isEmpty()) return new String[]{"400", "write something first"};
        Long last = lastReply.get(from);
        long nowMs = System.currentTimeMillis();
        if (last != null && nowMs - last < REPLY_INTERVAL_MS) return new String[]{"429", "wait a few seconds before replying again"};
        JSONArray items = items();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (!it.optString("id").equals(id)) continue;
            JSONArray replies = it.optJSONArray("replies");
            if (replies == null) { replies = new JSONArray(); it.put("replies", replies); }
            if (replies.length() >= MAX_REPLIES) return new String[]{"409", "this message has as many replies as it can take"};
            replies.put(new JSONObject().put("id", id()).put("t", t != null && t.matches("\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d") ? t : now())
                    .put("who", cut(who, MAX_WHO)).put("text", text));
            save(items);
            lastReply.put(from, nowMs);
            return null;
        }
        return new String[]{"404", "that message has gone"};
    }

    /** Remove a message (replyId null) or one reply. */
    public synchronized boolean delete(String id, String replyId) throws Exception {
        JSONArray items = items(), out = new JSONArray();
        boolean found = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            if (!it.optString("id").equals(id)) { out.put(it); continue; }
            if (replyId == null) { found = true; continue; }
            JSONArray replies = it.optJSONArray("replies"), kept = new JSONArray();
            if (replies != null) for (int j = 0; j < replies.length(); j++) {
                if (replies.getJSONObject(j).optString("id").equals(replyId)) found = true;
                else kept.put(replies.get(j));
            }
            it.put("replies", kept);
            out.put(it);
        }
        if (found) save(out);
        return found;
    }

    private void save(JSONArray items) throws Exception {
        file.getParentFile().mkdirs();
        File tmp = new File(file.getPath() + ".part");
        Files.write(tmp.toPath(), new JSONObject().put("items", items).toString(1).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("couldn't save messages");
        }
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        s = s.replace("\r", "").trim();
        return s.codePointCount(0, s.length()) > n ? s.substring(0, s.offsetByCodePoints(0, n)) : s;
    }

    private static String id() {
        byte[] b = new byte[6];
        RNG.nextBytes(b);
        StringBuilder h = new StringBuilder();
        for (byte x : b) h.append(String.format("%02x", x));
        return h.toString();
    }

    static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK).format(new Date()); }
}
