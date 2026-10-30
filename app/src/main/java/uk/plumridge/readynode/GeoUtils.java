package uk.plumridge.readynode;

import java.util.Locale;

/**
 * Offline position formats, worked out on the phone with no internet: Plus Codes (Open Location Code) and Ordnance
 * Survey grid references. Pure Java, tested on a computer.
 *
 * OS grid: GPS (WGS84) is moved to the OS's own datum (OSGB36) with the standard 7-parameter Helmert transformation,
 * then projected (Transverse Mercator on the Airy 1830 ellipsoid). The Helmert step is good to about 5 m across Great
 * Britain, which is plenty for telling someone where you are.
 */
public final class GeoUtils {
    private GeoUtils() {}

    // ------------------------------------------------------------------ Plus Codes
    private static final String ALPHABET = "23456789CFGHJMPQRVWX";
    private static final double[] RES = {20.0, 1.0, 0.05, 0.0025, 0.000125};

    /** A full 10-character Plus Code, e.g. 9C3VCCX9+JF. */
    public static String plusCode(double lat, double lon) {
        lat = Math.min(Math.max(lat, -90), 90);
        if (lat == 90) lat -= 0.000125 / 2;                       // the north pole belongs to the cell below it
        lon = ((lon + 180) % 360 + 360) % 360 - 180;
        // whole numbers of the smallest cell, so rounding can't nudge a point into the next cell
        long la = (long) Math.floor((lat + 90) * 8000 + 1e-9), lo = (long) Math.floor((lon + 180) * 8000 + 1e-9);
        StringBuilder b = new StringBuilder();
        long[] div = {160000, 8000, 400, 20, 1};
        for (int i = 0; i < 5; i++) {
            b.append(ALPHABET.charAt((int) ((la / div[i]) % 20))).append(ALPHABET.charAt((int) ((lo / div[i]) % 20)));
            if (i == 3) b.append('+');
        }
        return b.toString();
    }

    /**
     * The middle of a Plus Code's square, as {lat, lon}; null if it isn't one. Short codes ("CCX9+JF Bristol" or just
     * "CCX9+JF") need a nearby reference position (e.g. your own), and the nearest match to it is chosen.
     */
    public static double[] decodePlus(String text, Double refLat, Double refLon) {
        if (text == null) return null;
        String code = text.trim().toUpperCase(Locale.ROOT).split("\\s+")[0];
        int plus = code.indexOf('+');
        if (plus < 0 || code.indexOf('+', plus + 1) >= 0) return null;
        String digits = code.replace("+", "");
        for (char c : digits.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        if (plus % 2 != 0 || plus > 8 || digits.length() - plus < 2) return null;
        if (plus < 8) {                                          // a short code: borrow the start from the reference
            if (refLat == null || refLon == null) return null;
            String ref = plusCode(refLat, refLon).replace("+", "");
            int missing = 8 - plus;
            digits = ref.substring(0, missing) + digits;
            double[] c = centre(digits);
            if (c == null) return null;
            // the borrowed start might be one square off from where the code really is: pick the nearest candidate
            double span = RES[missing / 2 - 1];
            double[] best = c;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    double[] cand = {c[0] + dy * span, c[1] + dx * span};
                    if (dist(cand, refLat, refLon) < dist(best, refLat, refLon)) best = cand;
                }
            return best;
        }
        return centre(digits);
    }

    private static double dist(double[] p, double lat, double lon) {
        double dl = Math.abs(p[1] - lon);
        if (dl > 180) dl = 360 - dl;
        return Math.hypot(p[0] - lat, dl);
    }

    private static double[] centre(String digits) {
        if (digits.length() < 10 || digits.length() % 2 != 0) {
            if (digits.length() < 10) return null;
        }
        double lat = -90, lon = -180;
        for (int i = 0; i < 5 && 2 * i + 1 < digits.length(); i++) {
            lat += ALPHABET.indexOf(digits.charAt(2 * i)) * RES[i];
            lon += ALPHABET.indexOf(digits.charAt(2 * i + 1)) * RES[i];
        }
        int pairs = Math.min(5, digits.length() / 2);
        return new double[]{lat + RES[pairs - 1] / 2, lon + RES[pairs - 1] / 2};
    }

    // ------------------------------------------------------------------ OS National Grid
    private static final double AIRY_A = 6377563.396, AIRY_B = 6356256.909;
    private static final double WGS_A = 6378137.0, WGS_B = 6356752.314245;
    private static final double F0 = 0.9996012717, LAT0 = Math.toRadians(49), LON0 = Math.toRadians(-2), N0 = -100000, E0 = 400000;
    // WGS84 -> OSGB36 (metres, ppm, arc-seconds)
    private static final double TX = -446.448, TY = 125.157, TZ = -542.060, S = 20.4894, RX = -0.1502, RY = -0.2470, RZ = -0.8421;

