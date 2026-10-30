package uk.plumridge.readynode;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Finds a live ReadyNet on the Wi-Fi this phone has joined: a ReadyNode phone's hotspot (the hotspot's own address,
 * :8080), or another ReadyNet server on the Wi-Fi's own address (:8088), or an address you typed in. Phones can't make a captive portal, so this
 * is what lets someone scan just the Wi-Fi QR code: the app finds the site by itself.
 */
public final class Finder {
    private Finder() {}

    public static class Found {
        public final String base, name, kind;
        Found(String base, String name, String kind) { this.base = base; this.name = name; this.kind = kind; }
    }

    /** What the last search tried, and what happened, for Settings to show when nothing is found. */
    static volatile String lastReport = "";

    static Network wifi(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** Looks for a ReadyNet; null if there isn't one. Slow-ish (network): call it off the main thread. */
    public static Found find(Context ctx, String extra) {
        Network wifi = wifi(ctx);
        Set<String> tries = new LinkedHashSet<>();
        if (extra != null && !extra.trim().isEmpty()) tries.add(Syncer.base(extra));
        if (wifi != null) {
            LinkProperties lp = ctx.getSystemService(ConnectivityManager.class).getLinkProperties(wifi);
            if (lp != null) for (RouteInfo r : lp.getRoutes()) {
                InetAddress gw = r.getGateway();
                if (gw instanceof Inet4Address && !gw.isAnyLocalAddress()) {
                    tries.add("http://" + gw.getHostAddress() + ":" + NodeServer.PORT);   // a ReadyNode phone's hotspot
                    tries.add("http://" + gw.getHostAddress() + ":8088");                // another ReadyNet server
                }
            }
        }
        StringBuilder report = new StringBuilder(wifi == null ? "Not on any Wi-Fi. " : "");
        for (String base : tries) {
            String[] why = {""};
            Found f = probe(wifi, base, why);
            report.append(base.replace("http://", "")).append(": ").append(f != null ? "found " + f.name : why[0]).append("\n");
            if (f != null) { lastReport = report.toString(); return f; }
        }
        lastReport = report.toString();
        return null;
    }

    static Found probe(Network wifi, String base) { return probe(wifi, base, new String[1]); }

    static Found probe(Network wifi, String base, String[] why) {
        try {
            URL u = new URL(base + "/api/net/info");
            HttpURLConnection c = (HttpURLConnection) (wifi != null ? wifi.openConnection(u) : u.openConnection());
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setInstanceFollowRedirects(false);
            try {
                int code = c.getResponseCode();
                if (code != 200) { why[0] = "answered " + code; return null; }
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
                    JSONObject j = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                    if (j.optInt("readynet") != 1) { why[0] = "not a ReadyNet"; return null; }
                    return new Found(base, j.optString("name", "ReadyNet"), j.optString("kind", "node"));
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            why[0] = e instanceof java.net.SocketTimeoutException ? "no answer (timed out)"
                    : e instanceof java.net.ConnectException ? "refused: " + m
                    : e instanceof java.net.UnknownHostException ? "name not found" : m;
            return null;
        }
    }
}
