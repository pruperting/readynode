/* ReadyNet: the emergency-information website. The same files are served by every ReadyNode phone (inside the app)
   and by any other ReadyNet server, and all answer the same /api/net/... addresses, so it looks and works the same
   wherever it's opened. No internet, no outside files: phones, old browsers, iPhones and Android all welcome. */
"use strict";

const SECTIONS = [
  {id: "news", title: "News", about: "Messages from whoever runs this ReadyNet, and broadcasts from other ReadyNodes. Anyone can reply."},
  {id: "maps", title: "Offline maps", dir: "Maps", tag: "maps", about: "Map apps and map files: directions with no signal."},
  {id: "survival", title: "Survival library", dir: "Survival library", tag: "survival", about: "Survival, first aid, water and food: guides and books."},
  {id: "apps", title: "Android apps", dir: "Apps", about: "Useful apps that work offline. Free to copy and share."},
  {id: "knowledge", title: "Knowledge", dir: "Knowledge", tag: "knowledge", about: "Offline encyclopedias: medicine, travel and Wikipedia."},
  {id: "files", title: "Files", dir: "Files", about: "Files shared by whoever runs this ReadyNet.", onlyIfFull: true},
];
const IPHONE_APPS = {
  maps: "Organic Maps (then download England and Wales inside the app)",
  survival: "Kiwix (for .zim libraries) and Apple Books (for ebooks and PDFs)",
  knowledge: "Kiwix",
};
const MAP_NAMES = {
  "World_basemap_mini_2": "World overview (zoomed out)",
  "Gb_england_south-west-england_europe_2": "South West England (Bristol, Somerset, Gloucestershire)",
  "Gb_wales_europe_2": "Wales (Monmouth to Llandudno)",
  "Gb_england_west-midlands_europe_2": "West Midlands (the M5 and M6 north)",
  "Gb_england_north-west-england_europe_2": "North West England (Cheshire, the Wirral)",
};
const ZIM_NAMES = {
  "zimgit-post-disaster_en": "Post Disaster Resource Library", "zimgit-medicine_en": "Medical Library (field and emergency medicine)",
  "zimgit-water_en": "Water Treatment Library", "zimgit-food-preparation_en": "Food for Preppers", "zimgit-knots_en": "Knots",
  "wikipedia_en_medicine_mini": "WikiMed (medical encyclopedia, short)", "wikipedia_en_medicine_maxi": "WikiMed in full, with pictures",
  "wikivoyage_en_all_nopic": "Wikivoyage travel guide", "wikipedia_en_top_mini": "Wikipedia: most-read articles (short)",
  "wikipedia_en_top_nopic": "Wikipedia: most-read articles (full text)", "wikipedia_en_all_mini": "All of Wikipedia (short)",
};
const EXT = {
  video: ["mp4", "m4v", "webm", "mov", "mkv", "avi"],
  audio: ["mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"],
  image: ["jpg", "jpeg", "png", "gif", "webp"],
  read: ["md", "txt"],
};
const HINT = {
  zim: "A library file. Download it, then open it in the Kiwix app.",
  obf: "A map file. Download it, then open it and choose OsmAnd.",
  epub: "An ebook. Download it, then open it in Librera (Android) or Books (iPhone).",
  mobi: "An ebook. Download it, then open it in Librera.",
  pdf: "A PDF. Open it here, or download it to keep.",
  apk: "An Android app. Download it, then tap it to install (allow \"install unknown apps\" if asked).",
};

const NOT_FOR_PHONES = "Not for phones";   // a folder of bigger versions a server keeps, but never offers phones to sync
const $ = s => document.querySelector(s);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const enc = p => p.split("/").map(encodeURIComponent).join("/");
const ext = n => (n.match(/\.([^.\/]+)$/) || ["", ""])[1].toLowerCase();
const join = (a, b) => a ? a + "/" + b : b;
const human = n => n < 1024 ? n + " B" : n < 1048576 ? (n / 1024).toFixed(0) + " KB" : n < 1073741824 ? (n / 1048576).toFixed(1) + " MB" : (n / 1073741824).toFixed(2) + " GB";
const title = n => n.replace(/\.[^.]+$/, "").replace(/_+/g, " ");
const isMeta = n => /\.json$/i.test(n);      // apps.json and friends: descriptions, not things to show
const fileUrl = (p, dl) => "/net/" + enc(p) + (dl ? "?dl=1" : "");
const store = {
  get(k) { try { return localStorage.getItem(k) || ""; } catch (e) { return ""; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } },
};

