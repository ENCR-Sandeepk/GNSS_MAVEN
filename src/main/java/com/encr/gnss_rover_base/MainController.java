package com.encr.gnss_rover_base;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.base.Base;
import com.encr.gnss_rover_base.controller.TcpClientService;
import com.encr.gnss_rover_base.ftp.ConfigBackupManager;
import com.encr.gnss_rover_base.rover.Rover;
import com.encr.gnss_rover_base.tool.BeagleControllerConnectionChecker;
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.Tool;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainController {

    private static Path CONTROL_FILE_PATH;
    private static Path UPDATE_FLAG;
    private static Path jarDir;
    private static String currentMode = "";
    private static Thread runningThread = null;
    private static Thread burstTimerThread = null;
    private static String currentLogDate = "";   // YYYY-MM-DD
    private static String currentLogFilename = "";
    private static boolean firstTimeRun = true;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) {

        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));

        jarDir = getJarDirectory();
        CONTROL_FILE_PATH = jarDir.resolve("gnss")
                .resolve("config")
                .resolve("save_gnss_receiver_settings.txt");

        UPDATE_FLAG = jarDir.resolve("gnss")
                .resolve("config")
                .resolve("update.flag");

        File controlFile = CONTROL_FILE_PATH.toFile();
        writeVersionFile(jarDir);
        log("Controller started safely...");

        // Storage retention: purge CSVs older than DATA_RETENTION_DAYS from
        // files_for_upload/ and archive/. Runs once now (covers every burst wake,
        // since the JVM restarts each wake) and then every 24h (covers continuous mode).
        purgeOldUploadFiles();
        ScheduledExecutorService retentionExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread rt = new Thread(r, "retention-sweep");
            rt.setDaemon(true);
            return rt;
        });
        retentionExec.scheduleAtFixedRate(MainController::purgeOldUploadFiles, 24, 24, TimeUnit.HOURS);
        // Backstop for guaranteed backup delivery (esp. continuous units that rarely
        // restart the base/rover loop): retry any pending config backup every 5 min.
        retentionExec.scheduleAtFixedRate(ConfigBackupManager::retryPendingAsync, 5, 5, TimeUnit.MINUTES);

        Variable.config = safeReadModeFromFile();
        String mode = Variable.config.getOrDefault(Constant.KEY_deviceType, "").trim();
        currentMode = mode.toUpperCase(Locale.ROOT);

        try {
            String[] ipPort = getIPAndPort(jarDir);
            Variable.system_ip = ipPort[0];
            Variable.system_port = Integer.parseInt(ipPort[1]);
        } catch (Exception e) {
        }

        TcpClientService.start_server(Variable.system_ip, 2001);
        try {
            Thread.sleep(2000);
        } catch (Exception e) {
        }
        TcpClientService.sendStatusCode(Constant.STAT_BOOT);

        BeagleControllerConnectionChecker scBeagleControllerConnectionChecker = new BeagleControllerConnectionChecker();
        scBeagleControllerConnectionChecker.start();

        while (true) {
            try {
                if (controlFile.exists()) {
                    Thread.sleep(250);

                    Variable.isSettingModified = isUpdateFlagPresent();

                    if (Variable.isSettingModified || firstTimeRun) {

                        MainController.deleteUpdateFlag();
                        firstTimeRun = false;

                        Variable.config = safeReadModeFromFile();

                        if (Variable.config == null || Variable.config.isEmpty()) {
                            log("Config file missing or invalid. Skipping...");
                            continue;
                        }

                        mode = Variable.config.getOrDefault(Constant.KEY_deviceType, "").trim();

                        Variable.operating_mode = Variable.config.getOrDefault(Constant.KEY_operating_mode, Variable.operating_mode).trim();
                        Variable.burst_window = Variable.config.getOrDefault(Constant.KEY_burst_window, Variable.burst_window).trim();
                        Variable.burst_interval = Variable.config.getOrDefault(Constant.KEY_burst_interval, Variable.burst_interval).trim();

                        System.out.println("Variable.operating_mode : " + Variable.operating_mode);
                        System.out.println("Variable.burst_window   : " + Variable.burst_window);
                        System.out.println("Variable.burst_interval : " + Variable.burst_interval);

                        Variable.is_burst_mode = "burst".equalsIgnoreCase(Variable.operating_mode);

                        TcpClientService.sendStatusCode(Constant.STAT_CONFIG_LOADED);

                        // Debug logging on/off from the UI (defaults to true if not present)
                        Constant.DEBUG = !"false".equalsIgnoreCase(
                                Variable.config.getOrDefault(Constant.KEY_debugEnable, "true").trim());
                        System.out.println("Constant.DEBUG = " + Constant.DEBUG);

                        if (mode.isEmpty()) {
                            log("Device type not found in config file. Skipping...");
                            continue;
                        }

//                        if (!mode.equalsIgnoreCase(currentMode)) {
                        safeHandleModeChange(mode);

//                        }
                    }
                } else {
                    log("Control file not found: " + CONTROL_FILE_PATH);
                }

//                checkAutoRestart(); // 👈 added watchdog
                Thread.sleep(10000);
            } catch (Throwable t) {
                log("Error in main loop: " + safeMessage(t));
                safeSleep(1000);
            }
        }
    }

    public static String[] getIPAndPort(Path jarDir) {
        try {
            Path ipPortFile = jarDir.resolve("gnss")
                    .resolve("config")
                    .resolve("ip_port.txt");

            // Ensure directory exists
            Files.createDirectories(ipPortFile.getParent());

            // Default values
            String defaultIP = "192.168.10.100";
            String defaultPort = "2000";

            // If file does NOT exist → create it with default values
            if (!Files.exists(ipPortFile)) {
                String defaultContent = defaultIP + ":" + defaultPort;
                Files.write(ipPortFile, defaultContent.getBytes(StandardCharsets.UTF_8));
                return new String[]{defaultIP, defaultPort};
            }

            // Read file (Java 7/8 safe)
            String line = new String(Files.readAllBytes(ipPortFile), StandardCharsets.UTF_8).trim();

            // Expect "IP:PORT"
            if (line.contains(":")) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    return new String[]{parts[0].trim(), parts[1].trim()};
                }
            }

            // File exists but corrupted → rewrite default
            String defaultContent = defaultIP + ":" + defaultPort;
            Files.write(ipPortFile, defaultContent.getBytes(StandardCharsets.UTF_8));
            return new String[]{defaultIP, defaultPort};

        } catch (IOException e) {
            e.printStackTrace();
        }

        return new String[]{"192.168.10.100", "2000"}; // fallback
    }

    public static boolean isUpdateFlagPresent() {
        return Files.exists(UPDATE_FLAG);
    }

    public static boolean deleteUpdateFlag() {
        try {
            boolean deleted = Files.deleteIfExists(UPDATE_FLAG);
            return deleted;
        } catch (Exception e) {
            System.err.println("Failed to delete update.flag: " + e.getMessage());
            return false;
        }
    }

    public static Path getJarDirectory() {
        try {
            // Get location of running class (works both in JAR and IDE)
            String path = MainController.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();

            // Convert to Path and go to parent directory
            return Paths.get(path).getParent();
        } catch (Exception e) {
            // Fallback to current directory if something goes wrong
            return Paths.get("").toAbsolutePath();
        }
    }

    private static void writeVersionFile(Path jarDir) {
        try {
            Path versionFile = jarDir.resolve("gnss").resolve("config").resolve("version.txt");
            Files.write(versionFile, Constant.VERSION.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --------------------- AUTO-RESTART WATCHDOG ----------------------
    private static void checkAutoRestart() {
        try {
            if ("BASE".equalsIgnoreCase(currentMode) && (runningThread == null || !runningThread.isAlive())) {
                log("Base thread stopped unexpectedly. Restarting Base...");
                Variable.rover = true;
                startBase();
            } else if ("ROVER".equalsIgnoreCase(currentMode) && (runningThread == null || !runningThread.isAlive())) {
                log("Rover thread stopped unexpectedly. Restarting Rover...");
                Variable.rover = true;
                startRover();
            }
        } catch (Throwable t) {
            log("Auto-restart error: " + safeMessage(t));
        }
    }

    // -----------------------------------------------------------------
    private static void safeHandleModeChange(String mode) {
        try {
            handleModeChange(mode);
        } catch (Throwable t) {
            log("Error handling mode change (" + mode + "): " + safeMessage(t));
        }
    }

    private static void handleModeChange(String mode) {
        log("Mode change detected: " + mode);
        stopBurstTimer();
        stopCurrentProcess();

        currentMode = mode.toUpperCase(Locale.ROOT);

        switch (currentMode) {
            case "BASE" -> {
                Variable.is_base_station_coordinates_reads = false;
                waitUntilStopped();
                startBase();
                TcpClientService.sendStatusCode(Constant.STAT_BASE_START);

                // Wait for base coordinates to be established, but stay responsive:
                // break out if the base is stopped, or if a NEW settings change arrives,
                // so the main loop can process a STOP / reconfigure instead of hanging.
                while (!Variable.is_base_station_coordinates_reads
                        && Variable.base
                        && !isUpdateFlagPresent()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                System.out.println("Variable.is_burst_mode = " + Variable.is_burst_mode);
                System.out.println("Variable.base_station_coordinates_done = " + Variable.base_station_coordinates_done);

                // Only schedule the burst shutdown if coordinates were actually read
                // (not if we bailed out early due to stop / new settings).
                if (Variable.is_burst_mode
                        && Variable.is_base_station_coordinates_reads) {
                    scheduleBurstShutdown();
                }
            }

            case "ROVER" -> {
                waitUntilStopped();
                System.out.println("STOPPED.............................................");
                startRover();
                TcpClientService.sendStatusCode(Constant.STAT_ROVER_START);
                if (Variable.is_burst_mode) {
                    scheduleBurstShutdown();
                }
            }

            case "STOP" -> {
                Variable.base = false;
                Variable.rover = false;
                log("All operations stopped.");
            }

            default ->
                log("Invalid mode: " + mode);
        }
    }

    private static void stopBurstTimer() {
        if (burstTimerThread != null && burstTimerThread.isAlive()) {
            log("Stopping existing BurstModeTimer...");
            burstTimerThread.interrupt();
            burstTimerThread = null;
        }
    }

    private static void scheduleBurstShutdown() {
        stopBurstTimer();

        long extraRunMin = "BASE".equals(currentMode) ? Constant.EXTRA_RUN_MIN_BASE : Constant.EXTRA_RUN_MIN_ROVER;
        long wakeLeadMin = "BASE".equals(currentMode) ? Constant.WAKEUP_LEAD_MIN_BASE : Constant.WAKEUP_LEAD_MIN_ROVER;

        final long windowLenMs = Tool.get_burst_duration_ms(Variable.burst_window) + (extraRunMin * 60 * 1000);
        final long idleMs = Constant.IDLE_SLEEP_TIMEOUT_MIN * 60 * 1000;

        final long imminentMs = (wakeLeadMin + Constant.WAKEUP_SAFETY_MIN) * 60 * 1000;

        burstTimerThread = new Thread(() -> {
            final long start = System.currentTimeMillis();   // = time of last UI activity (config save restarts this timer)
            while (true) {
                try {
                    Thread.sleep(1000);                       // re-check every 1 s
                } catch (InterruptedException e) {
                    log("BurstModeTimer interrupted — settings changed, aborting shutdown.");
                    return;
                }
                long now = System.currentTimeMillis();

                boolean logging = Tool.isBurstLoggingTime(windowLenMs);
                long untilNext = Tool.msUntilNextBurstWindow();
                long idleElapsed = now - start;
                Tool.dbg("BurstTimer", "mode=" + currentMode
                        + " logging=" + logging
                        + " untilNextWindow=" + (untilNext / 1000) + "s"
                        + " imminent<=" + (imminentMs / 1000) + "s"
                        + " idleElapsed=" + (idleElapsed / 1000) + "s"
                        + " idleLimit=" + (idleMs / 1000) + "s");

                if (logging) {
                    // It IS logging time -> keep running the full duration; never idle-sleep here.
                } else {
                    if (untilNext <= imminentMs) {
                        // A logging window is imminent -> stay awake and wait for it.
                    } else if (idleElapsed >= idleMs) {
                        // Not logging, no window coming soon, no UI activity for the idle period -> sleep.
                        log("BurstModeTimer -> sleep (no UI activity for "
                                + Constant.IDLE_SLEEP_TIMEOUT_MIN + " min, no logging window)");
                        break;
                    }
                }
            }
            Variable.rover = false;
            Variable.base = false;
            if (runningThread != null && runningThread.isAlive()) {
                runningThread.interrupt();
            }
            waitUntilStopped();
            if ("ROVER".equals(currentMode)) {
                Rover.flushLastBurstRecord();
            }
            Tool.set_next_wawkeup_time_and_sleep();
        }, "BurstModeTimer");

        burstTimerThread.setDaemon(true);
        burstTimerThread.start();
        log("BurstModeTimer started (windowLen=" + (windowLenMs / 60000) + "min, idle="
                + Constant.IDLE_SLEEP_TIMEOUT_MIN + "min)");
    }
//    private static void scheduleBurstShutdown() {
//        stopBurstTimer();
//        long extraRunMin = "BASE".equals(currentMode) ? Constant.EXTRA_RUN_MIN_BASE : Constant.EXTRA_RUN_MIN_ROVER;
//        final long windowMs = Tool.get_burst_duration_ms(Variable.burst_window) + (extraRunMin * 60 * 1000);
//        burstTimerThread = new Thread(() -> {
//            try {
//
//                Thread.sleep(windowMs);
//            } catch (InterruptedException e) {
//                log("BurstModeTimer interrupted — settings changed, aborting shutdown.");
//                return;
//            }
//            Variable.rover = false;
//            Variable.base = false;
//            if (runningThread != null && runningThread.isAlive()) {
//                runningThread.interrupt();
//            }
//            waitUntilStopped();
//            if ("ROVER".equals(currentMode)) {
//                Rover.flushLastBurstRecord();   // write the single last record for this wake
//            }
//            Tool.set_next_wawkeup_time_and_sleep();
//        }, "BurstModeTimer");
//        burstTimerThread.setDaemon(true);
//        burstTimerThread.start();
//        log("BurstModeTimer started: window=" + Variable.burst_window + ", interval=" + Variable.burst_interval);
//    }

    private static void waitUntilStopped() {
        for (int i = 0; i < 20; i++) {
            try {
                if (Variable.baseStopped && Variable.roverStopped) {
                    break;
                }
                Thread.sleep(1000);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void startBase() {
        log("Starting Base Station...");
        Variable.base = true;   // set BEFORE spawning so the BASE wait loop doesn't exit early (race fix)
        runningThread = new Thread(() -> {
            try {
                Base.startBase(); // main base logic
                log("BaseStation exited normally.");
            } catch (Throwable t) {
                log("BaseStation error: " + safeMessage(t));
            }

        }, "BaseThread");
        runningThread.setDaemon(true);
        runningThread.start();
    }

    private static void startRover() {
        log("Starting Rover Station...");
        runningThread = new Thread(() -> {
            try {
                Rover.startRover(); // main rover logic
                log("RoverStation exited normally.");
            } catch (Throwable t) {
                log("RoverStation error: " + safeMessage(t));
            }

        }, "RoverThread");
        runningThread.setDaemon(true);
        runningThread.start();
    }

    private static void stopCurrentProcess() {
        try {
            Variable.base = false;
            Variable.rover = false;
            if (runningThread != null && runningThread.isAlive()) {
                log("Stopping current process...");
                runningThread.interrupt();
                runningThread.join(2000);
            }
            runningThread = null;
        } catch (Throwable t) {
            log("Error stopping current process: " + safeMessage(t));
        }
    }

    private static Map<String, String> parseConfig(String data) {
        Map<String, String> map = new LinkedHashMap<>();
        if (data == null || data.trim().isEmpty()) {
            return map;
        }

        try {
            String[] pairs = data.split("&");
            for (String pair : pairs) {
                if (pair == null || pair.isEmpty()) {
                    continue;
                }
                String[] keyValue = pair.split("=", 2);
                String key = keyValue.length > 0 ? keyValue[0].trim() : "";
                String value = keyValue.length > 1 ? keyValue[1].trim() : "";
                if (!key.isEmpty()) {
                    map.put(key, value);
                }
            }
        } catch (Throwable t) {
            log("Error parsing config: " + safeMessage(t));
        }
        return map;
    }

    public static Map<String, String> safeReadModeFromFile() {
        try {
            if (!Files.exists(CONTROL_FILE_PATH)) {
                log("Config file not found: " + CONTROL_FILE_PATH);
                return new LinkedHashMap<>();
            }
            String content = new String(Files.readAllBytes(CONTROL_FILE_PATH)).trim();
            return parseConfig(content);
        } catch (Throwable t) {
            log("Error reading config file: " + safeMessage(t));
            return new LinkedHashMap<>();
        }
    }

    public static void update_contol_file_after_base_coordinates_reset() {
        try {
            // 1. Read file content
            String content = new String(Files.readAllBytes(CONTROL_FILE_PATH)).trim();

            // 2. Replace resetBaseReading value
            content = content.replaceAll("lat=[^&]*", "lat=null")
                    .replaceAll("lon=[^&]*", "lon=null")
                    .replaceAll("alt=[^&]*", "alt=null")
                    .replaceAll("resetBaseCoordinates=[^&]*", "resetBaseCoordinates=false");

            // 3. Write updated content back to file
            Files.write(CONTROL_FILE_PATH, content.getBytes());

            System.out.println("✔ reset Basestation location updated successfully.");

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void update_contol_file_after_base_coordinates_get(String[] baseCoordinates) {
        try {
            // 1. Read file content
            String content = new String(Files.readAllBytes(CONTROL_FILE_PATH)).trim();
            // 2. Replace resetBaseReading value
            content = content.replaceAll("lat=[^&]*", "lat=" + baseCoordinates[0])
                    .replaceAll("lon=[^&]*", "lon=" + baseCoordinates[1])
                    .replaceAll("alt=[^&]*", "alt=" + baseCoordinates[2]);
            // 3. Write updated content back to file
            Files.write(CONTROL_FILE_PATH, content.getBytes());
            System.out.println("✔ resetBaseReading updated successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void update_contol_file_after_base_reading_reset() {
        try {
            // 1. Read file content
            String content = new String(Files.readAllBytes(CONTROL_FILE_PATH)).trim();

            // 2. Replace resetBaseReading value
            content = content.replaceAll("resetBaseReading=[^&]*", "resetBaseReading=false");
            // the baseline is being re-established, so its recorded temperature is no longer valid
            content = content.replaceAll("tempBaselineTemp=[^&]*", "tempBaselineTemp=null");

            // 3. Write updated content back to file
            Files.write(CONTROL_FILE_PATH, content.getBytes());

            System.out.println("✔ resetBaseReading updated successfully.");

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * Writes the recorded baseline temperature into the config file so the UI can
     * display it (Automatic reference-temperature mode). Display copy only: the value
     * actually used is gnss/rover_files/baseline_temp.txt.
     */
    public static synchronized void update_control_file_temp_baseline(String value) {
        try {
            String content = new String(Files.readAllBytes(CONTROL_FILE_PATH)).trim();
            if (content.contains("tempBaselineTemp=")) {
                content = content.replaceAll("tempBaselineTemp=[^&]*", "tempBaselineTemp=" + value);
            } else {
                content = content + "&tempBaselineTemp=" + value;
            }
            Files.write(CONTROL_FILE_PATH, content.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static long safeLastModified(File file) {

        try {
            return file.lastModified();
        } catch (Throwable t) {
            log("Error checking file modified time: " + safeMessage(t));
            return 0;
        }
    }

    public static synchronized void log(String message) {
        try {
            LocalDateTime now = LocalDateTime.now();
            String nowDate = now.format(DATE_FMT);
            String time = now.format(TIME_FMT);
            String logMsg = "[" + nowDate + " " + time + "] " + message;

            // Print to console
            System.out.println(logMsg);

            // Prepare log folder
            Path logDir = jarDir.resolve("gnss").resolve("logs");
            Files.createDirectories(logDir);

            // Create a new file when the date changes
            if (!nowDate.equals(currentLogDate)) {
                currentLogDate = nowDate;
                currentLogFilename = "log-" + nowDate + ".txt";

                // Clean old log files (keep only 31 newest)
                cleanOldLogs(logDir);
            }

            Path logFile = logDir.resolve(currentLogFilename);

            // Append log entry
            try (FileWriter fw = new FileWriter(logFile.toFile(), true)) {
                fw.write(logMsg + System.lineSeparator());
            }

        } catch (Throwable t) {
            try {
                System.err.println("Log failed: " + t.getMessage());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void cleanOldLogs(Path logDir) {
        try (Stream<Path> files = Files.list(logDir)) {
            List<Path> logFiles = files
                    .filter(p -> p.getFileName().toString().startsWith("log-"))
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted((a, b) -> {
                        // Sort newest first
                        return b.getFileName().toString().compareTo(a.getFileName().toString());
                    })
                    .collect(Collectors.toList());

            // If more than 31 log files → delete oldest ones
            for (int i = 31; i < logFiles.size(); i++) {
                try {
                    Files.deleteIfExists(logFiles.get(i));
                } catch (Exception ignored) {
                }
            }

        } catch (Exception ignored) {
        }
    }

    /**
     * Storage retention: delete data CSVs older than
     * {@link Constant#DATA_RETENTION_DAYS} days from gnss/files_for_upload/ and
     * gnss/files_for_upload/archive/. Prevents the board disk from filling when
     * FTP is disabled (files_for_upload) or set to "archive". Safe no-op if the
     * folders don't exist yet.
     */
    public static void purgeOldUploadFiles() {
        try {
            long cutoff = System.currentTimeMillis()
                    - (long) Constant.DATA_RETENTION_DAYS * 24L * 60L * 60L * 1000L;

            Path uploadDir = jarDir.resolve("gnss").resolve("files_for_upload");
            Path archiveDir = uploadDir.resolve("archive");

            int deleted = 0;
            deleted += purgeDirOlderThan(uploadDir, cutoff);   // regular files only (skips archive/ subdir)
            deleted += purgeDirOlderThan(archiveDir, cutoff);

            if (Constant.DEBUG) {
                System.out.println("[DBG][Retention] purge (>" + Constant.DATA_RETENTION_DAYS
                        + "d) removed " + deleted + " file(s)");
            }
        } catch (Throwable t) {
            try {
                System.err.println("Retention sweep failed: " + safeMessage(t));
            } catch (Throwable ignored) {
            }
        }
    }

    private static int purgeDirOlderThan(Path dir, long cutoffMillis) {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> list = files.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path p : list) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoffMillis) {
                        Files.deleteIfExists(p);
                        count++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        String msg = t.getMessage();
        return (msg == null || msg.isEmpty()) ? t.toString() : msg;
    }

    private static void safeSleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Throwable ignored) {
        }
    }
}
