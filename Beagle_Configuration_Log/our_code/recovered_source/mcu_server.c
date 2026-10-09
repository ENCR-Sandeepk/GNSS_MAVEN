/*
 * mcu_server.c   (original source filename: uart2_server.c)
 * ---------------------------------------------------------
 * REVERSE-ENGINEERED reconstruction from DWARF/strings of the aarch64 binary
 * /usr/local/bin/mcu_server.
 *
 * SAME code lineage as serial_bridge.c (server.c). mcu_server has NO
 * .debug_info of its own beyond the symbol table (nm shows the identical
 * function set: main, serial_reader, tcp_to_serial), so this file is
 * serial_bridge.c with ONLY the known constant differences applied. All
 * confidence ratings therefore inherit from serial_bridge.c.
 *
 *   Differences vs serial_bridge (confirmed in mcu_server_analysis.txt):
 *     SERIAL_PORT : /dev/ttyS3            (was /dev/ttyS0)
 *     PORT        : 2001                  (was 2000)
 *     SHM_NAME    : /beagleplay_uart2_shm (was /beagleplay_shm)
 *     SEM_EMPTY   : /sem_empty1           (was /sem_empty)
 *     SEM_FULL    : /sem_full1            (was /sem_full)
 *
 * Protocol observed on port 2001: client sends  $COMMAND,"ARG"\r\n  and the
 * MCU replies  $<digits>  (e.g. $WATCHDOG,"ENABLE" -> $1000). This program is
 * a TRANSPARENT byte bridge and does NOT parse that protocol.
 *
 * The GNSS receiver config command strings below are copy-paste leftovers from
 * server.c and are almost certainly VESTIGIAL / dead on the /dev/ttyS3 (MCU)
 * path -- they are kept for fidelity but flagged.
 *
 * Build (native, on BeaglePlay / aarch64):
 *   gcc mcu_server.c -o mcu_server -pthread -lrt
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

/* ---- #define constants (MCU / UART2 variant) ---- */
#define PORT          2001                       /* was 2000                    */
#define SERIAL_PORT   "/dev/ttyS3"               /* was /dev/ttyS0              */
#define SHM_NAME      "/beagleplay_uart2_shm"    /* was /beagleplay_shm         */
#define SHM_SIZE      1024
#define SEM_EMPTY     "/sem_empty1"              /* was /sem_empty              */
#define SEM_FULL      "/sem_full1"               /* was /sem_full               */
#define MAX_CLIENTS   10
#define BUFFER_SIZE   2024

/* ---- struct definitions ---- */
struct shared_data {
    char message[256];
};

/* ---- global variables ---- */
char buf[BUFFER_SIZE];
char temp[BUFFER_SIZE];
char response[1024];
char client_ip[16];
char ip_address[17];
int  serial_fd;
int  sfd;
int  cfd;
int  clients[MAX_CLIENTS];
pthread_mutex_t client_mutex;

/*
 * serial_reader -- producer thread: read MCU UART, broadcast to all clients.
 */
void *serial_reader(void *arg)
{
    char buf[BUFFER_SIZE];
    int  n;

    (void)arg;

    while (1) {
        n = read(serial_fd, buf, sizeof(buf));
        if (n > 0) {
            pthread_mutex_lock(&client_mutex);
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (clients[i] != -1) {
                    int sent = write(clients[i], buf, n);
                    (void)sent;   /* RECONSTRUCTED -- verify against binary */
                }
            }
            pthread_mutex_unlock(&client_mutex);
        }
    }
    return NULL;
}

/*
 * tcp_to_serial -- per-client consumer: forward client bytes to the MCU UART.
 */
void *tcp_to_serial(void *arg)
{
    int  client_fd = *(int *)arg;
    char buf[BUFFER_SIZE];
    int  n;

    free(arg);   /* RECONSTRUCTED -- arg is a heap int* allocated in main */

    while ((n = recv(client_fd, buf, sizeof(buf), 0)) > 0) {
        write(serial_fd, buf, n);
    }

    pthread_mutex_lock(&client_mutex);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] == client_fd) {
            clients[i] = -1;
            break;
        }
    }
    pthread_mutex_unlock(&client_mutex);

    close(client_fd);
    return NULL;
}

