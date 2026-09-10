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




void view_log_file(socket_t client, const char *filename) {
    char path[512];
    snprintf(path, sizeof(path), "%s%s", LOG_DIR, filename);

    size_t len;
    char *data = read_file_to_buffer(path, &len);

    if (!data) {
        const char *resp = "HTTP/1.1 404 Not Found\r\n\r\nFile not found";
        send(client, resp, strlen(resp), 0);
        return;
    }

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/plain\r\n"
             "Content-Length: %zu\r\n\r\n",
             len);

    send(client, header, strlen(header), 0);
    send(client, data, len, 0);
    free(data);
}


void list_log_files(socket_t client, const char *type) {
    DIR *d = opendir(LOG_DIR);
    if (!d) {
        const char *resp = "HTTP/1.1 500 Internal Error\r\n\r\nCannot open log folder";
        send(client, resp, strlen(resp), 0);
        return;
    }

    struct dirent *dir;
    char html[8192];
    strcpy(html,
           "<html><body><h2>Available Logs</h2><ul>");

    while ((dir = readdir(d)) != NULL) {

        if (strcmp(type, "server") == 0) {
            if (!strstr(dir->d_name, "server_log_")) continue;
        } else if (strcmp(type, "log") == 0) {
            if (!strstr(dir->d_name, "log-")) continue;
        } else {
            continue;
        }

        char line[256];
        snprintf(line, sizeof(line),
                 "<li><a href=\"/view?file=%s\">%s</a></li>",
                 dir->d_name, dir->d_name);

        strcat(html, line);
    }
    closedir(d);

    strcat(html, "</ul></body></html>");

    char header[256];
    snprintf(header, sizeof(header),
             "HTTP/1.1 200 OK\r\n"
             "Content-Type: text/html\r\n"
             "Content-Length: %zu\r\n\r\n",
             strlen(html));

    send(client, header, strlen(header), 0);
    send(client, html, strlen(html), 0);
}


void get_daily_log_filename(char *out, size_t size) {
    time_t now = time(NULL);
    struct tm *t = localtime(&now);

    strftime(out, size, LOG_DIR "server_log_%Y-%m-%d.txt", t);
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
    struct tm *t = localtime(&now);
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

    } else if (strncmp(buffer + 4, "/load_gnss_receiver_settings", 28) == 0) {

        size_t len;
        char *data = read_file_to_buffer(CONFIG_DIR "save_gnss_receiver_settings.txt", &len);

        if (!data) {
            const char *resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNO_DATA";
            send(client_sock, resp, strlen(resp), 0);
        } else {
            char header[256];
            snprintf(header, sizeof(header),
                     "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %zu\r\n\r\n",
                     len);

            send(client_sock, header, strlen(header), 0);
            send(client_sock, data, len, 0);
            free(data);
        }

    } else if (strncmp(buffer + 4, "/list?type=", 11) == 0) {
    char type[32];
    sscanf(buffer + 15, "%31[^ ]", type);
    list_log_files(client_sock, type);
}
else if (strncmp(buffer + 4, "/view?file=", 11) == 0) {
    char file[128];
    sscanf(buffer + 15, "%127[^ ]", file);
    view_log_file(client_sock, file);
}
else {
        serve_index(client_sock);
    }
}    else if (strncmp(buffer, "POST ", 5) == 0) {
        char path[256];
        sscanf(buffer + 5, "%s", path);

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
            continue;
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
