/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.ftp;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.tool.Variable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.stream.Stream;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;

public class FTPUploaderThread extends Thread {

    private final String host;
    private final int port;
    private final String user;
    private final String pass;

    private static final int MAX_RETRIES = 3;
    private static final int UPLOAD_TIMEOUT_MINUTES = 10;

    private static volatile boolean isRunning = false;

    public FTPUploaderThread(String host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        setName("FTPUploaderThread");
        setDaemon(true);
    }

    // =====================================================
    //                HOST VALIDATION METHODS
    // =====================================================
    private boolean isValidIPv4(String ip) {
        return ip.matches(
                "^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)"
                + "(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}$"
        );
    }

    // IPv6 validation including compressed (::)
    private boolean isValidIPv6(String ip) {
        return ip.matches(
                "^(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}$"
                + // full
                "|^(?:[0-9A-Fa-f]{1,4}:){1,7}:$"
                + // compressed ending ::
                "|^:(?::[0-9A-Fa-f]{1,4}){1,7}$"
                + // starting ::
                "|^(?:[0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4}$" // partial compression
        );
    }

    private boolean isValidDomain(String domain) {
        return domain.matches(
                "^(?!-)([A-Za-z0-9-]{1,63}\\.)+[A-Za-z]{2,}$"
        );
    }

    private boolean isLocalhost(String host) {
        return host.equalsIgnoreCase("localhost");
    }

    // DNS resolution
    private boolean resolveHost(String host) {
        try {
            java.net.InetAddress.getByName(host);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // TCP reachability test
    private boolean testConnection(String host, int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 5000);
            return true;
        } catch (Exception e) {
            System.err.println("⚠ Cannot connect to " + host + ":" + port);
            return false;
        }
    }

    private boolean isValidHost(String host, int port) {

        boolean formatOk = (isValidIPv4(host)
                || isValidIPv6(host)
                || isValidDomain(host)
                || isLocalhost(host));

        if (!formatOk) {
            System.err.println("❌ Invalid host format: " + host);
            return false;
        }

        if (!resolveHost(host)) {
            System.err.println("❌ Host cannot be resolved: " + host);
            return false;
        }

        if (!testConnection(host, port)) {
            System.err.println("❌ Host not reachable: " + host + ":" + port);
            return false;
        }

        return true;
    }

    // =====================================================
    //                     MAIN RUN LOOP
    // =====================================================
    public static Path getJarDirectory() {
        try {
            String path = FTPUploaderThread.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            return Paths.get(path).getParent();
        } catch (Exception e) {
            return Paths.get("").toAbsolutePath();
        }
    }

