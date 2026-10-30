package uk.plumridge.readynode;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Where am I?": your position from GPS, with no signal or internet needed, as a Plus Code, an OS grid reference and
 * latitude/longitude, to copy, share (e.g. into Meshtastic) or open in a map app. Also turns a code someone sent you
 * back into a place on the map. Nothing is ever sent anywhere unless you tap Share. GPS runs only while this screen
 * is open.
 */
public class LocationActivity extends Activity implements LocationListener {
    private static final int REQ_LOC = 3;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, plus, grid, latlon, found;
    private EditText codeField;
    private Location fix;
    private LocationManager lm;
    private double[] foundPlace;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Where am I?");
        lm = getSystemService(LocationManager.class);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad * 3);

        status = text("Finding your position…", 15, false);
        col.addView(status);
        col.addView(label("Plus Code"));
        plus = big();
        col.addView(plus);
        col.addView(label("OS grid reference"));
        grid = big();
        col.addView(grid);
        col.addView(label("Latitude, longitude"));
        latlon = big();
        col.addView(latlon);

        LinearLayout row = new LinearLayout(this);
        row.addView(button("Copy", v -> copy(message(false))), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("Share", v -> share()), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(button("Map", v -> { if (fix != null) openMap(fix.getLatitude(), fix.getLongitude()); }), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(row);
        col.addView(small("Share sends a short line you can paste into Meshtastic or a text: who, Plus Code, grid reference, "
                + "latitude/longitude, battery and time. Nothing is sent unless you tap it. GPS works with no signal, but needs "
                + "a view of the sky: indoors it may not find you, and the first fix can take a few minutes."));

        col.addView(label("Find a place someone sent you"));
        codeField = new EditText(this);
        codeField.setHint("Plus Code, grid reference, or lat, lon");
        codeField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        col.addView(codeField);
        col.addView(button("Find it", v -> find()));
        found = text("", 15, false);
        col.addView(found);
        Button openFound = button("Open it in a map app", v -> { if (foundPlace != null) openMap(foundPlace[0], foundPlace[1]); });
        col.addView(openFound);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_LOC) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) start();
        else status.setText("ReadyNode isn't allowed to use your location. Allow it in the phone's settings to see where you are. "
                + "You can still find places someone sent you, below.");
    }

    @SuppressWarnings("MissingPermission")
    private void start() {
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (!lm.isProviderEnabled(p)) continue;
                Location last = lm.getLastKnownLocation(p);
                if (last != null && (fix == null || last.getTime() > fix.getTime())) fix = last;
                lm.requestLocationUpdates(p, 2000, 0, this, Looper.getMainLooper());
            }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                status.setText("Location is switched off. Turn it on (quick settings) to find your position.");
        } catch (Exception e) {
            status.setText("Couldn't start GPS: " + e.getMessage());
        }
        show();
        ui.postDelayed(this::tick, 5000);
    }

    private void tick() {
        show();
        ui.postDelayed(this::tick, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (fix == null || l.getTime() >= fix.getTime() || l.getAccuracy() < fix.getAccuracy()) fix = l;
        show();
    }

    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }

    private void show() {
        if (fix == null) {
            plus.setText("—");
            grid.setText("—");
            latlon.setText("—");
            return;
        }
        double lat = fix.getLatitude(), lon = fix.getLongitude();
        long ageS = Math.max(0, (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1_000_000_000L);
        String age = ageS < 60 ? "just now" : ageS < 3600 ? (ageS / 60) + " min ago" : (ageS / 3600) + " h ago";
        status.setText((ageS > 120 ? "OLD POSITION: " : "") + "From " + (LocationManager.GPS_PROVIDER.equals(fix.getProvider()) ? "GPS" : "the phone")
                + ", " + age + ", to within about " + Math.round(fix.getAccuracy()) + " m"
                + (fix.hasAltitude() ? " · height " + Math.round(fix.getAltitude()) + " m" : "")
                + (ageS > 120 ? ". Waiting for a fresh fix: go outside if you can." : "."));
        plus.setText(GeoUtils.plusCode(lat, lon));
        String g = GeoUtils.gridRef(lat, lon, 5);
        grid.setText(g == null ? "(outside Great Britain)" : g);
        latlon.setText(String.format(Locale.ROOT, "%.5f, %.5f", lat, lon));
    }

    private String message(boolean withName) {
        if (fix == null) return "";
        double lat = fix.getLatitude(), lon = fix.getLongitude();
        String g = GeoUtils.gridRef(lat, lon, 4);
        org.json.JSONObject bat = Node.battery(this);
        return (withName ? "[LOC] " + Node.name(this) + " | " : "") + GeoUtils.plusCode(lat, lon) + (g == null ? "" : " | " + g)
                + String.format(Locale.ROOT, " | %.5f,%.5f", lat, lon)
                + (bat == null ? "" : " | Bat " + bat.optInt("pct") + "%")
                + " | " + new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(fix.getTime()))
                + " ±" + Math.round(fix.getAccuracy()) + "m";
    }

    private void share() {
        if (fix == null) { toast("No position yet."); return; }
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message(true));
        startActivity(Intent.createChooser(i, "Share your position"));
    }

    private void copy(String s) {
        if (s.isEmpty()) { toast("No position yet."); return; }
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Position", s));
        toast("Copied.");
    }

    private void find() {
        Double rl = fix == null ? null : fix.getLatitude(), rn = fix == null ? null : fix.getLongitude();
        String t = codeField.getText().toString();
        foundPlace = GeoUtils.parse(t, rl, rn);
        if (foundPlace == null) {
            found.setText(t.contains("+") && fix == null && t.trim().indexOf('+') < 8
                    ? "That's a short Plus Code: ask for the full one (four more characters at the start), or wait until this phone knows where it is."
                    : "That doesn't look like a Plus Code (e.g. 9C3VCCX9+JF), a grid reference (e.g. ST 596 724) or lat, lon (e.g. 51.4491, -2.5813).");
            return;
        }
        double lat = foundPlace[0], lon = foundPlace[1];
        String g = GeoUtils.gridRef(lat, lon, 3);
        String away = "";
        if (fix != null) {
            float[] d = new float[2];
            Location.distanceBetween(fix.getLatitude(), fix.getLongitude(), lat, lon, d);
            String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
            away = String.format(Locale.UK, "\nAbout %s %s of you.", d[0] < 1000 ? Math.round(d[0]) + " m" : String.format(Locale.UK, "%.1f km", d[0] / 1000),
                    dirs[(int) Math.round(((d[1] + 360) % 360) / 45) % 8]);
        }
        found.setText(String.format(Locale.ROOT, "%.5f, %.5f", lat, lon) + (g == null ? "" : "  ·  " + g) + "  ·  " + GeoUtils.plusCode(lat, lon) + away);
    }

    /** Opens OsmAnd, Organic Maps or whatever map app is installed, at that spot. */
    /** Offline map apps, best first: OsmAnd opens the map files from ReadyNet; Organic Maps has its own. */
    static final String[][] OFFLINE_MAPS = {{"net.osmand.plus", "OsmAnd"}, {"net.osmand", "OsmAnd"}, {"app.organicmaps", "Organic Maps"}};

    /** Open the place in an offline map app if there is one; only otherwise the phone's usual map app, with a warning. */
    private void openMap(double lat, double lon) {
        Uri u = Uri.parse(String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f", lat, lon, lat, lon));
        for (String[] app : OFFLINE_MAPS) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u).setPackage(app[0]));
                return;
            } catch (ActivityNotFoundException ignored) {
                // not installed: try the next
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
            toast("No offline map app found, so this opened your usual map app, which may not work without a signal. "
                    + "Install OsmAnd or Organic Maps from ReadyNet's Android apps.");
        } catch (ActivityNotFoundException e) {
            toast("No map app on this phone yet: install OsmAnd or Organic Maps from ReadyNet's Android apps.");
        }
    }

    // ------------------------------------------------------------------ small UI helpers
    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView big() {
        TextView t = text("—", 26, true);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, true);
        t.setPadding(0, dp(14), 0, 0);
        t.setAlpha(0.7f);
        return t;
    }

    private TextView small(String s) {
        TextView t = text(s, 13, false);
        t.setAlpha(0.7f);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
