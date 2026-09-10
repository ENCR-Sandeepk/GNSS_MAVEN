/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.controller.TcpClientService;
import static com.encr.gnss_rover_base.tool.NextIntervalTime.getNextMultipleDateTime;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Sandeep K
 */
public class Tool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static String backslashReplace(String myStr) {
        return myStr == null ? "" : myStr.replace("\\", "/");
    }

    // Debug log -> System.out, only when Constant.DEBUG is enabled (toggleable from the UI).
    public static void dbg(String tag, String msg) {
        if (Constant.DEBUG) {
            LocalDateTime now = LocalDateTime.now();
            String nowDate = now.format(DATE_FMT);
            String time = now.format(TIME_FMT);
            String dateTimeStamp = "[" + nowDate + " " + time + "] : ";
            System.out.println(dateTimeStamp + "[DBG][" + tag + "] " + msg);
        }
    }

    public static String setDecimalDigitsWithoutE(String input, int decimalPlaces) {
        try {
            BigDecimal value = new BigDecimal(input);
            value = value.setScale(decimalPlaces, RoundingMode.HALF_UP);
            return value.toPlainString(); // Avoids scientific notation
        } catch (NumberFormatException e) {
            e.printStackTrace();
            // Return default "0.00" if input is invalid
            return BigDecimal.ZERO.setScale(decimalPlaces, RoundingMode.HALF_UP).toPlainString();
        }
    }

    public static long toLocalTimeMillis(long utcMillis) {
        ZoneId zone = ZoneId.systemDefault();
        ZoneOffset offset = zone.getRules().getOffset(Instant.ofEpochMilli(utcMillis));
        return utcMillis + offset.getTotalSeconds() * 1000L;
    }

    public static String getDateTimeStamp(String dateTimeFormat) {
        try {
            return new SimpleDateFormat(dateTimeFormat, Locale.US).format(new Date());
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            // fallback to ISO format
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        }
    }

    public static long convertDateTimeToMillis(String timeStamp, String dateTimeFormat) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(dateTimeFormat, Locale.US);
            return sdf.parse(timeStamp).getTime();
        } catch (ParseException e) {
            System.err.println("Failed to parse date: " + timeStamp + " with format: " + dateTimeFormat);
            e.printStackTrace();
        }
        return 0L;
    }

    public static String convert_ms_into_date(long ms, String format) {
        try {
            return new SimpleDateFormat(format).format(new Date(ms));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    private static final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    public static void save_records(String file_path, String header, String data) {
        // Use a lock object per file
        Object lock = fileLocks.computeIfAbsent(file_path, k -> new Object());

        synchronized (lock) {
            File file = new File(file_path);
            boolean isNewFile = false;

            try {
                if (!file.exists()) {
                    isNewFile = file.createNewFile();
                }

                // try-with-resources for safe closing
                try (PrintWriter pr = new PrintWriter(new BufferedWriter(new FileWriter(file, true)))) {
                    if (isNewFile) {
                        pr.println(header);
                    }
                    pr.println(data);
                }

            } catch (IOException e) {
                System.err.println("IOException while saving records to " + file_path);
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("Unexpected error in save_records for file: " + file_path);
                e.printStackTrace();
            }
        }
    }

    /**
     * Converts burst window/interval string (e.g. "30s", "10min", "1hr") to
     * milliseconds. Returns default 60_000ms (1 minute) if value is unknown.
     */
    public static long get_burst_duration_ms(String value) {
        if (value == null) {
            return 60_000L;
        }

        Map<String, Long> map = new HashMap<>();
        map.put("30s", 30_000L);
        map.put("1min", 60_000L);
        map.put("2min", 2L * 60_000L);
        map.put("5min", 5L * 60_000L);
        map.put("10min", 10L * 60_000L);
        map.put("15min", 15L * 60_000L);
        map.put("20min", 20L * 60_000L);
        map.put("30min", 30L * 60_000L);
        map.put("1hr", 1L * 3600_000L);
        map.put("2hr", 2L * 3600_000L);
        map.put("3hr", 3L * 3600_000L);
        map.put("4hr", 4L * 3600_000L);
        map.put("6hr", 6L * 3600_000L);
        map.put("12hr", 12L * 3600_000L);
        map.put("24hr", 24L * 3600_000L);

        Long result = map.get(value.toLowerCase().trim());
        return (result != null) ? result : 60_000L;
    }

    public static void set_next_wawkeup_time_and_sleep() {

        Tool.dbg("Sleep", "set_next_wawkeup_time_and_sleep() ENTERED");
        TcpClientService.sendStatusCode(Constant.STAT_PREPARE_SLEEP);
        try {
            Tool.dbg("Sleep", "stopping watchdog scheduler + sending WATCHDOG DISABLE (connected=" + TcpClientService.isConnected() + ")");
            BeagleControllerConnectionChecker.stopWatchdog();
            if (TcpClientService.isConnected()) {
                TcpClientService.sendCommandAndGetReply("WATCHDOG,\"DISABLE\"");
            }
        } catch (Exception e) {
        }
        try {
            int intervalHr = (int) (Tool.get_burst_duration_ms(Variable.burst_interval) / 3600000);

            if (intervalHr < 1) {
                intervalHr = 1;
            }

            boolean isBase = "base".equalsIgnoreCase(Variable.config.getOrDefault(Constant.KEY_deviceType, "").trim());
            long wakeLead = isBase ? Constant.WAKEUP_LEAD_MIN_BASE : Constant.WAKEUP_LEAD_MIN_ROVER;

            long wakeLeadSeconds = wakeLead * 60L;

            long alarmSeconds = NextIntervalTime.getDifferenceInSeconds(
                    NextIntervalTime.getCurrentTime("HH:mm:ss"),
                    getNextMultipleDateTime(intervalHr).format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                    - wakeLeadSeconds;

            // SAFETY FLOOR: never send 0/negative (would strand the unit). Now in SECONDS.
            final long MIN_SAFE_ALARM_SEC = Constant.MIN_SAFE_ALARM_SEC;
            if (alarmSeconds < MIN_SAFE_ALARM_SEC) {
                Tool.dbg("Sleep", "alarmSeconds=" + alarmSeconds + " below safe floor; clamping to " + MIN_SAFE_ALARM_SEC);
                alarmSeconds = MIN_SAFE_ALARM_SEC;
            }

            String alarmCommand = "SETALARM," + alarmSeconds;

            Tool.dbg("Sleep", "computed alarm: isBase=" + isBase + " wakeLead=" + wakeLead + " -> " + alarmCommand);

            int attempt = 0;
            while (true) {
                attempt++;
                if (TcpClientService.isConnected()) {

                    TcpClientService.sendCommandAndGetReply(alarmCommand);
                    Tool.dbg("Sleep", "SETALARM attempt " + attempt + " sent, status=" + Variable.statusCode);
                    if (Variable.statusCode == Constant.OK_STATUS) {
                        System.out.println("SETALARM confirmed OK (1000), sending second time for safety...");
                        TcpClientService.sendCommand(alarmCommand);
                        break;
                    }
                    System.out.println("SETALARM not acknowledged (status: " + Variable.statusCode + "), retrying in 5s...");
                } else {
                    System.out.println("SETALARM not sent (not connected), retrying in 5s...");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
            TcpClientService.sendStatusCode(Constant.STAT_ALARM_SET);
            Tool.dbg("Sleep", "SETALARM acknowledged; shutting down in 3s...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            TcpClientService.sendStatusCode(Constant.STAT_SHUTTING_DOWN);
            Tool.shutdownSystem();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public static int get_base_line_data_points(String value) {

        if (value == null) {
            return Constant.BASE_LINE_DATA_POINTS_1_HOUR;
        }

        Map<String, Integer> intervalMap = new HashMap<>();

        intervalMap.put("1hr", Constant.BASE_LINE_DATA_POINTS_1_HOUR);
        intervalMap.put("2hr", Constant.BASE_LINE_DATA_POINTS_2_HOUR);
        intervalMap.put("3hr", Constant.BASE_LINE_DATA_POINTS_3_HOUR);
        intervalMap.put("4hr", Constant.BASE_LINE_DATA_POINTS_4_HOUR);
        intervalMap.put("6hr", Constant.BASE_LINE_DATA_POINTS_6_HOUR);
        intervalMap.put("8hr", Constant.BASE_LINE_DATA_POINTS_8_HOUR);
        intervalMap.put("12hr", Constant.BASE_LINE_DATA_POINTS_12_HOUR);
        intervalMap.put("24hr", Constant.BASE_LINE_DATA_POINTS_24_HOUR);
        intervalMap.put("48hr", Constant.BASE_LINE_DATA_POINTS_48_HOUR);
        intervalMap.put("72hr", Constant.BASE_LINE_DATA_POINTS_72_HOUR);

        Integer result = intervalMap.get(value.toLowerCase().trim());
        return result != null ? result : Constant.BASE_LINE_DATA_POINTS_1_HOUR;
    }

    public static int get_data_in_sec(String value) {

        if (value == null) {
            return Constant.DATA_REPORTING_INTERVAL_1_MIN;
        }

        Map<String, Integer> intervalMap = new HashMap<>();

        intervalMap.put("1min", Constant.DATA_REPORTING_INTERVAL_1_MIN);
        intervalMap.put("5min", Constant.DATA_REPORTING_INTERVAL_5_MIN);
        intervalMap.put("10min", Constant.DATA_REPORTING_INTERVAL_10_MIN);
        intervalMap.put("15min", Constant.DATA_REPORTING_INTERVAL_15_MIN);
        intervalMap.put("30min", Constant.DATA_REPORTING_INTERVAL_30_MIN);
        intervalMap.put("1hr", Constant.DATA_REPORTING_INTERVAL_1_HOUR);
        intervalMap.put("2hr", Constant.DATA_REPORTING_INTERVAL_2_HOUR);
        intervalMap.put("3hr", Constant.DATA_REPORTING_INTERVAL_3_HOUR);
        intervalMap.put("6hr", Constant.DATA_REPORTING_INTERVAL_6_HOUR);
        intervalMap.put("12hr", Constant.DATA_REPORTING_INTERVAL_12_HOUR);
        intervalMap.put("24hr", Constant.DATA_REPORTING_INTERVAL_24_HOUR);

        Integer result = intervalMap.get(value.toLowerCase().trim());
        return result != null ? result : Constant.DATA_REPORTING_INTERVAL_1_MIN;
    }

    public static String removeDoubleQuotes(String text) {
        try {
            if ((text != null)) {
                if (text.contains("\"")) {
                    text = text.substring(1, text.length() - 1).trim();
                }
                return text;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return text;
    }

    public static void delayInMilliSecond(int milliSeconds) {

        long curmillies = System.currentTimeMillis();
        while ((System.currentTimeMillis() - curmillies) < milliSeconds) {
            try {
                Thread.sleep(50);
            } catch (Exception e) {
            }
        }
    }
// milliseconds since local midnight

    private static long msOfDay() {
        java.time.LocalTime t = java.time.LocalTime.now();
        return t.toSecondOfDay() * 1000L + t.getNano() / 1_000_000L;
    }

// Is 'now' inside a scheduled logging window?  windowLenMs = burst window (+ extra run).
    public static boolean isBurstLoggingTime(long windowLenMs) {
        long intervalMs = get_burst_duration_ms(Variable.burst_interval);
        if (intervalMs <= 0) {
            return false;
        }
        long into = msOfDay() % intervalMs;      // time since the most recent boundary
        return into < windowLenMs;
    }

// milliseconds until the next logging window starts
    public static long msUntilNextBurstWindow() {
        long intervalMs = get_burst_duration_ms(Variable.burst_interval);
        if (intervalMs <= 0) {
            return Long.MAX_VALUE;
        }
        long into = msOfDay() % intervalMs;
        return intervalMs - into;
    }

    public static void shutdownSystem() {
        System.out.println("shut down System ...................................");
        try {
            String[] command = {"sudo", "-S", "shutdown", "-h", "now"};
            Process process = Runtime.getRuntime().exec(command);

            java.io.OutputStream outputStream = process.getOutputStream();
            outputStream.write(("beagle" + "\n").getBytes());
            outputStream.flush();
            outputStream.close();

            process.waitFor();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void updateNextScanTime() {

        long currentTime = System.currentTimeMillis();

        long scanStartTime = Variable.scan_start_time_in_ms;
        long interval = Variable.scan_interval_sec_in_ms;

        if (interval <= 0) {
            return;
        }

        long nextScanTime;

        if (scanStartTime > currentTime) {

            long difference = scanStartTime - currentTime;

            long intervals = difference / interval;

            if (difference % interval != 0) {
                intervals++;
            }

            nextScanTime = scanStartTime - (interval * intervals);

            while (nextScanTime <= currentTime) {
                nextScanTime += interval;
            }

        } else {

            long difference = currentTime - scanStartTime;

            long intervals = (difference / interval) + 1;

            nextScanTime = scanStartTime + (interval * intervals);
        }

        // Update the variable
        Variable.scan_start_time_in_ms = nextScanTime;
    }
}
