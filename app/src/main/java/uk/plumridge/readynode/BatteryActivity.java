package uk.plumridge.readynode;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Save battery: when power is short and the phone masts are down, the things that make the most difference, each
 * showing how it stands now, with a button to the right Android setting. Apps aren't allowed to switch aeroplane
 * mode, battery saver or location themselves, so these open the setting for you. Brightness and screen timeout the
 * app can change, once you allow it to "modify system settings".
 *
 * Deliberately not here: killing other apps. Android already rests apps you aren't using, and force-stopped apps
 * restart, which costs more battery than it saves.
 */
public class BatteryActivity extends Screen {
    private final Runnable every = new Runnable() {
        @Override
        public void run() {
            Node.sampleBattery(BatteryActivity.this);
            refresh();
            ui.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Save battery");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(every);
        ui.post(every);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(every);
        super.onPause();
    }

    @Override
    protected void fill() {
        ContentResolver cr = getContentResolver();
        SharedPreferences p = Node.prefs(this);

        // the battery now, and how long it might last
        JSONObject bat = Node.battery(this);
        int pct = bat == null ? -1 : bat.optInt("pct", -1);
        boolean charging = bat != null && bat.optBoolean("charging");
        double[] est = Power.estimate(p.getString("battLog", ""));
        String head = pct < 0 ? "Battery" : "Battery " + pct + "%" + (charging ? ", charging" : "");
        String line;
        if (charging) line = "Charging. Top it up whenever you have power: a power bank, the car, a solar panel.";
        else if (est != null) line = Power.hours(est[0]) + " left at the rate it's been going since "
                + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date((long) est[2] * 1000))
                + String.format(Locale.UK, " (%.0f%% down to %.0f%%, about %.0f%% an hour).", est[3], est[4], est[1])
                + " The steps below make it last longer.";
        else line = "Once the battery has dropped a few percent, this shows roughly how long it will last at the rate "
                + "you're using it. ReadyNode notes the level while it's open or sharing.";
        check(charging || pct > 30, head, line);

        // aeroplane mode: the phone stops searching for masts that aren't there
        boolean plane = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        boolean wifiOn = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled();
        check(plane, plane ? "Aeroplane mode is on" : "Aeroplane mode is off",
                (plane ? "Good: the phone isn't wasting power searching for a signal." + (wifiOn ? "" : " Wi-Fi is off: turn it back on to use ReadyNet.")
                        : "With no phone signal, the phone keeps searching for a mast, and that drains the battery fast. "
                        + "Turn aeroplane mode on, then turn Wi-Fi back on (and Bluetooth, if you use a Meshtastic radio). "
                        + "ReadyNet and sharing keep working. Turn it off now and then to check for a signal.")
                        + " To call 999, turn aeroplane mode off first if your phone doesn't do it for you.",
                button(plane ? "Aeroplane mode settings" : "Turn on aeroplane mode", v -> openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)));