    @Override
    public void run() {
        synchronized (FTPUploaderThread.class) {
            if (isRunning) {
                System.out.println("⚠ FTP upload already running. Skipping…");
                return;
            }
            isRunning = true;
        }

        try {

            System.out.println("🚀 FTP upload starting…");

            // ✔ Host validation before uploading anything
            if (!isValidHost(host, port)) {
                System.err.println("❌ Host validation failed — aborting upload.");
                return;
            }

            Path uploadDir = getJarDirectory().resolve("gnss/files_for_upload");

            if (!Files.exists(uploadDir)) {
                System.out.println("⚠ Folder missing: " + uploadDir);
                return;
            }

            try (Stream<Path> files = Files.list(uploadDir)) {
                files.filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong(f -> f.toFile().lastModified()))
                        .forEach(this::handleFileUpload);
            } catch (IOException e) {
                System.err.println("❌ Error reading files: " + e.getMessage());
            }

            System.out.println("✅ FTP upload completed.");

        } finally {
            isRunning = false;
        }
    }

    // =====================================================
    //                     FILE UPLOAD
    // =====================================================
    private void handleFileUpload(Path file) {
        String fileName = file.getFileName().toString();
        ExecutorService executor = Executors.newSingleThreadExecutor();  // ← OUTSIDE loop, created ONCE

        try {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {

                // NOTE: do NOT gate uploading on Variable.rover. The uploader is a
                // short-lived queue drainer; in burst mode it is started from the
                // sleep/flush path (when rover is already false), and in continuous
                // mode it may still be draining when the rover briefly stops. Gating
                // on rover made it skip every file and upload nothing. The per-attempt
                // timeout + MAX_RETRIES + isRunning guard already bound its lifetime.

                System.out.println("📤 Uploading " + fileName
                        + " (attempt " + attempt + "/" + MAX_RETRIES + ")");

                Future<Boolean> future = executor.submit(() -> uploadSingleFile(file));

                try {
                    boolean success = future.get(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                    if (success) {
                        System.out.println("✅ Uploaded: " + fileName);
                        return;  // success, exit method
                    } else {
                        System.out.println("⚠ Upload failed: " + fileName);
                    }
                } catch (TimeoutException te) {
                    System.err.println("⏱ Timeout: " + fileName);
                    future.cancel(true);
                } catch (Exception e) {
                    System.err.println("❌ Upload error: " + e.getMessage());
                }

                // Retry delay
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }

            System.err.println("❌ All retries failed for " + fileName);

        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Actual FTP upload
    private boolean uploadSingleFile(Path file) {
        FTPClient ftpClient = new FTPClient();
        String fileName = file.getFileName().toString();

        try (FileInputStream fis = new FileInputStream(file.toFile())) {

            ftpClient.setConnectTimeout(10000);
            ftpClient.connect(host, port);
            ftpClient.login(user, pass);
            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            boolean done = ftpClient.storeFile(fileName, fis);

            fis.close();  // <-- VERY IMPORTANT FOR WINDOWS FILE UNLOCK

            ftpClient.logout();
            ftpClient.disconnect();

            // give OS time to release lock
            Thread.sleep(50);

            if (done) {
                delete_or_archiveFile(file);
            }

            return done;

        } catch (Exception e) {
            try {
                if (ftpClient.isConnected()) {
                    ftpClient.disconnect();
                }
            } catch (IOException ignored) {
            }
            System.err.println("❌ Upload exception: " + fileName + " → " + e.getMessage());
            return false;
        }
    }

    // Move file to archive folder or delete after successful upload
    private void delete_or_archiveFile(Path file) {
        try {
            if (Variable.upload_file_action.equalsIgnoreCase("archive")) {
                try {
                    int attempts = 0;
                    while (attempts < 5) {
                        if (archiveFile(file)) {
                            break;
                        }
                        attempts++;
                        Thread.sleep(500); // handle InterruptedException
                    }

                } catch (Exception e) {
                    System.err.println("⚠ Failed to Archive for: " + file.getFileName());
                }
            } else {
                try {
                    // delete file  
                    Files.deleteIfExists(file);
                    System.out.println("🗑 File deleted after successful upload: " + file.getFileName());
                } catch (Exception e) {
                    System.err.println("⚠ Failed to delete for: " + file.getFileName());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean archiveFile(Path file) {
        if (file == null || !Files.exists(file)) {
            return false;
        }
        if (Files.isDirectory(file)) {
            return false;
        }

        Path parent = file.getParent();
        Path archiveDir = parent.resolve("archive");

        try {
            if (!Files.exists(archiveDir)) {
                Files.createDirectories(archiveDir);
            }
        } catch (Exception e) {
            return false;
        }

        Path target = archiveDir.resolve(file.getFileName());

        // ===== FIRST TRY MOVE WITH RETRIES =====
        for (int i = 0; i < 5; i++) {
            try {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("📦 Archived: " + file.getFileName());
                return true;
            } catch (Exception ex) {
                try {
                    Thread.sleep(100);
                } catch (Exception ignore) {
                }
            }
        }

        // ===== FALLBACK COPY+DELETE WITH RETRIES =====
        for (int i = 0; i < 5; i++) {
            try {
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(file);
                System.out.println("📦 Archived (copied+deleted): " + file.getFileName());
                return true;
            } catch (Exception ex) {
                try {
                    Thread.sleep(100);
                } catch (Exception ignore) {
                }
            }
        }

        System.err.println("❌ Failed to archive: " + file.getFileName());
        return false;
    }

    /**
     * One-shot upload of a single local file to the FTP root under an explicit
     * remote name. This is fully ISOLATED from the CSV-drain flow
     * (run()/handleFileUpload/uploadSingleFile): it does NOT touch isRunning,
     * does NOT read files_for_upload, and does NOT archive or delete the local
     * file. Used for the base config-backup upload. Returns true only on a
     * confirmed store.
     */
    public static boolean uploadFileAs(String host, int port, String user, String pass,
            Path localFile, String remoteName) {
        if (localFile == null || !Files.exists(localFile)) {
            System.err.println("❌ Config backup: local file missing: " + localFile);
            return false;
        }
        FTPClient ftpClient = new FTPClient();
        try (FileInputStream fis = new FileInputStream(localFile.toFile())) {
            ftpClient.setConnectTimeout(10000);
            ftpClient.connect(host, port);
            ftpClient.login(user, pass);
            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);

            boolean done = ftpClient.storeFile(remoteName, fis);

            ftpClient.logout();
            ftpClient.disconnect();

            System.out.println((done ? "✅ Config backup uploaded: " : "⚠ Config backup upload failed: ") + remoteName);
            return done;
        } catch (Exception e) {
            try {
                if (ftpClient.isConnected()) {
                    ftpClient.disconnect();
                }
            } catch (IOException ignored) {
            }
            System.err.println("❌ Config backup upload exception: " + remoteName + " → " + e.getMessage());
            return false;
        }
    }

    /**
     * Exclusive one-shot upload — waits (bounded) for any in-progress uploader to
     * finish, then uploads under the SAME isRunning guard used by the CSV drain,
     * so only ONE uploader is ever active at a time. Used for the rover
     * config+state backup (which can coincide with the rover's CSV upload).
     */
    public static boolean uploadFileExclusive(String host, int port, String user, String pass,
            Path localFile, String remoteName) {
        for (int i = 0; i < 60; i++) {   // wait up to ~60s for any active upload to finish
            boolean acquired;
            synchronized (FTPUploaderThread.class) {
                acquired = !isRunning;
                if (acquired) {
                    isRunning = true;
                }
            }
            if (acquired) {
                try {
                    return uploadFileAs(host, port, user, pass, localFile, remoteName);
                } finally {
                    isRunning = false;
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        System.err.println("⚠ Config backup skipped — uploader busy >60s: " + remoteName);
        return false;
    }

}
