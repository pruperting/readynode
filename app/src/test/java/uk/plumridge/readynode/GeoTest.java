package uk.plumridge.readynode;

import static org.junit.Assert.*;

import org.junit.Test;

/** Plus Codes and OS grid references, checked against published examples and round trips. */
public class GeoTest {
    @Test
    public void projectionMatchesTheOrdnanceSurveysWorkedExample() {
        // OS "A guide to coordinate systems in Great Britain", worked example (OSGB36 52°39'27.2531"N 1°43'4.5177"E)
        double lat = 52 + 39 / 60.0 + 27.2531 / 3600, lon = 1 + 43 / 60.0 + 4.5177 / 3600;
        double[] en = GeoUtils.project(lat, lon);
        assertEquals(651409.903, en[0], 0.01);
        assertEquals(313177.270, en[1], 0.01);
        double[] back = GeoUtils.unproject(651409.903, 313177.270);
        assertEquals(lat, back[0], 1e-7);
        assertEquals(lon, back[1], 1e-7);
        assertEquals("TG 51409 13177", GeoUtils.gridRefEN(651409.903, 313177.270, 5));
        assertEquals("TG 514 131", GeoUtils.gridRefEN(651409.903, 313177.270, 3));
    }

    @Test
    public void bristolTempleMeads() {
        double lat = 51.4491, lon = -2.5813;
        String ref = GeoUtils.gridRef(lat, lon, 3);
        System.out.println("Temple Meads: " + ref + "  " + GeoUtils.gridRef(lat, lon, 5) + "  " + GeoUtils.plusCode(lat, lon));
        assertTrue(ref, ref.startsWith("ST 59"));
        double[] en = GeoUtils.osgb(lat, lon);
        assertEquals(359600, en[0], 1500);                   // the station is around ST 596 724
        assertEquals(172400, en[1], 1500);
        assertEquals("9C3VCCX9+JF", GeoUtils.plusCode(lat, lon));
    }

    @Test
    public void roundTrips() {
        double[][] places = {{51.4491, -2.5813}, {51.5007, -0.1246}, {55.9533, -3.1883}, {53.4084, -2.9916}, {50.0657, -5.7132}, {58.6373, -3.0689}};
        for (double[] p : places) {
            double[] g = GeoUtils.fromGridRef(GeoUtils.gridRef(p[0], p[1], 5));
            assertEquals("lat via grid " + p[0], p[0], g[0], 0.00005);   // within a few metres
            assertEquals("lon via grid " + p[1], p[1], g[1], 0.00008);
            double[] c = GeoUtils.decodePlus(GeoUtils.plusCode(p[0], p[1]), null, null);
            assertEquals(p[0], c[0], 0.000125);
            assertEquals(p[1], c[1], 0.000125);
        }
    }

    @Test
    public void shortPlusCodesUseANearbyReference() {
        double[] c = GeoUtils.decodePlus("CCX9+JF Bristol", 51.50, -2.49);   // reference: a few miles away
        assertNotNull(c);
        assertEquals(51.4491, c[0], 0.0002);
        assertEquals(-2.5813, c[1], 0.0002);
        assertNull("a short code needs a reference", GeoUtils.decodePlus("CCX9+JF", null, null));
    }

    @Test
    public void parsesWhatPeopleType() {
        assertArrayEquals(new double[]{51.4491, -2.5813}, GeoUtils.parse("51.4491, -2.5813", null, null), 1e-9);
        assertNotNull(GeoUtils.parse("9c3vccx9+jf", null, null));
        double[] g = GeoUtils.parse("ST 596 724", null, null);
        assertEquals(51.449, g[0], 0.01);
        assertEquals(-2.58, g[1], 0.01);
        assertNull(GeoUtils.parse("hello", null, null));
        assertNull(GeoUtils.parse("9C3VCCX9+J0", null, null));          // 0 is never in a Plus Code
        assertNull("outside GB", GeoUtils.gridRef(48.85, 2.35, 3));
    }
}