        // battery saver, and the phone maker's stronger one
        boolean saver = getSystemService(PowerManager.class).isPowerSaveMode();
        check(saver, saver ? "Battery saver is on" : "Battery saver is off",
                (saver ? "Good. " : "Battery saver slows background work, syncing and effects. ") + strongerSaver(),
                button("Battery saver settings", v -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)));

        // the screen: usually the biggest drain of all
        boolean canWrite = Settings.System.canWrite(this);
        boolean auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        int bright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1), max = maxBrightness();
        boolean dim = !auto && bright >= 0 && bright <= dimLevel(max);
        check(dim, dim ? "Screen is dimmed" : "Screen brightness",
                "The screen uses more power than anything else, so keep it dim and switch it off when you're not looking at it."
                        + (canWrite ? "" : " To let ReadyNode change it for you, allow it to modify system settings (one switch)."),
                dim ? null : button("Dim the screen", v -> withWrite(() -> {
                    if (!p.contains("prevBright")) p.edit().putInt("prevBright", bright).putBoolean("prevAuto", auto).apply();
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, dimLevel(max));
                    refresh();
                })),
                p.contains("prevBright") ? button("Put the brightness back", v -> withWrite(() -> {
                    if (p.getInt("prevBright", -1) >= 0) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.getInt("prevBright", max / 2));
                    if (p.getBoolean("prevAuto", false))
                        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                    p.edit().remove("prevBright").remove("prevAuto").apply();
                    refresh();
                })) : null);

        int timeout = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1);
        boolean quick = timeout > 0 && timeout <= 30_000;
        check(quick, "Screen switches off after " + (timeout <= 0 ? "?" : timeout < 60_000 ? timeout / 1000 + " seconds" : timeout / 60_000 + " minute" + (timeout < 120_000 ? "" : "s")),
                quick ? "Good." : "30 seconds is plenty. Every minute the screen stays on for nothing costs battery.",
                quick ? null : button("Switch off after 30 seconds", v -> withWrite(() -> {
                    if (!p.contains("prevTimeout")) p.edit().putInt("prevTimeout", timeout).apply();
                    Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 30_000);
                    refresh();
                })),
                p.contains("prevTimeout") ? button("Put the timeout back", v -> withWrite(() -> {
                    if (p.getInt("prevTimeout", -1) > 0) Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, p.getInt("prevTimeout", 60_000));
                    p.edit().remove("prevTimeout").apply();
                    refresh();
                })) : null);

        // location (GPS)
        boolean loc = Build.VERSION.SDK_INT >= 28 ? getSystemService(LocationManager.class).isLocationEnabled()
                : Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF;
        check(!loc, loc ? "Location is on" : "Location is off",
                loc ? "GPS uses power whenever an app asks for it. Turn location off, and on again only when you need Where am I? or a map."
                        : "Good. Turn it on when you need Where am I? or a map: GPS works with no signal.",
                button("Location settings", v -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));

        // sharing
        if (NodeService.running) check(false, "You're sharing your ReadyNet",
                "Sharing (with the phone's own hotspot) used about 10% battery an hour with five people joined, in our tests. "
                        + "Stop when nobody needs it, and share from a power bank if you can.",
                button("Stop sharing", v -> { startService(new Intent(this, NodeService.class).setAction(NodeService.ACTION_STOP)); ui.postDelayed(this::refresh, 800); }));

        col.addView(text("Also", 17, true));
        col.addView(text("• Apps you won't need (social media, shopping, games): set their battery use to Restricted, in Settings → Apps → "
                + "the app → Battery. Then they can't run in the background.\n"
                + "• Don't use \"task killer\" or \"cleaner\" apps: force-stopped apps start again, and that uses more battery than "
                + "leaving them alone.\n"
                + "• Keep the phone warm (an inside pocket) but not hot: cold batteries run down faster.\n"
                + "• Charge from a power bank while the screen is off, and top up whenever there's power.", 15, false));
        File guide = new File(Node.root(this), "Survival library/Guides/SAVING BATTERY.md");
        if (guide.isFile()) col.addView(button("Read the full guide", v -> startActivity(new Intent(this, BrowseActivity.class)
                .putExtra(BrowseActivity.EXTRA_URL, Node.localUrl() + "#/view/" + NodeServer.enc("Survival library/Guides/SAVING BATTERY.md"))
                .putExtra(BrowseActivity.EXTRA_TITLE, "Saving battery"))));
    }

    /** The phone maker's stronger battery saver, where we know what it's called. */
    static String strongerSaver() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        if (m.contains("google")) return "For the most saving, Pixel phones have Extreme Battery Saver (Settings → Battery → Battery Saver).";
        if (m.contains("samsung")) return "For the most saving, Samsung phones have a stronger power saving mode in Settings → Battery (look for \"Limit apps and Home screen\" or \"Maximum power saving\").";
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco"))
            return "For the most saving, this phone has Ultra battery saver (Settings → Battery): it leaves only calls, messages and a few apps you choose. Add ReadyNode to its apps if you need it.";
        if (m.contains("oneplus") || m.contains("oppo") || m.contains("realme"))
            return "For the most saving, look in Settings → Battery for Super power saving mode.";
        return "For the most saving, look in Settings → Battery for an \"extreme\", \"ultra\" or \"maximum\" power saving mode.";
    }

    /** The brightness setting's top value (255 on most phones, more on some). */
    static int maxBrightness() {
        try {
            int id = Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android");
            if (id != 0) {
                int v = Resources.getSystem().getInteger(id);
                if (v > 0) return v;
            }
        } catch (Exception ignored) { }
        return 255;
    }

    static int dimLevel(int max) { return Math.max(1, max / 8); }

    /** Run a change to the system settings, first asking Android's permission if we haven't got it. */
    private void withWrite(Runnable r) {
        if (Settings.System.canWrite(this)) {
            try { r.run(); } catch (Exception e) { toast("This phone wouldn't let ReadyNode change that: " + e.getMessage()); }
            return;
        }
        new AlertDialog.Builder(this).setTitle("Allow ReadyNode to change the screen settings?")
                .setMessage("Android asks you to switch on \"Allow modifying system settings\" for ReadyNode. It's only used for "
                        + "the brightness and screen timeout buttons here. Come back with the Back button afterwards.")
                .setPositiveButton("Open the setting", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) { openSettings(Settings.ACTION_SETTINGS); }
                })
                .setNegativeButton("Not now", null).show();
    }
}
