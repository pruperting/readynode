package uk.plumridge.readynode;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The app's shared pieces: where this phone's ReadyNet lives, its name, its message board and its one web server.
 *
 * The server always runs while the app is in use, so the app can show this phone's own ReadyNet. It listens on every
 * address, but answers only this phone until server mode (NodeService) switches sharing on.
 */
public final class Node {
    private Node() {}

    static volatile NodeServer server;
    private static NewsStore news;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("readynode", Context.MODE_PRIVATE); }

    /** This phone's ReadyNet: Maps, Survival library, Knowledge, Apps, Files. */
    static File root(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        return new File(base, "readynet");
    }

    /** Private files the website never serves: sync's index, the message board, broadcasts. */
    static File meta(Context c, String name) { return new File(c.getFilesDir(), name); }

    static synchronized NewsStore news(Context c) {
        if (news == null) news = new NewsStore(meta(c, "news.json"));
        return news;
    }

    private static Broadcasts broadcasts;

    /** This phone's broadcast, and the ones it receives and passes on (see Broadcasts). */
    static synchronized Broadcasts broadcasts(Context c) {
        if (broadcasts == null) {
            Context app = c.getApplicationContext();
            broadcasts = new Broadcasts(meta(app, "broadcasts.json"), () -> id(app), () -> name(app));
        }
        return broadcasts;
    }

    static String name(Context c) {
        String n = prefs(c).getString("name", "").trim();
        return n.isEmpty() ? "ReadyNode " + Build.MODEL : n;
    }

    static String version(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** A random id for this phone's ReadyNet, made once. Other phones can tell it apart from others with the same name. */
    static synchronized String id(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("nodeId", "");
        if (id.isEmpty()) {
            id = token().substring(0, 16);
            p.edit().putString("nodeId", id).apply();
        }
        return id;
    }

    /** Battery level and charging, for the badge on this phone's ReadyNet. */
    static org.json.JSONObject battery(Context c) {
        try {
            android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return null;
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            if (level < 0 || scale <= 0) return null;
            return new org.json.JSONObject().put("pct", Math.round(level * 100f / scale))
                    .put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL);
        } catch (Exception e) {
            return null;
        }
    }

    /** Note the battery level, for Save battery's estimate (see Power). */
    static void sampleBattery(Context c) {
        org.json.JSONObject b = battery(c);
        if (b == null) return;
        SharedPreferences p = prefs(c);
        String log = Power.add(p.getString("battLog", ""), System.currentTimeMillis() / 1000, b.optInt("pct", -1), b.optBoolean("charging"));
        p.edit().putString("battLog", log).apply();
    }

    private static volatile long lastRelay;
    private static volatile String lastRelayBase = "";

    /**
     * Swap broadcasts with the ReadyNet at found (if it takes part in them): take theirs if receiving, hand over ours.
     * At most every two minutes with the same phone unless forced. Call off the main thread. Null if skipped.
     */
    static Relay.Result relayNews(Context ctx, Finder.Found found, boolean force) {
        if (found == null) return null;
        String base = found.base;
        Context c = ctx.getApplicationContext();
        long now = System.currentTimeMillis();
        if (!force && base.equals(lastRelayBase) && now - lastRelay < 120_000) return null;
        lastRelay = now;
        lastRelayBase = base;
        return Relay.exchange(base, u -> {
            Network w = Finder.wifi(c);
            return (java.net.HttpURLConnection) (w != null ? w.openConnection(u) : u.openConnection());
        }, broadcasts(c), id(c), name(c));
    }

    static String localUrl() { return "http://127.0.0.1:" + NodeServer.PORT + "/readynet/"; }

    /** Start (or restart) the web server: everyone=true for server mode, false for this phone only. */
    static synchronized void serve(Context ctx, boolean everyone) throws IOException {
        Context c = ctx.getApplicationContext();
        if (server != null && server.isAlive()) { server.setShared(everyone); return; }
        if (server != null) { server.stop(); server = null; }
        File root = root(c);
        prepare(c, root);
        NodeServer s = new NodeServer(null, NodeServer.PORT, root, name -> c.getAssets().open("readynet/" + name),
                news(c), () -> name(c), new File(c.getApplicationInfo().sourceDir), version(c));
        s.setShared(everyone);
        s.setHashIndex(meta(c, "sync-index.json"));
        s.setId(() -> id(c));
        s.setPower(() -> battery(c));
        s.setBroadcasts(broadcasts(c));
        // The in-app browser pins the app's connections to a ReadyNet's Wi-Fi. A server started while pinned would
        // answer through that Wi-Fi instead of the hotspot, so start it unpinned, then put the pin back.
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network pinned = cm.getBoundNetworkForProcess();
        if (pinned != null) cm.bindProcessToNetwork(null);
        try {
            s.start(5000, false);
        } finally {
            if (pinned != null) cm.bindProcessToNetwork(pinned);
        }
        server = s;
    }

    /** Make the folders, and move things over from the app before ReadyNet (the "node" folder). */
    static void prepare(Context c, File root) {
        for (String s : new String[]{"Maps", "Survival library", "Knowledge", "Apps", "Files/Media", "Files/Apps", "Files/Books"})
            new File(root, s).mkdirs();
        meta(c, "relayed.json").delete();                         // 2.5's news relay, replaced by broadcasts
        File base = root.getParentFile(), old = new File(base, "node");
        if (!old.isDirectory()) return;
        boolean allMoved = true;
        File[] mine = new File(old, "Phone").listFiles();
        if (mine != null) for (File f : mine) {
            if (!f.isFile()) continue;
            File to = new File(root, "Files/" + folderFor(f.getName()) + "/" + f.getName());
            if (to.exists() || !f.renameTo(to)) allMoved = false;
        }
        // the rest comes back with the next sync; but if any of your own files couldn't be moved, keep the old folder
        if (allMoved) deleteTree(old);
    }

    /** Media, Apps or Books, by the kind of file. */
    static String folderFor(String name) {
        String e = NodeServer.ext(name);
        if (e.equals(".apk")) return "Apps";
        if (e.matches("\\.(epub|mobi|azw3|pdf|txt|md|djvu|cbz|zim|doc|docx|odt)")) return "Books";
        return "Media";
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String token() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder h = new StringBuilder();
        for (byte x : b) h.append(String.format(Locale.ROOT, "%02x", x));
        return h.toString();
    }
}
