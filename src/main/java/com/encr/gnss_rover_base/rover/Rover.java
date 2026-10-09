package com.encr.gnss_rover_base.rover;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.MainController;
import com.encr.gnss_rover_base.avg.SingleRoverAverageManager;
import com.encr.gnss_rover_base.calculation.GGAtoECEF_Converter;
import com.encr.gnss_rover_base.controller.TcpClientService;
import com.encr.gnss_rover_base.ftp.ConfigBackupManager;
import com.encr.gnss_rover_base.ftp.FTPUploaderThread;
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.DataServices;
import com.encr.gnss_rover_base.tool.FileManager;
import com.encr.gnss_rover_base.tool.MoveGnssFiles;
import com.encr.gnss_rover_base.tool.Tool;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// For Test Comits
public class Rover {

    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257223563;
    private static final double E2 = F * (2 - F);
//    static DecimalFormat dfLatLon = new DecimalFormat("0.00000000");
//    static DecimalFormat dfH = new DecimalFormat("0.0000");

    private static final DateTimeFormatter SIMPLE_FMT
            = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS XXX");

    private static final String GNSS_RESET_COMMANDS = "freset\r\n\r\n";
    private static final String[] GNSS_CONFIG_COMMANDS = {
        "interfacemode compass compass on",
        "unlogall",
        "log com1 gpgga ontime 1",
        "interfacemode auto auto on",
        "saveconfig"
    };

    private static SingleRoverAverageManager roverManager;
    private static Path jarDir;
    private static Path csvPath;

    // thread-safe counters / flags
    private static final AtomicInteger numberOfRecInFile = new AtomicInteger(0);
    private static final AtomicInteger validDataNotReceivedCounter = new AtomicInteger(0);
    private static final AtomicBoolean running = new AtomicBoolean(false);

    // Keep a reference so we can interrupt/join
    private static Thread uplinkThread = null;

    public static String numberOfSatellite = "";

    static boolean isIgnoredSampleCompleted = false;
    static int ingnoredSampleCount = 0;

    private static final ScheduledExecutorService scheduler
            = Executors.newSingleThreadScheduledExecutor();

    private static volatile boolean batteryTaskScheduled = false;

