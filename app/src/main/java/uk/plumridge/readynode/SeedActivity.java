package uk.plumridge.readynode;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Seed: make this phone a complete ReadyNet that others can sync from with no internet. Either download everything
 * from the internet (once), or copy it in from a ReadyNet pack on an SD card or USB stick; and make a pack for someone
 * else. The guides and this app come from inside the app, so even a phone with nothing else has something to share.
 */
public class SeedActivity extends Screen {
    private static final int REQ_IMPORT = 11, REQ_EXPORT = 12;
    private final Map<String, CheckBox> regionBoxes = new LinkedHashMap<>();
    private final List<Seeder.Region> regions = new ArrayList<>();
    private CheckBox libs, apps;
    private LinearLayout regionList;
    private TextView status, estimate;
    private ProgressBar bar;
    private Button go;
    private volatile Seeder running;
    private volatile boolean busy;
    private boolean built;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Seed");
    }

    @Override
    protected void refresh() {
        if (!built) { built = true; col.removeAllViews(); fill(); }        // built once: it holds a running download
    }

    @Override
    protected void fill() {
        SharedPreferences p = Node.prefs(this);
        col.addView(text("Make this phone a complete ReadyNet", 20, true));
        col.addView(small("A seed has everything: maps, survival and knowledge libraries, apps and guides. Others join its hotspot "
                + "(Share), install ReadyNode from it and Sync, and become complete ReadyNets themselves, with no internet anywhere. "
                + "Only the first phone in a chain needs the internet, or a pack."));

        col.addView(text("From the internet", 17, true));
        col.addView(small("Downloads from the original sources: maps from OsmAnd, libraries from Kiwix, open-source apps from F-Droid. "
                + "A few GB: use Wi-Fi if you can. Only new or updated things are downloaded, so run it again now and then."));
        col.addView(text("Maps: choose your areas", 15, true));
        regionList = new LinearLayout(this);
        regionList.setOrientation(LinearLayout.VERTICAL);
        col.addView(regionList);
        col.addView(button("Show the map areas", v -> loadRegions()));
        libs = check("Survival and knowledge libraries (Kiwix)", p.getBoolean("seedLibs", true));
        apps = check("Android apps (maps, reading, radio, files…)", p.getBoolean("seedApps", true));
        estimate = small("");
        col.addView(estimate);
        go = button("Download", v -> download());
        col.addView(go);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        col.addView(bar);
        status = text(p.getString("lastSeed", ""), 15, false);
        col.addView(status);

        col.addView(text("No internet? From a pack", 17, true));
        col.addView(small("A ReadyNet pack is a folder of files someone made on an SD card, a USB-C stick or a computer. "
                + "Plug it in, tap below and pick the \"" + Packs.FOLDER + "\" folder. Files already here are skipped."));
        col.addView(button("Copy in a pack", v -> pick(REQ_IMPORT)));

        col.addView(text("Make a pack for someone", 17, true));
        col.addView(small("Copies this phone's ReadyNet (not Files) into a \"" + Packs.FOLDER + "\" folder on an SD card, USB-C "
                + "stick or computer you pick. Run it again to bring a pack up to date."));
        col.addView(button("Make a pack", v -> pick(REQ_EXPORT)));
        updateEstimate();
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        c.setOnClickListener(v -> updateEstimate());
        col.addView(c);
        return c;
    }

    private Set<String> chosenRegions() {
        Set<String> out = new HashSet<>();
        if (regionBoxes.isEmpty()) {
            try { JSONArray a = new JSONArray(Node.prefs(this).getString("seedMaps", "[]")); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); }
            catch (Exception ignored) { }
        } else for (Map.Entry<String, CheckBox> e : regionBoxes.entrySet()) if (e.getValue().isChecked()) out.add(e.getKey());
        return out;
    }

    private void updateEstimate() {
        List<Seeder.Region> chosen = new ArrayList<>();
        Set<String> want = chosenRegions();
        for (Seeder.Region r : regions) if (want.contains(r.file)) chosen.add(r);
        long n = seeder().estimate(chosen, libs.isChecked(), apps.isChecked());
        Node.root(this).mkdirs();
        estimate.setText("About " + NodeServer.human(n) + (regions.isEmpty() && !want.isEmpty() ? " plus your " + want.size() + " map areas" : "")
                + ". Free on this phone: " + NodeServer.human(Node.root(this).getUsableSpace()) + ".");
    }

    private void loadRegions() {
        regionList.removeAllViews();
        regionList.addView(small("Asking OsmAnd…"));
        Set<String> want = chosenRegions();
        new Thread(() -> {
            List<Seeder.Region> got;
            String err = null;
            try { got = seeder().regions(); } catch (Exception e) { got = null; err = e.getMessage(); }
            List<Seeder.Region> fin = got;
            String ferr = err;
            ui.post(() -> {
                regionList.removeAllViews();
                if (fin == null) { regionList.addView(small("Couldn't reach OsmAnd (" + ferr + "). Are you on the internet?")); return; }
                regions.clear();
                regions.addAll(fin);
                regionBoxes.clear();
                JSONObject mapsDef = pack().optJSONObject("maps");
                JSONArray always = mapsDef == null ? null : mapsDef.optJSONArray("always");
                for (Seeder.Region r : fin) {
                    CheckBox c = new CheckBox(this);
                    c.setText(r.name + "  (" + NodeServer.human(r.bytes) + (r.date.isEmpty() ? "" : ", " + r.date) + ")");
                    boolean always1 = always != null && always.toString().contains("\"" + r.file + "\"");
                    c.setChecked(want.contains(r.file) || always1);
                    c.setOnClickListener(v -> updateEstimate());
                    regionBoxes.put(r.file, c);
                    regionList.addView(c);
                }
                updateEstimate();
            });
        }).start();
    }

    private void download() {
        if (running != null) { running.cancel(); return; }
        if (busy) return;
        Set<String> maps = chosenRegions();
        SharedPreferences.Editor e = Node.prefs(this).edit();
        e.putString("seedMaps", new JSONArray(maps).toString()).putBoolean("seedLibs", libs.isChecked()).putBoolean("seedApps", apps.isChecked()).apply();
        if (maps.isEmpty() && regionBoxes.isEmpty())
            toast("Tip: tap Show the map areas to add maps. Downloading the rest now.");
        Seeder s = seeder();
        running = s;
        busy = true;
        go.setText("Cancel");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        boolean l = libs.isChecked(), a = apps.isChecked();
        new Thread(() -> {
            String msg;
            try {
                builtIn();
                Seeder.Result r = s.run(maps, l, a, Arrays.asList(Build.SUPPORTED_ABIS), (m, done, total) -> ui.post(() -> {
                    status.setText(m);
                    if (total > 0) { bar.setIndeterminate(false); bar.setProgress((int) (done * 100 / total)); } else bar.setIndeterminate(true);
                }));
                msg = "Seeded " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()) + ": " + r.downloaded
                        + " downloaded (" + NodeServer.human(r.bytes) + "), " + r.upToDate + " already up to date"
                        + (r.failed > 0 ? ", " + r.failed + " couldn't be fetched (run it again to retry): " + String.join("; ", r.problems) : "") + ".";
                Node.prefs(this).edit().putString("lastSeed", msg).apply();
            } catch (Exception ex) {
                msg = "Stopped: " + ex.getMessage() + ". Run it again to carry on: what's done is kept.";
            }
            String fin = msg;
            ui.post(() -> {
                running = null;
                busy = false;
                go.setText("Download");
                bar.setVisibility(View.GONE);
                status.setText(fin);
                updateEstimate();
            });
        }).start();
    }

    /** The guides and this app, from inside the app: every seed has these, with or without the internet. */
    private void builtIn() throws Exception {
        File root = Node.root(this);
        String[] guides = getAssets().list("guides");
        if (guides != null) for (String g : guides) {
            File out = new File(root, "Survival library/Guides/" + g);
            out.getParentFile().mkdirs();
            try (InputStream in = getAssets().open("guides/" + g)) {
                byte[] data = NodeServer.readAll(in);
                if (out.isFile() && out.length() == data.length) continue;
                File part = new File(out.getParentFile(), "." + g + ".part");
                Files.write(part.toPath(), data);
                Files.move(part.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        File self = new File(getApplicationInfo().sourceDir), apk = new File(root, "Apps/ReadyNode.apk");
        if (!apk.isFile() || apk.length() != self.length()) {
            apk.getParentFile().mkdirs();
            Files.copy(self.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        File appsJson = new File(root, "Apps/apps.json");
        JSONObject doc = new JSONObject();
        try { if (appsJson.isFile()) doc = new JSONObject(new String(Files.readAllBytes(appsJson.toPath()), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
        JSONObject all = doc.optJSONObject("apps") == null ? new JSONObject() : doc.getJSONObject("apps");
        all.put("ReadyNode.apk", new JSONObject().put("name", "ReadyNode (carry and share ReadyNet)").put("tags", new JSONArray().put("readynode")));
        Syncer.write(appsJson, new JSONObject().put("apps", all).toString(1));
    }

    private JSONObject packCache;

    private JSONObject pack() {
        if (packCache == null) try (InputStream in = getAssets().open("pack.json")) {
            packCache = new JSONObject(new String(NodeServer.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) { packCache = new JSONObject(); }
        return packCache;
    }

    /** Over whatever network has the internet (not a ReadyNet's Wi-Fi the app may be browsing). */
    private Seeder seeder() {
        return new Seeder(Node.root(this), Node.meta(this, "seed-state.json"), pack(), u -> {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            return (HttpURLConnection) (n != null ? n.openConnection(u) : u.openConnection());
        });
    }

    // ------------------------------------------------------------------ packs
    private void pick(int req) {
        if (busy) { toast("Wait for the current copy to finish."); return; }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), req);
        } catch (Exception e) {
            toast("This phone has no folder picker.");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        if (req == REQ_EXPORT) {
            new AlertDialog.Builder(this).setTitle("Make a pack here?")
                    .setMessage("Copies this phone's maps, libraries, apps and guides into a \"" + Packs.FOLDER + "\" folder there. Big files take a while.")
                    .setPositiveButton("Make it", (d, w) -> packJob(tree, true)).setNegativeButton("Cancel", null).show();
        } else packJob(tree, false);
    }

    private void packJob(Uri tree, boolean export) {
        busy = true;
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        new Thread(() -> {
            String msg;
            try {
                Syncer.Progress p = (m, done, total) -> ui.post(() -> {
                    status.setText(m);
                    if (total > 0) { bar.setIndeterminate(false); bar.setProgress((int) (done * 100 / total)); }
                });
                Packs.Result r;
                if (export) {
                    builtIn();
                    r = Packs.export(Node.root(this), new HashSet<>(Arrays.asList("Maps", "Survival library", "Knowledge", "Apps")),
                            SafTree.of(this, tree), Node.name(this), p);
                } else r = Packs.importFrom(SafTree.of(this, tree), Node.root(this), p);
                msg = (export ? "Pack made: " : "Pack copied in: ") + r.copied + " copied (" + NodeServer.human(r.bytes) + "), " + r.skipped
                        + " already there" + (r.failed > 0 ? ", " + r.failed + " failed (last problem: " + r.lastError + ")" : "") + ".";
            } catch (Exception e) {
                msg = "Couldn't: " + e.getMessage();
            }
            String fin = msg;
            ui.post(() -> {
                busy = false;
                bar.setVisibility(View.GONE);
                status.setText(fin);
                updateEstimate();
            });
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (running != null) running.cancel();
        super.onDestroy();
    }
}
