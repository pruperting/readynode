package uk.plumridge.readynode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Search: file names everywhere in this phone's ReadyNet, and the words inside the guides (.md and .txt), the same as
 * every ReadyNet server's /api/net/search. Pure Java, so it's tested on a computer.
 */
public final class Search {
    private Search() {}

    static final int MAX_HITS = 60, SIDE = 70;
    static final long GUIDE_MAX = 1 << 20;

    static final class Hit {
        String path, title = "", snippet = "";
        long size;
        boolean guide;
        int score;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject().put("path", path).put("size", size).put("guide", guide).put("title", title);
            if (!snippet.isEmpty()) o.put("snippet", snippet);
            return o;
        }
    }

    static List<String> words(String q) {
        List<String> out = new ArrayList<>();
        if (q == null) return out;
        for (String w : q.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (out.size() == 8) break;
            out.add(w);
        }
        return out;
    }

    public static List<Hit> find(File root, String q) {
        List<Hit> hits = new ArrayList<>();
        List<String> words = words(q);
        if (words.isEmpty() || q.length() > 200) return hits;
        for (String sec : NodeServer.SECTIONS) walk(new File(root, sec), sec, words, hits);
        hits.sort((a, b) -> a.score != b.score ? b.score - a.score
                : a.guide != b.guide ? (a.guide ? -1 : 1) : a.path.toLowerCase(Locale.ROOT).compareTo(b.path.toLowerCase(Locale.ROOT)));
        return hits.size() > MAX_HITS ? new ArrayList<>(hits.subList(0, MAX_HITS)) : hits;
    }

    public static JSONObject json(File root, String q) throws Exception {
        JSONArray a = new JSONArray();
        for (Hit h : find(root, q)) a.put(h.json());
        return new JSONObject().put("hits", a);
    }

    private static void walk(File dir, String rel, List<String> words, List<Hit> hits) {
        File[] all = dir.listFiles();
        if (all == null) return;
        Arrays.sort(all, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : all) {
            String n = f.getName(), low = n.toLowerCase(Locale.ROOT), p = rel + "/" + n;
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) { walk(f, p, words, hits); continue; }
            if (low.endsWith(".part") || low.endsWith(".json")) continue;
            Hit h = new Hit();
            h.path = p;
            h.size = f.length();
            if (all(p.replace('_', ' ').toLowerCase(Locale.ROOT), words))
                h.score = all(n.replace('_', ' ').toLowerCase(Locale.ROOT), words) ? 3 : 1;
            if (low.endsWith(".md") || low.endsWith(".txt")) {
                h.guide = true;
                if (f.length() <= GUIDE_MAX) try {
                    String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    h.title = title(text, n);
                    if (h.score == 0 && all(text.toLowerCase(Locale.ROOT), words)) h.score = 2;
                    if (h.score > 0) h.snippet = snippet(text, words);
                } catch (Exception ignored) { }
            }
            if (h.score > 0) hits.add(h);
        }
    }

    static boolean all(String hay, List<String> words) {
        for (String w : words) if (!hay.contains(w)) return false;
        return true;
    }

    static String title(String text, String name) {
        String[] lines = text.split("\n", 30);
        for (String l : lines) {
            if (!l.startsWith("#")) continue;
            String t = l.replaceAll("^#+", "").trim();
            if (!t.isEmpty()) return t;
        }
        return name.replaceAll("\\.(md|txt)$", "");
    }

    static String snippet(String text, List<String> words) {
        String plain = text.replaceAll("[#*_`>|\\[\\]]+", " ").trim().replaceAll("\\s+", " ");
        StringBuilder lowB = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) lowB.append(Character.toLowerCase(plain.charAt(i)));   // same positions
        String low = lowB.toString();
        int at = low.indexOf(String.join(" ", words));               // the words together, as typed, if they're there
        if (at < 0) for (String w : words) {
            int i = low.indexOf(w);
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        if (at < 0) return "";
        int[] cps = plain.codePoints().toArray();
        int ca = plain.codePointCount(0, at), start = ca - SIDE, end = ca + SIDE * 2;
        String pre = "…", post = "…";
        if (start <= 0) { start = 0; pre = ""; }
        if (end >= cps.length) { end = cps.length; post = ""; }
        return pre + new String(cps, start, end - start).trim() + post;
    }
}
