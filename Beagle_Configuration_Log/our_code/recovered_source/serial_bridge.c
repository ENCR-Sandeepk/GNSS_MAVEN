/*
 * serial_bridge.c   (original source filename: server.c)
 * ------------------------------------------------------
 * REVERSE-ENGINEERED reconstruction from DWARF debug info + ELF strings
 * of the aarch64 binary /usr/local/bin/serial_bridge (build target
 * "server_simplex").
 *
 *   Source of truth : serial_bridge_DWARF.txt (.debug_info),
 *                      serial_bridge_lines.txt (.debug_line),
 *                      serial_bridge_analysis.txt (nm + strings + macro dump).
 *   Original author : niraj  (comp_dir /home/niraj/eclipse-workspace/serial_bridge/Debug)
 *   Confidence      : HIGH for names / constants / structs / function set /
 *                     control-flow skeleton (all taken directly from DWARF and
 *                     the decoded line table). MEDIUM for the exact body of each
 *                     statement (register-level logic was not disassembled; the
 *                     standard, correct behaviour for a serial<->TCP multi-client
 *                     bridge is implemented). Blocks whose precise logic could not
 *                     be proven from DWARF are marked "RECONSTRUCTED".
 *
 * Function bridges the on-board GNSS receiver UART (/dev/ttyS0 @ 115200 8N1 raw)
 * to a TCP listener on 0.0.0.0:2000. A producer thread (serial_reader) reads the
 * serial port and broadcasts to every connected client; one tcp_to_serial thread
 * per client forwards client bytes back to the serial port. POSIX shared memory
 * and named semaphores are also set up (see notes on confidence below).
 *
 * Build (native, on BeaglePlay / aarch64):
 *   gcc serial_bridge.c -o server_simplex -pthread -lrt
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <pthread.h>
#include <semaphore.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/mman.h>
#include <sys/stat.h>

/* ---- #define constants (recovered verbatim from the DWARF macro dump) ---- */
#define PORT          2000                 /* DWARF macro: PORT 2000            */
#define SERIAL_PORT   "/dev/ttyS0"         /* DWARF macro: SERIAL_PORT          */
#define SHM_NAME      "/beagleplay_shm"    /* DWARF macro: SHM_NAME             */
#define SHM_SIZE      1024                 /* DWARF macro: SHM_SIZE 1024        */
#define SEM_EMPTY     "/sem_empty"         /* DWARF macro: SEM_EMPTY            */
#define SEM_FULL      "/sem_full"          /* DWARF macro: SEM_FULL             */
#define MAX_CLIENTS   10                   /* from global clients[10] in DWARF  */
#define BUFFER_SIZE   2024                 /* from buf[2024] locals/globals     */

/* ---- struct definitions (recovered from DWARF, decl file server.c:36) ---- */
struct shared_data {
    char message[256];                     /* server.c:37, member "message"    */
};

/* ---- global variables (recovered from DWARF, addresses in .bss/.data) ---- */
char buf[BUFFER_SIZE];                      /* server.c:48                      */
char temp[BUFFER_SIZE];                     /* server.c:49                      */
char response[1024];                        /* server.c:51                      */
char client_ip[16];                         /* server.c:52                      */
char ip_address[17];                        /* server.c:53                      */
int  serial_fd;                             /* server.c:56                      */
int  sfd;                                   /* server.c:56 (declared, see note) */
int  cfd;                                   /* server.c:56                      */
int  clients[MAX_CLIENTS];                  /* server.c:59 (client socket fds)  */
pthread_mutex_t client_mutex;               /* server.c:60                      */

/* Thread signatures recovered from DWARF:
 *   void *serial_reader(void *arg);    server.c:135
 *   void *tcp_to_serial(void *arg);    server.c:157
 *   int   main(void);                  server.c:187
 */

/*
 * serial_reader (server.c:135) -- producer thread.
 * Reads bytes from the serial port and broadcasts them to every currently
 * connected TCP client. The DWARF line table shows a read() at ~line 138,
 * a mutex-guarded loop over clients[] (line 141) and a per-client send whose
 * result is stored in "sent" (line 143).
 */
