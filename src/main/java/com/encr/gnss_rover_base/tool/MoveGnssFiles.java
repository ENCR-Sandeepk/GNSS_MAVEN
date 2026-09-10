/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MoveGnssFiles {

    public static Path getJarDirectory() {
        try {
            String path = MoveGnssFiles.class
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

    public static void moveFilesForUpload() {
        try {
            Path jarDir = getJarDirectory();
            Path gnssDir = jarDir.resolve("gnss");
            Path dataFilesDir = gnssDir.resolve("data_files");
            Path uploadDir = gnssDir.resolve("files_for_upload");

            if (!Files.exists(dataFilesDir)) {
                System.out.println("⚠ Source folder not found: " + dataFilesDir);
                return;
            }
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // STEP 1: Load files into a list (important!)
            List<Path> filesToMove;
            try (Stream<Path> stream = Files.list(dataFilesDir)) {
                filesToMove = stream
                        .filter(Files::isRegularFile)
                        .collect(Collectors.toList());
            } // stream closes completely here

            // STEP 2: Now safely move files
            for (Path file : filesToMove) {
                try {
                    Path targetFile = uploadDir.resolve(file.getFileName());
                    Files.move(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("✅ Moved: " + file.getFileName());
                } catch (IOException e) {
                    System.err.println("❌ Failed to move: " + file.getFileName());
                    e.printStackTrace();
                }
            }

            System.out.println("🎯 All files moved successfully to: " + uploadDir);

        } catch (IOException e) {
            System.err.println("❌ Error occurred while moving files:");
            e.printStackTrace();
        }
    }

}
