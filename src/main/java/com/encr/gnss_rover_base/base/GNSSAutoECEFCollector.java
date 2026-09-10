/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.base;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.MainController;
import com.encr.gnss_rover_base.avg.BaseAverageManager;
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.List;

/**
 * GNSSAutoECEFCollector
 *
 * - Sends GNSS config commands automatically: unlogall fix auto log com1 gpgga
 * ontime 1
 *
 * - Collects GPGGA lines (either N seconds or N valid samples) - Filters by fix
 * quality, satellites, HDOP - Converts accepted points to ECEF (meters) -
 * Removes outliers using MAD and keeps points within 3-sigma (relaxes to
 * 4-sigma if needed) - Averages in ECEF, converts back to geodetic
 * lat/lon/height - Prints FIX POSITION command
 *
 * Usage: java GNSSAutoECEFCollector -> run for default 1 hour java
 * GNSSAutoECEFCollector 600 -> run for 600 seconds (10 minutes) java
 * GNSSAutoECEFCollector samples 500 -> collect 500 valid samples (filtered)
 */
public class GNSSAutoECEFCollector {

    private static BaseAverageManager baseManager;
    private static Path jarDir;

    private static final int MIN_FIX_QUALITY = 0;
    private static final int MIN_SATELLITES = 15;
    private static final double MAX_HDOP = 2.0;

    // WGS84 constants
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = F * (2 - F);

    boolean isIgnoredSampleCompleted = false;
    int ingnoredSampleCount = 0;

