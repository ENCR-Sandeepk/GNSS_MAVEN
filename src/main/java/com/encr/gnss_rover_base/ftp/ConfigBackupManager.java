package com.encr.gnss_rover_base.ftp;

/**
 * Guaranteed-delivery backup of base/rover configuration to FTP.
 *
 * A backup is recorded as a PERSISTENT marker file (survives reboots / burst
 * power-cycles). The marker holds the exact remote filename (so the version
 * timestamp is fixed at trigger time -> one FTP file per event, even across
 * retries). The marker is deleted ONLY after a confirmed successful upload.
 * Until then, retryPending()/retryPendingAsync() keep re-attempting.
 *
 * Uploads go through FTPUploaderThread.uploadFileExclusive -> the shared
 * isRunning guard, so only ONE uploader is ever active at a time.
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.MainController;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ConfigBackupManager {

    private static final AtomicBoolean retrying = new AtomicBoolean(false);

    private static Path gnss() {
        return MainController.getJarDirectory().resolve("gnss");
    }

    private static Path baseMarker() {
        return gnss().resolve("config").resolve("base_backup_pending.flag");
    }

    private static Path roverMarker() {
        return gnss().resolve("rover_files").resolve("rover_backup_pending.flag");
    }

    /** Record a pending base config backup (remote filename fixed now). */
    public static synchronized void markBasePending(String remoteName) {
        writeMarker(baseMarker(), remoteName);
    }

    /** Record a pending rover config+state backup (remote zip name fixed now). */
    public static synchronized void markRoverPending(String zipName) {
        writeMarker(roverMarker(), zipName);
    }

    private static void writeMarker(Path marker, String name) {
        try {
            Files.createDirectories(marker.getParent());
            Files.write(marker, (name == null ? "" : name).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MainController.log("Backup marker write failed: " + e.getMessage());
        }
    }

    /**
     * Non-blocking retry: runs retryPending() on a daemon thread, but never more
     * than one at a time (so calling it every loop iteration is safe/cheap).
     */
    public static void retryPendingAsync() {
        if (retrying.compareAndSet(false, true)) {
            Thread t = new Thread(() -> {
                try {
                    retryPending();
                } finally {
                    retrying.set(false);
                }
            }, "cfg-backup-retry");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Attempt any pending uploads. A marker is deleted ONLY on confirmed success;
     * on failure it is left in place for the next retry. No-op if FTP disabled.
     */
    public static synchronized void retryPending() {
        if (!Variable.ftp_enable) {
            return;
        }
        int port = 21;
        try {
            port = Integer.parseInt(Variable.ftp_port.trim());
        } catch (Exception ignored) {
        }

        // ---- Base: upload the config file as-is ----
        try {
            Path marker = baseMarker();
            if (Files.exists(marker)) {
                String remoteName = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                Path configFile = gnss().resolve("config").resolve("save_gnss_receiver_settings.txt");
                if (remoteName.isEmpty() || !Files.exists(configFile)) {
                    Files.deleteIfExists(marker); // nothing valid to upload
                } else {
                    boolean ok = FTPUploaderThread.uploadFileExclusive(Variable.ftp_url, port,
                            Variable.ftp_user, Variable.ftp_password, configFile, remoteName);
                    if (ok) {
                        Files.deleteIfExists(marker);
                        MainController.log("Base config backup uploaded: " + remoteName);
                    } else {
                        MainController.log("Base config backup still pending (will retry): " + remoteName);
                    }
                }
            }
        } catch (Exception e) {
            MainController.log("Base backup retry error: " + e.getMessage());
        }

        // ---- Rover: bundle config + state files into a zip, then upload ----
        try {
            Path marker = roverMarker();
            if (Files.exists(marker)) {
                String zipName = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                if (zipName.isEmpty()) {
                    Files.deleteIfExists(marker);
                } else {
                    Path zip = buildRoverZip(zipName);
                    if (zip == null) {
                        Files.deleteIfExists(marker); // nothing to bundle
                    } else {
                        boolean ok = FTPUploaderThread.uploadFileExclusive(Variable.ftp_url, port,
                                Variable.ftp_user, Variable.ftp_password, zip, zipName);
                        try {
                            Files.deleteIfExists(zip); // local zip is a transport artifact
                        } catch (Exception ignored) {
                        }
                        if (ok) {
                            Files.deleteIfExists(marker);
                            MainController.log("Rover config backup uploaded: " + zipName);
                        } else {
                            MainController.log("Rover config backup still pending (will retry): " + zipName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            MainController.log("Rover backup retry error: " + e.getMessage());
        }
    }

    // Build gnss/<zipName> with config + the three rover state files; null if empty.
    private static Path buildRoverZip(String zipName) throws IOException {
        Path g = gnss();
        Path configFile = g.resolve("config").resolve("save_gnss_receiver_settings.txt");
        Path rf = g.resolve("rover_files");
        Path zipPath = g.resolve(zipName);
        int added = 0;
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            added += addEntry(zos, configFile, "config/save_gnss_receiver_settings.txt");
            added += addEntry(zos, rf.resolve("baseline.txt"), "rover_files/baseline.txt");
            added += addEntry(zos, rf.resolve("rover_avg.txt"), "rover_files/rover_avg.txt");
            added += addEntry(zos, rf.resolve("rover_liveBuffer.txt"), "rover_files/rover_liveBuffer.txt");
        }
        if (added == 0) {
            try {
                Files.deleteIfExists(zipPath);
            } catch (Exception ignored) {
            }
            return null;
        }
        return zipPath;
    }

    private static int addEntry(ZipOutputStream zos, Path file, String entry) {
        try {
            if (file == null || !Files.exists(file)) {
                return 0;
            }
            zos.putNextEntry(new ZipEntry(entry));
            Files.copy(file, zos);
            zos.closeEntry();
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Keep only filename-safe characters (letters, digits, dot, dash). */
    public static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("[^A-Za-z0-9.\\-]", "-");
    }
}