const UA = navigator.userAgent;
const inApp = /ReadyNode\//.test(UA);
const android = /Android/.test(UA);
const iphone = /iPhone|iPad|iPod/.test(UA) || (/Macintosh/.test(UA) && navigator.maxTouchPoints > 1);

let info = {name: "ReadyNet", kind: "node", sections: {}}, apps = null, manifest = null;

async function getJSON(url) {
  const r = await fetch(url, {cache: "no-store"});
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

async function ls(path) {
  try {
    return (await getJSON("/api/net/ls/" + (path ? enc(path) + "/" : "")))
      .filter(e => !e.name.startsWith(".") && !(e.type !== "directory" && isMeta(e.name)))
      .map(e => ({name: e.name, dir: e.type === "directory", size: e.size || 0}))
      .sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name, undefined, {numeric: true, sensitivity: "base"}));
  } catch (e) { return null; }
}

async function appList() {
  if (apps) return apps;
  let meta = {};
  try { meta = (await getJSON(fileUrl("Apps/apps.json"))).apps || {}; } catch (e) { /* none: use file names */ }
  const files = ((await ls("Apps")) || []).filter(e => !e.dir && ext(e.name) === "apk");
  apps = files.map(f => ({file: f.name, size: f.size, name: (meta[f.name] || {}).name || title(f.name), tags: (meta[f.name] || {}).tags || []}))
    .sort((a, b) => (b.file === "ReadyNode.apk") - (a.file === "ReadyNode.apk") || a.name.localeCompare(b.name));
  return apps;
}

/* ------------------------------------------------------------------ building blocks */
const view = html => { $("#view").innerHTML = html; window.scrollTo(0, 0); };

function crumbs(path) {
  const parts = path ? path.split("/") : [];
  const sec = SECTIONS.find(s => s.dir === parts[0]);
  let acc = "", out = `<div class="crumbs"><a href="#/">Home</a>`;
  parts.forEach((p, i) => {
    acc = join(acc, p);
    const href = i === 0 && sec ? `#/s/${sec.id}` : `#/browse/${enc(acc)}`;
    out += `<span class="sep">/</span><a href="${href}">${esc(i === 0 && sec ? sec.title : p)}</a>`;
  });
  return out + "</div>";
}

function kindOf(name) {
  const x = ext(name);
  for (const k in EXT) if (EXT[k].includes(x)) return k;
  return "file";
}

function niceName(path, name) {
  if (ext(name) === "zim") {
    const m = name.match(/^(.*)_(\d{4}-\d{2})\.zim$/);
    if (m && ZIM_NAMES[m[1]]) return `${ZIM_NAMES[m[1]]} (${m[2]})`;
  }
  if (ext(name) === "obf") return MAP_NAMES[name.replace(/\.obf$/, "")] || title(name).replace(/ europe 2$/i, "").replace(/^Gb /, "");
  return name;
}

function rows(path, list) {
  list = list.filter(e => e.dir || !isMeta(e.name));
  if (!list.length) return `<div class="files"><div class="empty">Nothing here yet.</div></div>`;
  return `<div class="files">` + list.map(e => {
    const p = join(path, e.name);
    const label = e.dir && e.name === NOT_FOR_PHONES ? "Bigger versions (not copied to phones)" : e.dir ? e.name : niceName(path, e.name);
    return `<a class="f" href="${e.dir ? `#/browse/${enc(p)}` : `#/view/${enc(p)}`}"><span class="ico ${e.dir ? "dir" : ""}">${e.dir ? "dir" : esc(ext(e.name).slice(0, 4))}</span>` +
      `<span class="fname">${esc(label)}</span><span class="meta num">${e.dir ? "" : human(e.size)}</span></a>`;
  }).join("") + `</div>`;
}

