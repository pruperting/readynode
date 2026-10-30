package uk.plumridge.readynode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server mode: shares this phone's ReadyNet with everyone on its hotspot (or the same Wi-Fi), with the screen off,
 * and optionally makes the hotspot itself. State is published in static fields the Settings screen polls.
 * When it stops, the server goes back to answering this phone only (client mode).
 */
public class NodeService extends Service {
    public static final String ACTION_STOP = "uk.plumridge.readynode.STOP";
    public static final String EXTRA_HOTSPOT = "hotspot";
    private static final String CHANNEL = "readynode";

    static volatile boolean running;
    static volatile String hotspotSsid, hotspotPass, hotspotError;
    /** This phone's addresses just before it made its hotspot (null if it didn't make one). */
    static volatile java.util.Set<String> before;

    private WifiManager.LocalOnlyHotspotReservation reservation;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // note the battery every five minutes while sharing, for Save battery's estimate
    private final Handler sampler = new Handler(Looper.getMainLooper());
    private final Runnable sample = new Runnable() {
        @Override
        public void run() {
            Node.sampleBattery(NodeService.this);
            sampler.postDelayed(this, 5 * 60_000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground();
        try {
            Node.serve(this, true);
            // the app's own browser shows this phone's copy now: unpin it from any ReadyNet Wi-Fi it was browsing
            getSystemService(android.net.ConnectivityManager.class).bindProcessToNetwork(null);
            running = true;
        } catch (IOException e) {
            hotspotError = "Couldn't start sharing: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        holdLocks();
        sampler.removeCallbacksAndMessages(null);
        sampler.post(sample);
        if (intent != null && intent.getBooleanExtra(EXTRA_HOTSPOT, false) && reservation == null) startHotspot();
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, SettingsActivity.open(this, SettingsActivity.SERVER), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, NodeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ReadyNode is sharing ReadyNet")
                .setContentText("Others on your hotspot can open " + Node.name(this) + ".")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop sharing", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @SuppressWarnings("deprecation")
    private void holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "readynode:serve");
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "readynode:serve");
            wifiLock.acquire();
        }
    }

    /** A hotspot the phone creates itself. Android picks the name and password; the app shows them as a QR code. */
    @SuppressWarnings("deprecation")
    private void startHotspot() {
        hotspotError = null;
        before = ips();                        // whatever appears after this is the hotspot
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    reservation = r;
                    if (Build.VERSION.SDK_INT >= 30) {
                        SoftApConfiguration c = r.getSoftApConfiguration();
                        hotspotSsid = c.getSsid();
                        hotspotPass = c.getPassphrase();
                    } else {
                        WifiConfiguration c = r.getWifiConfiguration();
                        hotspotSsid = c == null ? null : c.SSID;
                        hotspotPass = c == null ? null : c.preSharedKey;
                    }
                    if (hotspotSsid != null) hotspotSsid = hotspotSsid.replace("\"", "");
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    hotspotSsid = hotspotPass = null;
                }

                @Override
                public void onFailed(int reason) {
                    hotspotError = reason == ERROR_INCOMPATIBLE_MODE
                            ? "Android won't start a hotspot alongside the normal one. Switch off the normal hotspot, or use it instead."
                            : reason == ERROR_TETHERING_DISALLOWED ? "Hotspots are blocked on this phone."
                            : "The phone couldn't start a hotspot (code " + reason + "). Use the normal hotspot instead.";
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            hotspotError = "Allow the permission, and on older Android switch on Location, to start a hotspot.";
        }
    }

    @Override
    public void onDestroy() {
        sampler.removeCallbacksAndMessages(null);
        if (reservation != null) { reservation.close(); reservation = null; }
        hotspotSsid = hotspotPass = null;
        before = null;
        try { Node.serve(this, false); } catch (IOException ignored) { }   // back to this phone only
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        running = false;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers used by the activities too
    /** This phone's IPv4 addresses, by interface, skipping mobile data and VPNs. */
    static java.util.Map<String, String> ipsByInterface() {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.put(a.getHostAddress(), n);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static java.util.Set<String> ips() { return new java.util.HashSet<>(ipsByInterface().keySet()); }

    /** Addresses this phone has on Wi-Fi networks it has joined (as a client), from two places, to be sure. */
    @SuppressWarnings("deprecation")
    static java.util.Set<String> joined(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            android.net.ConnectivityManager cm = ctx.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) for (android.net.LinkAddress la : lp.getLinkAddresses()) out.add(la.getAddress().getHostAddress());
            }
        } catch (Exception ignored) {
        }
        try {
            int ip = ((WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getIpAddress();
            if (ip != 0) out.add((ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 24 & 0xff));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** What each address is, best guess first: "hotspot", "joined Wi-Fi" or "other". */
    static java.util.Map<String, String> classify(Context ctx) {
        java.util.Map<String, String> ips = ipsByInterface(), out = new java.util.LinkedHashMap<>();
        java.util.Set<String> joined = joined(ctx), was = before;
        for (String ip : ips.keySet()) {
            String n = ips.get(ip);
            boolean appeared = was != null && !was.contains(ip);
            boolean apName = n.contains("ap") || n.startsWith("swlan") || n.startsWith("softap");
            out.put(ip, appeared ? "hotspot" : joined.contains(ip) ? "joined Wi-Fi" : apName ? "hotspot" : "other");
        }
        return out;
    }

    /** Every network this phone is on, for the diagnostics line: "wlan2 10.170.141.236 (hotspot) · wlan0 …". */
    static String describe(Context ctx) {
        java.util.Map<String, String> ips = ipsByInterface(), kind = classify(ctx);
        StringBuilder b = new StringBuilder();
        for (String ip : order(kind)) {
            if (b.length() > 0) b.append(" · ");
            b.append(ips.get(ip)).append(' ').append(ip).append(" (").append(kind.get(ip)).append(')');
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static List<String> order(java.util.Map<String, String> kind) {
        List<String> out = new ArrayList<>();
        for (String want : new String[]{"hotspot", "other", "joined Wi-Fi"})
            for (java.util.Map.Entry<String, String> e : kind.entrySet()) if (e.getValue().equals(want)) out.add(e.getKey());
        return out;
    }

    /** Addresses others can reach this phone on: the hotspot first, the Wi-Fi it has joined last. */
    static List<String> addresses(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String ip : order(classify(ctx))) out.add("http://" + ip + ":" + NodeServer.PORT + "/");
        return out;
    }
}
