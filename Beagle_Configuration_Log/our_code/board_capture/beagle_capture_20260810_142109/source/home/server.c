/*
 * Multi-threaded cross-platform HTTP server (Windows + Linux)
 * - Preserves all features from your original code:
 *   index.html serving, /logs, POST saving endpoints, logging, directories, update.flag, etc.
 * - Each client is handled in a detached thread.
 *
 * Build:
 *  Linux: gcc server_multi.c -o server_multi -pthread
 *  Windows (MSVC): cl server_multi.c /link Ws2_32.lib
 *  Windows (MinGW): gcc server_multi.c -o server_multi.exe -lws2_32 -lmswsock
 *
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <ctype.h>
#include <stdint.h>


#ifdef _WIN32
  #define WIN32_LEAN_AND_MEAN
  #include <winsock2.h>
  #include <ws2tcpip.h>
  #include <direct.h>
  #include <process.h> /* _beginthreadex if needed */ 
  #pragma comment(lib, "Ws2_32.lib")
  typedef SOCKET socket_t;
  #define close_socket(s) closesocket(s)
  #define THREAD_RETURN unsigned __stdcall
  #define THREAD_PARAM void *
#else
  #include <dirent.h>
  #include <sys/types.h>
  #include <sys/time.h>   /* struct timeval (SO_RCVTIMEO/SO_SNDTIMEO) */
  #include <sys/socket.h>
  #include <netinet/in.h>
  #include <arpa/inet.h>
  #include <unistd.h>
  #include <sys/stat.h>
  #include <fcntl.h>
  #include <pthread.h>
  #include <signal.h>
  typedef int socket_t;
  #define INVALID_SOCKET (-1)
  #define SOCKET_ERROR   (-1)
  #define close_socket(s) close(s)
  #define THREAD_RETURN void *
  #define THREAD_PARAM void *
#endif

#define PORT 8080
#define BACKLOG 5
#define WEB_DIR "./gnss/"           // Folder where index.html is located
#define CONFIG_DIR "./gnss/config/" // Config folder
#define LOG_DIR "./gnss/logs/"      // Log folder
#define LOG_FILE "./gnss/logs/log.txt"
#define UPDATE_FLAG "./gnss/config/update.flag"
#define BUFFER_SIZE 4096
#define UI_DIR      "./gnss/ui/"         // Folder for external CSS/JS assets
#define SERIAL_FILE "./gnss/.sys_cal.dat"

// ---- Admin credentials (compile-time, not changeable by customer) ----
#define ADMIN_USERNAME "admin"
#define ADMIN_PASSWORD "Encardio@2026"

// ---- Serial number encryption key (compile-time, hidden in firmware binary) ----
#define SERIAL_ENCRYPT_KEY "EncDite$ecR3tK#y!2026"
#define SERIAL_SIGNATURE_SEED 0xA5C3E1D7  // Used to generate tamper-detection signature

// prototypes
void handle_post(socket_t client_sock, const char *path, char *body);
void save_to_file(const char *filename, const char *data);
void ensure_dir(const char *path);
void get_timestamp(char *buf, size_t size);
void write_log(const char *message);
char *read_file_to_buffer(const char *path, size_t *len_out);
void serve_index(socket_t client);
void serve_logs(socket_t client);
void urldecode(char *src, char *dest);
void handle_client(socket_t client_sock);
THREAD_RETURN client_thread_func(THREAD_PARAM arg);
int check_basic_auth(const char *request);
void send_auth_required(socket_t client);
void serve_admin_page(socket_t client);
void serve_serial(socket_t client);
int base64_encode(const unsigned char *in, int in_len, char *out, int max_out);
void xor_encrypt_decrypt(const unsigned char *in, int in_len, unsigned char *out, const char *key);
uint32_t compute_signature(const char *serial, const char *key);
int encrypt_and_save_serial(const char *serial);
int load_and_decrypt_serial(char *serial_out, int max_len);
int is_serial_locked(void);




// ---- Base64 decode (for HTTP Basic Auth) ----
static const unsigned char b64_table[256] = {
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,62,64,64,64,63,
    52,53,54,55,56,57,58,59,60,61,64,64,64,64,64,64,
    64, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
    15,16,17,18,19,20,21,22,23,24,25,64,64,64,64,64,
    64,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
    41,42,43,44,45,46,47,48,49,50,51,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,
    64,64,64,64,64,64,64,64,64,64,64,64,64,64,64,64
};

int base64_decode(const char *in, char *out, int max_out) {
    int len = 0;
    unsigned int buf = 0;
    int bits = 0;
    while (*in && *in != '=' && len < max_out - 1) {
        unsigned char c = b64_table[(unsigned char)*in++];
        if (c == 64) continue; // skip invalid chars
        buf = (buf << 6) | c;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[len++] = (char)((buf >> bits) & 0xFF);
        }
    }
    out[len] = '\0';
    return len;
}

// ---- Base64 encode ----
static const char b64_enc_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

int base64_encode(const unsigned char *in, int in_len, char *out, int max_out) {
    int i, j = 0;
    for (i = 0; i < in_len && j < max_out - 4; i += 3) {
        int remaining = in_len - i;
        unsigned int b0 = in[i];
        unsigned int b1 = (remaining > 1) ? in[i + 1] : 0;
        unsigned int b2 = (remaining > 2) ? in[i + 2] : 0;
        unsigned int triple = (b0 << 16) | (b1 << 8) | b2;

        out[j++] = b64_enc_table[(triple >> 18) & 0x3F];
        out[j++] = b64_enc_table[(triple >> 12) & 0x3F];
        out[j++] = (remaining > 1) ? b64_enc_table[(triple >> 6) & 0x3F] : '=';
        out[j++] = (remaining > 2) ? b64_enc_table[triple & 0x3F] : '=';
    }
    out[j] = '\0';
    return j;
}

