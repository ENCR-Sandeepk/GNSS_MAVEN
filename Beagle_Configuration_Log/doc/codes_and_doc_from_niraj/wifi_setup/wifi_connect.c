/*
 * wifi_connect.c
 * ---------------
 * Reads WiFi credentials from gnss/config/wifi_setting.txt and
 * connects the BeaglePlay to an external WiFi network (as a client)
 * using NetworkManager (nmcli) on the wlan0 interface.
 *
 * This gives the BeaglePlay internet access through another
 * WiFi router or hotspot.
 *
 * The wifi_setting.txt file is saved by the web UI and contains
 * URL-encoded data: wifiSSID=MyNetwork&wifiPassword=secret123
 *
 * Build (on BeaglePlay):
 *   gcc wifi_connect.c -o wifi_connect
 *
 * Usage:
 *   sudo ./wifi_connect
 *
 * What it does:
 *   1. Reads gnss/config/wifi_setting.txt
 *   2. Extracts wifiSSID and wifiPassword (URL-decoded)
 *   3. Uses nmcli to connect wlan0 to that WiFi network
 *   4. If already connected to the same SSID, does nothing
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>

/* Configuration */
#define WIFI_SETTINGS_FILE "./gnss/config/wifi_setting.txt"
#define WIFI_INTERFACE     "wlan0"
#define RETRY_INTERVAL     5    /* seconds between retry attempts */
#define MAX_SSID           128
#define MAX_PASS           128
#define MAX_CMD            512
#define MAX_LINE           1024

/* Flag for graceful shutdown on SIGTERM/SIGINT */
static volatile int keep_running = 1;

void handle_signal(int sig) {
    (void)sig;
    keep_running = 0;
}

/*
 * URL-decode a string in-place.
 * Converts %XX hex sequences and '+' to space.
 */
void url_decode(char *str) {
    char *src = str;
    char *dst = str;
    while (*src) {
        if (*src == '%' && src[1] && src[2]) {
            char hex[3] = { src[1], src[2], '\0' };
            *dst = (char)strtol(hex, NULL, 16);
            src += 3;
            dst++;
        } else if (*src == '+') {
            *dst = ' ';
            src++;
            dst++;
        } else {
            *dst = *src;
            src++;
            dst++;
        }
    }
    *dst = '\0';
}

/*
 * Extract a value for a given key from URL-encoded data.
 * e.g., from "wifiSSID=MyNetwork&wifiPassword=secret123"
 * Returns 1 if found, 0 if not found.
 */
int extract_param(const char *data, const char *key, char *value, int max_len) {
    char search_key[128];
    snprintf(search_key, sizeof(search_key), "%s=", key);

    const char *start = strstr(data, search_key);
    if (!start) return 0;

    /* Make sure we're at the start of the key, not a substring */
    if (start != data && *(start - 1) != '&') return 0;

    start += strlen(search_key);

    /* Find end (either '&' or end of string) */
    const char *end = strchr(start, '&');
    int len;
    if (end) {
        len = (int)(end - start);
    } else {
        len = (int)strlen(start);
    }

    /* Remove trailing newline/carriage return */
    while (len > 0 && (start[len - 1] == '\n' || start[len - 1] == '\r')) {
        len--;
    }

    if (len >= max_len) len = max_len - 1;

    strncpy(value, start, len);
    value[len] = '\0';

    /* URL-decode the value */
    url_decode(value);

    return 1;
}

/*
 * Read WiFi settings from the settings file.
 * Returns 1 if both SSID and password were found, 0 otherwise.
 */
int read_wifi_settings(char *ssid_out, int ssid_max, char *pass_out, int pass_max) {
    FILE *f = fopen(WIFI_SETTINGS_FILE, "r");
    if (!f) {
        printf("[wifi_connect] Settings file not found: %s\n", WIFI_SETTINGS_FILE);
        return 0;
    }

    /* Read entire file (single line of URL-encoded data) */
    char buf[MAX_LINE];
    memset(buf, 0, sizeof(buf));

    int total = 0;
    while (fgets(buf + total, (int)(sizeof(buf) - total), f)) {
        total = (int)strlen(buf);
        if (total >= (int)sizeof(buf) - 1) break;
    }
    fclose(f);

    if (total == 0) {
        printf("[wifi_connect] Settings file is empty.\n");
        return 0;
    }

    /* Extract SSID and password */
    int got_ssid = extract_param(buf, "wifiSSID", ssid_out, ssid_max);
    int got_pass = extract_param(buf, "wifiPassword", pass_out, pass_max);

    if (!got_ssid || strlen(ssid_out) == 0) {
        printf("[wifi_connect] No SSID found in settings file.\n");
        return 0;
    }

    if (!got_pass || strlen(pass_out) == 0) {
        printf("[wifi_connect] No password found in settings file.\n");
        return 0;
    }

    return 1;
}

/*
 * Check if wlan0 is currently connected to a specific SSID.
 * Returns 1 if connected to the given SSID, 0 otherwise.
 */
