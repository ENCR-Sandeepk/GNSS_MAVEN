/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import java.util.Map;

/**
 *
 * @author Sandeep K
 */
public class Variable {

    public static String system_ip = "192.168.10.100";
    public static int system_port = 2000;
    public static volatile boolean got_reply = false;
    public static volatile String reply = "";
    public static int statusCode = 1000;
    public static long downloadWatchdogTimer = 0;
    public static int rec_count = 1000;
    public static String downloadData = "";
    public static String tempDownloadedData = "";
    public static volatile boolean dataDownloadCompleted = false;
    public static volatile boolean is_burst_mode = false;
    public static volatile boolean burst_window_in_sec = false;

    public static volatile String operating_mode = "burst";
    public static volatile String burst_window = "10min";
    public static volatile String burst_interval = "1hr";

    public static int current_status_code = 0;

    // Base
    public static boolean base_station_coordinates_done = false;
    public static boolean is_base_station_coordinates_reads = false;

    // Rover Settings
    public static String rover_id = "Rover";
    public static long scan_start_time_in_ms;
    public static int scan_interval_sec_in_ms = 60000;
    public static volatile boolean axis_enable = false;
    public static volatile double axis_angle_deg = 0.0;   // azimuth, degrees, CW from North

    // Temperature correction settings (read from config, applied to the horizontal deviation)
    public static volatile boolean temp_enable = false;
    public static volatile String structure_type = "bridge";
    public static volatile double temp_distance_m = 0.0;       // distance from fixed bearing, metres
    public static volatile double temp_ref_c = 0.0;            // reference temperature (manual), deg C
    public static volatile double temp_coeff_ppm = 0.0;        // structure thermal coefficient, PPM / deg C
    public static volatile double temp_direction_deg = 0.0;    // azimuth fixed bearing -> rover, deg CW from True North
    public static volatile String temp_ref_mode = "manual";    // "manual" (temp_ref_c) | "auto" (temp_baseline_c)
    public static volatile double temp_baseline_c = Double.NaN; // avg temperature over the baseline period (auto mode)
    public static volatile String last_burst_record = null;
    public static volatile boolean rover_got_valid_data = false;   // true once a real RTK fix is processed this wake
    public static volatile boolean rover_baseline_just_established = false;   // set once when a FRESH baseline is computed (not loaded); triggers config-backup upload

    // Ftp Settings 
    public static boolean ftp_enable = false;
    public static String ftp_url = "0.0.0.0";
    public static String ftp_port = "";
    public static String ftp_user = "";
    public static String ftp_password = "";

    // Data Storage
    public static int max_rec_per_file = 10;
    public static String upload_file_action = "archive";
    public static String date_time_format = "yyyy/MM/dd HH:mm:ss";

    // Reporting
    public static String reporting_unit = "mm";
    public static int base_line_duration = 48 * 60 * 60;
    public static boolean reset_base_reading = false;
    public static boolean reportGeodeticValues = true;
    public static String battery = "--";
    public static String temperature = "--";

    // Advance Settings
    public static volatile boolean base = false;
    public static volatile boolean baseStopped = true;
    public static volatile boolean rover = false;
    public static volatile boolean roverStopped = true;
    public static volatile boolean isSettingModified = false;

    // Receiver Configuration
    public static volatile Map<String, String> config;
    public static double base_data[] = new double[Constant.MAX_PARAMETER];

}