// ---- XOR encrypt/decrypt (symmetric — same function for both) ----
void xor_encrypt_decrypt(const unsigned char *in, int in_len, unsigned char *out, const char *key) {
    int key_len = (int)strlen(key);
    for (int i = 0; i < in_len; i++) {
        out[i] = in[i] ^ (unsigned char)key[i % key_len];
    }
}

// ---- Keyed signature for tamper detection ----
// Simple hash: mixes serial bytes with key to produce a 32-bit signature.
// If someone edits the file, the signature won't match.
uint32_t compute_signature(const char *serial, const char *key) {
    uint32_t hash = SERIAL_SIGNATURE_SEED;
    int key_len = (int)strlen(key);
    for (int i = 0; serial[i]; i++) {
        hash ^= ((uint32_t)(unsigned char)serial[i]) << ((i % 4) * 8);
        hash = (hash << 7) | (hash >> 25); // rotate left
        hash += (uint32_t)(unsigned char)key[i % key_len];
        hash *= 0x5BD1E995; // mixing constant
    }
    return hash;
}

// ---- Encrypt serial and save to file ----
// File format: <Base64(XOR(serial))>:<8-hex-signature>\n
// Returns 1 on success, 0 on failure
int encrypt_and_save_serial(const char *serial) {
    int slen = (int)strlen(serial);

    // XOR encrypt
    unsigned char encrypted[256];
    if (slen > (int)sizeof(encrypted) - 1) return 0;
    xor_encrypt_decrypt((const unsigned char *)serial, slen, encrypted, SERIAL_ENCRYPT_KEY);

    // Base64 encode
    char b64[512];
    base64_encode(encrypted, slen, b64, sizeof(b64));

    // Compute signature on the ORIGINAL plaintext serial
    uint32_t sig = compute_signature(serial, SERIAL_ENCRYPT_KEY);

    // Write to file: "base64data:signature\n"
    FILE *fp = fopen(SERIAL_FILE, "w");
    if (!fp) return 0;
    fprintf(fp, "%s:%08X\n", b64, sig);
    fclose(fp);

    // On Windows, set hidden + system attributes so file is invisible in Explorer
    #ifdef _WIN32
    SetFileAttributesA(SERIAL_FILE, FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM);
    #endif

    return 1;
}

// ---- Load and decrypt serial from file ----
// Returns 1 if valid serial loaded, 0 if not found or tampered
int load_and_decrypt_serial(char *serial_out, int max_len) {
    size_t flen;
    char *data = read_file_to_buffer(SERIAL_FILE, &flen);
    if (!data) return 0;

    // Trim trailing whitespace/newline
    while (flen > 0 && (data[flen-1] == '\n' || data[flen-1] == '\r' || data[flen-1] == ' '))
        data[--flen] = '\0';

    // Split on ':'  —  format is "base64:signature"
    char *colon = strrchr(data, ':');
    if (!colon) { free(data); return 0; }
    *colon = '\0';
    const char *b64_part = data;
    const char *sig_hex = colon + 1;

    // Parse stored signature
    uint32_t stored_sig = 0;
    if (sscanf(sig_hex, "%8X", &stored_sig) != 1) { free(data); return 0; }

    // Base64 decode
    char decoded_enc[256];
    int dec_len = base64_decode(b64_part, decoded_enc, sizeof(decoded_enc));
    if (dec_len <= 0) { free(data); return 0; }

    // XOR decrypt
    unsigned char plaintext[256];
    xor_encrypt_decrypt((const unsigned char *)decoded_enc, dec_len, plaintext, SERIAL_ENCRYPT_KEY);
    plaintext[dec_len] = '\0';

    // Verify signature
    uint32_t computed_sig = compute_signature((const char *)plaintext, SERIAL_ENCRYPT_KEY);
    if (computed_sig != stored_sig) {
        free(data);
        return -1; // TAMPERED!
    }

    // Copy to output
    int copy_len = (dec_len < max_len - 1) ? dec_len : max_len - 1;
    memcpy(serial_out, plaintext, copy_len);
    serial_out[copy_len] = '\0';

    free(data);
    return 1;
}

// ---- Check if serial is already set (write-once lock) ----
// Returns 1 if locked (valid serial exists), 0 if not set, -1 if tampered
int is_serial_locked(void) {
    char serial[128];
    return load_and_decrypt_serial(serial, sizeof(serial));
}

// Check HTTP Basic Auth header against admin credentials
// Returns 1 if authenticated, 0 if not
int check_basic_auth(const char *request) {
    const char *auth = strstr(request, "Authorization: Basic ");
    if (!auth) return 0;

    auth += 21; // skip "Authorization: Basic "
    char encoded[256];
    int i = 0;
    while (auth[i] && auth[i] != '\r' && auth[i] != '\n' && i < (int)sizeof(encoded) - 1) {
        encoded[i] = auth[i];
        i++;
    }
    encoded[i] = '\0';

    char decoded[256];
    base64_decode(encoded, decoded, sizeof(decoded));

    // decoded should be "username:password"
    char expected[256];
    snprintf(expected, sizeof(expected), "%s:%s", ADMIN_USERNAME, ADMIN_PASSWORD);

    return (strcmp(decoded, expected) == 0);
}

