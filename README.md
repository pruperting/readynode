# ReadyNode

**ReadyNode is an Android app that carries ReadyNet, a small offline emergency library, and shares it with phones nearby. No internet or mobile signal is needed.**

ReadyNet holds offline maps, survival and first-aid libraries, plain-English guides, and the free apps that open them. Once one phone has it, the next phone can copy it over local Wi-Fi or a hotspot, and that phone can pass it on again. Each phone that carries ReadyNet becomes a **node** others can copy from.

## What the app does

- **Browse.** It opens this phone's ReadyNet: maps, libraries, guides and apps, with search across the guides and a plain "simple view" for old or slow phones.
- **Share.** It turns this phone into a ReadyNet server on its hotspot or Wi-Fi. Other phones open the address it shows, or scan its QR code, to browse it in a web browser or download the ReadyNode app itself.
- **Sync.** It copies from another ReadyNet: a phone running ReadyNode, or any server that speaks the same ReadyNet API. It checks free space first, verifies every file by checksum, and carries on where it stopped. A file is offered for removal only if the source that gave it has since dropped it, and you are always asked first.
- **Seed.** It makes this phone a complete ReadyNet from the original sources on the internet: OsmAnd maps for the areas you pick, Kiwix libraries (newest versions only), the apps from F-Droid, and the guides built into the app. See [`pack/pack.json`](pack/pack.json) for exactly what it fetches.
- **Packs.** For when phones can't reach each other: **Seed → Make a pack** puts this phone's ReadyNet into one `.rdynd` file, on an SD card, a USB-C stick or in Downloads to send on. Get it to another phone however you like, then open it, or use **Seed → Copy in a pack**. Maps are compressed (to about 56%); libraries that are already compressed are stored as they are, so a typical pack is 20–25% smaller than the files. Over 3.8 GB it splits into parts that each fit a FAT32 card, and an interrupted copy carries on where it stopped. Inside, a `.rdynd` is an ordinary zip with `readynet-pack.json` first.
- **Where am I?** It shows your position as a Plus Code, a grid reference and latitude/longitude, from GPS only.
- **Save battery.** It gives a short list of settings that make a phone last for days, with links straight to each one.
- **Broadcasts (optional, off by default).** One short message per phone, passed from node to node. You can mute any sender.

## Install

Download `ReadyNode.apk` from the [latest release](../../releases/latest) and open it. Android will ask you to allow installs from your browser or file manager. Or get it from any phone already sharing ReadyNet: open the address it shows and tap **Get the ReadyNode app**.

It needs Android 8 or newer. It has no ads, no accounts and no tracking, and it never contacts the internet except when you tap **Seed**.

## Getting ReadyNet onto a phone

1. **With internet:** open **Seed**, choose your map areas, and tap **Download**. It needs a few GB, so use Wi-Fi.
2. **From a phone that has it:** on that phone, tap **Share**. On yours, join its hotspot (or the same Wi-Fi) and tap **Sync**.
3. **From a pack:** open the `.rdynd` file, or **Seed → Copy in a pack** and pick it (all its parts, if there are several).

## Privacy

Nothing leaves the phone unless you share it. Sharing serves only the ReadyNet sections, never the rest of the phone. Broadcasts are off until you turn them on. Seed mode talks only to download.osmand.net, download.kiwix.org and f-droid.org.

## Building

You need JDK 17, the Android SDK (platform 34), and Gradle 8.x or Android Studio.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle testReleaseUnitTest assembleRelease
```

The website in `site/`, the guides in `guides/` and `pack/pack.json` are copied into the app at build time. Release signing reads a `keystore.properties` file from outside the repo (path in `READYNODE_SIGNING`); without it, the release build is unsigned. To publish, put the signed APK at `ReadyNode.apk` in the repo root and run `tools/release.sh v3.1`.

## The ReadyNet API

Every ReadyNet server speaks the same API, which is how ReadyNode syncs from a phone or a home server alike. The endpoints are `/api/net/info`, `/api/net/ls`, `/api/net/manifest` (with sha256), `/api/net/search`, `/api/net/news`, and the files under `/net/`. The site in `site/` works on any of them.

## Licence

ReadyNode is free software under the [GNU General Public License v3.0](LICENSE): use it, change it and share it, as long as what you share stays under the same licence. The maps, libraries and apps it fetches keep their own licences: see [NOTICE.md](NOTICE.md).