function appRows(list, dir = "Apps") {
  if (!list.length) return `<div class="files"><div class="empty">No apps here yet.</div></div>`;
  return `<div class="files apps">` + list.map(a => `<div class="app"><span class="fname"><b>${esc(a.name)}</b><br><span class="meta">${human(a.size)}</span></span>` +
    (android ? `<a class="btnlink" href="${fileUrl(dir + "/" + a.file, true)}" download>Install</a>` : `<a class="btnlink ghost" href="${fileUrl(dir + "/" + a.file, true)}" download>Download</a>`) + `</div>`).join("") + `</div>`;
}

function appsFor(sec, list) {
  const mine = list.filter(a => a.tags.includes(sec.tag));
  let h = `<h2>Apps you need</h2>`;
  if (android || !iphone) h += appRows(mine);
  if (!android) h += `<p class="note">${iphone ? "On an iPhone" : "On an iPhone or iPad"}, apps only come from the App Store: install ${esc(IPHONE_APPS[sec.tag] || "them")} <b>before</b> you need it.${iphone ? "" : " The downloads above are for Android phones."}</p>`;
  return h;
}

/* ------------------------------------------------------------------ pages */
async function home() {
  const tiles = [];
  for (const s of SECTIONS) {
    const n = s.dir ? (info.sections || {})[s.dir] || 0 : -1;
    if (s.onlyIfFull && n === 0) continue;
    tiles.push(`<a class="tile${n === 0 ? " muted" : ""}" href="#/${s.id === "news" ? "news" : "s/" + s.id}"><strong>${esc(s.title)}</strong><span>${esc(s.about)}${n === 0 ? " (Nothing here yet.)" : ""}</span></a>`);
  }
  if (android && !inApp) tiles.push(`<a class="tile get" href="#/app"><strong>Get the ReadyNode app</strong><span>Carry all of this on your phone, and share it on with others.</span></a>`);
  tiles.push(`<a class="tile" href="#/help"><strong>Help</strong><span>What ReadyNet is, and how to use it.</span></a>`);
  let news = [], casts = [];
  try { const d = await getJSON("/api/net/news"); news = d.items || []; casts = liveCasts(d); } catch (e) { /* none */ }
  const read = readKeys(), me = myId();
  const unread = casts.filter(b => !read.has(castKey(b)) && !(b.from && b.from.id === me)), others = casts.filter(b => !(b.from && b.from.id === me));
  const pw = info.power;
  view(`<h1>${esc(info.name)}</h1>
    <p class="meta">ReadyNet: emergency information shared over Wi-Fi, with no internet needed.</p>
    ${pw && pw.pct <= 20 && !pw.charging ? `<div class="flash"><b>The phone sharing this ReadyNet is low on battery (${pw.pct}%).</b> Download only what you need, and soon.</div>` : ""}
    ${news.length ? `<h2>Latest news</h2><div class="list" style="margin-bottom:16px">${news.slice(0, 3).map(n => newsItem(n, false)).join("")}</div>
      ${news.length > 3 ? `<p><a href="#/news">All ${news.length} messages</a></p>` : ""}` : ""}
    ${unread.length ? `<h2>Broadcasts</h2><div class="list" style="margin-bottom:8px">${unread.slice(0, 3).map(b => castItem(b, "read")).join("")}</div>
      <p>${unread.length > 1 ? `<button class="ghost small" data-readall="${esc(unread.map(castKey).join(","))}">Mark all read</button> ` : ""}${unread.length > 3 ? `<a href="#/news">${unread.length - 3} more on News</a>` : ""}</p>`
      : others.length ? `<p class="meta"><a href="#/news">${others.length} ${others.length === 1 ? "broadcast" : "broadcasts"}, all read: on News</a></p>` : ""}
    <form class="searchbar" onsubmit="location.hash='#/search?q='+encodeURIComponent(this.q.value);return false">
      <input name="q" type="search" placeholder="Search guides, maps, books, apps…" aria-label="Search"><button>Search</button></form>
    <div class="tiles">${tiles.join("")}</div>`);
}