// Send 401 Unauthorized response (browser will show login dialog)
void send_auth_required(socket_t client) {
    const char *resp =
        "HTTP/1.1 401 Unauthorized\r\n"
        "WWW-Authenticate: Basic realm=\"Admin Panel\"\r\n"
        "Content-Type: text/html\r\n"
        "Content-Length: 42\r\n\r\n"
        "<html><body>401 - Unauthorized</body></html>";
    send(client, resp, (int)strlen(resp), 0);
}

// Serve the admin page for serial number management
void serve_admin_page(socket_t client) {
    const char *html =
        "<!DOCTYPE html><html><head>"
        "<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>Admin - Serial Number</title>"
        "<style>"
        "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Arial,sans-serif;"
        "background:#f0f2f5;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0;}"
        ".card{background:white;border-radius:12px;box-shadow:0 4px 20px rgba(0,0,0,0.1);padding:40px;max-width:480px;width:90%;}"
        "h1{color:#333;font-size:22px;margin-bottom:8px;}"
        ".subtitle{color:#888;font-size:13px;margin-bottom:24px;}"
        "label{display:block;font-weight:600;color:#555;margin-bottom:6px;font-size:14px;}"
        "input[type=text]{width:100%;padding:12px;border:2px solid #ddd;border-radius:8px;font-size:16px;"
        "transition:border-color 0.2s;box-sizing:border-box;}"
        "input[type=text]:focus{border-color:#667eea;outline:none;}"
        "input:disabled{background:#f5f5f5;color:#999;cursor:not-allowed;}"
        ".btn{background:#667eea;color:white;border:none;padding:12px 28px;border-radius:8px;"
        "font-size:15px;cursor:pointer;margin-top:16px;width:100%;font-weight:600;}"
        ".btn:hover{background:#5a6fd6;}"
        ".btn:disabled{background:#ccc;cursor:not-allowed;}"
        ".status{margin-top:16px;padding:12px;border-radius:8px;font-size:14px;display:none;}"
        ".status.ok{background:#d4edda;color:#155724;display:block;}"
        ".status.err{background:#f8d7da;color:#721c24;display:block;}"
        ".status.warn{background:#fff3cd;color:#856404;display:block;}"
        ".current{background:#e8f4fd;padding:12px;border-radius:8px;margin-bottom:20px;font-size:14px;color:#0c5460;}"
        ".locked-badge{display:inline-block;background:#dc3545;color:white;padding:3px 10px;"
        "border-radius:12px;font-size:11px;font-weight:700;margin-left:8px;vertical-align:middle;}"
        ".unlocked-badge{display:inline-block;background:#28a745;color:white;padding:3px 10px;"
        "border-radius:12px;font-size:11px;font-weight:700;margin-left:8px;vertical-align:middle;}"
        "</style></head><body>"
        "<div class='card'>"
        "<h1>Device Serial Number <span id='badge'></span></h1>"
        "<p class='subtitle'>Admin access only &mdash; not visible to device users</p>"
        "<div class='current' id='cur'>Loading...</div>"
        "<form id='frm'>"
        "<label for='sn'>Serial Number</label>"
        "<input type='text' id='sn' name='serial' placeholder='e.g. GNSS-RB-20260001' required maxlength='64'/>"
        "<button class='btn' id='sbtn' type='submit'>Save Serial Number</button>"
        "</form>"
        "<div class='status' id='st'></div>"
        "</div>"
        "<script>"
        "var isLocked=false;"
        "function load(){fetch('/load_serial').then(r=>{"
        "if(r.status===200)return r.text();"
        "if(r.status===409)return r.text().then(t=>{throw{type:'tamper',msg:t}});"
        "throw{type:'none'};"
        "}).then(t=>{"
        "var s=t.trim();document.getElementById('cur').textContent='Current: '+s;"
        "document.getElementById('sn').value=s;"
        "isLocked=true;lockForm();"
        "}).catch(e=>{"
        "if(e&&e.type==='tamper'){"
        "document.getElementById('cur').innerHTML='<b style=color:red>WARNING: Serial file has been tampered with!</b>';"
        "document.getElementById('badge').innerHTML='<span class=locked-badge>TAMPERED</span>';"
        "document.getElementById('sn').disabled=true;document.getElementById('sbtn').disabled=true;"
        "}else{"
        "document.getElementById('cur').textContent='No serial number set yet.';"
        "document.getElementById('badge').innerHTML='<span class=unlocked-badge>OPEN</span>';"
        "}});}"
        "function lockForm(){"
        "document.getElementById('sn').disabled=true;"
        "document.getElementById('sbtn').disabled=true;"
        "document.getElementById('sbtn').textContent='LOCKED - Cannot Change';"
        "document.getElementById('badge').innerHTML='<span class=locked-badge>LOCKED</span>';"
        "var st=document.getElementById('st');"
        "st.className='status warn';st.textContent='Serial number is permanently set and cannot be changed.';}"
        "document.getElementById('frm').onsubmit=function(e){e.preventDefault();"
        "if(isLocked)return;"
        "var s=document.getElementById('sn').value.trim();if(!s)return;"
        "document.getElementById('sbtn').disabled=true;document.getElementById('sbtn').textContent='Saving...';"
        "fetch('/save_serial',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},"
        "body:'serial='+encodeURIComponent(s)}).then(r=>r.text().then(t=>({ok:r.ok,status:r.status,text:t})))"
        ".then(d=>{var st=document.getElementById('st');"
        "if(d.ok){st.className='status ok';st.textContent='Serial number saved and locked permanently!';"
        "isLocked=true;lockForm();load();}"
        "else if(d.status===403){st.className='status err';st.textContent=d.text;lockForm();}"
        "else{st.className='status err';st.textContent='Error: '+d.text;"
        "document.getElementById('sbtn').disabled=false;document.getElementById('sbtn').textContent='Save Serial Number';}});};load();"
        "</script></body></html>";

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/html; charset=utf-8\r\n"
             "Content-Length: %zu\r\n\r\n",
             strlen(html));
    send(client, header, (int)strlen(header), 0);
    send(client, html, (int)strlen(html), 0);
}

