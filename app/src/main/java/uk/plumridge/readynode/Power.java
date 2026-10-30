package uk.plumridge.readynode;

/**
 * Battery readings and the "hours left" estimate on Save battery. The app notes the battery level now and then (when
 * it's opened, every few minutes while sharing, every minute while Save battery is open), and estimates from how fast
 * it has fallen since it was last charged. Pure Java, so it's tested on a computer.
 *
 * The log is "seconds:percent" pairs separated by spaces, oldest first.
 */
public final class Power {
    private Power() {}

    static final int MAX_SAMPLES = 300;
    static final long MIN_SPAN = 20 * 60, WINDOW = 12 * 3600;
    static final int MIN_DROP = 3;

    /** Add a reading. Charging (or a level that has gone up: it was charged while we weren't looking) starts afresh. */
    static String add(String log, long nowSec, int pct, boolean charging) {
        if (charging || pct < 0) return "";
        long[][] s = parse(log);
        if (s.length > 0 && pct > s[s.length - 1][1] + 1) s = new long[0][];
        if (s.length > 0 && nowSec - s[s.length - 1][0] < 50) return log;     // at most one a minute
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, s.length - MAX_SAMPLES + 1); i < s.length; i++) b.append(s[i][0]).append(':').append(s[i][1]).append(' ');
        return b.append(nowSec).append(':').append(pct).toString();
    }

    /** {hours left, % per hour, since (seconds), from %, to %}, or null if there isn't enough to go on yet. */
    static double[] estimate(String log) {
        long[][] s = parse(log);
        if (s.length < 2) return null;
        long[] last = s[s.length - 1];
        long[] first = null;
        for (long[] x : s) if (x[0] >= last[0] - WINDOW) { first = x; break; }   // the last 12 hours: today's use
        if (first == null) return null;
        long span = last[0] - first[0];
        long drop = first[1] - last[1];
        if (span < MIN_SPAN || drop < MIN_DROP) return null;
        double rate = drop * 3600.0 / span;
        return new double[]{last[1] / rate, rate, first[0], first[1], last[1]};
    }

    static long[][] parse(String log) {
        if (log == null || log.trim().isEmpty()) return new long[0][];
        String[] parts = log.trim().split("\\s+");
        long[][] out = new long[parts.length][];
        int n = 0;
        for (String p : parts) {
            int c = p.indexOf(':');
            if (c < 0) continue;
            try { out[n++] = new long[]{Long.parseLong(p.substring(0, c)), Long.parseLong(p.substring(c + 1))}; } catch (NumberFormatException ignored) { }
        }
        long[][] r = new long[n][];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /** "About 7 hours", "About 40 minutes", "More than 2 days". */
    static String hours(double h) {
        if (h > 48) return "More than 2 days";
        if (h < 1) return "About " + Math.max(5, Math.round(h * 60 / 5) * 5) + " minutes";
        long r = Math.round(h);
        return "About " + r + (r == 1 ? " hour" : " hours");
    }
}
