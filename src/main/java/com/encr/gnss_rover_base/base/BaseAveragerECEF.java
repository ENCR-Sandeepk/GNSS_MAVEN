/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.base;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.tool.Variable;
import java.io.*;
import java.net.*;
import java.text.DecimalFormat;
import java.util.*;

public class BaseAveragerECEF {

    // ---------- WGS84 CONSTANTS ----------
    private static final double a = 6378137.0;              // semi-major (meters)
    private static final double f = 1 / 298.257223563;      // flattening
    private static final double e2 = f * (2 - f);           // eccentricity^2

    public String[] startBaseAveragerECEF(Integer requiredValidSamples, String host, int port) throws Exception {

        System.out.println("requiredValidSamples = " + requiredValidSamples);
        int accepted = 0;

        Socket socket = connect(host, port);
        BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        OutputStream out = socket.getOutputStream();

        // --- Step 1: Auto-configure GNSS receiver ---
        // Send GNSS configuration commands
        sendCommand(out, "freset");
        Thread.sleep(10_000);
        sendCommand(out, "unlogall");
        Thread.sleep(300);
        sendCommand(out, "fix auto");
        Thread.sleep(300);
        sendCommand(out, "log com1 gpgga ontime 1");
        Thread.sleep(300);
        sendCommand(out, "saveconfig");
        Thread.sleep(300);
        sendCommand(out, "\r\n\r\n");
        Thread.sleep(10_000);

        System.out.println("Receiver configured. Starting data collection...\n");

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> zs = new ArrayList<>();

        while (Variable.base) {

            if (requiredValidSamples != null && accepted >= requiredValidSamples) {
                System.out.println("Required valid samples reached.");
                break;
            }

            String line = br.readLine();

            if (line == null) {
                continue;
            }

            if (!line.startsWith("$GPGGA") && !line.startsWith("$GNGGA")) {
                continue;
            }

            try {
                GGA g = parseGGA(line);
                if (g == null) {
                    continue;
                }

                // Apply filtering rules
                if (g.fixQuality == 0) {
                    continue;
                }
                if (g.hdop > 2.0) {
                    continue;
                }
                if (g.sats < 15) {
                    continue;
                }
                if (g.lat == 0 || g.lon == 0) {
                    continue;
                }

                // Convert to ECEF
                double[] ecef = llaToECEF(g.lat, g.lon, g.alt);

                xs.add(ecef[0]);
                ys.add(ecef[1]);
                zs.add(ecef[2]);

                accepted++;
                System.out.println(line);

            } catch (Exception ignored) {
                // Skip bad NMEA lines
            }
        }

        System.out.println("Data collection finished.");
        if (xs.size() < 100) {
            System.out.println("WARNING: Not enough valid samples collected!");
        }

        // --- Step 2: Compute Median ---
        double mx = median(xs);
        double my = median(ys);
        double mz = median(zs);

        // --- Step 3: Median-based filtering (remove outliers) ---
        List<Double> fx = new ArrayList<>();
        List<Double> fy = new ArrayList<>();
        List<Double> fz = new ArrayList<>();

        for (int i = 0; i < xs.size(); i++) {
            if (Math.abs(xs.get(i) - mx) < 3
                    && Math.abs(ys.get(i) - my) < 3
                    && Math.abs(zs.get(i) - mz) < 3) {

                fx.add(xs.get(i));
                fy.add(ys.get(i));
                fz.add(zs.get(i));
            }
        }

        // --- Step 4: Compute Mean of filtered samples ---
        double meanX = mean(fx);
        double meanY = mean(fy);
        double meanZ = mean(fz);

        // --- Step 5: Convert ECEF → Lat/Lon/Alt ---
        double[] lla = ecefToLLA(meanX, meanY, meanZ);

        double lat = lla[0];
        double lon = lla[1];
        double alt = lla[2];

        DecimalFormat df = new DecimalFormat("0.0000000000");

        System.out.println("\n--- FINAL AVERAGED BASE COORDINATE (WGS84) ---");
        System.out.println("Lat : " + df.format(lat));
        System.out.println("Lon : " + df.format(lon));
        System.out.println("Alt : " + df.format(alt));

        System.out.println("\n--- SEND THIS FIX COMMAND TO K803 ---");
        System.out.println("unlogall");
        System.out.println("fix position " + df.format(lat) + " " + df.format(lon) + " " + df.format(alt));

        String[] result = new String[]{
            df.format(lat),
            df.format(lon),
            df.format(alt)
        };
        return result;
    }