// Serve current serial number (GET /load_serial) — decrypts and verifies
void serve_serial(socket_t client) {
    char serial[128];
    int result = load_and_decrypt_serial(serial, sizeof(serial));

    if (result == 0) {
        // No serial file exists
        const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNO_SERIAL";
        send(client, resp, (int)strlen(resp), 0);
    } else if (result == -1) {
        // File exists but was tampered with!
        const char *resp = "HTTP/1.1 409 Conflict\r\nContent-Type: text/plain\r\n\r\n"
                           "TAMPERED - Serial file integrity check failed!";
        send(client, resp, (int)strlen(resp), 0);
        write_log("WARNING: Serial number file tamper detected!");
    } else {
        // Valid serial — send decrypted plaintext to admin page
        size_t slen = strlen(serial);
        char header[256];
        snprintf(header, sizeof(header),
                 "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n", slen);
        send(client, header, (int)strlen(header), 0);
        send(client, serial, (int)slen, 0);
    }
}

void view_log_file(socket_t client, const char *filename) {
    char path[512];
    snprintf(path, sizeof(path), "%s%s", LOG_DIR, filename);

    size_t len;
    char *data = read_file_to_buffer(path, &len);

    if (!data) {
        const char *resp = "HTTP/1.1 404 Not Found\r\n\r\nFile not found";
        send(client, resp, (int)strlen(resp), 0);
        return;
    }

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/plain\r\n"
             "Content-Length: %zu\r\n\r\n",
             len);

    send(client, header, (int)strlen(header), 0);
    send(client, data, (int)len, 0);
    free(data);
}


void list_log_files(socket_t client, const char *type) {

    /* Collect matching file names with their modification times, then sort
     * newest-first so the latest log appears at the top of the list. */
    struct logEntry {
        char name[256];
        time_t modTime;
    } entries[256];
    int count = 0;

#ifdef _WIN32
    /* ---- Windows version: use FindFirstFileA ---- */

    char searchPath[512];
    snprintf(searchPath, sizeof(searchPath), "%s*.*", LOG_DIR);

    WIN32_FIND_DATAA ffd;
    HANDLE hFind = FindFirstFileA(searchPath, &ffd);
    if (hFind == INVALID_HANDLE_VALUE) {
        const char *resp = "HTTP/1.1 500 Internal Error\r\n\r\nCannot open log folder";
        send(client, resp, (int)strlen(resp), 0);
        return;
    }

    do {
        if (ffd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
            continue;

        if (strcmp(type, "server") == 0) {
            if (!strstr(ffd.cFileName, "server_log_")) continue;
        } else if (strcmp(type, "log") == 0) {
            if (!strstr(ffd.cFileName, "log-")) continue;
        } else continue;

        if (count >= 256) break;
        snprintf(entries[count].name, sizeof(entries[count].name), "%s", ffd.cFileName);

        ULARGE_INTEGER ull;
        ull.LowPart  = ffd.ftLastWriteTime.dwLowDateTime;
        ull.HighPart = ffd.ftLastWriteTime.dwHighDateTime;
        entries[count].modTime = (time_t)((ull.QuadPart - 116444736000000000ULL) / 10000000ULL);

        count++;

    } while (FindNextFileA(hFind, &ffd));

    FindClose(hFind);

#else
    /* ---- Linux version: use opendir/readdir ---- */

    DIR *d = opendir(LOG_DIR);
    if (!d) {
        const char *resp = "HTTP/1.1 500 Internal Error\r\n\r\nCannot open log folder";
        send(client, resp, (int)strlen(resp), 0);
        return;
    }

    struct dirent *dir;
    while ((dir = readdir(d)) != NULL) {

        if (strcmp(type, "server") == 0) {
            if (!strstr(dir->d_name, "server_log_")) continue;
        } else if (strcmp(type, "log") == 0) {
            if (!strstr(dir->d_name, "log-")) continue;
        } else continue;

        if (count >= 256) break;
        snprintf(entries[count].name, sizeof(entries[count].name), "%s", dir->d_name);

        char fullpath[512];
        snprintf(fullpath, sizeof(fullpath), "%s%s", LOG_DIR, dir->d_name);
        struct stat st;
        entries[count].modTime = (stat(fullpath, &st) == 0) ? st.st_mtime : 0;

        count++;
    }
    closedir(d);

#endif

    /* Sort newest-first (descending modification time) */
    for (int i = 0; i < count - 1; i++) {
        for (int j = i + 1; j < count; j++) {
            if (entries[i].modTime < entries[j].modTime) {
                struct logEntry tmp = entries[i];
                entries[i] = entries[j];
                entries[j] = tmp;
            }
        }
    }

    /* Build the HTML list in sorted order */
    char html[32768];
    strcpy(html, "<html><body><h2>Available Logs</h2><ul>");

    for (int i = 0; i < count; i++) {
        char line[512];
        snprintf(line, sizeof(line),
                 "<li><a href=\"/view?file=%s\">%s</a></li>",
                 entries[i].name, entries[i].name);
        if (strlen(html) + strlen(line) < sizeof(html) - 32)
            strcat(html, line);
    }

    strncat(html, "</ul></body></html>", sizeof(html) - strlen(html) - 1);

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/html\r\n"
             "Content-Length: %zu\r\n\r\n",
             strlen(html));

    send(client, header, (int)strlen(header), 0);
    send(client, html, (int)strlen(html), 0);
}



