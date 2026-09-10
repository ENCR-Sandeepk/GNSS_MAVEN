/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import java.io.*;
import java.nio.file.*;

public class FileManager {

    /**
     * Create (if not exists) a "data_files" folder inside "gnss" directory next
     * to your running JAR.
     *
     * @param jarDir
     */
    public static Path getOrCreateDataFolder(Path jarDir) {
        try {

            // Locate "gnss" folder inside jarDir
            Path gnssDir = jarDir.resolve("gnss");
            if (!Files.exists(gnssDir)) {
                Files.createDirectories(gnssDir);
            }

            // Create "data_files" inside gnss
            Path dataFilesDir = gnssDir.resolve("data_files");
            if (!Files.exists(dataFilesDir)) {
                Files.createDirectories(dataFilesDir);
            }

            return dataFilesDir;
        } catch (IOException e) {
            e.printStackTrace();
            return Paths.get("").toAbsolutePath();
        }
    }

    /**
     * Create a new GNSS data CSV file inside the data_files folder. The file
     * will be named based on current date & time.
     */
    public static Path createGnssCsvFile(Path jarDir, String header, String fileDT_Stamp) {
        try {
            Path dataDir = getOrCreateDataFolder(jarDir);

            // Timestamped filename (e.g. gnss_data_2025-11-13_1145.csv)
            String fileName = Variable.rover_id + "_" + fileDT_Stamp + ".csv";

            Path csvPath = dataDir.resolve(fileName);

            // Create file with header if new
            if (!Files.exists(csvPath)) {
                try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
                    writer.write(header);
                    writer.write(System.lineSeparator());  // <-- Add new line here
                }
            }

            return csvPath;

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Append one GNSS data line into the latest CSV file.
     */
    public static void appendGnssData(Path csvPath, String data) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
        )) {
            writer.write(data);
            writer.newLine();
            writer.flush();                         // IMPORTANT for Windows
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Example test usage
//    public static void main(String[] args) {
//        Path csvFile = createGnssCsvFile("", "");
//        if (csvFile != null) {
//            System.out.println("Logging to: " + csvFile);
//            appendGnssData(csvFile, 28.6139, 77.2090, 213.5, "RTK_FIX");
//            appendGnssData(csvFile, 28.6140, 77.2091, 213.6, "RTK_FLOAT");
//        }
//    }
}