    public static void startRover() {

        boolean settingUpdated = Variable.isSettingModified;
        Variable.isSettingModified = false;
        jarDir = MainController.getJarDirectory();
        running.set(true);
        Variable.base = false;
        Variable.rover = true;

        TcpClientService.sendCommandUntilOkCount("GNSSTX,\"ENABLE\"", Constant.GNSSTX_ENABLE_OK_COUNT);

        while (Variable.rover) {

            if (!batteryTaskScheduled) {
                try {
                    scheduler.scheduleAtFixedRate(() -> updateBatteryVoltageAndTemp(), 0, 1, TimeUnit.MINUTES);
                    batteryTaskScheduled = true;
                } catch (Exception e) {
                }
            }

            Variable.roverStopped = false;

            try (Socket ntrip = createNtripSocket()) {
                // Load config safely
                Variable.config = MainController.safeReadModeFromFile();
                if (Variable.config == null || Variable.config.isEmpty()) {
                    MainController.log("Config file missing or invalid. Skipping...");
                    TcpClientService.sendStatusCode(Constant.STAT_ERR_CONFIG);
                    Thread.sleep(1000);
                    continue;
                }

                // ---------- IMPORTANT: Use correct keys here. Replace these Constant.KEY_* names if your Constant class uses different names ----------
                String ip = Variable.config.getOrDefault(Constant.KEY_ip, "").trim();
                String port = Variable.config.getOrDefault(Constant.KEY_port, "").trim();
                String mount = Variable.config.getOrDefault(Constant.KEY_mountPoint, "").trim();
                String user = Variable.config.getOrDefault(Constant.KEY_user, "").trim();
                String pass = Variable.config.getOrDefault(Constant.KEY_password, "").trim();

                // Fix: use the correct keys (previously password was reused for everything)
                String id = Variable.config.getOrDefault(Constant.KEY_rover_id, "").trim();              // e.g. KEY_rover_id
                String ssTime = Variable.config.getOrDefault(Constant.KEY_scan_start_time, "").trim();   // e.g. KEY_scan_start_time
                String logInterval = Variable.config.getOrDefault(Constant.KEY_scanInterval, "").trim();  // e.g. KEY_log_interval

                Variable.axis_enable = "true".equalsIgnoreCase(Variable.config.getOrDefault(Constant.KEY_axisEnable, "false").trim());
                try {
                    Variable.axis_angle_deg = Double.parseDouble(Variable.config.getOrDefault(Constant.KEY_axisAngle, "0").trim());
                } catch (NumberFormatException e) {
                    Variable.axis_angle_deg = 0.0;
                }

                // Temperature correction settings (stored only; not yet applied to the data)
                Variable.temp_enable = "true".equalsIgnoreCase(Variable.config.getOrDefault(Constant.KEY_tempEnable, "false").trim());
                String sType = Variable.config.getOrDefault(Constant.KEY_structureType, "bridge").trim();
                Variable.structure_type = (sType.isEmpty() || "null".equalsIgnoreCase(sType)) ? "bridge" : sType;
                Variable.temp_distance_m = configDouble(Constant.KEY_tempDistance, 0.0);
                Variable.temp_ref_c = configDouble(Constant.KEY_tempRefTemp, 0.0);
                Variable.temp_coeff_ppm = configDouble(Constant.KEY_tempCoeff, 0.0);
                Tool.dbg("Rover", "temp correction: enable=" + Variable.temp_enable + " type=" + Variable.structure_type
                        + " L=" + Variable.temp_distance_m + "m Tref=" + Variable.temp_ref_c + "C alpha=" + Variable.temp_coeff_ppm + "ppm/C");

                String ftpEnable = Variable.config.getOrDefault(Constant.KEY_ftpEnable, "false").trim();
                String ftpIp = Variable.config.getOrDefault(Constant.KEY_ftpIP, "").trim();
                String ftpPort = Variable.config.getOrDefault(Constant.KEY_ftpPort, "21").trim();
                String ftpUser = Variable.config.getOrDefault(Constant.KEY_ftpUser, "").trim();
                String ftpPass = Variable.config.getOrDefault(Constant.KEY_ftpPass, "").trim();

                String maxRecPerFile = Variable.config.getOrDefault(Constant.KEY_maxRecPerFile, "10").trim();
                String uploadFileAct = Variable.config.getOrDefault(Constant.KEY_uploadFileAction, "").trim();
                String dtFormat = Variable.config.getOrDefault(Constant.KEY_dateTimeFormat, "").trim();

                String reportingUnit = Variable.config.getOrDefault(Constant.KEY_deviationUnit, "").trim();
                String baseReadingDuration = Variable.config.getOrDefault(Constant.KEY_baseReading, "").trim();

                Variable.reportGeodeticValues = Variable.config.getOrDefault(Constant.KEY_geodetic, "false").trim().equalsIgnoreCase("true");
                String resetBaseReading = Variable.config.getOrDefault(Constant.KEY_resetBaseReading, "false").trim();

                // basic validation and helpful logs
                if (ip.isEmpty() || port.isEmpty() || mount.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    MainController.log("Missing core NTRIP settings. Check config file.");
                    Thread.sleep(1000);
                    continue;
                }

                // assign validated config to runtime variables and parse
                Variable.rover_id = id;

                if (Variable.is_burst_mode) {
                    Variable.scan_interval_sec_in_ms = Constant.DATA_REPORTING_INTERVAL_1_MIN;
                    Variable.scan_start_time_in_ms = Tool.convertDateTimeToMillis(Tool.getDateTimeStamp("dd-MMM-yyyy HH:mm"), "dd-MMM-yyyy HH:mm");
                } else {
                    Variable.scan_interval_sec_in_ms = Tool.get_data_in_sec(logInterval);
                    Variable.scan_start_time_in_ms = Tool.convertDateTimeToMillis(Tool.getDateTimeStamp("dd-MMM-yyyy ") + ssTime, "dd-MMM-yyyy HH:mm");
                    Tool.updateNextScanTime();
                }

                // Advance scan_start_time to the next occurrence in the future.
                // Continuous mode with a fixed daily start clock jumps by whole days
                // (only when settings were just updated). BURST mode must NEVER jump by
                // days -- its scan_start is the current minute, so it steps by the scan
                // interval; otherwise the record would be scheduled ~24h out and no CSV
                // is ever written during the short wake.
                if (settingUpdated && !Variable.is_burst_mode) {
                    settingUpdated = false;
                    while (Variable.scan_start_time_in_ms <= System.currentTimeMillis()) {
                        Variable.scan_start_time_in_ms += TimeUnit.DAYS.toMillis(1);
                    }
                } else {
                    settingUpdated = false;
                    while (Variable.scan_start_time_in_ms <= System.currentTimeMillis()) {
                        Variable.scan_start_time_in_ms += Variable.scan_interval_sec_in_ms;
                    }
                }

                Variable.ftp_enable = "true".equalsIgnoreCase(ftpEnable);
                Variable.ftp_url = ftpIp;
                Variable.ftp_port = ftpPort;
                Variable.ftp_user = ftpUser;
                Variable.ftp_password = ftpPass;

                // Retry any pending config backup (guaranteed delivery). Cheap no-op if none.
                ConfigBackupManager.retryPendingAsync();

                try {
                    Variable.max_rec_per_file = Integer.parseInt(maxRecPerFile);
                } catch (NumberFormatException nfe) {
                    Variable.max_rec_per_file = 100;
                }
                Variable.upload_file_action = uploadFileAct;
                Variable.date_time_format = dtFormat;
                Variable.reporting_unit = reportingUnit;
                Variable.base_line_duration = Tool.get_base_line_data_points(baseReadingDuration);
                Variable.reset_base_reading = "true".equalsIgnoreCase(resetBaseReading);

                if (Variable.reset_base_reading) {
                    clearRoverFilesOnReset();
                }

                if (Variable.is_burst_mode) {

                    int baselineSec = Variable.base_line_duration;
                    int intervalSec = (int) (Tool.get_burst_duration_ms(Variable.burst_interval) / 1000);
                    int windowSec = (int) (Tool.get_burst_duration_ms(Variable.burst_window) / 1000);
                    if (intervalSec <= 0) {
                        intervalSec = (windowSec > 0) ? windowSec : 1;
                    }
                    int sessions = baselineSec / intervalSec;
                    if (sessions < 1) {
                        sessions = 1;
                    }
                    Variable.base_line_duration = sessions * windowSec;
                    roverManager = new SingleRoverAverageManager(jarDir, Variable.base_line_duration);
                } else {
                    roverManager = new SingleRoverAverageManager(jarDir, Variable.base_line_duration);
                }

                // Connect to NTRIP
                connectAndRunStreams(ip, Integer.parseInt(port), mount, user, pass);

            } catch (Exception e) {
                // log the exception, don't swallow it
                e.printStackTrace();
                System.err.println(ts() + " Rover loop error: " + e.getMessage());
                TcpClientService.sendStatusCode(Constant.STAT_ERR_NTRIP_UNREACH);

            } finally {
                try {
                    Thread.sleep(5000);

                } catch (InterruptedException ignored) {
                }
            }
        }
        System.out.println("======================EXIT====================================");
        try {
            Thread.sleep(5000);
        } catch (Exception e) {
        }
        Variable.roverStopped = true;
        TcpClientService.sendStatusCode(Constant.STAT_ROVER_STOPPED);
    }