    /** {easting, northing} in metres on the OS grid, or null outside the grid's area. */
    public static double[] osgb(double lat, double lon) {
        double[] p = helmert(lat, lon, WGS_A, WGS_B, AIRY_A, AIRY_B, 1);
        double[] en = project(p[0], p[1]);
        if (en[0] < 0 || en[0] >= 700000 || en[1] < 0 || en[1] >= 1300000) return null;
        return en;
    }

    /** A grid reference like "ST 59612 72345" (digits per axis: 3 = 100 m, 4 = 10 m, 5 = 1 m); null outside GB. */
    public static String gridRef(double lat, double lon, int digits) {
        double[] en = osgb(lat, lon);
        return en == null ? null : gridRefEN(en[0], en[1], digits);
    }

    static String gridRefEN(double e, double n, int digits) {
        int e100k = (int) Math.floor(e / 100000), n100k = (int) Math.floor(n / 100000);
        int l1 = (19 - n100k) - (19 - n100k) % 5 + (e100k + 10) / 5;
        int l2 = (19 - n100k) * 5 % 25 + e100k % 5;
        if (l1 > 7) l1++;                                        // the grid letters skip I
        if (l2 > 7) l2++;
        String letters = "" + (char) ('A' + l1) + (char) ('A' + l2);
        long div = (long) Math.pow(10, 5 - digits);
        long ee = (long) Math.floor((e % 100000) / div), nn = (long) Math.floor((n % 100000) / div);
        return String.format(Locale.ROOT, "%s %0" + digits + "d %0" + digits + "d", letters, ee, nn);
    }