int is_connected_to(const char *ssid) {
    char cmd[MAX_CMD];
    char result[256];

    /* Get the currently active SSID on wlan0 */
    snprintf(cmd, sizeof(cmd),
        "nmcli -t -f active,ssid dev wifi list ifname %s 2>/dev/null | grep '^yes:' | cut -d':' -f2",
        WIFI_INTERFACE);

    FILE *fp = popen(cmd, "r");
    if (!fp) return 0;

    memset(result, 0, sizeof(result));
    if (fgets(result, sizeof(result), fp)) {
        /* Remove trailing newline */
        int len = (int)strlen(result);
        while (len > 0 && (result[len - 1] == '\n' || result[len - 1] == '\r')) {
            result[--len] = '\0';
        }
    }
    pclose(fp);

    if (strlen(result) > 0 && strcmp(result, ssid) == 0) {
        return 1;
    }

    return 0;
}

/*
 * Ensure eth0 LAN route stays active after WiFi connection.
 * Sets wlan0 route metric high (600) so eth0 (metric 100) is preferred
 * for local network traffic. Also adds explicit route for 192.168.10.0/24
 * via eth0 as a safety net.
 */
void ensure_eth0_route(void) {
    printf("[wifi_connect] Ensuring eth0 LAN route stays active...\n");

    /* Set wlan0 connection to high metric (lower priority for routing) */
    system("nmcli con modify \"$(nmcli -t -f NAME,DEVICE con show --active | grep wlan0 | cut -d: -f1)\" ipv4.route-metric 600 2>/dev/null");

    /* Set eth0 connection to low metric (higher priority for local traffic) */
    system("nmcli con modify \"$(nmcli -t -f NAME,DEVICE con show --active | grep eth0 | cut -d: -f1)\" ipv4.route-metric 100 2>/dev/null");

    /* Ensure local LAN subnet is always routed through eth0 */
    system("ip route replace 192.168.10.0/24 dev eth0 2>/dev/null");

    /* Bring eth0 connection back up if it was disrupted */
    system("nmcli dev reapply eth0 2>/dev/null");

    printf("[wifi_connect] eth0 LAN route preserved.\n");
}

/*
 * Connect to WiFi using nmcli.
 * First tries to activate an existing connection profile.
 * If that fails, creates a new connection.
 * Returns 0 on success, non-zero on failure.
 */
int connect_wifi(const char *ssid, const char *password) {
    char cmd[MAX_CMD];
    int ret;

    /* First, try to activate an existing saved connection for this SSID */
    printf("[wifi_connect] Checking for existing connection profile '%s'...\n", ssid);
    snprintf(cmd, sizeof(cmd),
        "nmcli con up id \"%s\" ifname %s 2>/dev/null",
        ssid, WIFI_INTERFACE);
    ret = system(cmd);

    if (ret == 0) {
        printf("[wifi_connect] Activated existing connection profile.\n");
        /* Ensure eth0 LAN stays active after reconnection */
        ensure_eth0_route();
        return 0;
    }

    /* No existing profile — create a new connection with high route metric */
    printf("[wifi_connect] No existing profile. Connecting to '%s' on %s...\n",
        ssid, WIFI_INTERFACE);
    snprintf(cmd, sizeof(cmd),
        "nmcli dev wifi connect \"%s\" password \"%s\" ifname %s -- ipv4.route-metric 600",
        ssid, password, WIFI_INTERFACE);
    ret = system(cmd);

    if (ret == 0) {
        printf("[wifi_connect] Successfully connected to '%s'.\n", ssid);
        /* Ensure eth0 LAN stays active after new connection */
        ensure_eth0_route();
        return 0;
    }

    printf("[wifi_connect] FAILED to connect to '%s' (exit code: %d).\n", ssid, ret);
    return ret;
}

int main(void) {
    char ssid[MAX_SSID];
    char password[MAX_PASS];

    /* Handle SIGTERM and SIGINT for graceful shutdown */
    signal(SIGTERM, handle_signal);
    signal(SIGINT, handle_signal);

    printf("[wifi_connect] ========================================\n");
    printf("[wifi_connect] GNSS WiFi Client Connect (continuous)\n");
    printf("[wifi_connect] ========================================\n");
    printf("[wifi_connect] Retry interval: %d seconds\n", RETRY_INTERVAL);
    printf("[wifi_connect] Settings file: %s\n", WIFI_SETTINGS_FILE);

    /*
     * Main loop: keeps trying to connect until successful,
     * then monitors the connection and reconnects if lost.
     */
    while (keep_running) {

        /* Step 1: Read settings file (re-read each loop in case user updates it) */
        if (!read_wifi_settings(ssid, sizeof(ssid), password, sizeof(password))) {
            printf("[wifi_connect] No WiFi settings configured. Waiting...\n");
            sleep(RETRY_INTERVAL);
            continue;
        }

        /* Step 2: Check if already connected */
        if (is_connected_to(ssid)) {
            /* Connected — check again after interval */
            sleep(RETRY_INTERVAL);
            continue;
        }

        /* Step 3: Not connected — try to connect */
        printf("[wifi_connect] Not connected to '%s'. Attempting connection...\n", ssid);
        int ret = connect_wifi(ssid, password);

        if (ret == 0) {
            printf("[wifi_connect] Connected to '%s'. Internet should be available.\n", ssid);
        } else {
            printf("[wifi_connect] Failed to connect to '%s'. Will retry in %d seconds...\n",
                ssid, RETRY_INTERVAL);
        }

        sleep(RETRY_INTERVAL);
    }

    printf("[wifi_connect] Service stopped.\n");
    return 0;
}