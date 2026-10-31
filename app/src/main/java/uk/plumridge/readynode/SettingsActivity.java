package uk.plumridge.readynode;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReadyNode's settings: sync from the ReadyNet you're connected to, broadcasts, your ReadyNet's name, your
 * broadcast, your messages, your own files, and client or server mode (with the QR codes).
 */
public class SettingsActivity extends Activity {
    private static final int REQ_PERMS = 1, REQ_FILES = 2;
    private static final String[][] SYNC = {
            {"Maps", "Offline maps"}, {"Survival library", "Survival library"}, {"Knowledge", "Knowledge"},
            {"Apps", "Android apps"}, {"Files", "Their shared files"}};
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView haveText, timerText;
    private volatile Syncer.Offer lastOffer;
    private TextView connText, syncStatus, shareStatus, hotspotText, urlText, filesHint, versionText;
    private EditText hostField, nameField;
    private final Map<String, CheckBox> ticks = new LinkedHashMap<>();
    private Button syncBtn, shareBtn;
    private ProgressBar syncBar;
    private LinearLayout filesList;
    private CheckBox makeHotspot;
    private ImageView wifiQr, urlQr;
    private String lastQrKey = "", pendingFolder = "Media";
    private volatile Syncer syncer;

    static final String EXTRA_SCREEN = "screen", CLIENT = "client", SERVER = "server";

    private boolean serverScreen;
    private TextView modeText;
    private Button backToClient;

    static Intent open(android.content.Context c, String screen) {
        return new Intent(c, SettingsActivity.class).putExtra(EXTRA_SCREEN, screen);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        serverScreen = SERVER.equals(getIntent().getStringExtra(EXTRA_SCREEN));
        setTitle(serverScreen ? "Share your ReadyNet" : "Use other ReadyNets");
        SharedPreferences p = Node.prefs(this);
        try { Node.serve(this, NodeService.running); } catch (Exception ignored) { }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad * 3);
        if (serverScreen) serverScreen(col, p); else clientScreen(col, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    // the once-a-second refresh runs only while this screen is showing (battery)
    private final Runnable ticker = this::tick;

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        tick();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(ticker);                         // only the refresh: a running sync still reports back
        super.onPause();
    }