function newsItem(n, full) {
  const replies = n.replies || [];
  return `<div class="msg" id="n-${esc(n.id)}"><span class="src">${esc(n.t || "")}${n.who ? " · " + esc(n.who) : ""}</span><pre class="plain">${esc(n.text)}</pre>
    ${full ? replies.map(r => `<div class="reply"><span class="src">${esc(r.t || "")}${r.who ? " · " + esc(r.who) : ""}</span><pre class="plain">${esc(r.text)}</pre></div>`).join("") +
      `<form class="replyform" data-id="${esc(n.id)}"><textarea name="text" maxlength="1000" placeholder="Reply" aria-label="Reply" required></textarea>
        <div class="row"><input name="who" maxlength="40" placeholder="Your name (optional)" aria-label="Your name" value="${esc(store.get("rn-who"))}"><button>Reply</button></div>
        <span class="meta" data-msg></span></form>`
      : `<a class="meta" href="#/news">${replies.length ? replies.length + (replies.length === 1 ? " reply" : " replies") + " · " : ""}Reply</a>`}</div>`;
}

/* Broadcasts: one short message per ReadyNode phone, passed on from phone to phone (servers without them show none). */
/* In the ReadyNode app with "Receive broadcasts" off, other people's broadcasts are hidden on every ReadyNet it shows
   (this phone's own still shows). Browsers, and the app with it on, see them all. */
function castsOff() { try { return !!window.ReadyNodeApp && !window.ReadyNodeApp.broadcasts(); } catch (e) { return false; } }
function myId() { try { return window.ReadyNodeApp ? String(window.ReadyNodeApp.myId()) : ""; } catch (e) { return ""; } }
const liveCasts = doc => (doc.broadcasts || []).filter(b => !b.removed && b.text && !(castsOff() && !(b.from && b.from.id === myId())))
  .sort((a, b) => String(b.t || "").localeCompare(String(a.t || "")));     // newest first

function castItem(b, action) {
  const mine = b.from && b.from.id === myId();
  return `<div class="msg"><span class="src passed">${mine ? "Your broadcast" : "Broadcast from " + esc(b.from && b.from.name)}${b.via ? " · via " + esc(b.via) : ""}</span>` +
    `<span class="src">${esc(b.t || "")}</span><pre class="plain">${esc(b.text)}</pre>` +
    (action && !mine ? `<button class="ghost small" data-read="${esc(castKey(b))}" data-on="${action === "read" ? 1 : 0}">${action === "read" ? "Mark read" : "Mark unread"}</button>` : "") + `</div>`;
}

/* Read broadcasts leave the home page and wait under "Read" on News. In the app the app keeps the list (the same on
   every ReadyNet it shows); in a browser, that browser does. */
const castKey = b => (b.from && b.from.id || "") + ":" + (b.v || 0);
function readKeys() {
  try { if (window.ReadyNodeApp && window.ReadyNodeApp.readCasts) return new Set(JSON.parse(window.ReadyNodeApp.readCasts())); } catch (e) { /* older app */ }
  try { return new Set(JSON.parse(store.get("rn-read") || "[]")); } catch (e) { return new Set(); }
}
function setRead(key, on) {
  try { if (window.ReadyNodeApp && window.ReadyNodeApp.setRead) { window.ReadyNodeApp.setRead(key, !!on); return; } } catch (e) { /* older app */ }
  const s = readKeys();
  if (on) s.add(key); else s.delete(key);
  store.set("rn-read", JSON.stringify([...s].slice(-500)));
}
document.addEventListener("click", ev => {
  const b = ev.target.closest && ev.target.closest("[data-read],[data-readall]");
  if (!b) return;
  if (b.dataset.readall) b.dataset.readall.split(",").forEach(k => setRead(k, true));
  else setRead(b.dataset.read, b.dataset.on === "1");
  route();
});