    private static Socket createNtripSocket() throws IOException {
        // placeholder - actual connection created later in connectAndRunStreams
        return null;
    }

    private static void connectAndRunStreams(String ip, int port, String mount, String user, String pass) throws IOException, InterruptedException {

        try (Socket ntrip = new Socket(ip, port)) {
            ntrip.setTcpNoDelay(true);
            ntrip.setSoTimeout(15000);
            InputStream nIn = new BufferedInputStream(ntrip.getInputStream());
            OutputStream nOut = new BufferedOutputStream(ntrip.getOutputStream());

            String auth = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            String req = "GET /" + mount + " HTTP/1.1\r\n"
                    + "User-Agent: RoverClient\r\n"
                    + "Authorization: Basic " + auth + "\r\n"
                    + "Ntrip-Version: Ntrip/1.0\r\n"
                    + "Connection: Keep-Alive\r\n\r\n";
            nOut.write(req.getBytes(StandardCharsets.US_ASCII));
            nOut.flush();

            String hdr = readHttpHeader(nIn);
            if (hdr == null || !hdr.startsWith("ICY 200")) {
                throw new IOException("NTRIP rejected: " + hdr);
            }
            System.out.println(ts() + " Connected to server NTRIP");

            // Connect to rover
            try (Socket rover = new Socket(Variable.system_ip, Variable.system_port)) {
                rover.setTcpNoDelay(true);
                // set a small timeout so readLine doesn't block forever (helps shutdown)
                rover.setSoTimeout(10_000); // 10s
                InputStream rIn = new BufferedInputStream(rover.getInputStream());
                OutputStream rOut = new BufferedOutputStream(rover.getOutputStream());
                System.out.println(ts() + " Connected to rover " + Variable.system_ip + ":" + Variable.system_port);

                TcpClientService.sendStatusCode(Constant.STAT_ROVER_CONNECTED);

                sendInitialCommands(rOut);

                long lastRtcmTime = System.currentTimeMillis();

                // start uplink thread (reads GGA and sends to NTRIP and handles CSV)
                final AtomicBoolean uplinkRunning = new AtomicBoolean(true);
                uplinkThread = new Thread(() -> {
                    try {
                        pumpGGAUpstream(rIn, nOut, uplinkRunning);
                    } catch (Exception ex) {
                        System.err.println(ts() + " uplink thread exception: " + ex.getMessage());
                    }
                }, "gga-uplink");
                uplinkThread.setDaemon(true);
                uplinkThread.start();

                // Main loop: forward RTCM to rover
                byte[] buf = new byte[8192];
                int n;
                while (Variable.rover && running.get()) {
                    try {
                        n = nIn.read(buf); // socket timeout triggers SocketTimeoutException
                        if (n > 0) {
                            lastRtcmTime = System.currentTimeMillis();
//                            printRTCMDebug(buf, n);
                            rOut.write(buf, 0, n);
                            rOut.flush();
                        }
                    } catch (SocketTimeoutException ste) {
                        if (System.currentTimeMillis() - lastRtcmTime > TimeUnit.MINUTES.toMillis(5)) {
                            System.err.println(ts() + " No correction data for 5 min. Reconnecting...");
                            throw new IOException("No RTCM for 5 minutes");
                        }
                        // otherwise continue waiting
                    }
                }

                // shutdown uplink politely
                uplinkRunning.set(false);
                try {
                    if (uplinkThread != null) {
                        uplinkThread.join(2000);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static void sendInitialCommands(OutputStream rOut) throws IOException {
        // Small warm-up delay after establishing socket before writing
        try {
            Thread.sleep(300); // allow rover firmware to become ready
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Wrap write in try/catch to detect SocketExceptions early
        try {
            rOut.write(GNSS_RESET_COMMANDS.getBytes(StandardCharsets.US_ASCII));
            rOut.flush();
            System.out.println(ts() + " Sent reset command to rover");
        } catch (SocketException se) {
            // propagate so caller can handle reconnect/backoff
            throw se;
        }

        try {
            Thread.sleep(5_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        for (String cmd : GNSS_CONFIG_COMMANDS) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String full = cmd + "\r\n";
            try {
                rOut.write(full.getBytes(StandardCharsets.US_ASCII));
                rOut.flush();
                System.out.println(ts() + " Sent init command to rover: " + cmd.trim());
            } catch (SocketException se) {
                // Rover closed socket while sending a config command
                System.err.println(ts() + " SocketException while sending config command: " + se.getMessage());
                throw se;
            }
        }
    }

    /**
     * pumpGGAUpstream: reads lines from rover input, converts them, updates
     * averages, writes CSVs and optionally starts FTP uploader. This version
     * uses a small read timeout and is safely stoppable via the uplinkRunning
     * flag.
     */
    static void pumpGGAUpstream(InputStream roverIn, OutputStream ntripOut, AtomicBoolean uplinkRunning) {
        isIgnoredSampleCompleted = false;
        ingnoredSampleCount = 0;
        DecimalFormat dfLatLon = new DecimalFormat("0.00000000");
        DecimalFormat dfH = new DecimalFormat("0.0000");

        BufferedReader br = new BufferedReader(new InputStreamReader(roverIn, StandardCharsets.US_ASCII));
        String line;

        // initialize baseline if available
        double[] baseline = (roverManager != null) ? roverManager.getBaseline() : null;
        if (baseline != null) {
            Variable.base_data[Constant.NORTH] = baseline[Constant.NORTH];
            Variable.base_data[Constant.EAST] = baseline[Constant.EAST];
            Variable.base_data[Constant.ALTITUDE] = baseline[Constant.ALTITUDE];
        }

        while (Variable.rover && running.get() && uplinkRunning.get()) {
            try {
                line = br.readLine();
                if (line == null) {
                    break;
                }
                if (!line.startsWith("$")) {
                    continue;
                }

                Tool.dbg("Rover", "Received GGA: " + line);

                ingnoredSampleCount++;

                if (!isIgnoredSampleCompleted) {
                    if (ingnoredSampleCount > Constant.IGNORED_SAMPLE) {
                        isIgnoredSampleCompleted = true;
                    }
                    continue;
                }

                String ecef = GGAtoECEF_Converter.convertGGAtoECEF(line);

                validDataNotReceivedCounter.incrementAndGet();

                if (ecef != null && !ecef.isEmpty()) {
                    validDataNotReceivedCounter.set(0);
                    String[] parts = ecef.split(",");

                    numberOfSatellite = parts[Constant.GPS];

                    // safety checks
                    if (parts.length <= Math.max(Constant.ALTITUDE, Math.max(Constant.NORTH, Constant.EAST))) {
                        System.err.println(ts() + " ecef conversion returned insufficient fields: " + ecef);
                        continue;
                    }
                    try {
                        double north = Double.parseDouble(parts[Constant.NORTH]);
                        double east = Double.parseDouble(parts[Constant.EAST]);
                        double alt = Double.parseDouble(parts[Constant.ALTITUDE]);
                        if (!Variable.rover_got_valid_data) {
                            TcpClientService.sendStatusCode(Constant.STAT_ROVER_RTK_FIX);   // once, on first RTK fix this wake
                            // BURST: upload the backlog NOW, while the device is safely
                            // awake for the rest of the window. This avoids the shutdown
                            // race of the flush-time upload. Runs once per wake (this
                            // block only executes on the first fix). The single-run guard
                            // in FTPUploaderThread ensures no concurrent uploader.
                            if (Variable.is_burst_mode) {
                                MoveGnssFiles.moveFilesForUpload();
                                startFtpUploaderSafely();
                            }
                        }
                        roverManager.addSample(north, east, alt);

                        Variable.rover_got_valid_data = true;

                        // If addSample just established a FRESH baseline, record a
                        // PERSISTENT backup marker and kick a retry. The marker is cleared
                        // only after a confirmed FTP upload, so it retries (this wake and
                        // future wakes) until the config+state zip truly reaches the server.
                        if (Variable.rover_baseline_just_established) {
                            Variable.rover_baseline_just_established = false;
                            String stamp = Tool.convert_ms_into_date(System.currentTimeMillis(),
                                    Constant.DATE_TIME_FORMAT_FOR_FILE_NAME);
                            String serverIp = Variable.config.getOrDefault(Constant.KEY_ip, "").trim();
                            String serverPort = Variable.config.getOrDefault(Constant.KEY_port, "").trim();
                            String mount = Variable.config.getOrDefault(Constant.KEY_mountPoint, "").trim();
                            String zipName = ConfigBackupManager.sanitize(Variable.rover_id) + "_"
                                    + ConfigBackupManager.sanitize(serverIp) + "_"
                                    + ConfigBackupManager.sanitize(serverPort) + "_"
                                    + ConfigBackupManager.sanitize(mount) + "_" + stamp + ".zip";
                            ConfigBackupManager.markRoverPending(zipName);
                            ConfigBackupManager.retryPendingAsync();
                        }
                    } catch (NumberFormatException nfe) {
                        System.err.println(ts() + " Number parse error from UTM: " + nfe.getMessage());
                        continue;
                    }
                }

                double[] result = roverManager.getAverage();

                if (result != null && validDataNotReceivedCounter.get() < Constant.MAX_VALID_DATA_NOT_REC_COUNT) {
                    String[] outputParts = new String[partsSizeGuess()];
                    outputParts[Constant.GPS] = numberOfSatellite;

                    if (Variable.base_data[Constant.NORTH] != 0.0) {

                        double[] geod = ecefToGeodetic(result[Constant.NORTH], result[Constant.EAST], result[Constant.ALTITUDE]);

                        double latDeg = Math.toDegrees(geod[0]);
                        double lonDeg = Math.toDegrees(geod[1]);
                        double h = geod[2];

//                        outputParts[Constant.NORTH] = String.valueOf(result[Constant.NORTH] - Variable.base_data[Constant.NORTH]);
//                        outputParts[Constant.EAST] = String.valueOf(result[Constant.EAST] - Variable.base_data[Constant.EAST]);
//                        outputParts[Constant.ALTITUDE] = String.valueOf(result[Constant.ALTITUDE] - Variable.base_data[Constant.ALTITUDE]);
//                        ======================================================================
//                      ENU Conversion
                        double dX = result[Constant.NORTH] - Variable.base_data[Constant.NORTH];
                        double dY = result[Constant.EAST] - Variable.base_data[Constant.EAST];
                        double dZ = result[Constant.ALTITUDE] - Variable.base_data[Constant.ALTITUDE];

                        double[] baseGeod = ecefToGeodetic(Variable.base_data[Constant.NORTH],
                                Variable.base_data[Constant.EAST],
                                Variable.base_data[Constant.ALTITUDE]);

                        // Convert ECEF deviation -> true local ENU.
                        // geod[] was computed just above (line 452) = ecefToGeodetic(rover avg);
                        // geod[0]=latitude(rad), geod[1]=longitude(rad). Rover is within cm of the
                        // base, so it is the correct rotation origin to nanoradian precision.
                        double[] enu = ecefDeltaToEnu(dX, dY, dZ, baseGeod[0], baseGeod[1]);

                        double dE = enu[0];   // East
                        double dN = enu[1];   // North
                        double dU = enu[2];   // Up

                        if (Variable.axis_enable) {
                            // Rotate horizontal (N,E) onto the user's reference axis.
                            // theta = azimuth clockwise from True North.
                            double theta = Math.toRadians(Variable.axis_angle_deg);
                            double along = dN * Math.cos(theta) + dE * Math.sin(theta);
                            double transverse = -dN * Math.sin(theta) + dE * Math.cos(theta);

                            outputParts[Constant.NORTH] = String.valueOf(along);       // Along-axis
                            outputParts[Constant.EAST] = String.valueOf(transverse);  // Transverse-axis
                            outputParts[Constant.ALTITUDE] = String.valueOf(dU);          // Up (unchanged)
                        } else {
                            outputParts[Constant.NORTH] = String.valueOf(dN);  // true North
                            outputParts[Constant.EAST] = String.valueOf(dE);  // true East
                            outputParts[Constant.ALTITUDE] = String.valueOf(dU);  // true Up
                        }

//                        outputParts[Constant.NORTH] = String.valueOf(enu[1]);  // dNORTHING = true North
//                        outputParts[Constant.EAST] = String.valueOf(enu[0]);  // dEASTING  = true East
//                        outputParts[Constant.ALTITUDE] = String.valueOf(enu[2]);  // dALTITUDE = true Up (settlement)
                        outputParts[Constant.GeodeticNORTH] = dfLatLon.format(latDeg);
                        outputParts[Constant.GeodeticEAST] = dfLatLon.format(lonDeg);
                        outputParts[Constant.GeodeticALTITUDE] = dfH.format(h);

                    } else {
                        outputParts[Constant.NORTH] = "0.0";
                        outputParts[Constant.EAST] = "0.0";
                        outputParts[Constant.ALTITUDE] = "0.0";
                        outputParts[Constant.GeodeticNORTH] = "0.0";
                        outputParts[Constant.GeodeticEAST] = "0.0";
                        outputParts[Constant.GeodeticALTITUDE] = "0.0";
                    }

                    // Unit conversion
                    try {
                        double conversionFactor = DataServices.getConversionFactor(Variable.reporting_unit);
                        outputParts[Constant.NORTH] = DataServices.convertUnit(outputParts[Constant.NORTH], conversionFactor);
                        outputParts[Constant.EAST] = DataServices.convertUnit(outputParts[Constant.EAST], conversionFactor);
                        outputParts[Constant.ALTITUDE] = DataServices.convertUnit(outputParts[Constant.ALTITUDE], conversionFactor);
                    } catch (Exception ex) {
                        System.err.println(ts() + " Error converting units: " + ex.getMessage());
                    }

                    // time to write CSV?
                    if (Variable.scan_start_time_in_ms <= System.currentTimeMillis()) {

                        String dataLine = Tool.convert_ms_into_date(Variable.scan_start_time_in_ms, Variable.date_time_format.trim())
                                + DataServices.getData_static(outputParts.clone(), Variable.reportGeodeticValues)
                                + "," + Variable.battery + "," + Variable.temperature;

                        if (Variable.is_burst_mode) {
                            // Burst: keep only the LAST record; it is flushed once, at sleep time.
                            if (Variable.rover_got_valid_data) {          // only if the rover actually computed a value
                                Variable.last_burst_record = dataLine;
                                Tool.dbg("Rover", "burst record buffered: " + dataLine);
                            }
                        } else {
                            // Continuous: existing behaviour (create file + move/FTP + append every interval)
                            if (numberOfRecInFile.get() == 0) {
                                try {
                                    MoveGnssFiles.moveFilesForUpload();
                                    if (Variable.ftp_enable) {
                                        FTPUploaderThread uploader = new FTPUploaderThread(Variable.ftp_url,
                                                Integer.parseInt(Variable.ftp_port), Variable.ftp_user, Variable.ftp_password);
                                        uploader.setDaemon(true);
                                        uploader.start();
                                    }
                                } catch (Exception e) {
                                }
                                csvPath = FileManager.createGnssCsvFile(jarDir,
                                        DataServices.getHeader(Variable.reportGeodeticValues),
                                        Tool.convert_ms_into_date(Variable.scan_start_time_in_ms, Constant.DATE_TIME_FORMAT_FOR_FILE_NAME));
                                if (csvPath == null) {
                                    System.err.println(ts() + " Failed to create CSV file. Skipping data write.");
                                    continue;
                                }
                            }
                            FileManager.appendGnssData(csvPath, dataLine);
                            numberOfRecInFile.incrementAndGet();
                            if (numberOfRecInFile.get() >= Variable.max_rec_per_file) {
                                numberOfRecInFile.set(0);
                            }
                        }

                        while (Variable.scan_start_time_in_ms <= System.currentTimeMillis()) {
                            Variable.scan_start_time_in_ms += Variable.scan_interval_sec_in_ms;
                        }

                    }
                }

            } catch (SocketTimeoutException ste) {
                // harmless -- read timed out; loop again to check running flags
            } catch (IOException ioe) {
                System.err.println(ts() + " GGA uplink closed: " + ioe.getMessage());
                break;
            } catch (Exception ex) {
                System.err.println(ts() + " Unexpected error in GGA uplink: " + ex.getMessage());
            }
        } // end while
    }

    private static int partsSizeGuess() {
        // return appropriate size to hold north,east,alt etc. Adjust to DataServices expectations
        return 7;
    }

    private static void clearRoverFilesOnReset() {
        Path roverFilePath = jarDir.resolve("gnss").resolve("rover_files");
        try (java.util.stream.Stream<Path> files = Files.list(roverFilePath)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                    System.out.println(ts() + " Deleted: " + path);
                } catch (IOException e) {
                    System.err.println(ts() + " Failed to delete: " + path + " -> " + e.getMessage());
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        roverFilePath = jarDir.resolve("gnss").resolve("base_files");
        try (java.util.stream.Stream<Path> files = Files.list(roverFilePath)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.delete(path);
                    System.out.println(ts() + " Deleted: " + path);
                } catch (IOException e) {
                    System.err.println(ts() + " Failed to delete: " + path + " -> " + e.getMessage());
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }

        MainController.update_contol_file_after_base_reading_reset();
    }

// Helper: print RTCM bytes (hex + ASCII)
//    static void printRTCMDebug(byte[] buf, int len) {
//        StringBuilder hex = new StringBuilder();
//        StringBuilder ascii = new StringBuilder();
//        for (int i = 0; i < len; i++) {
//            hex.append(String.format("%02X ", buf[i]));
//            ascii.append((buf[i] >= 32 && buf[i] < 127) ? (char) buf[i] : '.');
//            if ((i + 1) % 16 == 0 || i == len - 1) {
    ////                System.out.printf("%-48s | %s%n", hex.toString(), ascii.toString());
//                hex.setLength(0);
//                ascii.setLength(0);
//            }
//        }
//    }
    static String readHttpHeader(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // State machine to detect \r\n\r\n
        // state: 0=normal, 1=saw \r, 2=saw \r\n, 3=saw \r\n\r
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            baos.write(b);
            switch (state) {
                case 0:
                    state = (b == '\r') ? 1 : 0;
                    break;
                case 1:
                    state = (b == '\n') ? 2 : (b == '\r') ? 1 : 0;
                    break;
                case 2:
                    state = (b == '\r') ? 3 : 0;
                    break;
                case 3:
                    if (b == '\n') {
                        // Found \r\n\r\n — header complete
                        return baos.toString("US-ASCII");
                    }
                    state = (b == '\r') ? 1 : 0;
                    break;
            }
            if (baos.size() > 16 * 1024) {
                break; // safety limit
            }
        }
        return baos.size() == 0 ? null : baos.toString("US-ASCII");
    }

    /**
     * Starts the FTP uploader if FTP is enabled. Safe to call from multiple
     * points (first RTK fix, flush, continuous rotation): the static isRunning
     * guard inside FTPUploaderThread guarantees that only ONE upload actually
     * runs at a time — any call made while an upload is already in progress
     * simply no-ops ("already running"). So there is no race and never two
     * concurrent uploaders.
     */
    private static void startFtpUploaderSafely() {
        if (!Variable.ftp_enable) {
            return;
        }
        try {
            FTPUploaderThread uploader = new FTPUploaderThread(Variable.ftp_url,
                    Integer.parseInt(Variable.ftp_port), Variable.ftp_user, Variable.ftp_password);
            uploader.setDaemon(true);
            uploader.start();
        } catch (Exception e) {
            System.err.println(ts() + " FTP uploader start failed: " + e.getMessage());
        }
    }

    // Writes the single buffered burst record to the CSV, then clears it.
    public static void flushLastBurstRecord() {
        if (!Variable.is_burst_mode || Variable.last_burst_record == null) {
            Tool.dbg("Rover", "flushLastBurstRecord: nothing to write (no valid data this wake)");
            return;
        }
        Tool.dbg("Rover", "flushLastBurstRecord: writing 1 record for this wake");
        try {
            MoveGnssFiles.moveFilesForUpload();
            // Best-effort backup upload; primary upload already happened mid-window
            // on first RTK fix. Deduped by FTPUploaderThread's single-run guard.
            startFtpUploaderSafely();
            Path p = FileManager.createGnssCsvFile(jarDir,
                    DataServices.getHeader(Variable.reportGeodeticValues),
                    Tool.convert_ms_into_date(System.currentTimeMillis(), Constant.DATE_TIME_FORMAT_FOR_FILE_NAME));
            if (p != null) {
                FileManager.appendGnssData(p, Variable.last_burst_record);
            }
        } catch (Exception e) {
            System.err.println(ts() + " flushLastBurstRecord error: " + e.getMessage());
        }
        Variable.last_burst_record = null;
    }

//    static String ts() {
//        return String.format("[%tF %tT]", new Date(), new Date());
//    }
    static String ts() {

        return "[" + Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.systemDefault())
                .format(SIMPLE_FMT) + "]";
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

    // Rotate an ECEF displacement (dX,dY,dZ) into local East/North/Up
// at the given latitude/longitude (radians). Returns {E, N, U}.
    private static double[] ecefDeltaToEnu(double dX, double dY, double dZ,
            double latRad, double lonRad) {
        double sinLat = Math.sin(latRad), cosLat = Math.cos(latRad);
        double sinLon = Math.sin(lonRad), cosLon = Math.cos(lonRad);
        double e = -sinLon * dX + cosLon * dY;
        double n = -sinLat * cosLon * dX - sinLat * sinLon * dY + cosLat * dZ;
        double u = cosLat * cosLon * dX + cosLat * sinLon * dY + sinLat * dZ;
        return new double[]{e, n, u};
    }

    // Read a numeric config value; missing, empty, "null" or malformed -> default.
    private static double configDouble(String key, double def) {
        try {
            String v = Variable.config.getOrDefault(key, "").trim();
            if (v.isEmpty() || "null".equalsIgnoreCase(v)) {
                return def;
            }
            return Double.parseDouble(v);
        } catch (Exception e) {
            return def;
        }
    }

    private static void updateBatteryVoltageAndTemp() {
        if (!TcpClientService.isConnected()) {
            return;
        }

        try {
            String reply = TcpClientService.sendCommandAndGetReply("MONPARA,\"?\"");

            if (reply != null) {
                String[] values = reply.split(",");

                if (values.length >= 2) {
                    Variable.battery = values[0];
                    Variable.temperature = values[1];
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
