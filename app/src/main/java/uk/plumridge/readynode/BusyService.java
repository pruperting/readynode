package uk.plumridge.readynode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the phone working on a long job (syncing, seeding, making or copying in a pack) with the screen off: a notification
 * shows it's running, and the phone's processor and Wi-Fi stay awake until the job says it's done. Without this,
 * Android pauses the app soon after the screen goes off and downloads time out.
 */
public class BusyService extends Service {
    private static final String CHANNEL = "readynode-busy", EXTRA_TEXT = "text";
    private static final int ID = 2;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** Start, or update the line shown in the notification. */
    static void show(Context c, String text) {
        Intent i = new Intent(c, BusyService.class).putExtra(EXTRA_TEXT, text);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {
            // not allowed right now (the app is in the background): the job still runs, it may just pause with the screen
        }
    }

    static void done(Context c) {
        c.stopService(new Intent(c, BusyService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Syncing, downloads and packs", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_stat).setContentTitle("ReadyNode is working")
                .setContentText(text == null ? "Keep the phone on charge if you can" : text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadyNode:busy");
            wake.acquire(6 * 60 * 60 * 1000L);                       // a safety net: never more than six hours
        }
        if (wifi == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                // the low-latency lock only works with the screen on; this one keeps Wi-Fi from napping with it off
                @SuppressWarnings("deprecation") int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifi = wm.createWifiLock(mode, "ReadyNode:busy");
                wifi.acquire();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
