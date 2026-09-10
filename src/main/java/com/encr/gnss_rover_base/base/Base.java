package com.encr.gnss_rover_base.base;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.MainController;
import com.encr.gnss_rover_base.controller.TcpClientService;
import com.encr.gnss_rover_base.ftp.ConfigBackupManager;
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.Tool;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * Robust Base streamer for long-running operation.
 */
public class Base {

    // Local GNSS device (adjust per site)
    static String[] baseCoordinates;

    // Reset and config commands for GNSS
    private static final String GNSS_RESET_COMMANDS = "freset\r\n\r\n";

    static final String[] BASE_CONFIG_COMMANDS = {
        "unlogall\r\n",
        "fix auto\r\n",
        "log com1 rtcm1074b ontime 1\r\n",
        "log com1 rtcm1084b ontime 1\r\n",
        "log com1 rtcm1094b ontime 1\r\n",
        "log com1 rtcm1114b ontime 1\r\n",
        "log com1 rtcm1124b ontime 1\r\n",
        "log com1 rtcm1005b ontime 5\r\n",
        "log com1 rtcm1033b ontime 10\r\n",
        "log com1 gpgga ontime 1\r\n",
        "saveconfig\r\n\r\n",
        "\r\n\r\n"
    };

    /**
     * Main loop to start and maintain base streaming to the NTRIP server.
     * Designed to run for a very long time with robust reconnect/backoff.
     */
    public static void startBase() {
        Variable.rover = false;
        Variable.base = true;

        long reconnectDelayMs = 1000; // initial backoff
        final long MAX_BACKOFF = 60_000; // max 60s

        TcpClientService.sendCommandUntilOkCount("GNSSTX,\"ENABLE\"", Constant.GNSSTX_ENABLE_OK_COUNT);

        while (Variable.base) {
            Variable.isSettingModified = false;
            Variable.baseStopped = false;
            try {

                Variable.config = MainController.safeReadModeFromFile();

                if (Variable.config == null || Variable.config.isEmpty()) {
                    MainController.log("Config file missing or invalid. Sleeping 2s...");
                    sleepInterruptibly(2000);
                    continue;
                }

                if ("true".equalsIgnoreCase(Variable.config.getOrDefault(Constant.KEY_resetBaseCoordinates, "false").trim())) {
                    clearFilesOnBaseCoordinateReset();
                    MainController.update_contol_file_after_base_coordinates_reset();
                }

                String ip = Variable.config.getOrDefault(Constant.KEY_ip, "").trim();
                String portStr = Variable.config.getOrDefault(Constant.KEY_port, "").trim();
                String mount = Variable.config.getOrDefault(Constant.KEY_mountPoint, "").trim();
                String user = Variable.config.getOrDefault(Constant.KEY_user, "").trim();
                String pass = Variable.config.getOrDefault(Constant.KEY_password, "").trim();
                String avg = Variable.config.getOrDefault(Constant.KEY_average, "1hr").trim();

                String latitude = Variable.config.getOrDefault(Constant.KEY_lat, "").trim();
                String longitude = Variable.config.getOrDefault(Constant.KEY_lon, "").trim();
                String altitude = Variable.config.getOrDefault(Constant.KEY_alt, "").trim();
                String coordinateMode = Variable.config.getOrDefault(Constant.KEY_coordinateMode, "").trim();

                // FTP config (same keys as rover) — used only for the base config-backup
                // upload after a fresh position is computed. Does not affect anything else.
                Variable.ftp_enable = "true".equalsIgnoreCase(Variable.config.getOrDefault(Constant.KEY_ftpEnable, "false").trim());
                Variable.ftp_url = Variable.config.getOrDefault(Constant.KEY_ftpIP, "").trim();
                Variable.ftp_port = Variable.config.getOrDefault(Constant.KEY_ftpPort, "21").trim();
                Variable.ftp_user = Variable.config.getOrDefault(Constant.KEY_ftpUser, "").trim();
                Variable.ftp_password = Variable.config.getOrDefault(Constant.KEY_ftpPass, "").trim();

                // Retry any pending config backup (guaranteed delivery). Cheap no-op if none.
                ConfigBackupManager.retryPendingAsync();

                if (ip.isEmpty() || portStr.isEmpty() || mount.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                    MainController.log("Config incomplete. Sleeping 2s...");
                    TcpClientService.sendStatusCode(Constant.STAT_ERR_CONFIG);
                    sleepInterruptibly(2000);
                    continue;
                }

                Variable.base_station_coordinates_done = false;
                boolean baseLocationNotFound = false;
                if (coordinateMode.equalsIgnoreCase("auto")) {
                    if (latitude.isEmpty() || longitude.isEmpty() || altitude.isEmpty()
                            || latitude.equalsIgnoreCase("null") || longitude.equalsIgnoreCase("null") || altitude.equalsIgnoreCase("null")) {
                        MainController.log("Going to collect base station co ordinates ");
                        TcpClientService.sendStatusCode(Constant.STAT_BASE_SURVEY);
                        baseLocationNotFound = true;
                    }
                }

                if (baseLocationNotFound) {
                    baseCoordinates = new GNSSAutoECEFCollector().startGNSSAutoECEFCollector(Tool.get_base_line_data_points(avg), Variable.system_ip, Variable.system_port);
//                    baseCoordinates = new BaseAveragerECEF().startBaseAveragerECEF(Tool.get_base_line_data_points(avg), Variable.system_ip, Variable.system_port);
                    if (baseCoordinates == null) {
                        MainController.log("Failed to collect base coordinates. Retrying...");
                        TcpClientService.sendStatusCode(Constant.STAT_ERR_BASE_COORD_FAIL);
                        sleepInterruptibly(2000);
                        continue;
                    }
                    MainController.update_contol_file_after_base_coordinates_get(baseCoordinates);
                    // A fresh base position was just computed. Record a PERSISTENT backup
                    // marker (the full config file) and attempt upload now. The marker is
                    // cleared only after a confirmed FTP upload, so it keeps retrying
                    // (this wake and future wakes) until it truly succeeds.
                    {
                        String stamp = Tool.convert_ms_into_date(System.currentTimeMillis(),
                                Constant.DATE_TIME_FORMAT_FOR_FILE_NAME);
                        String backupName = ConfigBackupManager.sanitize(ip) + "_"
                                + ConfigBackupManager.sanitize(portStr) + "_"
                                + ConfigBackupManager.sanitize(mount) + "_" + stamp + ".txt";
                        ConfigBackupManager.markBasePending(backupName);
                        ConfigBackupManager.retryPendingAsync();
                    }
                    if (Variable.is_burst_mode) {
                        Tool.set_next_wawkeup_time_and_sleep();
                    }
                } else {
                    baseCoordinates = new String[]{latitude, longitude, altitude};
                }

                Variable.base_station_coordinates_done = true;
                Variable.is_base_station_coordinates_reads = true;
                TcpClientService.sendStatusCode(Constant.STAT_BASE_COORDS_READY);

//                try {
//                    while (baseLocationNotFound) {
//                        sleepInterruptibly(2000);
//                    }
//                } catch (Exception e) {
//                }
//                if (baseCoordinates == null) {
//                    sleepInterruptibly(2000);
//                    continue;
//                }
                // Use a local copy
                String[] commands = BASE_CONFIG_COMMANDS.clone();
                commands[1] = "FIX position " + baseCoordinates[0] + " " + baseCoordinates[1] + " " + baseCoordinates[2] + "\r\n";

                int serverPort;
                try {
                    serverPort = Integer.parseInt(portStr);
                } catch (NumberFormatException nfe) {
                    MainController.log("Invalid server port: " + portStr + " — sleeping 2s");
                    sleepInterruptibly(2000);
                    continue;
                }

                // Reset backoff on successful attempt to connect
                reconnectDelayMs = 1000;

                // Create and connect sockets with timeouts
                try (Socket base = new Socket(); Socket srv = new Socket()) {

                    Tool.dbg("Base", "connecting: receiver=" + Variable.system_ip + ":" + Variable.system_port
                            + " NTRIP=" + ip + ":" + serverPort);

                    base.connect(new InetSocketAddress(Variable.system_ip, Variable.system_port), 4000);
                    srv.connect(new InetSocketAddress(ip, serverPort), 5000);

                    // Set a read timeout for srv so ack reads throw SocketTimeoutException
                    srv.setSoTimeout(15_000); // 15s ack read timeout

                    // set tcp options
                    base.setTcpNoDelay(true);
                    base.setSoTimeout(60_000);
                    srv.setTcpNoDelay(true);

                    // Use buffered streams and close them with sockets
                    try (InputStream baseIn = new BufferedInputStream(base.getInputStream()); OutputStream baseOut = new BufferedOutputStream(base.getOutputStream()); InputStream srvIn = new BufferedInputStream(srv.getInputStream()); OutputStream srvOut = new BufferedOutputStream(srv.getOutputStream())) {

                        MainController.log("Connected to server NTRIP");
                        MainController.log("Connected to base GNSS " + Variable.system_ip + ":" + Variable.system_port);

                        // 1) Send reset & configuration commands to base device
                        sleepInterruptibly(2000);
                        baseOut.write(GNSS_RESET_COMMANDS.getBytes(StandardCharsets.US_ASCII));
                        baseOut.flush();
                        MainController.log("Sent reset command to Base");

                        sleepInterruptibly(10_000);

                        for (String cmd : commands) {
                            sleepInterruptibly(250);
                            baseOut.write(cmd.getBytes(StandardCharsets.US_ASCII));
                            baseOut.flush();
                            MainController.log("Sent config to base: " + cmd.trim());
                        }

                        sleepInterruptibly(10_000);

                        // 2) Authenticate as SOURCE to NTRIP server
                        String req = "SOURCE " + pass + " /" + mount + "\r\n\r\n";
                        srvOut.write(req.getBytes(StandardCharsets.US_ASCII));
                        srvOut.flush();

                        String line = readLineAscii(srvIn, 4096);
                        if (line == null || !line.toUpperCase(Locale.ROOT).contains("OK")) {
                            MainController.log("SOURCE rejected by server: " + line);
                            // server rejected, will reconnect after small delay
                            throw new IOException("SOURCE rejected: " + line);
                        }
                        MainController.log("SOURCE accepted by server");
                        TcpClientService.sendStatusCode(Constant.STAT_BASE_STREAMING);

                        // 3) Stream RTCM from base → server
                        byte[] buf = new byte[8192];
                        int n;
                        long lastBaseDataTime = System.currentTimeMillis();

                        // Reset base stopped flag inside active connection
                        Variable.baseStopped = false;

                        while (Variable.base) {
                            // Blocking read; returns -1 on EOF
                            try {
                                n = baseIn.read(buf);
                            } catch (SocketTimeoutException ste) {
                                // No data within the socket read timeout (60s). This is normal-ish; use it to run the watchdog.
                                if (System.currentTimeMillis() - lastBaseDataTime > 5L * 60L * 1000L) {  // 5 min
                                    MainController.log("No base data for 5 minutes. Reconnecting...");
                                    throw new IOException("No base data for 5 minutes");
                                }
                                continue;   // re-check Variable.base and keep waiting
                            }

                            if (n == -1) {
                                MainController.log("Base input stream closed (EOF). Will reconnect.");
                                throw new IOException("Base device closed connection");
                            }

                            lastBaseDataTime = System.currentTimeMillis();
//                            if (n == 0) {
//                                // rare, but avoid busy spinning
//                                sleepInterruptibly(10);
//                                continue;
//                            }

                            Tool.dbg("Base", "streamed " + n + " bytes RTCM -> NTRIP");

                            // Write to server
                            try {
                                srvOut.write(buf, 0, n);
                                srvOut.flush();
                            } catch (IOException writeEx) {
                                MainController.log("Failed to write to server: " + safeMsg(writeEx));
                                throw writeEx;
                            }

                            // Try to read an ACK/heartbeat from server (read with timeout)
//                            try {
//                                int ack = srvIn.read(); // will block up to setSoTimeout
//                                if (ack == -1) {
//                                    MainController.log("Server closed connection (ACK read -1). Reconnect.");
//                                    throw new IOException("Server closed connection");
//                                } else {
//                                    lastAckTime = System.currentTimeMillis();
//                                    MainController.log("Server ACK/heartbeat received");
//                                }
//                            } catch (SocketTimeoutException ste) {
//                                // No ack within timeout — check longer threshold (5 minutes)
//                                if (System.currentTimeMillis() - lastAckTime > 5L * 60L * 1000L) {
//                                    MainController.log("No ACK from server for 5 minutes. Reconnecting...");
//                                    throw new IOException("Server not acknowledging for 5 min");
//                                }
//                                // else just continue streaming
//                            }
                        } // end while(Variable.base) streaming loop

                        MainController.log("Base streaming loop ended normally");
                    } // streams auto-closed
                } // sockets auto-closed

            } catch (InterruptedException ie) {
                // Respect interruption and stop gracefully
                MainController.log("Base thread interrupted: " + safeMsg(ie));
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                MainController.log("Base error: " + safeMsg(e));
                TcpClientService.sendStatusCode(Constant.STAT_ERR_NTRIP_UNREACH);
                e.printStackTrace();
            }

            // connection ended — backoff before reconnecting
            try {
                MainController.log("Reconnecting in " + reconnectDelayMs + " ms");
                Thread.sleep(reconnectDelayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            reconnectDelayMs = Math.min(reconnectDelayMs * 2, MAX_BACKOFF);
        } // outer while

        Variable.baseStopped = true;
        MainController.log("Base stopped");
        TcpClientService.sendStatusCode(Constant.STAT_BASE_STOPPED);
    }

    private static void clearFilesOnBaseCoordinateReset() {
        Path gnssDir = MainController.getJarDirectory().resolve("gnss");
        String[] folders = {"base_files", "rover_files"};

        for (String folder : folders) {
            Path dir = gnssDir.resolve(folder);
            if (!Files.exists(dir)) {
                continue;                       // folder not on this device → skip safely
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    try {
                        Files.delete(path);
                        MainController.log("Reset base coords — deleted: " + path.getFileName());
                    } catch (Exception e) {
                        MainController.log("Reset base coords — failed to delete " + path.getFileName() + ": " + safeMsg(e));
                    }
                });
            } catch (Exception e) {
                MainController.log("Reset base coords — failed to list " + dir + ": " + safeMsg(e));
            }
        }
    }

    // read a single ASCII line (CRLF tolerant). returns null on EOF
    static String readLineAscii(InputStream in, int max) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        int cnt = 0;
        while (true) {
            b = in.read(); // -1 on EOF
            if (b == -1) {
                if (baos.size() == 0) {
                    return null;
                }
                break;
            }
            cnt++;
            if (cnt > max) {
                break;
            }
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                baos.write(b);
            }
        }
        return baos.toString(StandardCharsets.US_ASCII.name());
    }

    static String ts() {
        return String.format("[%tF %tT]", new java.util.Date(), new java.util.Date());
    }

    // helper: small safe wrapper
    private static void sleepInterruptibly(long ms) throws InterruptedException {
        if (ms <= 0) {
            return;
        }
        Thread.sleep(ms);
    }

    private static String safeMsg(Throwable t) {
        if (t == null) {
            return "Unknown";
        }
        String m = t.getMessage();
        return m == null ? t.toString() : m;
    }
}