    /** Client: this phone uses other ReadyNets and copies them. Plus the hidden Family (our HomeBox, Ready Kit). */
    private void clientScreen(LinearLayout col, SharedPreferences p) {
        modeText = body("");
        col.addView(modeText);
        backToClient = button("Stop sharing (back to client)", v -> toggleShare());
        col.addView(backToClient);

        col.addView(title("Sync"));
        connText = body("Looking for a ReadyNet on your Wi-Fi…");
        col.addView(connText);
        haveText = body("");
        haveText.setVisibility(View.GONE);
        col.addView(haveText);
        col.addView(small("Choose what to copy onto this phone. Unticked sections are removed from this phone to save space "
                + "(it asks first). Otherwise syncing only adds and updates."));
        for (String[] s : SYNC) {
            CheckBox c = new CheckBox(this);
            c.setText(s[1]);
            c.setChecked(p.getBoolean("sync_" + s[0], !s[0].equals("Files")));
            c.setOnClickListener(v -> showCoverage());
            ticks.put(s[0], c);
            col.addView(c);
        }
        syncBtn = button("Sync now", v -> sync());
        col.addView(syncBtn);
        syncBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        syncBar.setVisibility(View.GONE);
        col.addView(syncBar);
        syncStatus = body(p.getString("lastSync", "Not synced yet."));
        col.addView(syncStatus);
        hostField = field("Other address (optional), e.g. 192.168.43.1:8080", p.getString("host", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(hostField);
        col.addView(button("Look again", v -> { p.edit().putString("host", hostField.getText().toString().trim()).apply(); look(); }));

        col.addView(title("Broadcasts"));
        col.addView(small("Broadcasts are short messages that travel from phone to phone between ReadyNode apps. When this is on, this "
                + "phone takes them from the ReadyNode phones it meets, shows them on News, and passes them on. Off: you see none, and "
                + "this phone doesn't carry them (your own still goes out). Read them, mute senders and write your own in News and messages."));
        CheckBox receive = new CheckBox(this);
        receive.setText("Receive and pass on broadcasts");
        receive.setChecked(Node.broadcasts(this).receiving());
        receive.setOnCheckedChangeListener((b, on) -> {
            try { Node.broadcasts(this).setReceiving(on); } catch (Exception e) { toast("Couldn't save: " + e.getMessage()); }
        });
        col.addView(receive);
        col.addView(button("Open News and messages", v -> startActivity(new Intent(this, MessagesActivity.class))));

        versionText = small("ReadyNode " + Node.version(this));
        versionText.setPadding(0, dp(24), 0, dp(8));
        col.addView(versionText);
        look();
    }

    /** Server: share this phone's ReadyNet, with its name, messages and files. */
    private void serverScreen(LinearLayout col, SharedPreferences p) {
        col.addView(title("Sharing"));
        makeHotspot = new CheckBox(this);
        makeHotspot.setText("Make a hotspot from this phone");
        makeHotspot.setChecked(p.getBoolean("makeHotspot", true));
        col.addView(makeHotspot);
        col.addView(small("Untick to use your normal phone hotspot, or a Wi-Fi everyone is already on."));
        shareBtn = button("", v -> toggleShare());
        col.addView(shareBtn);
        shareStatus = body("");
        col.addView(shareStatus);
        hotspotText = body("");
        hotspotText.setTypeface(Typeface.MONOSPACE);
        col.addView(hotspotText);
        wifiQr = qrView();
        col.addView(wifiQr);
        urlText = body("");
        urlText.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(urlText);
        urlQr = qrView();
        col.addView(urlQr);

        col.addView(title("Sharing timer"));
        col.addView(small("Sharing keeps the phone and its Wi-Fi awake, which uses battery. Get a reminder after a while, or stop by itself."));
        String[] labels = {"Off", "1 hour", "2 hours", "4 hours", "8 hours"};
        android.widget.Spinner timer = new android.widget.Spinner(this);
        timer.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int now = p.getInt("shareTimerMin", 0), at = 0;
        for (int i = 0; i < NodeService.TIMER_MINUTES.length; i++) if (NodeService.TIMER_MINUTES[i] == now) at = i;
        timer.setSelection(at, false);
        timer.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                if (p.getInt("shareTimerMin", 0) == NodeService.TIMER_MINUTES[pos]) return;
                p.edit().putInt("shareTimerMin", NodeService.TIMER_MINUTES[pos]).apply();
                NodeService.timerChanged(SettingsActivity.this);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });
        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        timerRow.addView(body("Remind me after  "));
        timerRow.addView(timer);
        col.addView(timerRow);
        CheckBox autoStop = new CheckBox(this);
        autoStop.setText("Stop sharing then, instead of reminding me");
        autoStop.setChecked(p.getBoolean("shareAutoStop", false));
        autoStop.setOnCheckedChangeListener((b, on) -> p.edit().putBoolean("shareAutoStop", on).apply());
        col.addView(autoStop);
        timerText = small("");
        col.addView(timerText);

        col.addView(title("Your ReadyNet's name"));
        col.addView(small("Shown at the top of your ReadyNet, so people know whose they've joined."));
        nameField = field("e.g. Rupert's phone", p.getString("name", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        col.addView(nameField);
        col.addView(button("Save name", v -> {
            p.edit().putString("name", nameField.getText().toString().trim()).apply();
            toast("Saved: your ReadyNet is called " + Node.name(this) + ".");
        }));

        col.addView(title("Messages"));
        col.addView(small("Your broadcast, and the messages on your ReadyNet's News page, are in News and messages."));
        col.addView(button("Open News and messages", v -> startActivity(new Intent(this, MessagesActivity.class))));

        col.addView(title("Your files"));
        col.addView(small("Files you share show in your ReadyNet's Files section, in Media, Apps or Books. Sync never removes them."));
        LinearLayout row = new LinearLayout(this);
        for (String f : new String[]{"Media", "Apps", "Books"}) {
            Button btn = button("Add to " + f, v -> pickFiles(f));
            row.addView(btn, new LinearLayout.LayoutParams(0, -2, 1));
        }
        col.addView(row);
        filesHint = small("");
        col.addView(filesHint);
        filesList = new LinearLayout(this);
        filesList.setOrientation(LinearLayout.VERTICAL);
        col.addView(filesList);
        TextView v = small("ReadyNode " + Node.version(this));
        v.setPadding(0, dp(24), 0, dp(8));
        col.addView(v);
        showFiles();
    }

    // ------------------------------------------------------------------ finding a ReadyNet, and syncing
    private void look() {
        connText.setText("Looking for a ReadyNet on your Wi-Fi…");
        new Thread(() -> {
            Finder.Found f = Finder.find(this, Node.prefs(this).getString("host", ""));
            MainActivity.live = f;
            Syncer.Offer o = null;
            if (f != null) try { o = syncerFor().offer(f.base); } catch (Exception ignored) { }
            Syncer.Offer offer = o;
            ui.post(() -> {
                if (f == null) {
                    connText.setText("Not connected to a ReadyNet. Join another ReadyNode phone's hotspot (or the Wi-Fi it's on), then tap Look again."
                            + "\n\nWhat I tried:\n" + Finder.lastReport.trim() + "\nThis phone: " + NodeService.describe(this));
                    syncBtn.setEnabled(false);
                    return;
                }
                connText.setText("Connected to " + f.name + " at " + f.base.replace("http://", "") + ".");
                syncBtn.setEnabled(syncer == null);
                lastOffer = offer;
                showCoverage();
            });
        }).start();
    }

    /**
     * How complete this phone's copy is, against the ReadyNet it's connected to: per section on the ticks, and in all
     * for the ticked sections. Counts files here at the same path and size, however they arrived.
     */
    private void showCoverage() {
        Syncer.Offer offer = lastOffer;
        if (offer == null || haveText == null) return;
        new Thread(() -> {
            Map<String, long[]> cov;
            try { cov = syncerFor().coverage(offer); } catch (Exception e) { return; }
            ui.post(() -> {
                long files = 0, have = 0, bytes = 0, haveBytes = 0;
                StringBuilder missing = new StringBuilder();
                for (String[] s : SYNC) {
                    long[] t = cov.get(s[0]);
                    CheckBox c = ticks.get(s[0]);
                    if (t == null || c == null) continue;
                    c.setText(s[1] + (t[0] == 0 ? " (they have none)" : " (" + t[0] + " files, " + NodeServer.human(t[2]) + "; you have "
                            + (t[1] == t[0] ? "all" : t[1] + ", " + pct(t[1], t[0])) + ")"));
                    if (!c.isChecked() || t[0] == 0) continue;
                    files += t[0]; have += t[1]; bytes += t[2]; haveBytes += t[3];
                    if (t[1] < t[0]) missing.append(missing.length() == 0 ? "" : ", ").append(t[0] - t[1]).append(" in ").append(s[1]);
                }
                haveText.setVisibility(View.VISIBLE);
                if (files == 0) haveText.setText("Nothing to compare: tick a section they have.");
                else if (have == files) haveText.setText("✓ This phone has everything " + offer.name + " has in the ticked sections: " + files + " files, " + NodeServer.human(bytes) + ".");
                else haveText.setText("This phone has " + have + " of the " + files + " files " + offer.name + " has in the ticked sections ("
                        + pct(have, files) + "; " + NodeServer.human(haveBytes) + " of " + NodeServer.human(bytes) + "). Missing: " + missing + ". Sync to get them.");
            });
        }).start();
    }

    static String pct(long a, long b) {
        if (b <= 0) return "0%";
        long p = a * 100 / b;
        if (p == 100 && a < b) p = 99;                         // never say 100% while something's missing
        return p + "%";
    }

    /** Asks on screen, from the sync thread, before anything is removed. */
    private boolean askRemove(int unticked, int gone, long bytes, List<String> examples) {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        boolean[] yes = {false};
        StringBuilder m = new StringBuilder("This sync would remove " + (unticked + gone) + " file" + (unticked + gone == 1 ? "" : "s")
                + " (" + NodeServer.human(bytes) + ") from this phone:\n");
        if (unticked > 0) m.append("\n• ").append(unticked).append(" in sections you've unticked");
        if (gone > 0) m.append("\n• ").append(gone).append(" that the ReadyNet you got them from no longer has");
        m.append("\n\nFor example:");
        for (String e : examples) m.append("\n").append(e);
        ui.post(() -> new AlertDialog.Builder(this).setTitle("Remove files?").setMessage(m.toString())
                .setPositiveButton("Remove them", (d, w) -> { yes[0] = true; done.countDown(); })
                .setNegativeButton("Keep them", (d, w) -> done.countDown())
                .setOnCancelListener(d -> done.countDown()).show());
        try { done.await(); } catch (InterruptedException ignored) { }
        return yes[0];
    }

    private Syncer syncerFor() { return syncer(this); }

    /** A Syncer for this phone's copy, over the Wi-Fi (a ReadyNet has no internet). */
    static Syncer syncer(android.content.Context c) {
        return new Syncer(Node.root(c), Node.meta(c, "sync-index.json"), u -> {
            Network w = Finder.wifi(c);
            return (HttpURLConnection) (w != null ? w.openConnection(u) : u.openConnection());
        });
    }

    private void sync() {
        if (syncer != null) { syncer.cancel(); return; }
        Finder.Found f = MainActivity.live;
        if (f == null) { toast("Not connected to a ReadyNet."); return; }
        Set<String> chosen = new HashSet<>();
        SharedPreferences.Editor e = Node.prefs(this).edit();
        for (Map.Entry<String, CheckBox> t : ticks.entrySet()) {
            e.putBoolean("sync_" + t.getKey(), t.getValue().isChecked());
            if (t.getValue().isChecked()) chosen.add(t.getKey());
        }
        e.apply();
        Syncer s = syncerFor();
        syncer = s;
        syncBtn.setText("Cancel");
        syncBar.setVisibility(View.VISIBLE);
        syncBar.setIndeterminate(true);
        BusyService.show(this, "Syncing from " + f.name);           // keeps this phone and its Wi-Fi awake with the screen off
        long[] noted = {0};
        new Thread(() -> {
            String msg;
            try {
                SharedPreferences p = Node.prefs(this);
                Syncer.Result r = s.run(f.base, chosen, this::askRemove, (m, done, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - noted[0] > 5000) { noted[0] = now; BusyService.show(this, m); }
                    ui.post(() -> {
                        syncStatus.setText(m);
                        if (total > 0) { syncBar.setIndeterminate(false); syncBar.setProgress((int) (done * 100 / total)); }
                    });
                });
                if (r.failed == 0) p.edit().putLong("lastSyncTime", System.currentTimeMillis()).putString("lastSyncFrom", r.from).apply();
                Relay.Result news = Node.relayNews(this, f, true);
                msg = "Synced " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()) + " from " + r.from + ": "
                        + r.downloaded + " new or updated, " + r.kept + " unchanged, " + r.removed + " removed"
                        + (r.keptBack > 0 ? ", " + r.keptBack + " kept as you asked" : "")
                        + (r.failed > 0 ? ", " + r.failed + " failed (sync again to retry; last problem: " + r.lastError + ")" : "") + "."
                        + (news != null && news.got > 0 ? " Broadcasts: " + news.got + " new." : "");
                Node.prefs(this).edit().putString("lastSync", msg).apply();
            } catch (Exception ex) {
                msg = "Couldn't sync: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()) + ".";
            }
            String fin = msg;
            BusyService.done(this);
            ui.post(() -> {
                syncer = null;
                syncBtn.setText("Sync now");
                syncBar.setVisibility(View.GONE);
                syncStatus.setText(fin);
                if (filesList != null) showFiles();         // the files list is on the Share screen, not this one
                showCoverage();
            });
        }).start();
    }