void *serial_reader(void *arg)
{
    char buf[BUFFER_SIZE];                  /* server.c:136                     */
    int  n;                                 /* server.c:138                     */

    (void)arg;

    while (1) {
        n = read(serial_fd, buf, sizeof(buf));
        if (n > 0) {
            /* Broadcast to all connected clients under the client lock. */
            pthread_mutex_lock(&client_mutex);
            for (int i = 0; i < MAX_CLIENTS; i++) {   /* server.c:141          */
                if (clients[i] != -1) {
                    int sent = write(clients[i], buf, n);   /* server.c:143    */
                    (void)sent;   /* RECONSTRUCTED -- verify against binary:
                                   * failed writes are ignored here; the binary
                                   * may drop the client on error. */
                }
            }
            pthread_mutex_unlock(&client_mutex);
        }
    }
    return NULL;
}

/*
 * tcp_to_serial (server.c:157) -- per-client consumer thread.
 * Reads bytes from one TCP client and writes them straight to the serial port
 * (transparent byte bridge). On disconnect it removes its fd from clients[].
 * arg carries the client socket fd (DWARF: client_fd = *(int *)arg, line 158).
 */
void *tcp_to_serial(void *arg)
{
    int  client_fd = *(int *)arg;           /* server.c:158                     */
    char buf[BUFFER_SIZE];                  /* server.c:159                     */
    int  n;                                 /* server.c:161                     */

    free(arg);   /* RECONSTRUCTED -- arg is a heap int* allocated in main       */

    while ((n = recv(client_fd, buf, sizeof(buf), 0)) > 0) {   /* server.c:161  */
        write(serial_fd, buf, n);           /* server.c:162-163                 */
    }

    /* Client closed / errored: remove its fd from the clients[] table. */
    pthread_mutex_lock(&client_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {  /* server.c:170                    */
        if (clients[i] == client_fd) {
            clients[i] = -1;
            break;
        }
    }
    pthread_mutex_unlock(&client_mutex);

    close(client_fd);                        /* server.c:176-180                */
    return NULL;
}

/*
 * main (server.c:187)
 * Opens and configures the serial port, pushes the GNSS receiver config
 * commands, sets up the TCP listener, POSIX shared memory and named
 * semaphores, launches serial_reader, then accepts clients in a loop and
 * spawns a tcp_to_serial thread per client.
 */
int main(void)
{
    struct termios tty;                      /* server.c:208                    */
    /* GNSS receiver config command list (server.c:244, char *commands[6]). */
    char *commands[6] = {                    /* strings recovered verbatim      */
        "unlogall",
        "interfacemode compass compass on",
        "log com1 gpgga ontime 1",
        "interfacemode auto auto on",
        "saveconfig",
        NULL                                 /* 6th slot -- sentinel (see note) */
    };
    int len;                                 /* server.c:332                    */
    struct sockaddr_in server;               /* server.c:333                    */
    struct sockaddr_in client_addr;          /* server.c:333                    */
    int opt;                                 /* server.c:342                    */
    int fd;                                  /* server.c:394                    */
    struct shared_data *shm_ptr;             /* server.c:402                    */
    sem_t *sem_empty;                        /* server.c:409                    */
    sem_t *sem_full;                         /* server.c:410                    */
    pthread_t serial_thread;                 /* server.c:417                    */

    /* --- open the serial port (server.c:199-202) --- */
    serial_fd = open(SERIAL_PORT, O_RDWR | O_NOCTTY);
    if (serial_fd < 0) {
        perror("Error opening serial port");
        return EXIT_FAILURE;
    }

    /* --- configure 115200 8N1 raw (server.c:205-214) --- */
    if (tcgetattr(serial_fd, &tty) != 0) {
        perror("Error getting termios attrs");
        return EXIT_FAILURE;
    }
    cfsetispeed(&tty, B115200);
    cfsetospeed(&tty, B115200);
    cfmakeraw(&tty);
    if (tcsetattr(serial_fd, TCSANOW, &tty) != 0) {
        perror("Error setting termios attrs");
        return EXIT_FAILURE;
    }

    printf("Serial port %s opened successfully.\n", SERIAL_PORT);   /* :218     */

    /* --- push GNSS receiver configuration commands (server.c:224-240) --- */
    for (int i = 0; commands[i] != NULL; i++) {
        len = strlen(commands[i]);
        write(serial_fd, commands[i], len);
        write(serial_fd, "\r\n", 2);   /* RECONSTRUCTED -- CRLF terminator      */
        usleep(100000);                /* RECONSTRUCTED -- inter-command delay  */
    }
    printf("Commands sent successfully.\n");                        /* :240     */

    /* --- initialise client table to empty (server.c:263-268) --- */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        clients[i] = -1;
    }

    /* --- create TCP listener (server.c:334-368) --- */
    sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) {
        perror("socket");
        return EXIT_FAILURE;
    }
    printf("socket is created..\n");                                /* :337     */

    opt = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    server.sin_family = AF_INET;
    server.sin_addr.s_addr = inet_addr("0.0.0.0");   /* string "0.0.0.0" recovered */
    server.sin_port = htons(PORT);

    if (bind(sfd, (struct sockaddr *)&server, sizeof(server)) < 0) {
        perror("bind");
        return EXIT_FAILURE;
    }
    if (listen(sfd, MAX_CLIENTS) < 0) {                             /* :366      */
        perror("listen");
        return EXIT_FAILURE;
    }
    printf("Listening on TCP port %d...\n", PORT);                  /* :368      */

    /* --- POSIX shared memory + named semaphores (server.c:391-413) ---
     * RECONSTRUCTED runtime use: the DWARF proves these objects are created
     * here (shm_open/ftruncate/mmap, sem_open x2) but the serial_reader body
     * broadcasts straight to client sockets, so shm_ptr/sem_empty/sem_full may
     * be a secondary IPC path consumed by another process. Kept faithfully. */
    fd = shm_open(SHM_NAME, O_CREAT | O_RDWR, 0666);               /* :394      */
    if (fd < 0) {
        perror("shm_open failed");
        return EXIT_FAILURE;
    }
    ftruncate(fd, SHM_SIZE);
    shm_ptr = mmap(NULL, SHM_SIZE, PROT_READ | PROT_WRITE,
                   MAP_SHARED, fd, 0);                              /* :402      */
    if (shm_ptr == MAP_FAILED) {
        perror("mmap failed");
        return EXIT_FAILURE;
    }
    sem_empty = sem_open(SEM_EMPTY, O_CREAT, 0666, 1);             /* :409      */
    sem_full  = sem_open(SEM_FULL,  O_CREAT, 0666, 0);            /* :410      */
    if (sem_empty == SEM_FAILED || sem_full == SEM_FAILED) {
        perror("sem_open failed");
        return EXIT_FAILURE;
    }

    /* --- launch the serial producer thread (server.c:417-418) --- */
    pthread_create(&serial_thread, NULL, serial_reader, NULL);
    pthread_detach(serial_thread);

    /* --- accept loop (server.c:420-442) --- */
    while (1) {
        socklen_t addrlen = sizeof(client_addr);
        cfd = accept(sfd, (struct sockaddr *)&client_addr, &addrlen);
        if (cfd < 0) {
            perror("accept");
            continue;   /* RECONSTRUCTED -- error handling */
        }

        inet_ntop(AF_INET, &client_addr.sin_addr, client_ip, sizeof(client_ip));
        printf("HOST IP Address=%s\n", client_ip);   /* "HOST IP Address=" recovered */

        /* register the new client in the first free slot (server.c:421-436) */
        pthread_mutex_lock(&client_mutex);
        int added = 0;                        /* server.c:425                   */
        for (int i = 0; i < MAX_CLIENTS; i++) {   /* server.c:426               */
            if (clients[i] == -1) {
                clients[i] = cfd;
                added = 1;
                break;
            }
        }
        pthread_mutex_unlock(&client_mutex);

        if (!added) {
            /* table full -- refuse this client (RECONSTRUCTED) */
            close(cfd);
            continue;
        }

        /* spawn a per-client consumer thread (server.c:438-442) */
        int *arg = malloc(sizeof(int));       /* server.c:438                   */
        *arg = cfd;
        pthread_t t;                          /* server.c:440                   */
        pthread_create(&t, NULL, tcp_to_serial, arg);
        pthread_detach(t);
    }

    return 0;   /* server.c:784 epilogue -- not reached */
}