void get_daily_log_filename(char *out, size_t size) {
    time_t now = time(NULL);
#ifdef _WIN32
    struct tm *t = localtime(&now);      // Windows: per-thread static buffer (safe)
#else
    struct tm tmv;
    struct tm *t = localtime_r(&now, &tmv);  // POSIX: thread-safe
#endif
    if (t) strftime(out, size, LOG_DIR "server_log_%Y-%m-%d.txt", t);
    else   snprintf(out, size, LOG_DIR "server_log_unknown.txt");
}
void cleanup_old_logs() {
    struct logFileInfo {
        char name[512];
        time_t modTime;
    } files[256];

    int count = 0;

#ifdef _WIN32
    WIN32_FIND_DATAA ffd;
    HANDLE hFind = FindFirstFileA(LOG_DIR "server_log_*.txt", &ffd);
    if (hFind == INVALID_HANDLE_VALUE) return;

    do {
        if (!(ffd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
            if (count >= 256) break;
            snprintf(files[count].name, sizeof(files[count].name), "%s%s", LOG_DIR, ffd.cFileName);

            FILETIME ft = ffd.ftLastWriteTime;
            ULARGE_INTEGER ull;
            ull.LowPart  = ft.dwLowDateTime;
            ull.HighPart = ft.dwHighDateTime;
            files[count].modTime = (time_t)((ull.QuadPart - 116444736000000000ULL) / 10000000ULL);

            count++;
        }
    } while (FindNextFileA(hFind, &ffd));
    FindClose(hFind);

#else
    DIR *d = opendir(LOG_DIR);
    if (!d) return;
    struct dirent *dir;

    while ((dir = readdir(d)) != NULL) {
        if (strstr(dir->d_name, "server_log_") && strstr(dir->d_name, ".txt")) {
            if (count >= 256) break;
            snprintf(files[count].name, sizeof(files[count].name), "%s%s", LOG_DIR, dir->d_name);

            struct stat st;
            stat(files[count].name, &st);
            files[count].modTime = st.st_mtime;

            count++;
        }
    }
    closedir(d);
#endif

    if (count <= 31) return;

    // Sort by time (newest first)
    for (int i = 0; i < count - 1; i++) {
        for (int j = i + 1; j < count; j++) {
            if (files[i].modTime < files[j].modTime) {
                struct logFileInfo tmp = files[i];
                files[i] = files[j];
                files[j] = tmp;
            }
        }
    }

    // Delete all after first 31
    for (int i = 31; i < count; i++) {
        remove(files[i].name);
    }
}


void write_log(const char *message) {
    ensure_dir(LOG_DIR);

    char logfile[512];
    get_daily_log_filename(logfile, sizeof(logfile));

    FILE *fp = fopen(logfile, "a");
    if (!fp) return;

    char ts[64];
    get_timestamp(ts, sizeof(ts));
    fprintf(fp, "[%s] %s\n", ts, message);
    fclose(fp);

    cleanup_old_logs();  // auto-clean daily
}

  



// Helper: Ensure directory exists (works for intermediate paths)
void ensure_dir(const char *path) {
    char tmp[512];
    snprintf(tmp, sizeof(tmp), "%s", path);
    size_t len = strlen(tmp);
    if (len == 0) return;
    if (tmp[len - 1] == '/' || tmp[len - 1] == '\\') tmp[len - 1] = '\0';

    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/' || *p == '\\') {
#ifdef _WIN32
            char prev = *p;
            *p = '\0';
            _mkdir(tmp);
            *p = prev;
#else
            char prev = *p;
            *p = '\0';
            mkdir(tmp, 0755);
            *p = prev;
#endif
        }
    }
#ifdef _WIN32
    _mkdir(tmp);
#else
    mkdir(tmp, 0755);
#endif
}

// Helper: timestamp
void get_timestamp(char *buf, size_t size) {
    time_t now = time(NULL);
#ifdef _WIN32
    struct tm *t = localtime(&now);      // Windows: per-thread static buffer (safe)
#else
    struct tm tmv;
    struct tm *t = localtime_r(&now, &tmv);  // POSIX: thread-safe
#endif
    if (t) strftime(buf, size, "%Y-%m-%d %H:%M:%S", t);
    else snprintf(buf, size, "unknown-time");
}



// URL decode
void urldecode(char *src, char *dest) {
    char a, b;
    while (*src) {
        if ((*src == '%') && (a = src[1]) && (b = src[2]) && (isxdigit((unsigned char)a) && isxdigit((unsigned char)b))) {
            a = (a >= 'a') ? a - 'a' + 10 : (a >= 'A') ? a - 'A' + 10 : a - '0';
            b = (b >= 'a') ? b - 'a' + 10 : (b >= 'A') ? b - 'A' + 10 : b - '0';
            *dest++ = 16 * a + b;
            src += 3;
        } else if (*src == '+') {
            *dest++ = ' ';
            src++;
        } else {
            *dest++ = *src++;
        }
    }
    *dest = '\0';
}