    // ------------------------------------------------------------------ own files
    private void showFiles() {
        if (filesList == null) return;                     // only the Share screen has it
        filesList.removeAllViews();
        File files = new File(Node.root(this), "Files");
        int n = 0;
        for (String sub : new String[]{"Media", "Apps", "Books"}) {
            File[] all = new File(files, sub).listFiles();
            if (all == null) continue;
            for (File f : all) {
                if (!f.isFile() || f.getName().startsWith(".")) continue;
                n++;
                filesList.addView(card(sub + " / " + f.getName() + " (" + NodeServer.human(f.length()) + ")", "Remove",
                        () -> confirmThen("Stop sharing " + f.getName() + " and remove it from this phone?", () -> { f.delete(); showFiles(); }), 0));
            }
        }
        filesHint.setText(n == 0 ? "Nothing shared yet." : n + " file" + (n == 1 ? "" : "s") + " shared.");
    }

    private void pickFiles(String folder) {
        pendingFolder = folder;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FILES);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILES || result != RESULT_OK || data == null || filesHint == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        File dir = new File(Node.root(this), "Files/" + pendingFolder);
        dir.mkdirs();
        filesHint.setText("Adding " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + "…");
        new Thread(() -> {
            int ok = 0;
            for (Uri u : uris) {
                File out = new File(dir, displayName(u));
                File part = new File(dir, "." + out.getName() + ".part");
                try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(part)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } catch (Exception e) {
                    part.delete();
                    continue;
                }
                if (part.renameTo(out)) ok++;
            }
            int fin = ok;
            ui.post(() -> { showFiles(); toast("Added " + fin + " file" + (fin == 1 ? "" : "s") + " to " + pendingFolder + "."); });
        }).start();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replace("/", "_").replaceAll("^\\.+", "");
            }
        } catch (Exception ignored) {
        }
        return "file-" + System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ server mode
    private void toggleShare() {
        if (NodeService.running) {
            startService(new Intent(this, NodeService.class).setAction(NodeService.ACTION_STOP));
            return;
        }
        Node.prefs(this).edit().putBoolean("makeHotspot", makeHotspot.isChecked()).apply();
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (makeHotspot.isChecked())
            need.add(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION);
        List<String> missing = new ArrayList<>();
        for (String perm : need) if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) missing.add(perm);
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        else startSharing();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS) startSharing();       // the hotspot step reports clearly if a permission was refused
    }

    private void startSharing() {
        startForegroundService(new Intent(this, NodeService.class).putExtra(NodeService.EXTRA_HOTSPOT, makeHotspot.isChecked()));
    }

    private void tick() {
        boolean on = NodeService.running;
        if (!serverScreen) {
            modeText.setText(on ? "This phone is sharing its ReadyNet (server mode). Stop sharing to go back to being a client."
                    : "Client mode: this phone uses other ReadyNets, and shows its own copy when there isn't one. To share yours, use Share in the menu.");
            backToClient.setVisibility(on ? View.VISIBLE : View.GONE);
            ui.postDelayed(ticker, 1000);
            return;
        }
        shareBtn.setText(on ? "Stop sharing" : "Start sharing (server)");
        long started = NodeService.startedAt, remind = NodeService.remindAt, nowMs = System.currentTimeMillis();
        timerText.setText(!on || started == 0 ? "" : "Sharing for " + Syncer.duration((nowMs - started) / 1000)
                + (remind > nowMs ? (Node.prefs(this).getBoolean("shareAutoStop", false) ? "; stops in " : "; reminder in ")
                + Syncer.duration((remind - nowMs) / 1000) : "") + ".");
        makeHotspot.setEnabled(!on);
        List<String> urls = NodeService.addresses(this);
        String ssid = NodeService.hotspotSsid, pass = NodeService.hotspotPass;
        if (!on) {
            shareStatus.setText("Not sharing: this phone is a client, and your ReadyNet is only on this phone.");
            hotspotText.setText(NodeService.hotspotError == null ? "" : NodeService.hotspotError);
        } else {
            shareStatus.setText(urls.isEmpty() ? "Sharing, but this phone isn't on any Wi-Fi or hotspot yet." : "Sharing " + Node.name(this) + ". Others:");
            if (ssid != null) hotspotText.setText("1. Join Wi-Fi \"" + ssid + "\"\n   password  " + (pass == null ? "(none)" : pass) + "\n   or scan this code:");
            else if (NodeService.hotspotError != null) hotspotText.setText(NodeService.hotspotError);
            else if (makeHotspot.isChecked()) hotspotText.setText("Starting the hotspot…");
            else hotspotText.setText("1. Join this phone's hotspot or the same Wi-Fi.");
        }
        String url = on && !urls.isEmpty() ? urls.get(0) + "readynet/" : null;
        urlText.setText(url == null ? "" : "2. Open " + url + "\n   (People with ReadyNode only need step 1: the app finds it.)"
                + "\n\nThis phone's networks: " + NodeService.describe(this)
                + "\nServer: " + (Node.server != null && Node.server.isAlive() ? (Node.server.everyone() ? "sharing with everyone" : "this phone only") : "not running")
                + " on port " + NodeServer.PORT);
        String key = on + "|" + ssid + "|" + pass + "|" + url;
        if (!key.equals(lastQrKey)) {
            lastQrKey = key;
            setQr(wifiQr, on && ssid != null ? "WIFI:T:" + (pass == null ? "nopass" : "WPA") + ";S:" + qrEscape(ssid) + ";P:" + qrEscape(pass == null ? "" : pass) + ";;" : null);
            setQr(urlQr, url);
        }
        ui.postDelayed(ticker, 1000);
    }

    // ------------------------------------------------------------------ small UI helpers
    private interface Action { void run() throws Exception; }

    private void confirmThen(String q, Action a) {
        new AlertDialog.Builder(this).setMessage(q).setPositiveButton("Remove", (d, w) -> {
            try { a.run(); } catch (Exception e) { toast("Couldn't: " + e.getMessage()); }
        }).setNegativeButton("Keep", null).show();
    }

    private View card(String text, String action, Action a, int indent) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(indent, dp(6), 0, dp(6));
        TextView t = body(text);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(action);
        b.setOnClickListener(v -> { try { a.run(); } catch (Exception e) { toast("Couldn't: " + e.getMessage()); } });
        row.addView(b);
        return row;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView small(String s) {
        TextView t = body(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(0.7f);
        return t;
    }

    private EditText field(String hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private ImageView qrView() {
        ImageView v = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(220));
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        v.setVisibility(View.GONE);
        return v;
    }

    private void setQr(ImageView v, String text) {
        if (text == null) { v.setVisibility(View.GONE); return; }
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 440, 440);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            v.setImageBitmap(Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565));
            v.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            v.setVisibility(View.GONE);
        }
    }

    private static String qrEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