    public String[] startGNSSAutoECEFCollector(Integer requiredValidSamples, String IP, int PORT) {
        isIgnoredSampleCompleted = false;
        ingnoredSampleCount = 0;
        jarDir = MainController.getJarDirectory();
        baseManager = new BaseAverageManager(jarDir, requiredValidSamples);

        int accepted = 0, rejected = 0;

        try (Socket sock = new Socket(IP, PORT); BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream())); OutputStream os = sock.getOutputStream()) {

            sock.setSoTimeout(0); // blocking read

            // Send GNSS configuration commands
            sendCmd(os, "freset");
            Thread.sleep(10_000);
            sendCmd(os, "unlogall");
            Thread.sleep(300);
            sendCmd(os, "fix auto");
            Thread.sleep(300);
            sendCmd(os, "log com1 gpgga ontime 1");
            Thread.sleep(300);
            sendCmd(os, "saveconfig");
            Thread.sleep(300);
            sendCmd(os, "\r\n\r\n");
            Thread.sleep(10_000);

            System.out.println("Sent GNSS config commands. Waiting for GPGGA...");

            // Read loop
            while (Variable.base) {

                String line = br.readLine();
                com.encr.gnss_rover_base.tool.Tool.dbg("Survey", "GGA: " + line);
                if (line == null) {
                    System.out.println("Stream closed by device.");
                    break;
                }
                if (!line.startsWith("$GPGGA")) {
                    continue;
                }

                String[] p = line.split(",");
                if (p.length < 10) {
                    continue; // incomplete
                }

                double lat = safeDeg(p[2], p[3]);
                double lon = safeDeg(p[4], p[5]);
                int fixQ = safeInt(p[6]);
                int sats = safeInt(p[7]);
                double hdop = safeDouble(p[8]);
                double alt = safeDouble(p[9]);

                boolean good = true;
                if (fixQ == MIN_FIX_QUALITY) {
                    good = false;
                }
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
                    rejected++;
                    continue;
                }

                ingnoredSampleCount++;

                if (!isIgnoredSampleCompleted) {
                    if (ingnoredSampleCount > Constant.IGNORED_SAMPLE) {
                        isIgnoredSampleCompleted = true;
                    }
                    continue;
                }

                double[] ecef = geodeticToECEF(lat, lon, alt); // lat/lon in degrees

                if (baseManager.addSample(ecef[0], ecef[1], ecef[2])) {
                    System.out.println("Required valid samples reached.");
                    break;
                }

                accepted++;

                if (accepted % 100 == 0) {
                    System.out.println("Accepted: " + accepted + "  Rejected: " + rejected);
                }
            }
        } catch (Exception e) {
            System.err.println("I/O error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        System.out.println("Collection finished. Accepted: " + accepted + ", Rejected: " + rejected);

        double[] baseline = baseManager.getAverage();

        if (baseline == null) {
            return null;
        }

        for (double value : baseline) {
            if (Double.isNaN(value)) {
                return null;
            }
        }

        // convert back to geodetic (lat rad, lon rad, h m)
        double[] geod = ecefToGeodetic(baseline[0], baseline[1], baseline[2]);
        double latDeg = Math.toDegrees(geod[0]);
        double lonDeg = Math.toDegrees(geod[1]);
        double h = geod[2];

        DecimalFormat dfLatLon = new DecimalFormat("0.00000000");
        DecimalFormat dfH = new DecimalFormat("0.0000");

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

    // ---------------- helpers ----------------
    private static void sendCmd(OutputStream os, String cmd) throws Exception {
        os.write((cmd + "\r\n").getBytes("US-ASCII"));
        os.flush();
        System.out.println("Sent: " + cmd);
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

    // MAD-based filter for xs, ys, zs (ECEF). returns keep[] boolean array
    private static boolean[] computeMADFilter(List<Double> xs, List<Double> ys, List<Double> zs) {
        int n = xs.size();
        boolean[] keep = new boolean[n];

        double[] xa = toArr(xs);
        double[] ya = toArr(ys);
        double[] za = toArr(zs);

        double medX = median(xa);
        double medY = median(ya);
        double medZ = median(za);

        double madX = mad(xa, medX);
        double madY = mad(ya, medY);
        double madZ = mad(za, medZ);

        double sigmaX = 1.4826 * madX;
        double sigmaY = 1.4826 * madY;
        double sigmaZ = 1.4826 * madZ;

        double cutoffX = Math.max(1e-6, 3.0 * sigmaX);
        double cutoffY = Math.max(1e-6, 3.0 * sigmaY);
        double cutoffZ = Math.max(1e-6, 3.0 * sigmaZ);

        int kept = 0;
        for (int i = 0; i < n; i++) {
            double dx = Math.abs(xa[i] - medX);
            double dy = Math.abs(ya[i] - medY);
            double dz = Math.abs(za[i] - medZ);
            if (dx <= cutoffX && dy <= cutoffY && dz <= cutoffZ) {
                keep[i] = true;
                kept++;
            } else {
                keep[i] = false;
            }
        }

        // If kept too few (<25%), relax to 4-sigma
        if (kept < Math.max(1, n / 4)) {
            kept = 0;
            double c2x = Math.max(1e-6, 4.0 * sigmaX);
            double c2y = Math.max(1e-6, 4.0 * sigmaY);
            double c2z = Math.max(1e-6, 4.0 * sigmaZ);
            for (int i = 0; i < n; i++) {
                double dx = Math.abs(xa[i] - medX);
                double dy = Math.abs(ya[i] - medY);
                double dz = Math.abs(za[i] - medZ);
                if (dx <= c2x && dy <= c2y && dz <= c2z) {
                    keep[i] = true;
                    kept++;
                } else {
                    keep[i] = false;
                }
            }
            System.out.println("MAD filter relaxed to 4-sigma. Kept " + kept + " / " + n);
        }

        return keep;
    }

    private static double[] toArr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    private static double median(double[] a) {
        double[] b = a.clone();
        java.util.Arrays.sort(b);
        int n = b.length;
        if (n % 2 == 1) {
            return b[n / 2];
        }
        return 0.5 * (b[n / 2 - 1] + b[n / 2]);
    }

    private static double mad(double[] a, double med) {
        double[] dev = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            dev[i] = Math.abs(a[i] - med);
        }
        return median(dev);
    }

    private static double mean(List<Double> l) {
        if (l.isEmpty()) {
            return 0.0;
        }
        double s = 0;
        for (double v : l) {
            s += v;
        }
        return s / l.size();
    }
}
