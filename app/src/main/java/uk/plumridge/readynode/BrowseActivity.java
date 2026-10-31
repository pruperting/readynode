package uk.plumridge.readynode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A ReadyNet (live, or this phone's own), inside the app. Downloads (apps, maps, library files,
 * ebooks) are saved to Downloads and offered to open straight away; apps install from here.
 * MainActivity builds on this; on its own it opens one address (a guide, for one).
 */
public class BrowseActivity extends Activity {
    static final String EXTRA_URL = "url", EXTRA_TITLE = "title";

    protected WebView web;
    private ProgressBar bar;
    protected TextView status;
    private String startHost;
    private boolean boundToWifi;
    private View fullscreen;
    private android.webkit.ValueCallback<Uri[]> pickCallback;
    private static final int REQ_PICK = 7;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        build();
        start();
    }

    private boolean castsShown;

    /**
     * What ReadyNet's pages may ask the app. Any ReadyNet's pages can call these, so they're all harmless: a setting,
     * the id this phone broadcasts under, which broadcasts you've read, and opening the app's own Help or News screens.
     */
    static volatile boolean pagesChanged;

    static final class AppInfo {
        private final android.content.Context c;
        private long lastOpen;
        AppInfo(android.content.Context c) { this.c = c; }
        @android.webkit.JavascriptInterface public boolean broadcasts() { return Node.broadcasts(c).receiving(); }
        @android.webkit.JavascriptInterface public String myId() { return Node.id(c); }
        @android.webkit.JavascriptInterface public String readCasts() { return Node.broadcasts(c).readKeys().toString(); }
        @android.webkit.JavascriptInterface public void setRead(String key, boolean read) {
            try { Node.broadcasts(c).setRead(key, read); } catch (Exception ignored) { }
        }
        @android.webkit.JavascriptInterface public void openMessages() { open(MessagesActivity.class); }
        @android.webkit.JavascriptInterface public void openHelp() { open(HelpActivity.class); }

        private synchronized void open(Class<?> screen) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastOpen < 1500) return;                    // a page can't open screens over and over
            lastOpen = now;
            c.startActivity(new Intent(c, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // the page's timers and any playing media stop while ReadyNode isn't on screen (battery)
    @Override
    protected void onPause() {
        if (web != null) { web.onPause(); web.pauseTimers(); }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
        if (web != null && pagesChanged) { pagesChanged = false; web.reload(); }   // messages or read marks changed in the app
        boolean now = Node.broadcasts(this).receiving();
        if (web != null && now != castsShown) {                 // the setting changed in Sync: show the page again
            castsShown = now;
            web.reload();
        }
    }

    /** What to show first: the address this screen was opened with. MainActivity chooses its own. */
    protected void start() {
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) { finish(); return; }
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(t == null ? "ReadyNet" : t);
        go(url);
    }

    /** Open an address. Anything not on this phone goes over Wi-Fi: a ReadyNet has no internet, so Android might
     *  otherwise send the requests over mobile data. */
    protected void go(String url) {
        Uri u = Uri.parse(url);
        startHost = u.getHost();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if ("127.0.0.1".equals(startHost)) {
            if (boundToWifi) { cm.bindProcessToNetwork(null); boundToWifi = false; }
        } else {
            Network wifi = wifi(this);
            boundToWifi = wifi != null && cm.bindProcessToNetwork(wifi);
        }
        web.loadUrl(url);
    }

    protected String currentHost() { return startHost; }

    protected void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = new TextView(this);
        status.setPadding(24, 8, 24, 8);
        status.setVisibility(View.GONE);
        status.setBackgroundColor(Color.rgb(255, 241, 214));
        status.setTextColor(Color.rgb(28, 36, 34));
        col.addView(status);
        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(col);

        WebSettings s = web.getSettings();
        s.setUserAgentString(s.getUserAgentString() + " ReadyNode/" + Node.version(this));
        s.setJavaScriptEnabled(true);
        // Lets ReadyNet's pages follow this phone's "Receive broadcasts" setting, on any ReadyNet it shows (see readynet.js)
        web.addJavascriptInterface(new AppInfo(getApplicationContext()), "ReadyNodeApp");
        castsShown = Node.broadcasts(this).receiving();
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri target = req.getUrl();
                String scheme = target.getScheme() == null ? "" : target.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme))
                        && (sameHost(target.getHost()) || "127.0.0.1".equals(target.getHost()))) {
                    return false;                                   // stays in the app
                }
                try {
                    if ("intent".equals(scheme)) {                  // "Open in VLC" and the like
                        startActivity(Intent.parseUri(target.toString(), Intent.URI_INTENT_SCHEME));
                    } else {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    }
                } catch (Exception e) {
                    toast("No app on this phone can open that. Install one from ReadyNet's Android apps.");
                }
                return true;
            }

        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View v, CustomViewCallback cb) {   // full-screen video
                fullscreen = v;
                ((FrameLayout) getWindow().getDecorView()).addView(v, new FrameLayout.LayoutParams(-1, -1));
            }

            @Override
            public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {      // a page's "choose files" button
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(pick, REQ_PICK);
                } catch (ActivityNotFoundException e) {
                    pickCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onHideCustomView() {
                if (fullscreen != null) ((FrameLayout) getWindow().getDecorView()).removeView(fullscreen);
                fullscreen = null;
            }
        });
        web.setDownloadListener((dlUrl, ua, disposition, mime, length) -> download(dlUrl, disposition, mime));
    }

    private boolean sameHost(String host) {
        return host != null && host.equalsIgnoreCase(startHost);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_PICK || pickCallback == null) { super.onActivityResult(req, result, data); return; }
        Uri[] picked = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                picked = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[]{data.getData()};
            }
        }
        pickCallback.onReceiveValue(picked);
        pickCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreen != null) {
            ((FrameLayout) getWindow().getDecorView()).removeView(fullscreen);
            fullscreen = null;
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (boundToWifi) getSystemService(ConnectivityManager.class).bindProcessToNetwork(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ downloads

    private void download(String url, String disposition, String mime) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        String type = mimeFor(name, mime);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        showStatus("Downloading " + name + "…");
        new Thread(() -> {
            Uri saved = null;
            String error = null;
            try {
                saved = save(url, name, type);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            Uri fin = saved;
            String err = error;
            runOnUiThread(() -> {
                bar.setVisibility(View.GONE);
                if (fin == null) {
                    showStatus("Couldn't download " + name + ": " + err);
                    return;
                }
                showStatus("Saved " + name + " in Downloads.");
                offerOpen(fin, name, type);
            });
        }).start();
    }

    private Uri save(String url, String name, String type) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new IOException("the box answered " + c.getResponseCode());
        long total = c.getContentLengthLong();
        Uri uri;
        OutputStream out;
        File file = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, type);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("couldn't make a file in Downloads");
            out = getContentResolver().openOutputStream(uri);
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IOException("no storage");
            dir.mkdirs();
            file = new File(dir, name);
            out = new FileOutputStream(file);
            uri = FilesProvider.uriFor(file);
        }
        try (InputStream in = c.getInputStream(); OutputStream o = out) {
            byte[] buf = new byte[256 * 1024];
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        runOnUiThread(() -> { bar.setIndeterminate(false); bar.setProgress(pct); });
                    }
                }
            }
        } catch (IOException e) {
            if (Build.VERSION.SDK_INT >= 29) getContentResolver().delete(uri, null, null);
            else if (file != null) file.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, v, null, null);
        }
        return uri;
    }

    private void offerOpen(Uri uri, String name, String type) {
        boolean apk = name.toLowerCase().endsWith(".apk");
        String hint = name.toLowerCase().endsWith(".obf") ? "\n\nChoose OsmAnd to add this map."
                : name.toLowerCase().endsWith(".zim") ? "\n\nChoose Kiwix to add this library." : "";
        new AlertDialog.Builder(this)
                .setTitle(apk ? "Install " + name.replaceAll("\\.apk$", "") + "?" : "Downloaded")
                .setMessage(apk ? "It's saved in Downloads too." : name + " is in Downloads." + hint)
                .setPositiveButton(apk ? "Install" : "Open", (d, w) -> open(uri, type, apk))
                .setNegativeButton("Later", null)
                .show();
    }

    private void open(Uri uri, String type, boolean apk) {
        if (apk && Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Allow ReadyNode to install apps, then come back and tap Install again.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(apk ? i : Intent.createChooser(i, "Open with"));
        } catch (ActivityNotFoundException e) {
            toast("No app on this phone opens that yet. It's in Downloads for later.");
        }
    }

    static String mimeFor(String name, String given) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_")).toLowerCase();
        if ("apk".equals(ext)) return "application/vnd.android.package-archive";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m != null) return m;
        return given == null || given.isEmpty() ? "application/octet-stream" : given;
    }

    protected void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    static Network wifi(Context ctx) { return Finder.wifi(ctx); }
}
