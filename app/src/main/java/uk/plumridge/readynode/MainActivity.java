package uk.plumridge.readynode;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;

/**
 * What opens when you open ReadyNode: a ReadyNet website.
 *  - On a ReadyNet's Wi-Fi (another phone's hotspot, or a ReadyNet server), the live site from there.
 *  - Otherwise (no ReadyNet nearby, offline, or this phone is in server mode), this phone's own copy.
 * Settings (sync, your name, messages, your files, server mode) are in the menu.
 */
public class MainActivity extends BrowseActivity {
    private static final int SYNC = 1, SHARE = 4, SWITCH = 2, RELOAD = 3, WHERE = 5, BATTERY = 6, SEED = 7, NEWS = 8, HELP = 9;

    static volatile Finder.Found live;     // the ReadyNet this phone last found on its Wi-Fi (Settings syncs from it)
    private boolean showingLive, pinned;
    private long lastLook;

    @Override
    protected void start() {
        setTitle("ReadyNode");
        if (Updater.ACTION.equals(getIntent().getAction())) Updater.offer(this);
        if (!fromLink(getIntent())) decide(true);
        if (!Node.prefs(this).getBoolean("seenHelp", false)) startActivity(new Intent(this, HelpActivity.class));   // the first time
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Updater.ACTION.equals(intent.getAction())) { Updater.offer(this); return; }
        fromLink(intent);
    }

    /** "Open this ReadyNet in the app" links: readynet://192.168.x.y:8080/readynet/ */
    private boolean fromLink(Intent i) {
        Uri d = i == null ? null : i.getData();
        if (d == null || !"readynet".equals(d.getScheme()) || d.getHost() == null) return false;
        String url = "http://" + d.getHost() + (d.getPort() > 0 ? ":" + d.getPort() : "") + "/readynet/";
        pinned = true;
        showingLive = true;
        subtitle("Live: " + d.getHost());
        go(url);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> Node.sampleBattery(this)).start();
        if (!pinned && SystemClock.elapsedRealtime() - lastLook > 8000) decide(false);
    }

    /** Find a live ReadyNet, or show this phone's own. Only reloads if what should be showing has changed. */
    private void decide(boolean first) {
        lastLook = SystemClock.elapsedRealtime();
        if (NodeService.running) { showOwn(first, "Sharing from this phone (server mode)"); return; }
        new Thread(() -> {
            Finder.Found f = Finder.find(this, Node.prefs(this).getString("host", ""));
            live = f;
            if (f != null) new Thread(() -> {                    // a newer ReadyNode there? Ask once per version
                Updater.check(this, f.base, f.name);
                Updater.Available u = Updater.available;
                if (u != null) runOnUiThread(this::invalidateOptionsMenu);
                if (u != null && Node.prefs(this).getLong("updAsked", 0) != u.code) {
                    Node.prefs(this).edit().putLong("updAsked", u.code).apply();
                    runOnUiThread(() -> { if (!isFinishing()) Updater.offer(this); });
                }
            }).start();
            if (f != null) new Thread(() -> {                    // swap broadcasts, if it takes part in them
                Relay.Result r = Node.relayNews(this, f, false);
                if (r != null && r.got > 0) runOnUiThread(() -> android.widget.Toast.makeText(this, r.got
                        + (r.got == 1 ? " new broadcast" : " new broadcasts") + " from " + f.name + ". They're on your ReadyNet's News page.",
                        android.widget.Toast.LENGTH_LONG).show());
            }).start();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (f != null) {
                    boolean same = showingLive && f.base.contains("//" + currentHost());
                    showingLive = true;
                    subtitle("Live from " + f.name);
                    if (!same || first) go(f.base + "/readynet/");
                } else {
                    showOwn(first || showingLive, "Your copy (no ReadyNet on this Wi-Fi)");
                }
            });
        }).start();
    }

    private void showOwn(boolean load, String why) {
        try {
            Node.serve(this, NodeService.running);
        } catch (Exception e) {
            showStatus("Couldn't start this phone's ReadyNet: " + e.getMessage());
            return;
        }
        boolean wasLive = showingLive;
        showingLive = false;
        subtitle(why);
        if (load || wasLive) go(Node.localUrl());
    }

    private void subtitle(String s) {
        if (getActionBar() != null) getActionBar().setSubtitle(s);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, SYNC, 0, "Sync").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, SHARE, 0, "Share").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, SEED, 0, "Seed").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, NEWS, 1, "News and messages");
        m.add(0, WHERE, 1, "Where am I?");
        m.add(0, BATTERY, 1, "Save battery");
        m.add(0, SWITCH, 1, "Show my copy");
        m.add(0, RELOAD, 2, "Look for a ReadyNet again");
        m.add(0, HELP, 3, "Help");
        if (Updater.available != null) m.add(0, 10, 0, "Update ReadyNode").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu m) {
        m.findItem(SWITCH).setTitle(showingLive ? "Show my copy" : "Show the live ReadyNet").setEnabled(showingLive || live != null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case SYNC:
                startActivity(SettingsActivity.open(this, SettingsActivity.CLIENT));
                return true;
            case NEWS:
                startActivity(new Intent(this, MessagesActivity.class));
                return true;
            case 10:
                Updater.offer(this);
                return true;
            case HELP:
                startActivity(new Intent(this, HelpActivity.class));
                return true;
            case WHERE:
                startActivity(new Intent(this, LocationActivity.class));
                return true;
            case SEED:
                startActivity(new Intent(this, SeedActivity.class));
                return true;
            case BATTERY:
                startActivity(new Intent(this, BatteryActivity.class));
                return true;
            case SHARE:
                startActivity(SettingsActivity.open(this, SettingsActivity.SERVER));
                return true;
            case SWITCH:
                if (showingLive) { pinned = true; showOwn(true, "Your copy"); }
                else if (live != null) { pinned = false; showingLive = true; subtitle("Live from " + live.name); go(live.base + "/readynet/"); }
                return true;
            case RELOAD:
                pinned = false;
                decide(true);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
