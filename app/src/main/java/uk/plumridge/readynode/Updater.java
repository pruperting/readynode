package uk.plumridge.readynode;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Network;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Updates from any ReadyNet: when the one you're on carries a newer ReadyNode than this, say so (a notification, and
 * a question in the app), and one tap installs it. The app file is small (about half a megabyte), so it's fetched over
 * the ReadyNet's Wi-Fi and Android reads its version; it's only fetched again when its size changes.
 *
 * Safe from a stranger's ReadyNet: only an app with this app's name is offered, and Android itself refuses an update
 * signed by anyone but whoever signed this one. Your ReadyNet copy and settings are kept.
 */
final class Updater {
    private Updater() {}

    static final String ACTION = "uk.plumridge.readynode.UPDATE", FILE = "ReadyNode-update.apk";
    private static final String CHANNEL = "readynode-updates";

    /** A newer version waiting to be installed, or null. */
    static volatile Available available;

    static final class Available {
        final long code;
        final String version, from;
        final File apk;
        Available(long code, String version, String from, File apk) { this.code = code; this.version = version; this.from = from; this.apk = apk; }
    }

    /** Off the main thread: look at the app the ReadyNet at base carries, and notify if it's newer. */
    static void check(Context c, String base, String fromName) {
        try {
            Network wifi = Finder.wifi(c);
            String url = base.replaceAll("/+$", "") + "/readynode.apk";
            long size = size(wifi, url);
            if (size <= 0 || size > (100L << 20)) return;
            SharedPreferences p = Node.prefs(c);
            File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return;
            File apk = new File(dir, FILE);
            String seen = p.getString("updSeen", "");
            if (seen.equals(base + "|" + size) && !apk.isFile()) return;          // same file as last time, and not newer
            if (!(seen.equals(base + "|" + size) && apk.isFile() && apk.length() == size)) fetch(wifi, url, apk, size);
            p.edit().putString("updSeen", base + "|" + size).apply();
            PackageInfo theirs = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
            PackageInfo mine = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (theirs == null || !c.getPackageName().equals(theirs.packageName) || code(theirs) <= code(mine)) {
                apk.delete();
                if (available != null && !available.apk.isFile()) available = null;
                return;
            }
            available = new Available(code(theirs), theirs.versionName, fromName, apk);
            if (p.getLong("updNotified", 0) != code(theirs)) {
                p.edit().putLong("updNotified", code(theirs)).apply();
                notifyAbout(c, available);
            }
        } catch (Exception ignored) {
            // no app there, or the Wi-Fi went: try again next time
        }
    }

    @SuppressWarnings("deprecation")
    static long code(PackageInfo i) { return Build.VERSION.SDK_INT >= 28 ? i.getLongVersionCode() : i.versionCode; }

    /** The app file's size, asking for just its first byte (servers say the whole size); -1 if there's none. */
    private static long size(Network wifi, String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) (wifi != null ? wifi.openConnection(new URL(url)) : new URL(url).openConnection());
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = c.getResponseCode();
            if (code == 206) {
                String r = c.getHeaderField("Content-Range");                  // bytes 0-0/461409
                return r != null && r.contains("/") ? Long.parseLong(r.substring(r.lastIndexOf('/') + 1).trim()) : -1;
            }
            return code == 200 ? c.getContentLengthLong() : -1;
        } catch (NumberFormatException e) {
            return -1;
        } finally {
            c.disconnect();
        }
    }

    private static void fetch(Network wifi, String url, File dest, long size) throws IOException {
        HttpURLConnection c = (HttpURLConnection) (wifi != null ? wifi.openConnection(new URL(url)) : new URL(url).openConnection());
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        File part = new File(dest.getParentFile(), "." + dest.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            byte[] buf = new byte[64 * 1024];
            int n;
            long got = 0;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got > size) throw new IOException("bigger than it said");
                out.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (part.length() != size || !part.renameTo(dest)) { part.delete(); throw new IOException("incomplete"); }
    }

    private static void notifyAbout(Context c, Available a) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class).setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("ReadyNode " + a.version + " is available")
                .setContentText("From " + a.from + ". Tap to update: your copy and settings stay.")
                .setContentIntent(open).setAutoCancel(true).build();
        try { nm.notify(3, n); } catch (SecurityException ignored) { }        // notifications not allowed: the app still asks
    }

    /** Ask, then hand the new app to Android's installer. */
    static void offer(Activity a) {
        Available u = available;
        if (u == null || !u.apk.isFile()) return;
        new AlertDialog.Builder(a).setTitle("Update ReadyNode?")
                .setMessage("ReadyNode " + u.version + " is available from " + u.from + " (you have " + Node.version(a) + "). "
                        + "Your ReadyNet copy and settings stay. Android will ask you to confirm; the first time, it may ask you to allow "
                        + "ReadyNode to install apps.")
                .setPositiveButton("Update", (d, w) -> install(a, u)).setNegativeButton("Not now", null).show();
    }

    private static void install(Activity a, Available u) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.uriFor(u.apk), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.widget.Toast.makeText(a, "Couldn't open the installer: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