int main(void)
{
    struct termios tty;
    /* VESTIGIAL on the MCU path -- GNSS receiver config strings copy-pasted
     * from server.c. Kept for fidelity; likely harmless bytes on /dev/ttyS3. */
    char *commands[6] = {
        "unlogall",
        "interfacemode compass compass on",
        "log com1 gpgga ontime 1",
        "interfacemode auto auto on",
        "saveconfig",
        NULL
    };
    int len;
    struct sockaddr_in server;
    struct sockaddr_in client_addr;
    int opt;
    int fd;
    struct shared_data *shm_ptr;
    sem_t *sem_empty;
    sem_t *sem_full;
    pthread_t serial_thread;

    /* --- open the MCU serial port --- */
    serial_fd = open(SERIAL_PORT, O_RDWR | O_NOCTTY);
    if (serial_fd < 0) {
        perror("Error opening serial port");
        return EXIT_FAILURE;
    }

    /* --- configure 115200 8N1 raw --- */
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

    printf("Serial port %s opened successfully.\n", SERIAL_PORT);

    /* --- VESTIGIAL config push (see note above) --- */
    for (int i = 0; commands[i] != NULL; i++) {
        len = strlen(commands[i]);
        write(serial_fd, commands[i], len);
        write(serial_fd, "\r\n", 2);   /* RECONSTRUCTED */
        usleep(100000);                /* RECONSTRUCTED */
    }
    printf("Commands sent successfully.\n");

    /* --- initialise client table --- */
    for (int i = 0; i < MAX_CLIENTS; i++) {
        clients[i] = -1;
    }

    /* --- create TCP listener --- */
    sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) {
        perror("socket");
        return EXIT_FAILURE;
    }
    printf("socket is created..\n");

    opt = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    server.sin_family = AF_INET;
    server.sin_addr.s_addr = inet_addr("0.0.0.0");
    server.sin_port = htons(PORT);

    if (bind(sfd, (struct sockaddr *)&server, sizeof(server)) < 0) {
        perror("bind");
        return EXIT_FAILURE;
    }
    if (listen(sfd, MAX_CLIENTS) < 0) {
        perror("listen");
        return EXIT_FAILURE;
    }
    printf("Listening on TCP port %d...\n", PORT);

    /* --- POSIX shared memory + named semaphores (RECONSTRUCTED runtime use) --- */
    fd = shm_open(SHM_NAME, O_CREAT | O_RDWR, 0666);
    if (fd < 0) {
        perror("shm_open failed");
        return EXIT_FAILURE;
    }
    ftruncate(fd, SHM_SIZE);
    shm_ptr = mmap(NULL, SHM_SIZE, PROT_READ | PROT_WRITE,
                   MAP_SHARED, fd, 0);
    if (shm_ptr == MAP_FAILED) {
        perror("mmap failed");
        return EXIT_FAILURE;
    }
    sem_empty = sem_open(SEM_EMPTY, O_CREAT, 0666, 1);
    sem_full  = sem_open(SEM_FULL,  O_CREAT, 0666, 0);
    if (sem_empty == SEM_FAILED || sem_full == SEM_FAILED) {
        perror("sem_open failed");
        return EXIT_FAILURE;
    }

    /* --- launch the serial producer thread --- */
    pthread_create(&serial_thread, NULL, serial_reader, NULL);
    pthread_detach(serial_thread);

    /* --- accept loop --- */
    while (1) {
        socklen_t addrlen = sizeof(client_addr);
        cfd = accept(sfd, (struct sockaddr *)&client_addr, &addrlen);
        if (cfd < 0) {
            perror("accept");
            continue;   /* RECONSTRUCTED */
        }

        inet_ntop(AF_INET, &client_addr.sin_addr, client_ip, sizeof(client_ip));
        printf("HOST IP Address=%s\n", client_ip);

        pthread_mutex_lock(&client_mutex);
        int added = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (clients[i] == -1) {
                clients[i] = cfd;
                added = 1;
                break;
            }
        }
        pthread_mutex_unlock(&client_mutex);

        if (!added) {
            close(cfd);
            continue;
        }

        int *arg = malloc(sizeof(int));
        *arg = cfd;
        pthread_t t;
        pthread_create(&t, NULL, tcp_to_serial, arg);
        pthread_detach(t);
    }

    return 0;
}
