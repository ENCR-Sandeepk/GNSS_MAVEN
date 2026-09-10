package com.encr.gnss_rover_base.avg;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.tool.Constant;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BaseAverageManager {

    private final int samples = 600;
    private final int maxWindows;
    private final double[][] liveBuffer;
    private int liveIndex = 0;

    private final Deque<double[]> windowBuffer;
    private final File base_avg;
    Path dataFilesDir;

    public BaseAverageManager(Path path, int totalDataPoints) {
        liveIndex = 0;
        this.maxWindows = Math.max(1, totalDataPoints / samples);
        this.liveBuffer = new double[samples][3];
        this.windowBuffer = new ArrayDeque<>(maxWindows);

        try {
            Path gnssDir = path.resolve("gnss");
            if (!Files.exists(gnssDir)) {
                Files.createDirectories(gnssDir);
            }
            dataFilesDir = gnssDir.resolve("base_files");
            if (!Files.exists(dataFilesDir)) {
                Files.createDirectories(dataFilesDir);
            }
        } catch (Exception e) {
        }

        String dataDirectory = dataFilesDir.toAbsolutePath().toString();
        String base = dataDirectory.endsWith("/") ? dataDirectory : dataDirectory + "/";
        this.base_avg = new File(base + "base_avg.txt");
        loadFileData();
    }

    public synchronized boolean addSample(double northing, double easting, double altitude) {
        boolean requiredSampleDone = false;
        liveBuffer[liveIndex][Constant.NORTH] = northing;
        liveBuffer[liveIndex][Constant.EAST] = easting;
        liveBuffer[liveIndex][Constant.ALTITUDE] = altitude;
        liveIndex++;

        if (liveIndex == samples) {
            double[] tenMinAvg = computeAverage(liveBuffer);
            appendToWindowBuffer(tenMinAvg);
            requiredSampleDone = initializeBaselineIfReady();
            saveWindowBufferToFile();
            liveIndex = 0;
        }
        return requiredSampleDone;
    }

    private boolean initializeBaselineIfReady() {
        return windowBuffer.size() >= maxWindows;
    }

    public synchronized double[] getAverage() {
        try {
            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            List<Double> zs = new ArrayList<>();
            List<Double> cs = new ArrayList<>();

            double[] total = new double[4];

            for (double[] avg : windowBuffer) {
                if (avg.length >= 4) {
                    xs.add(avg[0]);
                    ys.add(avg[1]);
                    zs.add(avg[2]);
                    cs.add(avg[3]);
                }
            }

            boolean[] keep = computeMADFilter(xs, ys, zs);

            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) {
                    total[0] += (xs.get(i) * cs.get(i));
                    total[1] += (ys.get(i) * cs.get(i));
                    total[2] += (zs.get(i) * cs.get(i));
                    total[3] += (cs.get(i));
                }
            }

            if (total[3] == 0) {
                return null;
            } else {
                return new double[]{
                    total[0] / total[3],
                    total[1] / total[3],
                    total[2] / total[3]
                };
            }

        } catch (Exception e) {
            return null;
        }
    }

    private double[] computeAverage(double[][] data) {

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> zs = new ArrayList<>();

        for (double[] row : data) {
            if (row.length >= 3) { // Ensure there are at least 3 elements
                xs.add(row[0]);
                ys.add(row[1]);
                zs.add(row[2]);
            }
        }

        boolean[] keep = computeMADFilter(xs, ys, zs);

        List<Double> xsF = new ArrayList<>();
        List<Double> ysF = new ArrayList<>();
        List<Double> zsF = new ArrayList<>();

        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {

                xsF.add(xs.get(i));
                ysF.add(ys.get(i));
                zsF.add(zs.get(i));
            }
        }

        double meanX = mean(xsF);
        double meanY = mean(ysF);
        double meanZ = mean(zsF);

        return new double[]{
            meanX,
            meanY,
            meanZ,
            xsF.size()
        };
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

        // Minimum required percentage
        double requiredPercent = 0.70;

        // Start with 1 sigma
        double sigmaMultiplier = 1.0;

        int kept = 0;

        while (sigmaMultiplier <= 10.0) {

            kept = 0;

            double cutoffX = Math.max(1e-6, sigmaMultiplier * sigmaX);
            double cutoffY = Math.max(1e-6, sigmaMultiplier * sigmaY);
            double cutoffZ = Math.max(1e-6, sigmaMultiplier * sigmaZ);

            for (int i = 0; i < n; i++) {

                double dx = Math.abs(xa[i] - medX);
                double dy = Math.abs(ya[i] - medY);
                double dz = Math.abs(za[i] - medZ);

                if (dx <= cutoffX
                        && dy <= cutoffY
                        && dz <= cutoffZ) {

                    keep[i] = true;
                    kept++;

                } else {
                    keep[i] = false;
                }
            }

            double keptPercent = (double) kept / n;

            if (Constant.DEBUG) {
                System.out.println("Sigma: " + sigmaMultiplier
                        + "  Kept: " + kept + "/" + n
                        + " (" + (keptPercent * 100.0) + "%)");
            }

            // Stop when at least 70% data retained
            if (keptPercent >= requiredPercent) {
                break;
            }

            // Increase sigma by 0.2
            sigmaMultiplier += 0.2;
        }

        if (Constant.DEBUG) {
            System.out.println("Final Sigma Used: " + sigmaMultiplier);
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

    private void appendToliveBuffer(double[] avg) {
        liveBuffer[liveIndex][0] = avg[0];
        liveBuffer[liveIndex][1] = avg[1];
        liveBuffer[liveIndex][2] = avg[2];
        liveIndex++;

        if (liveIndex == samples) {
            liveIndex = 0;
        }
    }

    private void appendToWindowBuffer(double[] avg) {
        if (windowBuffer.size() == maxWindows) {
            windowBuffer.pollFirst();

        }
        windowBuffer.addLast(avg);
    }

    private void saveWindowBufferToFile() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(base_avg))) {
            for (double[] avg : windowBuffer) {
                writer.write(avg[0] + "," + avg[1] + "," + avg[2] + "," + avg[3]);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to save window buffer to file: " + base_avg.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private void loadFileData() {
        if (!base_avg.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(base_avg))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(",");
                if (parts.length != 4) {
                    continue;
                }
                double[] avg = {
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3])
                };
                appendToWindowBuffer(avg);
            }

        } catch (IOException | NumberFormatException e) {
            System.err.println("❌ Failed to load averages from file: " + base_avg.getAbsolutePath());
            e.printStackTrace();
        }
    }

}