    // ---------------------- SUPPORT FUNCTIONS -------------------------
    private static void sendCommand(OutputStream out, String cmd) throws Exception {
        String full = cmd + "\r\n";
        out.write(full.getBytes());
        out.flush();
        Thread.sleep(300);  // small delay for safety
    }

    private static Socket connect(String host, int port) throws Exception {
        while (true) {
            try {
                return new Socket(host, port);
            } catch (Exception e) {
                System.out.println("Retrying connection...");
                Thread.sleep(1000);
            }
        }
    }

    // Mean
    private static double mean(List<Double> list) {
        if (list.isEmpty()) {
            return 0.0;
        }
        double s = 0;
        for (double v : list) {
            s += v;
        }
        return s / list.size();
    }

    // Median
    private static double median(List<Double> list) {
        List<Double> tmp = new ArrayList<>(list);
        Collections.sort(tmp);
        int n = tmp.size();
        if (n == 0) {
            return 0;
        }
        if (n % 2 == 1) {
            return tmp.get(n / 2);
        }
        return (tmp.get(n / 2 - 1) + tmp.get(n / 2)) / 2.0;
    }

    // ---------------------- GGA PARSER -------------------------
    static class GGA {

        double lat, lon, alt;
        int fixQuality, sats;
        double hdop;
    }

    private static GGA parseGGA(String line) {
        String[] p = line.split(",");
        if (p.length < 15) {
            return null;
        }

        GGA g = new GGA();

        g.lat = nmeaToDeg(p[2], p[3]);
        g.lon = nmeaToDeg(p[4], p[5]);

        try {
            g.fixQuality = Integer.parseInt(p[6]);
            g.sats = Integer.parseInt(p[7]);
            g.hdop = Double.parseDouble(p[8]);
            g.alt = Double.parseDouble(p[9]);
        } catch (Exception e) {
            return null;
        }

        return g;
    }

    private static double nmeaToDeg(String v, String dir) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        double val = Double.parseDouble(v);
        double deg = Math.floor(val / 100);
        double min = val - (deg * 100);
        double dec = deg + min / 60.0;
        if (dir.equals("S") || dir.equals("W")) {
            dec = -dec;
        }
        return dec;
    }

    // ---------------------- LLA <-> ECEF -------------------------
    private static double[] llaToECEF(double latDeg, double lonDeg, double alt) {

        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);

        double sinLat = Math.sin(lat);
        double cosLat = Math.cos(lat);
        double sinLon = Math.sin(lon);
        double cosLon = Math.cos(lon);

        double N = a / Math.sqrt(1 - e2 * sinLat * sinLat);

        double x = (N + alt) * cosLat * cosLon;
        double y = (N + alt) * cosLat * sinLon;
        double z = (N * (1 - e2) + alt) * sinLat;

        return new double[]{x, y, z};
    }

    private static double[] ecefToLLA(double x, double y, double z) {

        double lon = Math.atan2(y, x);

        double p = Math.sqrt(x * x + y * y);
        double lat = Math.atan2(z, p * (1 - e2));

        double latPrev;
        double N;

        // Iterative refinement (very accurate)
        do {
            latPrev = lat;
            double sinLat = Math.sin(lat);
            N = a / Math.sqrt(1 - e2 * sinLat * sinLat);
            lat = Math.atan2(z + e2 * N * sinLat, p);
        } while (Math.abs(lat - latPrev) > 1e-12);

        double sinLat = Math.sin(lat);
        N = a / Math.sqrt(1 - e2 * sinLat * sinLat);
        double alt = p / Math.cos(lat) - N;

        return new double[]{
            Math.toDegrees(lat),
            Math.toDegrees(lon),
            alt
        };
    }
}