async function newsPage() {
  let doc = {};
  try { doc = await getJSON("/api/net/news"); } catch (e) { /* none */ }
  const items = doc.items || [], casts = liveCasts(doc);
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>News</h1>
    <p class="note">Messages from whoever runs ${esc(info.name)}. Replies can be read by everyone on this Wi-Fi, so don't share anything private.</p>
    <div class="list">${items.length ? items.map(n => newsItem(n, true)).join("") : `<div class="empty">No messages yet.</div>`}</div>
    ${castsOff() && (doc.broadcasts || []).some(b => !b.removed && b.text && !(b.from && b.from.id === myId())) ?
      `<p class="note">Broadcasts from other ReadyNodes are hidden: receiving them is off in ReadyNode (Sync → Broadcasts).</p>` : ""}
    ${inApp && window.ReadyNodeApp && window.ReadyNodeApp.openMessages ? `<p><button class="ghost" onclick="ReadyNodeApp.openMessages()">Write a message or broadcast</button></p>` : ""}
    ${casts.length ? (() => {
      const read = readKeys(), unread = casts.filter(b => !read.has(castKey(b))), done = casts.filter(b => read.has(castKey(b)));
      return `<h2>Broadcasts</h2><p class="note">One short message from each ReadyNode phone, passed on from phone to phone.
        They may have come a long way, so check the time.</p>
        <div class="list">${unread.length ? unread.map(b => castItem(b, "read")).join("") : `<div class="empty">No unread broadcasts.</div>`}</div>
        ${done.length ? `<details class="read"><summary>Read (${done.length})</summary><div class="list">${done.map(b => castItem(b, "unread")).join("")}</div></details>` : ""}`;
    })() : ""}`);
  document.querySelectorAll("form.replyform").forEach(f => f.onsubmit = async ev => {
    ev.preventDefault();
    const msg = f.querySelector("[data-msg]"), d = new Date(), pad = x => String(x).padStart(2, "0");
    store.set("rn-who", f.who.value.trim());
    try {
      const r = await fetch("/api/net/news/reply", {method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({id: f.dataset.id, who: f.who.value.trim(), text: f.text.value.trim(),
          t: `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`})});
      if (!r.ok) throw new Error((await r.text()).trim() || r.status);
      newsPage();
    } catch (e) { msg.textContent = "Couldn't send: " + e.message; }
  });
}

async function section(id) {
  const sec = SECTIONS.find(s => s.id === id);
  if (!sec) return home();
  if (id === "apps") {
    const list = await appList();
    return view(`<div class="crumbs"><a href="#/">Home</a></div><h1>Android apps</h1>
      <p class="note">${android ? "Tap Install, then open the download. The first time, Android asks you to allow installing apps from here: allow it." : "These are for Android phones. iPhones can only install apps from the App Store."}</p>
      ${appRows(list)}`);
  }
  if (id === "files") return filesPage();
  const list = (await ls(sec.dir)) || [];
  const guides = list.filter(e => !e.dir && ext(e.name) === "md");
  const rest = list.filter(e => !(!e.dir && ext(e.name) === "md"));
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>${esc(sec.title)}</h1><p class="note">${esc(sec.about)}</p>
    ${guides.length ? `<div class="actions">${guides.map(g => `<a class="btnlink ghost" href="#/view/${enc(join(sec.dir, g.name))}">${esc(title(g.name))}</a>`).join("")}</div>` : ""}
    ${appsFor(sec, await appList())}
    <h2>${id === "maps" ? "Map files" : id === "knowledge" ? "Libraries" : "Guides and books"}</h2>
    ${rows(sec.dir, rest)}`);
}

async function filesPage() {
  const subs = ((await ls("Files")) || []).filter(e => e.dir);
  const full = [];
  for (const s of subs) {
    const l = ((await ls("Files/" + s.name)) || []).filter(e => e.dir || !isMeta(e.name));
    if (l.length) full.push([s.name, l]);
  }
  const loose = ((await ls("Files")) || []).filter(e => !e.dir);
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>Files</h1><p class="note">Shared by ${esc(info.name)}.</p>
    ${full.map(([n, l]) => `<h2>${esc(n)}</h2>${n === "Apps" ? appRows(l.filter(e => !e.dir && ext(e.name) === "apk").map(e => ({file: e.name, name: title(e.name), size: e.size})), "Files/Apps") : rows("Files/" + n, l)}`).join("")}
    ${loose.length ? `<h2>Other</h2>${rows("Files", loose)}` : ""}
    ${full.length || loose.length ? "" : `<div class="files"><div class="empty">Nothing shared yet.</div></div>`}`);
}

async function browse(path) {
  const list = await ls(path);
  if (!list) return view(crumbs(path) + `<p class="flash">That folder isn't here.</p>`);
  view(crumbs(path) + `<h1>${esc(path.split("/").pop() === NOT_FOR_PHONES ? "Bigger versions (not copied to phones)" : path.split("/").pop())}</h1>` +
    (path.endsWith(NOT_FOR_PHONES) ? `<p class="note">Too big for phones to copy, so these stay on ${esc(info.name)}. Download one if your phone has room.</p>` : "") +
    rows(path, list));
}

