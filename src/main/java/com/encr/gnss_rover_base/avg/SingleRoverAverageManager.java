/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.avg;

/**
 *
 * @author Sandeep K
 */
/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SingleRoverAverageManager {

    // ======== Configuration - change to your device ========
    // WGS84 constants
    private final int samples = 100;
    private final int maxWindows = 1728;
    private final int avgWindows;
    private final double[][] liveBuffer;
    private int liveIndex = 0;
    private boolean isLiveBufferRollOver = false;

    private final Deque<double[]> windowBuffer;
    private final File rover_avg;
    private final File rover_liveBuffer;
    private final File baselineFile;
    private boolean baselineSaved = false;
    private double[] baselineData = null;
    Path dataFilesDir;

    public SingleRoverAverageManager(Path path, int totalDataPoints) {
        isLiveBufferRollOver = false;

        this.avgWindows = Math.max(1, totalDataPoints / samples);

        this.liveBuffer = new double[samples][3];
        this.windowBuffer = new ArrayDeque<>(maxWindows);

        try {
            // Locate "gnss" folder inside jarDir
            Path gnssDir = path.resolve("gnss");
            if (!Files.exists(gnssDir)) {
                Files.createDirectories(gnssDir);
            }

            // Create "data_files" inside gnss
            dataFilesDir = gnssDir.resolve("rover_files");
            if (!Files.exists(dataFilesDir)) {
                Files.createDirectories(dataFilesDir);
            }
        } catch (Exception e) {
        }

        String dataDirectory = dataFilesDir.toAbsolutePath().toString();
        // Use fixed filenames for single rover
        String base = dataDirectory.endsWith("/") ? dataDirectory : dataDirectory + "/";
        this.rover_avg = new File(base + "rover_avg.txt");
        this.rover_liveBuffer = new File(base + "rover_liveBuffer.txt");
        this.baselineFile = new File(base + "baseline.txt");

        loadliveBufferFileData();
        loadFileData();
        loadBaselineData();
    }

    public synchronized void addSample(double northing, double easting, double altitude) {
        liveBuffer[liveIndex][Constant.NORTH] = northing;
        liveBuffer[liveIndex][Constant.EAST] = easting;
        liveBuffer[liveIndex][Constant.ALTITUDE] = altitude;
        liveIndex++;

        if (liveIndex == samples) {
            double[] tenMinAvg = computeAverage(liveBuffer);
            appendToWindowBuffer(tenMinAvg);
            initializeBaselineIfReady();
            saveWindowBufferToFile();
            saveliveBufferToFile();
            liveIndex = 0;
            isLiveBufferRollOver = true;
        }
    }

    public synchronized double[] getAverage() {
        try {

            List<Double> xs = new ArrayList<>();
            List<Double> ys = new ArrayList<>();
            List<Double> zs = new ArrayList<>();
            List<Double> cs = new ArrayList<>();

            int count = 0;

            for (Iterator<double[]> it = windowBuffer.descendingIterator();
                    it.hasNext() && count < avgWindows; count++) {

                double[] avg = it.next();

                if (avg.length >= 4) {
                    xs.add(avg[0]);
                    ys.add(avg[1]);
                    zs.add(avg[2]);
                    cs.add(avg[3]);
                }
            }

            boolean[] keep = computeMADFilter(xs, ys, zs);
            double[] total = new double[4];

            for (int i = 0; i < keep.length; i++) {
                if (keep[i]) {
                    total[0] += (xs.get(i) * cs.get(i));
                    total[1] += (ys.get(i) * cs.get(i));
                    total[2] += (zs.get(i) * cs.get(i));
                    total[3] += (cs.get(i));
                }
            }

            if (isLiveBufferRollOver && liveIndex > 0) {
                double[] partialAvg = computeAverage(liveBuffer, liveIndex);
                total[0] += (partialAvg[0] * partialAvg[3]);
                total[1] += (partialAvg[1] * partialAvg[3]);
                total[2] += (partialAvg[2] * partialAvg[3]);
                total[3] += partialAvg[3];
            }

            if (total[3] == 0) {
                return null;
            }

            return new double[]{
                total[0] / total[3],
                total[1] / total[3],
                total[2] / total[3]
            };
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized double[] getBaseline() {
        if (baselineData == null) {
            System.err.println("⚠ Baseline not yet initialized.");
            return new double[]{0, 0, 0};
        }
        return baselineData;
    }

    private void initializeBaselineIfReady() {
        if (baselineSaved || windowBuffer.size() < avgWindows) {
            return;
        }

        double[] baseline = getAverage();

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(baselineFile))) {
            writer.write(baseline[0] + "," + baseline[1] + "," + baseline[2]);
            writer.newLine();
            baselineSaved = true;
            baselineData = baseline;

            Variable.base_data[Constant.NORTH] = baseline[Constant.NORTH];
            Variable.base_data[Constant.EAST] = baseline[Constant.EAST];
            Variable.base_data[Constant.ALTITUDE] = baseline[Constant.ALTITUDE];
            // FRESH baseline just computed (not loaded from file) -> signal Rover to
            // upload the config+state backup once. loadBaselineData() does NOT set this.
            Variable.rover_baseline_just_established = true;
            System.out.println("✅ Baseline initialized and saved to " + baselineFile.getName());
        } catch (IOException e) {
            System.err.println("❌ Failed to save baseline: " + baselineFile.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private double[] computeAverage(double[][] data) {
        return computeAverage(data, data.length);
    }

    private double[] computeAverage(double[][] data, int count) {

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        List<Double> zs = new ArrayList<>();

        for (int i = 0; i < count && i < data.length; i++) {
            double[] row = data[i];
            if (row.length >= 3) {
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
            isLiveBufferRollOver = true;
        }
    }

    private void appendToWindowBuffer(double[] avg) {
        if (windowBuffer.size() == maxWindows) {
            windowBuffer.pollFirst();
        }
        windowBuffer.addLast(avg);
    }

    private void saveWindowBufferToFile() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rover_avg))) {
            for (double[] avg : windowBuffer) {
                writer.write(avg[0] + "," + avg[1] + "," + avg[2] + "," + avg[3]);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to save window buffer to file: " + rover_avg.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private void saveliveBufferToFile() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(rover_liveBuffer))) {
            for (double[] liveData : liveBuffer) {
                writer.write(liveData[0] + "," + liveData[1] + "," + liveData[2]);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("❌ Failed to save window buffer to file: " + rover_liveBuffer.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private void loadFileData() {
        if (!rover_avg.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(rover_avg))) {
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
            baselineSaved = windowBuffer.size() == avgWindows && baselineFile.exists();
        } catch (IOException | NumberFormatException e) {
            System.err.println("❌ Failed to load averages from file: " + rover_avg.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private void loadliveBufferFileData() {
        if (!rover_liveBuffer.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(rover_liveBuffer))) {
            String line;
            liveIndex = 0;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(",");
                if (parts.length != 3) {
                    continue;
                }
                double[] avg = {
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2])
                };
                appendToliveBuffer(avg);
            }

        } catch (IOException | NumberFormatException e) {
            System.err.println("❌ Failed to load rover_liveBuffer from file: " + rover_liveBuffer.getAbsolutePath());
            e.printStackTrace();
        }
    }

    private void loadBaselineData() {
        if (!baselineFile.exists()) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(baselineFile))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split(",");
                if (parts.length == 3) {
                    baselineData = new double[]{
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2])
                    };
                    baselineSaved = true;
                }
            }
        } catch (IOException | NumberFormatException e) {
            System.err.println("❌ Failed to load baseline data from file: " + baselineFile.getAbsolutePath());
            e.printStackTrace();
        }
    }
}
