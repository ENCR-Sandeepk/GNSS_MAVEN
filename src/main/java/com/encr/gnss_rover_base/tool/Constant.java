package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
public class Constant {

    public static boolean DEBUG = true;

    public static final String VERSION = "0.A.0";

    public static final int FTP_TIMEOUT = 5 * 60 * 1000;
    public static final int MAX_VALID_DATA_NOT_REC_COUNT = 1 * 60 * 60;
    public static final int NORTH = 0;
    public static final int EAST = 1;
    public static final int ALTITUDE = 2;
    public static final int GeodeticNORTH = 4;
    public static final int GeodeticEAST = 5;
    public static final int GeodeticALTITUDE = 6;
    public static final int GPS = 3;
    public static final int MAX_PARAMETER = 3;

    public static final int IGNORED_SAMPLE = 30;

    public static final String DATE_TIME_FORMAT_FOR_FILE_NAME = "yyyyMMdd_HHmmss";
    // keys

    public static final String KEY_operating_mode = "gnssMode";
    public static final String KEY_burst_window = "burstWindow";
    public static final String KEY_burst_interval = "burstInterval";

    public static final String KEY_axisEnable = "axisEnable";
    public static final String KEY_axisAngle = "axisAngle";

    // Temperature correction (UI: Rover Settings -> Temperature Correction)
    public static final String KEY_tempEnable = "tempEnable";
    public static final String KEY_structureType = "structureType";
    public static final String KEY_tempDistance = "tempDistance";   // m, from fixed bearing
    public static final String KEY_tempRefTemp = "tempRefTemp";     // deg C
    public static final String KEY_tempCoeff = "tempCoeff";         // PPM / deg C

    public static final String KEY_debugEnable = "debugEnable";

    public static final String KEY_deviceType = "deviceType";
    public static final String KEY_coordinateMode = "coordinateMode";
    public static final String KEY_lat = "lat";
    public static final String KEY_lon = "lon";
    public static final String KEY_alt = "alt";
    public static final String KEY_average = "average";
    public static final String KEY_resetBaseCoordinates = "resetBaseCoordinates";
    public static final String KEY_ip = "ip";
    public static final String KEY_port = "port";
    public static final String KEY_mountPoint = "mountPoint";
    public static final String KEY_user = "user";
    public static final String KEY_password = "password";
    public static final String KEY_rover_id = "rover_id";
    public static final String KEY_scan_start_time = "scan_start_time";
    public static final String KEY_scanInterval = "scanInterval";
    public static final String KEY_ftpEnable = "ftpEnable";
    public static final String KEY_ftpIP = "ftpIP";
    public static final String KEY_ftpPort = "ftpPort";
    public static final String KEY_ftpUser = "ftpUser";
    public static final String KEY_ftpPass = "ftpPass";
    public static final String KEY_maxRecPerFile = "maxRecPerFile";
    public static final String KEY_uploadFileAction = "uploadFileAction";
    public static final String KEY_dateTimeFormat = "dateTimeFormat";
    public static final String KEY_deviationUnit = "deviationUnit";
    public static final String KEY_geodetic = "geodetic";
    public static final String KEY_baseReading = "baseReading";
    public static final String KEY_resetBaseReading = "resetBaseReading";

    public static final int DATA_REPORTING_INTERVAL_1_MIN = 60 * 1000;
    public static final int DATA_REPORTING_INTERVAL_5_MIN = 5 * 60 * 1000;
    public static final int DATA_REPORTING_INTERVAL_10_MIN = 10 * 60 * 1000;
    public static final int DATA_REPORTING_INTERVAL_15_MIN = 15 * 60 * 1000;
    public static final int DATA_REPORTING_INTERVAL_30_MIN = 30 * 60 * 1000;
    public static final int DATA_REPORTING_INTERVAL_1_HOUR = 3600 * 1000;
    public static final int DATA_REPORTING_INTERVAL_2_HOUR = 2 * 3600 * 1000;
    public static final int DATA_REPORTING_INTERVAL_3_HOUR = 3 * 3600 * 1000;
    public static final int DATA_REPORTING_INTERVAL_6_HOUR = 6 * 3600 * 1000;
    public static final int DATA_REPORTING_INTERVAL_12_HOUR = 12 * 3600 * 1000;
    public static final int DATA_REPORTING_INTERVAL_24_HOUR = 24 * 3600 * 1000;

    public static final int BASE_LINE_DATA_POINTS_1_HOUR = 1 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_2_HOUR = 2 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_3_HOUR = 3 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_4_HOUR = 4 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_6_HOUR = 6 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_8_HOUR = 8 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_12_HOUR = 12 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_24_HOUR = 24 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_48_HOUR = 48 * 60 * 60;
    public static final int BASE_LINE_DATA_POINTS_72_HOUR = 72 * 60 * 60;