async function viewFile(path) {
  const dir = path.split("/").slice(0, -1).join("/"), name = path.split("/").pop(), x = ext(name), k = kindOf(name);
  let body = "";
  if (k === "video") body = `<video class="media" controls playsinline preload="metadata" src="${fileUrl(path)}"></video>`;
  else if (k === "audio") body = `<audio class="media" style="height:54px" controls preload="metadata" src="${fileUrl(path)}"></audio>`;
  else if (k === "image") body = `<div class="viewer"><img src="${fileUrl(path)}" alt="${esc(name)}"></div>`;
  else if (k === "read") {
    try {
      const r = await fetch(fileUrl(path));
      if (!r.ok) throw 0;
      const text = await r.text();
      body = `<div class="doc">${x === "md" ? markdown(text) : `<pre>${esc(text)}</pre>`}</div>`;
    } catch (e) { body = `<p class="flash">Couldn't open that file.</p>`; }
  } else body = `<p class="note">${esc(HINT[x] || "Download it to open it on your phone.")}</p>`;
  view(crumbs(dir) + `<h1 style="overflow-wrap:anywhere">${esc(k === "read" ? title(name) : niceName(dir, name))}</h1>${body}
    <div class="actions">${x === "pdf" ? `<a class="btnlink" href="${fileUrl(path)}">Open the PDF</a>` : ""}
      <a class="btnlink${x === "pdf" ? " ghost" : ""}" href="${fileUrl(path, true)}" download>${x === "apk" && android ? "Install" : "Download"}</a></div>`);
}

function getApp() {
  const back = location.origin + "/readynet/";
  const open = `intent://${location.host}/readynet/#Intent;scheme=readynet;package=uk.plumridge.readynode;S.browser_fallback_url=${encodeURIComponent(back + "#/app")};end`;
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>Get the ReadyNode app</h1>
    <p class="note">ReadyNode copies this whole ReadyNet onto your phone, so you keep it when you move on, and lets your phone share it with others in turn. Android 8 or newer.</p>
    <div class="list">
      <div><b>1. Download and install it</b><p style="margin:6px 0"><a class="btnlink" href="/readynode.apk" download>Download ReadyNode</a></p>
        <span class="meta">Open the download. If Android asks, allow installing apps from your browser.</span></div>
      <div><b>2. Open ReadyNode</b><br><span class="meta">While you're on this Wi-Fi it opens this ReadyNet by itself. Then go to Settings → Sync to copy it to your phone.</span></div>
      <div><b>Already have it?</b><p style="margin:6px 0"><a class="btnlink ghost" href="${esc(open)}">Open this ReadyNet in the app</a></p></div>
    </div>`);
}

/* ------------------------------------------------------------------ search: file names, and the words inside the guides */
async function search(q) {
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>Search</h1>
    <form class="searchbar" onsubmit="location.hash='#/search?q='+encodeURIComponent(this.q.value);return false">
      <input name="q" type="search" value="${esc(q)}" placeholder="Search guides, maps, books, apps…" aria-label="Search" autofocus><button>Search</button></form>
    <div id="hits"></div>`);
  if (!q.trim()) return;
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  let hits = null;
  try { hits = (await getJSON("/api/net/search?q=" + encodeURIComponent(q))).hits || []; } catch (e) { /* an older ReadyNet: names only */ }
  // nice names ("WikiMed" for wikipedia_en_medicine) are only known here, so match those from the manifest too
  if (!manifest) { try { manifest = (await getJSON("/api/net/manifest")).files || []; } catch (e) { manifest = []; } }
  const seen = new Set((hits || []).map(h => h.path));
  const named = manifest.filter(f => !isMeta(f.path) && !seen.has(f.path) &&
    words.every(w => (hits ? niceName("", f.path.split("/").pop()) : f.path + " " + niceName("", f.path.split("/").pop())).toLowerCase().includes(w)))
    .map(f => ({path: f.path, size: f.size}));
  const all = [...(hits || []).filter(h => h.guide), ...named, ...(hits || []).filter(h => !h.guide)].slice(0, 200);
  const box = $("#hits");
  if (!box) return;
  box.innerHTML = all.length ? `<div class="files">` + all.map(f => {
    const name = f.path.split("/").pop(), dir = f.path.split("/").slice(0, -1).join("/");
    const label = f.title || (f.guide ? title(name) : niceName(dir, name));
    return `<a class="f" href="#/view/${enc(f.path)}"><span class="ico">${f.guide ? "read" : esc(ext(name).slice(0, 4))}</span><span class="fname">${esc(label)}<br>` +
      `<span class="meta">${f.snippet ? esc(f.snippet) : esc(dir)}</span></span><span class="meta num">${human(f.size)}</span></a>`;
  }).join("") + `</div>` : `<p class="flash">Nothing matched “${esc(q)}”.</p>`;
}