// Read file into memory
char *read_file_to_buffer(const char *path, size_t *len_out) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return NULL;
    if (fseek(fp, 0, SEEK_END) != 0) { fclose(fp); return NULL; }
    long len = ftell(fp);
    if (len < 0) { fclose(fp); return NULL; }
    rewind(fp);

    char *buffer = malloc((size_t)len ? (size_t)len : 1);
    if (!buffer) { fclose(fp); return NULL; }
    size_t read_len = fread(buffer, 1, (size_t)len, fp);
    fclose(fp);
    *len_out = read_len;
    return buffer;
}

// Serve index.html
void serve_index(socket_t client) {
    size_t html_len;
    char *html = read_file_to_buffer(WEB_DIR "index.html", &html_len);
    if (!html) {
        const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\n404 - index.html not found";
        send(client, resp, (int)strlen(resp), 0);
        return;
    }

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/html; charset=utf-8\r\n"
             "Content-Length: %zu\r\n\r\n",
             html_len);
    send(client, header, (int)strlen(header), 0);
    send(client, html, (int)html_len, 0);
    free(html);
}

// Serve logs
void serve_logs(socket_t client) {
    size_t len;
    char *data = read_file_to_buffer(LOG_FILE, &len);
    if (!data) {
        const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNo log file found.";
        send(client, resp, (int)strlen(resp), 0);
        return;
    }

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/plain; charset=utf-8\r\n"
             "Content-Length: %zu\r\n\r\n",
             len);
    send(client, header, (int)strlen(header), 0);
    send(client, data, (int)len, 0);
    free(data);
}

// Save data to file and mark update flag
void save_to_file(const char *filename, const char *data) {
    ensure_dir(CONFIG_DIR);
    FILE *fp = fopen(filename, "w");
    if (!fp) {
        perror("fopen failed");
        write_log("Error opening config file for writing");
        return;
    }
    fprintf(fp, "%s\n", data);
    fclose(fp);

    FILE *flag = fopen(UPDATE_FLAG, "w");
    if (flag) {
        char ts[64];
        get_timestamp(ts, sizeof(ts));
        fprintf(flag, "UPDATED: %s\n", ts);
        fclose(flag);
    }
    char msg[256];
    snprintf(msg, sizeof(msg), "Updated config file: %s", filename);
    write_log(msg);
}

// Handle POST
void handle_post(socket_t client_sock, const char *path, char *body) {
    char decoded[BUFFER_SIZE];
    urldecode(body, decoded);

    if (strcmp(path, "/save_gnss_receiver_settings") == 0) {
        save_to_file(CONFIG_DIR "save_gnss_receiver_settings.txt", decoded);
    } else if (strcmp(path, "/save_ipport") == 0) {
        save_to_file(CONFIG_DIR "ipport.txt", decoded);
    } else if (strcmp(path, "/save_password") == 0) {
        save_to_file(CONFIG_DIR "password.txt", decoded);
    } else if (strcmp(path, "/save_wifi") == 0) {
        save_to_file(CONFIG_DIR "wifi_setting.txt", decoded);
    } else if (strcmp(path, "/save_ip_setting") == 0) {
        // Extract ip from "ip=x.x.x.x&port=2000" and save as "x.x.x.x:2000" (port is fixed)
        char ip_val[64] = {0};
        char *ip_start = strstr(decoded, "ip=");
        if (ip_start) {
            ip_start += 3;
            char *amp = strchr(ip_start, '&');
            if (amp) {
                int len = (int)(amp - ip_start);
                if (len > 0 && len < (int)sizeof(ip_val))
                    strncpy(ip_val, ip_start, len);
            } else {
                strncpy(ip_val, ip_start, sizeof(ip_val) - 1);
            }
        }
        char ip_port_str[128];
        snprintf(ip_port_str, sizeof(ip_port_str), "%s:2000", ip_val);
        save_to_file(CONFIG_DIR "ip_port.txt", ip_port_str);
    } else if (strcmp(path, "/save_serial") == 0) {
        // ---- WRITE-ONCE CHECK: If serial already exists and is valid, REJECT ----
        int lock_status = is_serial_locked();
        if (lock_status == 1) {
            write_log("Rejected attempt to overwrite locked serial number");
            const char *resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\n"
                               "Serial number is already set and LOCKED. Cannot be changed.";
            send(client_sock, resp, (int)strlen(resp), 0);
            return;
        }
        if (lock_status == -1) {
            write_log("WARNING: Tampered serial file detected during save attempt");
            // Allow overwrite of tampered file — admin is re-setting it
        }

        // Extract just the serial value from "serial=XXXXX"
        char *serial_val = strstr(decoded, "serial=");
        if (serial_val) {
            serial_val += 7; // skip "serial="
            char *amp = strchr(serial_val, '&');
            if (amp) *amp = '\0';
        } else {
            serial_val = decoded;
        }

        // Encrypt and save
        if (encrypt_and_save_serial(serial_val)) {
            char msg[256];
            snprintf(msg, sizeof(msg), "Serial number set and LOCKED: %s", serial_val);
            write_log(msg);
        } else {
            write_log("Error: Failed to encrypt/save serial number");
            const char *resp = "HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\n\r\n"
                               "Failed to save serial number";
            send(client_sock, resp, (int)strlen(resp), 0);
            return;
        }
    } else {
        const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nUnknown endpoint";
        send(client_sock, resp, (int)strlen(resp), 0);
        return;
    }

    const char *resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nConfiguration saved successfully!";
    send(client_sock, resp, (int)strlen(resp), 0);
}