    public static final long COMMAND_REPLY_TIMEOUT_MS = 5000;   // 5s per command
    public static final long MIN_SAFE_ALARM_MIN = 120;
    public static final int GNSSTX_ENABLE_OK_COUNT = 3;
    public static final long WAKEUP_LEAD_MIN_BASE = 3;   // base powers on 3 min early
    public static final long WAKEUP_LEAD_MIN_ROVER = 3;   // rover powers on 2 min early
    public static final long MIN_SAFE_ALARM_SEC = 120;   // never send an alarm below this many seconds
    public static final long EXTRA_RUN_MIN_BASE = 1;   // base runs 1 min past the window
    public static final long EXTRA_RUN_MIN_ROVER = 1;   // rover runs 1 min past the window
    public static final long IDLE_SLEEP_TIMEOUT_MIN = 10;   // burst: sleep if no UI activity for this many minutes
    public static final long WAKEUP_SAFETY_MIN = 3;   // don't sleep if the next wake would be within this many minutes

//    public static int BASE_LINE_DATA_POINTS_1_HOUR = 1 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_2_HOUR = 2 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_3_HOUR = 3 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_4_HOUR = 4 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_6_HOUR = 6 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_8_HOUR = 8 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_12_HOUR = 12 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_24_HOUR = 24 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_48_HOUR = 48 * 60 * 60 * 10;
//    public static int BASE_LINE_DATA_POINTS_72_HOUR = 72 * 60 * 60 * 10;
    //  Status Code
    public static final int INVALID_COMMAND_ERROR = 1;
    public static final int STRING_FORMAT_ERROR = 2;
    public static final int DATA_SIZE_ERROR = 3;
    public static final int COMMAND_PACKET_SIZE_ERROR = 4;
    public static final int DATA_NULL_ERROR = 5;
    public static final int RTC_DATE_TIME_ERROR = 6;
    public static final int INVALID_DATA_ERROR = 7;
    public static final int MODEM_SIGNAL_ERROR = 8;
    public static final int MODEM_BAUDRATE_SET_ERROR = 9;
    public static final int LOG_INTERVAL_UNDERFLOW_ERROR = 10;
    public static final int DATA_OUT_OF_RANGE_ERROR = 11;
    public static final int DATA_RECORD_CORRUPTED_ERROR = 12;
    public static final int MODEM_TRAP_MODE_ERROR = 13;
    public static final int MODEM_POWER_ON_ERROR = 14;
    public static final int UNABLE_TO_SET_FTP_PARAMETER = 15;
    public static final int UNABLE_TO_OPEN_FTP_SOCKET = 16;
    public static final int UNABLE_TO_SEND_FTP_DATA = 17;
    public static final int MODEM_CME_MSG_FORMAT_ERROR = 50;
    public static final int NETWORK_FORMAT_ERROR = 51;
    public static final int MODEM_FLOW_CONTROL_ERROR = 52;
    public static final int MODEM_CHAR_SET_ERROR = 53;
    public static final int MODEM_MSG_SERVICE_ERROR = 54;
    public static final int SMS_OVER_FLOW_ERROR = 55;
    public static final int SMS_STORAGE_ERROR = 56;
    public static final int SMS_MSG_FORMAT_ERROR = 57;
    public static final int SMS_TEXT_PARA_ERROR = 58;
    public static final int SAVE_SETTINGS_ERROR = 59;
    public static final int OK_STATUS = 1000;
    public static final int MODEM_DISABLE_STATUS = 1001;
    public static final int MODEM_SIM_UNAVAILABLE_STATUS = 1002;
    public static final int MODEM_OPERATING_MODE_OFF_STATUS = 1003;
    public static final int BAROMETER_DISABLE_STATUS = 1004;
    public static final int BATTERY_DEAD_STATUS = 1005;
    public static final int MODEM_COMM_MODE_GPRS_STATUS = 1006;
    public static final int MODEM_OPERATING_MODE_ON_STATUS = 1007;
    public static final int RECORD_AVAILABLE_STATUS = 1010;
    public static final int RECORD_DOWNLOADING_COMPLETED_STATUS = 1011;
    public static final int RECORD_UNAVAILABLE_STATUS = 1012;
    public static final int SMS_ALERT_DISABLE_OR_CONTACT_NUMBER_NOT_AVAILBLE = 1013;
    public static final int SCAN_STATUS_RUNNING = 1014;
    public static final int SMS_SEND_FAIL = 1018;
    public static final int DEFAULT_STATUS = 9999;

    // Storage retention: delete CSVs older than this many days from
    // gnss/files_for_upload/ and gnss/files_for_upload/archive/.
    public static final int DATA_RETENTION_DAYS = 90;

    public static final int STAT_BOOT = 3;
    public static final int STAT_ACQUIRING = 4;
    public static final int STAT_RUNNING_OK = 5;
    public static final int STAT_SLEEP = 6;
    public static final int STAT_ERROR = 7;
    public static final int STAT_STOPPED = 8;
    // reserved: 7, 8, 9

    // ---- Legacy names kept as aliases so call sites need not all change ----
    public static final int STAT_CONFIG_LOADED = STAT_BOOT;
    public static final int STAT_BASE_START = STAT_ACQUIRING;
    public static final int STAT_BASE_SURVEY = STAT_ACQUIRING;
    public static final int STAT_ROVER_START = STAT_ACQUIRING;
    public static final int STAT_ROVER_CONNECTED = STAT_ACQUIRING;
    public static final int STAT_BASE_COORDS_READY = STAT_RUNNING_OK;
    public static final int STAT_BASE_STREAMING = STAT_RUNNING_OK;
    public static final int STAT_ROVER_RTK_FIX = STAT_RUNNING_OK;
    public static final int STAT_PREPARE_SLEEP = STAT_SLEEP;
    public static final int STAT_ALARM_SET = STAT_SLEEP;
    public static final int STAT_SHUTTING_DOWN = STAT_SLEEP;
    public static final int STAT_ERR_CONFIG = STAT_ERROR;
    public static final int STAT_ERR_NTRIP_UNREACH = STAT_ERROR;
    public static final int STAT_ERR_BASE_COORD_FAIL = STAT_ERROR;
    public static final int STAT_BASE_STOPPED = STAT_STOPPED;
    public static final int STAT_ROVER_STOPPED = STAT_STOPPED;
}