/* ------------------------------------------------------------------ the guides are Markdown */
function markdown(src) {
  const inline = s => esc(s)
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<b>$1</b>")
    .replace(/(^|[\s(])_([^_]+)_(?=[\s).,;:]|$)/g, "$1<i>$2</i>")
    .replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=[\s).,;:]|$)/g, "$1<i>$2</i>")
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (m, t, u) => /^(https?:|#|\/)/.test(u) ? `<a href="${u}">${t}</a>` : t);
  const out = [], lines = src.replace(/\r/g, "").split("\n");
  let list = null, para = [];
  const flush = () => {
    if (para.length) { out.push(`<p>${inline(para.join(" "))}</p>`); para = []; }
    if (list) { out.push(`</${list}>`); list = null; }
  };
  for (let k = 0; k < lines.length; k++) {
    const l = lines[k];
    let m;
    if (/^\s*$/.test(l)) { flush(); continue; }
    if ((m = l.match(/^(#{1,4})\s+(.*)/))) { flush(); out.push(`<h${m[1].length + 1}>${inline(m[2])}</h${m[1].length + 1}>`); continue; }
    if (/^\|/.test(l)) {
      flush();
      const cells = r => r.replace(/^\||\|$/g, "").split("|").map(c => c.trim());
      const head = cells(l), body = [];
      if (lines[k + 1] && /^\|[\s:|-]+\|?$/.test(lines[k + 1])) k++;
      while (lines[k + 1] && /^\|/.test(lines[k + 1])) body.push(cells(lines[++k]));
      out.push(`<div class="scroll"><table><tr>${head.map(c => `<th>${inline(c)}</th>`).join("")}</tr>${body.map(r => `<tr>${r.map(c => `<td>${inline(c)}</td>`).join("")}</tr>`).join("")}</table></div>`);
      continue;
    }
    if ((m = l.match(/^\s*([-*]|\d+[.)])\s+(.*)/))) {
      const want = /\d/.test(m[1]) ? "ol" : "ul";
      if (para.length) { out.push(`<p>${inline(para.join(" "))}</p>`); para = []; }
      if (list !== want) { if (list) out.push(`</${list}>`); out.push(`<${want}>`); list = want; }
      out.push(`<li>${inline(m[2])}</li>`);
      continue;
    }
    if (/^>\s?/.test(l)) { flush(); out.push(`<p class="flash">${inline(l.replace(/^>\s?/, ""))}</p>`); continue; }
    if (list && /^\s{2,}/.test(l)) { out[out.length - 1] = out[out.length - 1].replace(/<\/li>$/, " " + inline(l.trim()) + "</li>"); continue; }
    if (list) { out.push(`</${list}>`); list = null; }
    para.push(l.trim());
  }
  flush();
  return out.join("\n");
}

/* ------------------------------------------------------------------ help */
function helpPage() {
  const appHelp = inApp && window.ReadyNodeApp && window.ReadyNodeApp.openHelp;
  view(`<div class="crumbs"><a href="#/">Home</a></div><h1>Help</h1>
    ${appHelp ? `<p><button onclick="ReadyNodeApp.openHelp()">Help with the ReadyNode app</button></p>` : ""}
    <div class="doc">
    <h2>What this is</h2>
    <p>ReadyNet is emergency information you can use with no internet or phone signal: offline maps, survival and first-aid
    guides, reference libraries, the free apps that open them, and news. You're reading it from <b>${esc(info.name)}</b>
    over Wi-Fi. Everything stays on this Wi-Fi; nothing goes to the internet.</p>
    <h2>Finding your way round</h2>
    <ul>
      <li><b>News</b>: messages from whoever runs this ReadyNet. Anyone can reply, and everyone here can read replies, so keep them public.</li>
      <li><b>Offline maps</b>, <b>Survival library</b> and <b>Knowledge</b>: download what you need. Maps open in OsmAnd and libraries in Kiwix: install those from Android apps first.</li>
      <li><b>Search</b> finds file names and words inside the guides.</li>
      <li>The guides (water, saving battery, Plus Codes and more) open right here.</li>
    </ul>
    <h2>Broadcasts</h2>
    <p>Short messages that travel from phone to phone between ReadyNode apps. Tap <b>Mark read</b> to clear one from the home page;
    it stays under <b>Read</b> on the News page.</p>
    <h2>Take it with you</h2>
    <p>${android ? `Android: <a href="#/app">get the ReadyNode app</a>. It copies ReadyNet onto your phone, so you have it anywhere, and lets you share it on to others the same way.`
      : `On an iPhone you can use any ReadyNet like this one, but you can't carry a copy. Install the apps you'd need (offline maps, Kiwix) from the App Store before you need them.`}</p>
    <h2>If something won't load</h2>
    <ul>
      <li>Stay on this Wi-Fi. If the phone says "no internet", choose to stay connected anyway, or turn mobile data off for a moment.</li>
      <li>Very old phones get a simpler version of these pages automatically.</li>
      <li>In an emergency, call 999. With a SIM in, the call goes through on any mobile network with signal, not just yours.</li>
    </ul>
    </div>`);
}

/* ------------------------------------------------------------------ router */
async function route() {
  const h = location.hash.slice(1) || "/";
  const cut = h.indexOf("?");
  const segs = (cut < 0 ? h : h.slice(0, cut)).split("/").map(s => { try { return decodeURIComponent(s); } catch (e) { return s; } });
  const q = new URLSearchParams(cut < 0 ? "" : h.slice(cut + 1));
  const rest = segs.slice(2).filter(Boolean).join("/");
  document.querySelectorAll("#nav a").forEach(a => a.classList.toggle("on", a.dataset.nav === (segs[1] || "")));
  try {
    switch (segs[1] || "") {
      case "": return await home();
      case "news": return await newsPage();
      case "s": return await section(rest);
      case "browse": return await browse(rest);
      case "view": return await viewFile(rest);
      case "search": return await search(q.get("q") || "");
      case "app": return getApp();
      case "help": return helpPage();
      default: return await home();
    }
  } catch (e) {
    view(`<p class="flash">Something went wrong loading that page (${esc(e.message || e)}). Pull down to refresh.</p>`);
  }
}

window.readynetStarted = true;     // tells the old-browser check in index.html that this script runs here

(async () => {
  try { info = {...info, ...(await getJSON("/api/net/info"))}; } catch (e) { /* defaults */ }
  document.title = "ReadyNet · " + info.name;
  const pw = info.power;
  $("#brand").innerHTML = `<span>Ready</span>Net<small>${esc(info.name)}${pw ? ` · battery ${pw.pct}%${pw.charging ? " (charging)" : ""}` : ""}</small>`;
  if (info.home && /^\/[^\/]/.test(info.home.url || "") && info.home.title) {   // a server with its own home page links back to it
    $("#nav").insertAdjacentHTML("afterbegin", `<a href="${esc(info.home.url)}">${esc(info.home.title)}</a>`);
  }
  if (info.kind !== "node") {
    $("#foot").textContent = `Shared from ${info.name}. Nothing here needs the internet. Call 999 if someone is in immediate danger.`;
  } else if (!inApp) {
    $("#foot").textContent = `Shared from ${info.name}, a phone running ReadyNode. Nothing here needs the internet. Call 999 if someone is in immediate danger.`;
  }
  window.addEventListener("hashchange", route);
  route();
})();
