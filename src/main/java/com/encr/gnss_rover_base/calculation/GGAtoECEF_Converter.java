/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.calculation;

/**
 *
 * @author Sandeep K
 */
import java.text.DecimalFormat;

/**
 *
 * @author Sandeep K
 */
public class GGAtoECEF_Converter {

    private static final int MIN_SATELLITES = 15;
    private static final double MAX_HDOP = 2.0;

    // WGS84 constants
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = F * (2 - F);

    public static String convertGGAtoECEF(String line) {
        try {
            if (line == null) {
                return null;
            }

            if (!line.startsWith("$GPGGA")) {
                return null;
            }

            String[] p = line.split(",");

            if (isRTKFixed(p)) {

                double lat = safeDeg(p[2], p[3]);
                double lon = safeDeg(p[4], p[5]);
                int sats = safeInt(p[7]);
                double hdop = safeDouble(p[8]);
                double alt = safeDouble(p[9]);

                boolean good = true;

                if (sats < MIN_SATELLITES) {
                    good = false;
                }
                if (hdop > MAX_HDOP) {
                    good = false;
                }
                if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isNaN(alt) || (Math.abs(lat) < 1e-12 && Math.abs(lon) < 1e-12)) {
                    good = false;
                }
                if (!good) {
                    return null;
                }
                double[] ecef = geodeticToECEF(lat, lon, alt); // lat/lon in degrees
                return "" + ecef[0] + "," + ecef[1] + "," + ecef[2] + "," + sats;
            } else {
                return null;
            }

        } catch (Exception e) {
        }
        return null;
    }

    private static boolean isRTKFixed(String[] parts) {
        try {
            if (parts == null || parts.length < 15) {
                return false; // Incomplete or null input
            }

            if (!parts[0].startsWith("$GPGGA")) {
                return false; // Not a GGA sentence
            }

            // Ensure fix quality is 4 (RTK Fixed)
            if ("4".equals(parts[6])) {
                return true;
            }

        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private static double safeDeg(String val, String hemi) {
        try {
            if (val == null || val.isEmpty()) {
                return Double.NaN;
            }
            double ddmm = Double.parseDouble(val);
            double deg = Math.floor(ddmm / 100.0);
            double minutes = ddmm - deg * 100.0;
            double dec = deg + minutes / 60.0;
            if ("S".equalsIgnoreCase(hemi) || "W".equalsIgnoreCase(hemi)) {
                dec = -dec;
            }
            return dec;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static double safeDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
    // geodetic (degrees) -> ECEF (meters)

    private static double[] geodeticToECEF(double latDeg, double lonDeg, double h) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);

        double N = A / Math.sqrt(1.0 - E2 * sinLat * sinLat);
        double x = (N + h) * cosLat * cosLon;
        double y = (N + h) * cosLat * sinLon;
        double z = (N * (1 - E2) + h) * sinLat;
        return new double[]{x, y, z};
    }

    // convert back to geodetic (lat rad, lon rad, h m)
    public static String[] getEcefToGeodetic(double x, double y, double z) {
        double[] geod = ecefToGeodetic(x, y, z);
        double latDeg = Math.toDegrees(geod[0]);
        double lonDeg = Math.toDegrees(geod[1]);
        double h = geod[2];

        DecimalFormat dfLatLon = new DecimalFormat("0.0000000");
        DecimalFormat dfH = new DecimalFormat("0.000");

        System.out.println("\n===== FINAL AVERAGED POSITION =====");
        System.out.println("Lat: " + dfLatLon.format(latDeg));
        System.out.println("Lon: " + dfLatLon.format(lonDeg));
        System.out.println("Alt: " + dfH.format(h));
        System.out.println("\nFIX POSITION " + dfLatLon.format(latDeg) + " " + dfLatLon.format(lonDeg) + " " + dfH.format(h));

        String[] result = new String[]{
            dfLatLon.format(latDeg),
            dfLatLon.format(lonDeg),
            dfH.format(h)
        };
        return result;
    }
    // ECEF -> geodetic (latRad, lonRad, h) iterative (stable)

    private static double[] ecefToGeodetic(double x, double y, double z) {

        double lon = Math.atan2(y, x);
        double p = Math.sqrt(x * x + y * y);
        if (p < 1e-12) {
            double lat = (z >= 0) ? Math.PI / 2.0 : -Math.PI / 2.0;
            double h = Math.abs(z) - A * (1 - E2);
            return new double[]{lat, lon, h};
        }

        double lat = Math.atan2(z, p * (1 - E2)); // initial guess
        double last;
        int iter = 0;
        do {
            last = lat;
            double sinLat = Math.sin(lat);
            double N = A / Math.sqrt(1 - E2 * sinLat * sinLat);
            double h = p / Math.cos(lat) - N;
            lat = Math.atan2(z, p * (1 - E2 * (N / (N + h))));
            iter++;
        } while (Math.abs(lat - last) > 1e-12 && iter < 100);

        double sinLat = Math.sin(lat);
        double N = A / Math.sqrt(1 - E2 * sinLat * sinLat);
        double h = p / Math.cos(lat) - N;

        return new double[]{lat, lon, h};
    }
}
