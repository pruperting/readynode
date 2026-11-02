package uk.plumridge.readynode;

import android.content.Intent;
import android.os.Bundle;

/** What ReadyNode is and how to use it, in plain words. Opens by itself the first time, and from the menu. */
public class HelpActivity extends Screen {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Help");
        Node.prefs(this).edit().putBoolean("seenHelp", true).apply();
    }

    @Override
    protected void fill() {
        col.addView(text("ReadyNode", 22, true));
        col.addView(text("Emergency information on your phone, with no internet or signal needed, and a way to share it with the people "
                + "around you.", 15, false));

        section("What's in it",
                "ReadyNet: offline maps, survival and first-aid guides, reference libraries (a medical encyclopedia, Wikipedia's most-read "
                        + "articles, a travel guide), the free apps that open them, and news. The app shows it like a small website.");

        section("Getting ReadyNet onto this phone",
                "Three ways, whichever you can:\n"
                        + "• Sync: join the hotspot (or Wi-Fi) of someone sharing ReadyNet, then tap Sync. It copies theirs.\n"
                        + "• Seed: with internet, tap Seed and Download. It fetches everything from the original sources.\n"
                        + "• A pack: someone can give you a .rdynd file (SD card, USB stick, Bluetooth). Open it, or Seed → Copy in a pack.\n"
                        + "Sync and Seed show how complete your copy is, so you know you have the lot.");

        section("Using it",
                "ReadyNode opens the ReadyNet nearby if there is one, or your own copy if not. Maps open in OsmAnd and libraries in Kiwix: "
                        + "install those from Android apps (inside ReadyNet) first. The guides open right in the app. Search finds words "
                        + "inside the guides too.");

        section("Sharing with others",
                "Share turns this phone into a ReadyNet for everyone near you: it can make a hotspot, and shows QR codes for joining. "
                        + "Others open the address in any browser (iPhones too), or install ReadyNode from you and copy the lot. "
                        + "Sharing uses battery, so set a timer in Share (5 minutes to 12 hours) to be reminded, or to stop by itself.");

        section("News and broadcasts",
                "News is messages from whoever runs a ReadyNet; anyone there can reply. Broadcasts are short messages that travel from phone "
                        + "to phone between ReadyNode apps. Mark them read to clear them from the home page; they stay under Read on News. "
                        + "Write your own, and manage everything, in News and messages.");

        section("Where am I? and Save battery",
                "Where am I? gives your position as a Plus Code and grid reference from GPS alone. It only uses location while it's on "
                        + "screen. Save battery lists the settings that make a phone last for days.");

        section("Updates",
                "When the ReadyNet you're on has a newer ReadyNode, the app tells you, and one tap installs it. Your copy and settings stay.");

        section("Privacy",
                "Nothing goes to the internet except when you tap Seed. Sharing shows only ReadyNet, never the rest of your phone. Replies and "
                        + "broadcasts can be read by anyone, so keep them public.");

        col.addView(button("News and messages", v -> startActivity(new Intent(this, MessagesActivity.class))));
        col.addView(button("Sync", v -> startActivity(SettingsActivity.open(this, SettingsActivity.CLIENT))));
        col.addView(button("Seed", v -> startActivity(new Intent(this, SeedActivity.class))));
        col.addView(small("ReadyNode " + Node.version(this) + ". Free and open source: github.com/pruperting/readynode. In an emergency, call 999."));
    }

    private void section(String heading, String body) {
        android.widget.TextView h = text(heading, 17, true);
        h.setPadding(0, dp(18), 0, dp(2));
        col.addView(h);
        col.addView(text(body, 15, false));
    }
}
