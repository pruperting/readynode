package uk.plumridge.readynode;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** A plain scrolling screen of checks, each with a line saying how it stands and buttons to fix it. */
abstract class Screen extends Activity {
    protected final Handler ui = new Handler(Looper.getMainLooper());
    protected LinearLayout col;

    /** Build (or rebuild) the contents of col. */
    protected abstract void fill();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad * 3);
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();      // people come back from Android's settings: show how things stand now
    }

    protected void refresh() {
        col.removeAllViews();
        fill();
    }

    /** A check: a heading with ✓ (fine) or ○ (worth doing), what it means, and buttons. */
    protected void check(boolean ok, String heading, String text, Button... buttons) {
        TextView h = text((ok ? "✓  " : "○  ") + heading, 17, true);
        h.setPadding(0, dp(18), 0, dp(2));
        if (!ok) h.setTextColor(0xFFB26A00);
        col.addView(h);
        if (text != null && !text.isEmpty()) col.addView(text(text, 15, false));
        for (Button b : buttons) if (b != null) col.addView(b);
    }

    protected TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    protected TextView small(String s) {
        TextView t = text(s, 13, false);
        t.setAlpha(0.7f);
        return t;
    }

    protected Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    /** Open one of Android's settings pages; falls back to the main Settings if this phone hasn't got that one. */
    protected void openSettings(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException | SecurityException e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    protected void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    protected int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