// Handle a connected client (single request-response)
void handle_client(socket_t client_sock) {
    char buffer[BUFFER_SIZE];
    int bytes_received = recv(client_sock, buffer, sizeof(buffer) - 1, 0);
    if (bytes_received <= 0) {
        close_socket(client_sock);
        return;
    }
    buffer[bytes_received] = '\0';

    if (strncmp(buffer, "GET ", 4) == 0) {

    if (strncmp(buffer + 4, "/logs", 5) == 0) {
        serve_logs(client_sock);

    } else if (strncmp(buffer + 4, "/get_version", 12) == 0) {
        size_t vlen;
        char *ver = read_file_to_buffer(CONFIG_DIR "version.txt", &vlen);
        if (!ver) {
            const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nN/A";
            send(client_sock, resp, (int)strlen(resp), 0);
        } else {
            char vheader[256];
            snprintf(vheader, sizeof(vheader),
                     "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n", vlen);
            send(client_sock, vheader, (int)strlen(vheader), 0);
            send(client_sock, ver, (int)vlen, 0);
            free(ver);
        }

    } else if (strncmp(buffer + 4, "/load_gnss_receiver_settings", 28) == 0) {

        size_t len;
        char *data = read_file_to_buffer(CONFIG_DIR "save_gnss_receiver_settings.txt", &len);

        if (!data) {
            const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNO_DATA";
            send(client_sock, resp, (int)strlen(resp), 0);
        } else {
            char header[256];
            snprintf(header, sizeof(header),
                     "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n",
                     len);

            send(client_sock, header, (int)strlen(header), 0);
            send(client_sock, data, (int)len, 0);
            free(data);
        }

    } else if (strncmp(buffer + 4, "/load_wifi", 10) == 0) {

        size_t len;
        char *data = read_file_to_buffer(CONFIG_DIR "wifi_setting.txt", &len);

        if (!data) {
            const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNO_DATA";
            send(client_sock, resp, (int)strlen(resp), 0);
        } else {
            char header[256];
            snprintf(header, sizeof(header),
                     "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n",
                     len);
            send(client_sock, header, (int)strlen(header), 0);
            send(client_sock, data, (int)len, 0);
            free(data);
        }

    } else if (strncmp(buffer + 4, "/load_ip_setting", 16) == 0) {

        size_t len;
        char *data = read_file_to_buffer(CONFIG_DIR "ip_port.txt", &len);

        if (!data) {
            const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNO_DATA";
            send(client_sock, resp, (int)strlen(resp), 0);
        } else {
            char header[256];
            snprintf(header, sizeof(header),
                     "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n",
                     len);
            send(client_sock, header, (int)strlen(header), 0);
            send(client_sock, data, (int)len, 0);
            free(data);
        }

    } else if (strncmp(buffer + 4, "/ui/", 4) == 0) {
        char req_path[256];
        sscanf(buffer + 4, "%255[^ ]", req_path);
        if (strstr(req_path, "..")) {
            const char *resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\nForbidden";
            send(client_sock, resp, (int)strlen(resp), 0);
        } else {
            char filepath[512];
            snprintf(filepath, sizeof(filepath), "%s%s", WEB_DIR, req_path + 1);
            const char *mime = "application/octet-stream";
            if (strstr(req_path, ".css")) mime = "text/css; charset=utf-8";
            else if (strstr(req_path, ".js")) mime = "application/javascript; charset=utf-8";
            size_t flen;
            char *fdata = read_file_to_buffer(filepath, &flen);
            if (!fdata) {
                const char *resp404 = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nFile not found";
                send(client_sock, resp404, (int)strlen(resp404), 0);
            } else {
                char fheader[256];
                snprintf(fheader, sizeof(fheader),
                         "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %zu\r\n\r\n",
                         mime, flen);
                send(client_sock, fheader, (int)strlen(fheader), 0);
                send(client_sock, fdata, (int)flen, 0);
                free(fdata);
            }
        }
    } else if (strncmp(buffer + 4, "/list?type=", 11) == 0) {
    char type[32];
    sscanf(buffer + 15, "%31[^ ]", type);
    list_log_files(client_sock, type);
}
else if (strncmp(buffer + 4, "/view?file=", 11) == 0) {
    char file[128];
    sscanf(buffer + 15, "%127[^ ]", file);
    if (strstr(file, "..") || strchr(file, '/') || strchr(file, '\\')) {
        const char *resp = "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\nForbidden";
        send(client_sock, resp, (int)strlen(resp), 0);
    } else {
        view_log_file(client_sock, file);
    }
}
// ---- Admin routes (Basic Auth protected) ----
else if (strncmp(buffer + 4, "/admin", 6) == 0) {
    if (!check_basic_auth(buffer)) {
        send_auth_required(client_sock);
    } else {
        serve_admin_page(client_sock);
    }
}
else if (strncmp(buffer + 4, "/load_serial", 12) == 0) {
    // Read-only: no auth needed (customer can see serial, but cannot change it)
    serve_serial(client_sock);
}
else {
        serve_index(client_sock);
    }
}    else if (strncmp(buffer, "POST ", 5) == 0) {
        char path[256];
        sscanf(buffer + 5, "%255s", path);

        // Check auth for admin-only POST endpoints
        if (strcmp(path, "/save_serial") == 0) {
            if (!check_basic_auth(buffer)) {
                send_auth_required(client_sock);
                close_socket(client_sock);
                return;
            }
        }

        int content_length = 0;
        char *cl_header = strstr(buffer, "Content-Length:");
        if (cl_header) {
            sscanf(cl_header, "Content-Length: %d", &content_length);
        }

        char *body_start = strstr(buffer, "\r\n\r\n");
        if (!body_start) {
            printf("No body found.\n");
        } else {
            body_start += 4;
            int header_len = (int)(body_start - buffer);
            int body_bytes = bytes_received - header_len;

            char body[BUFFER_SIZE];
            memset(body, 0, sizeof(body));
            if (body_bytes > 0)
                memcpy(body, body_start, (size_t)body_bytes);

            while (body_bytes < content_length) {
                int r = recv(client_sock, body + body_bytes, sizeof(body) - body_bytes - 1, 0);
                if (r <= 0) break;
                body_bytes += r;
            }
            if (content_length < (int)sizeof(body))
                body[content_length] = '\0';
            else
                body[sizeof(body)-1] = '\0';

            handle_post(client_sock, path, body);
        }
    }
    else {
        const char *resp =
            "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\n\r\nUnsupported method";
        send(client_sock, resp, (int)strlen(resp), 0);
    }

    close_socket(client_sock);
}

