# Meshtastic: text messages with no phone signal

**Meshtastic** sends short text messages by radio (LoRa), from one small radio to the next, with no phone signal, no
internet and no power grid. This guide uses the **Heltec V3**, a cheap little board with a screen, a button and an aerial; other Meshtastic radios work the same way.

- A message can hop through other people's Meshtastic radios, so it can travel several miles. In most towns there are
  dozens of them.
- It's for **short texts**: "All OK at home", "Meet at the hall at 4". Not photos, not chat at speed.
- Messages aren't stored: if a radio is off when a message is sent, it never gets it. If it matters, send it again later.

## The Heltec V3 itself

**Always screw the aerial on before you power it.** Running the radio without its aerial can damage it.

1. Power it from a **USB-C** cable: a phone charger, a power bank or a power station. (It can also run from a small battery
   plugged into the white socket on the back.)
2. The screen lights up within a few seconds and shows Meshtastic pages.
3. Put it **high up and near a window**: the higher it is, the further it reaches. A loft is better than downstairs.

### The buttons

| Button | Press | What it does |
|---|---|---|
| **PRG** (top) | Short press | Next screen |
| **PRG** | Hold about half a second | Opens the menu for that screen (in a menu: choose the highlighted item) |
| **PRG** | Hold about 4 seconds, then let go | Switches it off (rising beeps). Only works once it has been on for 30 seconds |
| **RST** (bottom) | Short press | Restarts it |

### Reading messages on the radio

New messages pop up on the screen, with who sent them. Press **PRG** to step through the screens: messages, the list of
other radios it can hear, battery, and so on.

### Sending from the radio

On its own, the Heltec has no keyboard, so **you send messages from a phone** (below) or from a browser (at the end).
Some newer firmware can send a ready-made reply from the button menu, but don't rely on it.

## Connect your phone (the Meshtastic app)

**Install the app before you need it.** Android: **Meshtastic** from Android apps on ReadyNet. iPhone:
**Meshtastic** from the App Store.

### The first time

1. Switch on Bluetooth on your phone, and power up the Heltec.
2. Open the Meshtastic app and tap the **+** button (bottom right).
3. Pick the radio from the list. Its name starts **Meshtastic_** followed by four letters and numbers. Check it matches the
   name on the Heltec's screen.
4. The app asks for a **PIN**: type the six-digit number shown on the **Heltec's screen**.
5. When it's connected, the cloud icon at the top shows a **tick**.
6. **Check the region is set to EU_868** (the UK one). On Android, it's the **Region** drop-down at the top right; on
   iPhone, **Settings → Radio configuration → LoRa**. Without the right region, it can't talk to anyone.

After that, the phone reconnects by itself whenever the app is open and the radio is nearby. One phone at a time can use a
radio over Bluetooth.

### Send and receive messages

- **To everyone (the public channel):** open the **Messages** tab and tap **LongFast** (the main channel). Type and send,
  like any messaging app. Everyone nearby on Meshtastic can read it, so **don't put your address or anything private here**.
- **To one person:** open the **Nodes** tab (every radio the Heltec has heard), tap the person's radio, and send a direct
  message.
- **Your group's channel:** a private channel shows in Messages next to LongFast. Only radios with its key can read it.
  Use it for anything personal. How to set one up, and a check-in plan, are below.
- **Replies** arrive in the same conversation, and also pop up on the Heltec's screen.

### Did it get there?

The little icon next to each message you send:

| Icon | Meaning |
|---|---|
| Cloud with an up arrow | Still on your phone, waiting to go to the radio |
| Cloud | Your radio has sent it |
| Cloud with a tick | At least one other radio heard it |
| Person with a tick | The person you sent it to (direct message) got it |
| Cloud crossed out | No answer in time: it may not have arrived. Try again later, or from higher up |

### Tips

- Keep messages **short**, and don't send many in a row: the radio is only allowed to send a little at a time.
- Say **where** with a Plus Code (see the Plus Codes guide): a Plus Code is shorter and clearer than an address.
- Put a **time** in important messages ("16:30: leaving now"), because messages can arrive late.
- The **Nodes** tab shows how far away each radio is in hops. A radio marked as reached via the internet (MQTT) won't
  be there in a power cut.

## A private channel for your family or group

A private channel just for your group, alongside the public LongFast. Messages on it are encrypted: other people's radios
still pass them on, but only radios with your key can read them. Set it up once, on one radio, then copy it to every
radio in the group by QR code. Menus move about between app versions, so the names below may differ slightly.

### Set it up (once, on one phone and radio)

1. Connect the phone to its radio (above) and open the app's **Channels** settings.
2. **Leave LongFast alone** (the first channel). Don't rename it or change its key: its name sets the radio frequency
   everyone around you uses, and it's how you reach the wider mesh.
3. **Add a channel** (+). Give it a short name your group will recognise (up to 11 letters).
4. **Key:** make a new **random** key (the refresh or "generate" button by the key; a 256-bit key is right). Never use
   the default key or a "simple" one: anyone could read it.
5. **Location:** if the app shows a location (position) setting for each channel, turn on **precise location** for your
   channel, and turn location **off** for LongFast, so strangers don't see where you are.
6. **Save** (the app sends it to the radio). Your channel now shows in Messages.

### Copy it to everyone's radio

1. On the set-up phone, open **Channels** and show the **QR code** (or Share). Tick both LongFast and your channel.
2. On each phone in the group, connected to its **own** radio: open Meshtastic and scan the code. If it asks **Add** or
   **Replace**, either works: the code holds both channels.
3. Check it worked: send "test" on your channel from each radio, and see it arrive on the others.

### Keep the key safe

The QR code (or its link) **is** the key: anyone with it can read your channel. Keep a copy somewhere private that only
your group can open. Never post it in a public chat, on social media, or in a ReadyNet broadcast. If you think someone
else has it, make a new key and copy it to everyone again.

### A check-in plan

- **Agree two check-in times a day** (for example 09:00 and 18:00), and check in on your channel even if nothing has
  changed. Keep it short: who, OK or not, where, battery. For example: "Sam OK, home, 64%".
- To say **exactly where you are**, use ReadyNode's **Where am I?** → Share: it makes one short line with a Plus Code and
  grid reference that fits in a Meshtastic message.
- Agree in advance what happens if someone misses **two check-ins in a row**: where to meet, and who to contact.
- Put the **time** in anything important, as messages can arrive late. Radios only get messages while they're switched
  on and in range, so if it matters, send it again at the next check-in.
- **Test it once a month**: a "test" from each radio, so you know every radio still has the key and works.
