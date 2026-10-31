package uk.plumridge.readynode;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * News and messages, all in one place: writing and managing your own (your broadcast, and the messages on your
 * ReadyNet's News page), and the broadcasts you've received (read or not, mute). The settings that change how things
 * work live elsewhere: receiving broadcasts in Sync, your ReadyNet's name in Share.
 */
public class MessagesActivity extends Screen {
    private String castDraft = "", newsDraft = "";
    private EditText castText, newsText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("News and messages");
    }

    @Override
    protected void refresh() {
        if (castText != null) castDraft = castText.getText().toString();      // keep what you were typing
        if (newsText != null) newsDraft = newsText.getText().toString();
        super.refresh();
        BrowseActivity.pagesChanged = true;                                    // the ReadyNet pages show it fresh
    }

    @Override
    protected void fill() {
        Broadcasts b = Node.broadcasts(this);

        // ---- broadcasts received
        col.addView(text("Broadcasts", 20, true));
        col.addView(small("Short messages that travel from phone to phone between ReadyNode apps, well beyond one hotspot. "
                + "Each phone has one at a time. Mark them read to clear them from the home page."));
        if (!b.receiving()) {
            col.addView(text("Receiving broadcasts is off, so you won't see other people's. Turn it on in Sync.", 15, false));
            col.addView(button("Open Sync", v -> startActivity(SettingsActivity.open(this, SettingsActivity.CLIENT))));
        } else {
            Set<String> read = new HashSet<>();
            JSONArray rk = b.readKeys();
            for (int i = 0; i < rk.length(); i++) read.add(rk.optString(i));
            String me = Node.id(this);
            int unread = 0, done = 0;
            LinearLayout readList = new LinearLayout(this);
            readList.setOrientation(LinearLayout.VERTICAL);
            try {
                List<JSONObject> all = b.visible();
                for (JSONObject c : all) {
                    JSONObject from = c.optJSONObject("from");
                    if (from == null || from.optString("id").equals(me)) continue;
                    String key = Broadcasts.key(c), id = from.optString("id"), name = from.optString("name");
                    String body = name + " · " + c.optString("t") + (c.optString("via").isEmpty() ? "" : " · via " + c.optString("via")) + "\n" + c.optString("text");
                    if (read.contains(key)) {
                        done++;
                        readList.addView(card(body, "Mark unread", () -> { b.setRead(key, false); refresh(); }, "Mute", () -> confirmMute(id, name)));
                    } else {
                        unread++;
                        col.addView(card(body, "Mark read", () -> { b.setRead(key, true); refresh(); }, "Mute", () -> confirmMute(id, name)));
                    }
                }
            } catch (Exception ignored) { }
            if (unread == 0) col.addView(small(done == 0 ? "None yet. They arrive when you're on another ReadyNode's hotspot or Wi-Fi with the app open."
                    : "No unread broadcasts."));
            if (done > 0) {
                TextView h = text("Read (" + done + ")  ▸", 15, true);
                readList.setVisibility(View.GONE);
                h.setOnClickListener(v -> {
                    boolean show = readList.getVisibility() != View.VISIBLE;
                    readList.setVisibility(show ? View.VISIBLE : View.GONE);
                    h.setText("Read (" + readList.getChildCount() + ")  " + (show ? "▾" : "▸"));
                });
                col.addView(h);
                col.addView(readList);
            }
        }
        JSONArray muted = b.muted();
        if (muted.length() > 0) {
            col.addView(text("Muted", 15, true));
            col.addView(small("Their broadcasts are hidden and not passed on."));
            for (int i = 0; i < muted.length(); i++) {
                JSONObject m = muted.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id");
                col.addView(card(m.optString("name"), "Unmute", () -> { b.unmute(id); refresh(); }, null, null));
            }
        }

        // ---- your broadcast
        col.addView(heading("Your broadcast"));
        col.addView(small("One short message that travels on from phone to phone, to every ReadyNode receiving broadcasts. You have one at "
                + "a time: broadcasting again replaces it everywhere, and Stop takes it back, as phones meet. Anyone may see it, so nothing "
                + "private. For example: \"Water point open at the school, 9 to 5\"."));
        JSONObject mine = b.mine();
        col.addView(text(mine == null ? "Not broadcasting." : "Broadcasting since " + mine.optString("t") + ":\n" + mine.optString("text"), 15, false));
        castText = field("Your broadcast (up to " + Broadcasts.MAX_TEXT + " letters)", castDraft, Broadcasts.MAX_TEXT);
        col.addView(castText);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Broadcast", v -> {
            try {
                b.broadcast(castText.getText().toString());
                castText.setText("");
                toast("Broadcasting. It spreads as ReadyNode phones meet.");
                refresh();
            } catch (Exception e) { toast("Couldn't broadcast: " + e.getMessage()); }
        }), new LinearLayout.LayoutParams(0, -2, 1));
        if (mine != null) row.addView(button("Stop", v -> confirm("Stop broadcasting? It goes from other phones too, as they meet.", "Stop", () -> {
            b.stop();
            refresh();
        })), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(row);

        // ---- your ReadyNet's messages
        col.addView(heading("Your messages"));
        col.addView(small("Shown on your ReadyNet's News page to people joined to you (" + Node.name(this) + "). They can reply; "
                + "remove anything unhelpful. Change your ReadyNet's name in Share."));
        newsText = field("What's happening", newsDraft, 1000);
        col.addView(newsText);
        col.addView(button("Post", v -> {
            try {
                Node.news(this).post(Node.name(this), newsText.getText().toString());
                newsText.setText("");
                refresh();
            } catch (Exception e) { toast("Couldn't post: " + e.getMessage()); }
        }));
        JSONArray items = Node.news(this).items();
        if (items.length() == 0) col.addView(small("No messages yet."));
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id");
            col.addView(card(it.optString("t") + "\n" + it.optString("text"), "Remove",
                    () -> confirm("Remove this message and its replies?", "Remove", () -> { Node.news(this).delete(id, null); refresh(); }), null, null));
            JSONArray rs = it.optJSONArray("replies");
            if (rs != null) for (int j = 0; j < rs.length(); j++) {
                JSONObject r = rs.optJSONObject(j);
                String rid = r.optString("id");
                View c = card("↳ " + r.optString("t") + (r.optString("who").isEmpty() ? "" : " · " + r.optString("who")) + "\n" + r.optString("text"),
                        "Remove", () -> confirm("Remove this reply?", "Remove", () -> { Node.news(this).delete(id, rid); refresh(); }), null, null);
                c.setPadding(dp(20), c.getPaddingTop(), 0, c.getPaddingBottom());
                col.addView(c);
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    private interface Action { void run() throws Exception; }

    private void confirmMute(String id, String name) {
        confirm("Mute " + name + "? Their broadcasts are hidden and not passed on, until you unmute them.", "Mute", () -> {
            Node.broadcasts(this).mute(id, name);
            refresh();
        });
    }

    private void confirm(String q, String yes, Action a) {
        new AlertDialog.Builder(this).setMessage(q).setPositiveButton(yes, (d, w) -> run(a)).setNegativeButton("Cancel", null).show();
    }

    private void run(Action a) {
        try { a.run(); } catch (Exception e) { toast("Couldn't: " + e.getMessage()); }
    }

    private TextView heading(String s) {
        TextView t = text(s, 20, true);
        t.setPadding(0, dp(26), 0, dp(4));
        return t;
    }

    private EditText field(String hint, String value, int max) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSingleLine(false);
        e.setMinLines(2);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        return e;
    }

    /** A message with one or two small buttons beside it. */
    private View card(String body, String a1, Action r1, String a2, Action r2) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView t = text(body, 15, false);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        for (Object[] x : new Object[][]{{a1, r1}, {a2, r2}}) {
            if (x[0] == null) continue;
            Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btn.setText((String) x[0]);
            btn.setAllCaps(false);
            Action act = (Action) x[1];
            btn.setOnClickListener(v -> run(act));
            buttons.addView(btn);
        }
        row.addView(buttons);
        return row;
    }
}
