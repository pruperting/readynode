package uk.plumridge.readynode;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Broadcasts from phone to phone (see Broadcasts): when this phone is on another ReadyNode phone's ReadyNet, it takes
 * that phone's broadcasts (if this phone receives them) and hands over its own (and, if receiving, the ones it
 * carries). A ReadyNet that doesn't take part in broadcasts is simply skipped. Pure Java, so it's tested on a computer.
 */
public final class Relay {
    private Relay() {}

    /** What happened: how many new broadcasts came here, and how many the other phone took. */
    public static final class Result {
        public int got, gave;
        public boolean theyTake = true;
        public String error = "";
    }

    public static Result exchange(String host, Syncer.Opener opener, Broadcasts b, String myId, String myName) {
        Result r = new Result();
        String base = Syncer.base(host);
        try {
            JSONObject theirs = new JSONObject(call(opener, base + "/api/net/news", null));
            String theirId = theirs.optString("id");
            if (theirId.equals(myId) || !theirs.has("broadcasts")) return r;     // that's us, or not a ReadyNode phone
            r.got = b.merge(theirs.optJSONArray("broadcasts"), theirId, theirs.optString("name"), System.currentTimeMillis());
            org.json.JSONArray give = b.forOthers();
            if (give.length() == 0) return r;
            JSONObject body = new JSONObject().put("id", myId).put("name", myName).put("broadcasts", give);
            try {
                r.gave = new JSONObject(call(opener, base + "/api/net/news/relay", body.toString())).optInt("added");
            } catch (Syncer.HttpError e) {
                if (e.code != 403) throw e;
                r.theyTake = false;                                              // they haven't turned receiving on
            }
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return r;
    }

    private static String call(Syncer.Opener opener, String url, String post) throws Exception {
        HttpURLConnection c = opener.open(new URL(url));
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        if (post != null) {
            byte[] body = post.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = c.getOutputStream()) { o.write(body); }
        }
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new Syncer.HttpError(code); }
        try {
            return new String(NodeServer.readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
