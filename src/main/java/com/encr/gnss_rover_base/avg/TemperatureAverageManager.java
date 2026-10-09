package com.encr.gnss_rover_base.avg;

/**
 * Averages the board temperature over EXACTLY the same period and the same
 * data points as the GNSS position average (SingleRoverAverageManager).
 *
 * How it stays aligned with the GNSS average:
 *  - Same constructor input (totalDataPoints = Variable.base_line_duration),
 *    so continuous and burst mode get the same averaging span as GNSS.
 *  - addSample() is called once for every GNSS sample (right next to
 *    roverManager.addSample), with the latest temperature reading. So each
 *    100-sample window closes at the same moment as the GNSS window.
 *  - getAverage() uses the same last-N-windows + current partial window as GNSS.
 *
 * Unlike the GNSS average there is NO outlier filtering: the result is a plain
 * count-weighted mean, so real daily highs/lows are kept.
 *
 * If no temperature has been read yet (value "--"), the sample is stored as NaN:
 * it still advances the window (keeping alignment) but does not count in the mean.
 *
 * Persisted in gnss/rover_files/ (temp_avg.txt, temp_liveBuffer.txt), so the
 * average carries across burst wakes and is cleared by "Reset Base Reading".
 *
 * @author Sandeep K
 */
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public class TemperatureAverageManager {

    private final int samples = 100;      // same window size as SingleRoverAverageManager
    private final int maxWindows = 1728;  // same history cap as SingleRoverAverageManager
    private final int avgWindows;

    private final double[] liveBuffer = new double[samples];
    private int liveIndex = 0;
    private boolean isLiveBufferRollOver = false;

    // each entry: {mean, count} of the valid (non-NaN) readings in that window
    private final Deque<double[]> windowBuffer = new ArrayDeque<>(maxWindows);

    private File tempAvgFile;
    private File tempLiveFile;

    public TemperatureAverageManager(Path path, int totalDataPoints) {
        this.avgWindows = Math.max(1, totalDataPoints / samples);
        for (int i = 0; i < samples; i++) {
            liveBuffer[i] = Double.NaN;
        }
        try {
            Path dir = path.resolve("gnss").resolve("rover_files");
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            tempAvgFile = dir.resolve("temp_avg.txt").toFile();
            tempLiveFile = dir.resolve("temp_liveBuffer.txt").toFile();
            loadLiveBuffer();
            loadWindows();
        } catch (Exception e) {
            System.err.println("TemperatureAverageManager init failed: " + e.getMessage());
        }
    }

    /** Add one sample (call once per GNSS sample). NaN = no valid temperature yet. */
    public synchronized void addSample(double temperature) {
        liveBuffer[liveIndex] = temperature;
        liveIndex++;
        if (liveIndex == samples) {
            appendWindow(meanAndCount(liveBuffer, samples));
            saveWindows();
            saveLiveBuffer();
            liveIndex = 0;
            isLiveBufferRollOver = true;
        }
    }

    /** Plain count-weighted mean over the same span as the GNSS average; NaN if no data. */
    public synchronized double getAverage() {
        double sum = 0;
        double count = 0;
        int n = 0;
        for (Iterator<double[]> it = windowBuffer.descendingIterator(); it.hasNext() && n < avgWindows; n++) {
            double[] w = it.next();
            sum += w[0] * w[1];
            count += w[1];
        }
        if (isLiveBufferRollOver && liveIndex > 0) {
            double[] partial = meanAndCount(liveBuffer, liveIndex);
            sum += partial[0] * partial[1];
            count += partial[1];
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    private static double[] meanAndCount(double[] data, int count) {
        double s = 0;
        int c = 0;
        for (int i = 0; i < count && i < data.length; i++) {
            if (!Double.isNaN(data[i])) {
                s += data[i];
                c++;
            }
        }
        return new double[]{c > 0 ? s / c : 0.0, c};
    }

    private void appendWindow(double[] w) {
        if (windowBuffer.size() == maxWindows) {
            windowBuffer.pollFirst();
        }
        windowBuffer.addLast(w);
    }

    private void saveWindows() {
        if (tempAvgFile == null) {
            return;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tempAvgFile))) {
            for (double[] win : windowBuffer) {
                w.write(win[0] + "," + win[1]);
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to save temperature windows: " + e.getMessage());
        }
    }

    private void saveLiveBuffer() {
        if (tempLiveFile == null) {
            return;
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(tempLiveFile))) {
            for (double v : liveBuffer) {
                w.write(String.valueOf(v));   // NaN is written as "NaN" and parsed back by Double.parseDouble
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("Failed to save temperature live buffer: " + e.getMessage());
        }
    }

    private void loadWindows() {
        if (tempAvgFile == null || !tempAvgFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(tempAvgFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(",");
                if (p.length != 2) {
                    continue;
                }
                try {
                    appendWindow(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load temperature windows: " + e.getMessage());
        }
    }

    // Mirrors SingleRoverAverageManager.loadliveBufferFileData(): the file holds the last
    // completed 100-sample window, so after loading liveIndex wraps to 0 with rollover set.
    private void loadLiveBuffer() {
        if (tempLiveFile == null || !tempLiveFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(tempLiveFile))) {
            String line;
            liveIndex = 0;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    v = Double.NaN;
                }
                liveBuffer[liveIndex] = v;
                liveIndex++;
                if (liveIndex == samples) {
                    liveIndex = 0;
                    isLiveBufferRollOver = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load temperature live buffer: " + e.getMessage());
        }
    }
}
