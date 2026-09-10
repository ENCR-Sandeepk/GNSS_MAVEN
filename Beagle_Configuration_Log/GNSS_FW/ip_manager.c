#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#define IP_SETTING_FILE "/home/debian/gnss/config/ip_setting.txt"
#define IP_PORT_FILE    "/home/debian/gnss/config/ip_port.txt"

#define NET_IFACE       "eth0"
#define IP_PREFIX       "24"
#define BASE_SERVICE    "gnss.service"
#define DEFAULT_IP      "10.10.0.1"

static void trim(char *s)
{
    char *p = s;
    while (*p && isspace((unsigned char)*p)) p++;
    if (p != s) memmove(s, p, strlen(p) + 1);

    int len = strlen(s);
    while (len > 0 && isspace((unsigned char)s[len - 1])) {
        s[len - 1] = '\0';
        len--;
    }
}

static int read_file_line(const char *path, char *buf, size_t size)
{
    FILE *fp = fopen(path, "r");
    if (!fp) {
        perror(path);
        return -1;
    }

    if (!fgets(buf, size, fp)) {
        fclose(fp);
        return -1;
    }

    fclose(fp);
    trim(buf);
    return 0;
}

static int split_ip_port(const char *input, char *ip, size_t ip_size, char *port, size_t port_size)
{
    const char *colon = strchr(input, ':');
    if (!colon) return -1;

    size_t ip_len = colon - input;
    if (ip_len == 0 || ip_len >= ip_size) return -1;

    strncpy(ip, input, ip_len);
    ip[ip_len] = '\0';

    strncpy(port, colon + 1, port_size - 1);
    port[port_size - 1] = '\0';

    trim(ip);
    trim(port);

    if (strlen(ip) == 0 || strlen(port) == 0) return -1;

    return 0;
}

static int write_ip_port_file(const char *path, const char *ip, const char *port)
{
    FILE *fp = fopen(path, "w");
    if (!fp) {
        perror(path);
        return -1;
    }

    fprintf(fp, "%s:%s\n", ip, port);
    fclose(fp);
    return 0;
}

static int run_cmd(const char *cmd)
{
    int ret;
    printf("RUN: %s\n", cmd);
    ret = system(cmd);
    if (ret != 0) {
        printf("Command failed with code %d\n", ret);
    }
    return ret;
}

/*
 * eth0 par jo bhi IPv4 additional IPs hain unme se:
 * - DEFAULT_IP ko mat hatao
 * - target_ip ko bhi mat hatao
 * - baaki sab hata do
 */
static void delete_old_additional_ips(const char *iface, const char *target_ip)
{
    char cmd[512];
    char line[256];
    FILE *fp;

    snprintf(cmd, sizeof(cmd),
             "ip -o -4 addr show dev %s | awk '{print $4}' | cut -d/ -f1",
             iface);

    fp = popen(cmd, "r");
    if (!fp) {
        printf("Failed to read existing IPs\n");
        return;
    }

    while (fgets(line, sizeof(line), fp)) {
        trim(line);

        if (strlen(line) == 0)
            continue;

        if (strcmp(line, DEFAULT_IP) == 0)
            continue;

        if (strcmp(line, target_ip) == 0)
            continue;

        snprintf(cmd, sizeof(cmd),
                 "ip addr del %s/%s dev %s 2>/dev/null",
                 line, IP_PREFIX, iface);
        run_cmd(cmd);
    }

    pclose(fp);
}

int main(void)
{
    char setting_line[128] = {0};
    char current_line[128] = {0};

    char setting_ip[64] = {0};
    char setting_port[32] = {0};

    char current_ip[64] = {0};
    char current_port[32] = {0};

    char cmd[256];

    if (read_file_line(IP_SETTING_FILE, setting_line, sizeof(setting_line)) != 0) {
        printf("Failed to read %s\n", IP_SETTING_FILE);
        return 1;
    }

    if (split_ip_port(setting_line, setting_ip, sizeof(setting_ip), setting_port, sizeof(setting_port)) != 0) {
        printf("Invalid format in %s. Expected: IP:PORT\n", IP_SETTING_FILE);
        return 1;
    }

    if (read_file_line(IP_PORT_FILE, current_line, sizeof(current_line)) != 0) {
        current_line[0] = '\0';
    }

    if (strlen(current_line) > 0) {
        if (split_ip_port(current_line, current_ip, sizeof(current_ip), current_port, sizeof(current_port)) != 0) {
            current_ip[0] = '\0';
            current_port[0] = '\0';
        }
    }

    printf("Configured  : %s:%s\n", setting_ip, setting_port);
    printf("Stored file : %s\n", strlen(current_line) ? current_line : "(empty)");
    printf("Default IP  : %s\n", DEFAULT_IP);

    // eth0 par default ke alawa purane extra IP hatao
    delete_old_additional_ips(NET_IFACE, setting_ip);

    // Agar new IP default IP nahi hai, to add karo
    if (strcmp(setting_ip, DEFAULT_IP) != 0) {
        snprintf(cmd, sizeof(cmd),
                 "ip addr add %s/%s dev %s 2>/dev/null",
                 setting_ip, IP_PREFIX, NET_IFACE);
        run_cmd(cmd);
    } else {
        printf("Configured IP is default IP, no additional IP add needed.\n");
    }

    // ip_port.txt update karo
    if (write_ip_port_file(IP_PORT_FILE, setting_ip, setting_port) != 0) {
        printf("Failed to update %s\n", IP_PORT_FILE);
        return 1;
    }

    // service restart
    snprintf(cmd, sizeof(cmd), "systemctl restart %s", BASE_SERVICE);
    run_cmd(cmd);

    printf("IP cleanup and update completed successfully.\n");
    return 0;
}