    /** {lat, lon} (WGS84) from a grid reference like "ST 596 724", "ST5960072400" or "ST 59612 72345"; null if not one. */
    public static double[] fromGridRef(String text) {
        if (text == null) return null;
        String t = text.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s,]", "");
        if (!t.matches("[A-HJ-Z]{2}(\\d\\d){1,5}")) return null;
        int l1 = t.charAt(0) - 'A', l2 = t.charAt(1) - 'A';
        if (l1 > 7) l1--;
        if (l2 > 7) l2--;
        double e100k = ((l1 - 2) % 5) * 5 + (l2 % 5), n100k = (19 - Math.floor(l1 / 5.0) * 5) - Math.floor(l2 / 5.0);
        if (e100k < 0 || e100k > 6 || n100k < 0 || n100k > 12) return null;
        String d = t.substring(2);
        int half = d.length() / 2;
        double scale = Math.pow(10, 5 - half);
        double e = e100k * 100000 + (Long.parseLong(d.substring(0, half)) + 0.5) * scale;
        double n = n100k * 100000 + (Long.parseLong(d.substring(half)) + 0.5) * scale;
        double[] ll = unproject(e, n);
        return helmert(ll[0], ll[1], AIRY_A, AIRY_B, WGS_A, WGS_B, -1);
    }

    /** Move a position from one datum to the other (dir 1: WGS84 -> OSGB36, -1: back). Returns {lat, lon} in degrees. */
    private static double[] helmert(double latD, double lonD, double a1, double b1, double a2, double b2, int dir) {
        double lat = Math.toRadians(latD), lon = Math.toRadians(lonD);
        double e2 = 1 - (b1 * b1) / (a1 * a1);
        double nu = a1 / Math.sqrt(1 - e2 * Math.sin(lat) * Math.sin(lat));
        double x = nu * Math.cos(lat) * Math.cos(lon), y = nu * Math.cos(lat) * Math.sin(lon), z = (1 - e2) * nu * Math.sin(lat);
        double s = dir * S / 1e6, rx = Math.toRadians(dir * RX / 3600), ry = Math.toRadians(dir * RY / 3600), rz = Math.toRadians(dir * RZ / 3600);
        double x2 = dir * TX + (1 + s) * x - rz * y + ry * z;
        double y2 = dir * TY + rz * x + (1 + s) * y - rx * z;
        double z2 = dir * TZ - ry * x + rx * y + (1 + s) * z;
        double e2b = 1 - (b2 * b2) / (a2 * a2), p = Math.hypot(x2, y2);
        double phi = Math.atan2(z2, p * (1 - e2b)), prev = 2 * Math.PI;
        while (Math.abs(phi - prev) > 1e-12) {
            double nu2 = a2 / Math.sqrt(1 - e2b * Math.sin(phi) * Math.sin(phi));
            prev = phi;
            phi = Math.atan2(z2 + e2b * nu2 * Math.sin(phi), p);
        }
        return new double[]{Math.toDegrees(phi), Math.toDegrees(Math.atan2(y2, x2))};
    }

    private static double meridian(double phi) {
        double n = (AIRY_A - AIRY_B) / (AIRY_A + AIRY_B), n2 = n * n, n3 = n2 * n;
        double dp = phi - LAT0, sp = phi + LAT0;
        double ma = (1 + n + 1.25 * n2 + 1.25 * n3) * dp;
        double mb = (3 * n + 3 * n2 + 2.625 * n3) * Math.sin(dp) * Math.cos(sp);
        double mc = (1.875 * n2 + 1.875 * n3) * Math.sin(2 * dp) * Math.cos(2 * sp);
        double md = (35.0 / 24) * n3 * Math.sin(3 * dp) * Math.cos(3 * sp);
        return AIRY_B * F0 * (ma - mb + mc - md);
    }

    /** OSGB36 lat/lon (degrees) to {easting, northing}: the Ordnance Survey's own formulae. */
    static double[] project(double latD, double lonD) {
        double phi = Math.toRadians(latD), lam = Math.toRadians(lonD);
        double e2 = 1 - (AIRY_B * AIRY_B) / (AIRY_A * AIRY_A);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double nu = AIRY_A * F0 / Math.sqrt(1 - e2 * sin * sin);
        double rho = AIRY_A * F0 * (1 - e2) / Math.pow(1 - e2 * sin * sin, 1.5);
        double eta2 = nu / rho - 1, m = meridian(phi);
        double i = m + N0, ii = nu / 2 * sin * cos, iii = nu / 24 * sin * Math.pow(cos, 3) * (5 - tan * tan + 9 * eta2);
        double iiia = nu / 720 * sin * Math.pow(cos, 5) * (61 - 58 * tan * tan + Math.pow(tan, 4));
        double iv = nu * cos, v = nu / 6 * Math.pow(cos, 3) * (nu / rho - tan * tan);
        double vi = nu / 120 * Math.pow(cos, 5) * (5 - 18 * tan * tan + Math.pow(tan, 4) + 14 * eta2 - 58 * tan * tan * eta2);
        double dl = lam - LON0;
        double n = i + ii * dl * dl + iii * Math.pow(dl, 4) + iiia * Math.pow(dl, 6);
        double e = E0 + iv * dl + v * Math.pow(dl, 3) + vi * Math.pow(dl, 5);
        return new double[]{e, n};
    }

    /** {easting, northing} to OSGB36 lat/lon (degrees). */
    static double[] unproject(double e, double n) {
        double e2 = 1 - (AIRY_B * AIRY_B) / (AIRY_A * AIRY_A);
        double phi = LAT0, m = 0;
        do {
            phi = (n - N0 - m) / (AIRY_A * F0) + phi;
            m = meridian(phi);
        } while (Math.abs(n - N0 - m) >= 0.00001);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double nu = AIRY_A * F0 / Math.sqrt(1 - e2 * sin * sin);
        double rho = AIRY_A * F0 * (1 - e2) / Math.pow(1 - e2 * sin * sin, 1.5);
        double eta2 = nu / rho - 1, sec = 1 / cos;
        double vii = tan / (2 * rho * nu);
        double viii = tan / (24 * rho * Math.pow(nu, 3)) * (5 + 3 * tan * tan + eta2 - 9 * tan * tan * eta2);
        double ix = tan / (720 * rho * Math.pow(nu, 5)) * (61 + 90 * tan * tan + 45 * Math.pow(tan, 4));
        double x = sec / nu, xi = sec / (6 * Math.pow(nu, 3)) * (nu / rho + 2 * tan * tan);
        double xii = sec / (120 * Math.pow(nu, 5)) * (5 + 28 * tan * tan + 24 * Math.pow(tan, 4));
        double xiia = sec / (5040 * Math.pow(nu, 7)) * (61 + 662 * tan * tan + 1320 * Math.pow(tan, 4) + 720 * Math.pow(tan, 6));
        double de = e - E0;
        double lat = phi - vii * de * de + viii * Math.pow(de, 4) - ix * Math.pow(de, 6);
        double lon = LON0 + x * de - xi * Math.pow(de, 3) + xii * Math.pow(de, 5) - xiia * Math.pow(de, 7);
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon)};
    }

    // ------------------------------------------------------------------ anything someone might type
    /**
     * Understands a Plus Code, an OS grid reference or "lat, lon". Returns {lat, lon}, or null. The reference position
     * (yours, if known) is only used for short Plus Codes.
     */
    public static double[] parse(String text, Double refLat, Double refLon) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[, ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$").matcher(t);
        if (m.find()) {
            double lat = Double.parseDouble(m.group(1)), lon = Double.parseDouble(m.group(2));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return new double[]{lat, lon};
        }
        if (t.contains("+")) return decodePlus(t, refLat, refLon);
        return fromGridRef(t);
    }
}