// Thread wrapper: receives pointer to socket_t (allocated on heap)
THREAD_RETURN client_thread_func(THREAD_PARAM arg) {
    socket_t client = *((socket_t *)arg);
    free(arg); // free the pointer passed from accept loop
    handle_client(client);
#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}

int main() {
#ifdef _WIN32
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2,2), &wsa) != 0) {
        fprintf(stderr, "WSAStartup failed\n");
        return 1;
    }
#else
    // Ignore SIGPIPE to prevent termination when sending to closed sockets
    signal(SIGPIPE, SIG_IGN);
#endif

    // Ensure required directories
    ensure_dir(WEB_DIR);
    ensure_dir(UI_DIR);
    ensure_dir(CONFIG_DIR);
    ensure_dir(LOG_DIR);
    printf("Directories ready.\n");

     

    socket_t server_fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (
#ifdef _WIN32
        server_fd == INVALID_SOCKET
#else
        server_fd < 0
#endif
    ) {
        perror("Socket creation failed");
#ifdef _WIN32
        WSACleanup();
#endif
        return 1;
    }

    // Allow address reuse
    int opt = 1;
#ifdef _WIN32
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, (const char*)&opt, sizeof(opt));
#else
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
#endif

    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(PORT);
    server_addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(server_fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        perror("Bind failed");
        close_socket(server_fd);
#ifdef _WIN32
        WSACleanup();
#endif
        return 1;
    }

    if (listen(server_fd, BACKLOG) < 0) {
        perror("Listen failed");
        close_socket(server_fd);
#ifdef _WIN32
        WSACleanup();
#endif
        return 1;
    }

    printf("Server running on http://localhost:%d\n", PORT);

    while (1) {
        struct sockaddr_in client_addr;
        socklen_t addr_size = sizeof(client_addr);
        socket_t client = accept(server_fd, (struct sockaddr*)&client_addr, &addr_size);
        if (
#ifdef _WIN32
            client == INVALID_SOCKET
#else
            client < 0
#endif
        ) {
            perror("Accept failed");
            // Defensive: if accept fails (e.g. EMFILE "too many open files"),
            // pause briefly instead of spinning at 100% CPU, then retry.
#ifdef _WIN32
            Sleep(100);
#else
            usleep(100000); // 0.1s
#endif
            continue;
        }

        // Give every client socket a recv/send timeout so a stalled or half-open
        // connection cannot block its worker thread forever (which would leak the
        // file descriptor). After the timeout, recv()/send() return an error, the
        // thread finishes, and close_socket() frees the fd.
        {
#ifdef _WIN32
            DWORD tmo = 10000; // milliseconds
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tmo, sizeof(tmo));
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tmo, sizeof(tmo));
#else
            struct timeval tmo;
            tmo.tv_sec = 10;
            tmo.tv_usec = 0;
            setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tmo, sizeof(tmo));
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &tmo, sizeof(tmo));
#endif
        }

        // Allocate socket for thread argument (so each thread gets its own value)
        socket_t *pclient = malloc(sizeof(socket_t));
        if (!pclient) {
            close_socket(client);
            continue;
        }
        *pclient = client;

#ifdef _WIN32
        /* Create a detached thread on Windows */
        uintptr_t thr = _beginthreadex(NULL, 0, client_thread_func, (void*)pclient, 0, NULL);
        if (thr == 0) {
            // thread creation failed
            free(pclient);
            close_socket(client);
            fprintf(stderr, "Failed to create thread\n");
        } else {
            CloseHandle((HANDLE)thr); // detach (close handle, thread keeps running)
        }
#else
        pthread_t tid;
        if (pthread_create(&tid, NULL, client_thread_func, (void*)pclient) != 0) {
            free(pclient);
            close_socket(client);
            fprintf(stderr, "Failed to create pthread\n");
        } else {
            pthread_detach(tid); // let thread resources be reclaimed automatically
        }
#endif
    }

    // unreachable in normal run
    close_socket(server_fd);
#ifdef _WIN32
    WSACleanup();
#endif
    return 0;
}
