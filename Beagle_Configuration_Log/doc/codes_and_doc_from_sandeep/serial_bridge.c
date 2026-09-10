/*
 * serial_bridge.c
 *
 * Transparent TCP <-> Serial bridge
 *
 * TCP:
 *     0.0.0.0:2000
 *
 * SERIAL:
 *     /dev/ttyS0
 *     115200 8N1
 *
 * Maximum TCP clients:
 *     8
 *
 * IMPORTANT:
 *     This program does NOT understand the protocol.
 *
 *     TCP -> Serial:
 *         bytes received from TCP are sent unchanged
 *         to the serial device.
 *
 *     Serial -> TCP:
 *         bytes received from serial are sent unchanged
 *         to all currently connected TCP clients.
 *
 * No:
 *     - command parsing
 *     - response parsing
 *     - line parsing
 *     - timeout handling
 *     - command queue
 *     - application synchronization
 *
 * Build:
 *     gcc -O2 -Wall -Wextra -pthread serial_bridge.c -o serial_bridge
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <termios.h>
#include <unistd.h>

#define TCP_PORT            2000
#define SERIAL_DEVICE       "/dev/ttyS0"

#define MAX_CLIENTS         8
#define TCP_BACKLOG         16
#define BUFFER_SIZE         4096

#define LOG_FILE            "/var/log/serial_bridge.log"

#define SERIAL_RETRY_MS     2000

typedef struct
{
    int fd;
    int active;
    pthread_t thread;
} client_t;

static volatile sig_atomic_t running = 1;

static int server_fd = -1;
static int serial_fd = -1;

static client_t clients[MAX_CLIENTS];

static pthread_mutex_t clients_mutex =
    PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t serial_write_mutex =
    PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t serial_state_mutex =
    PTHREAD_MUTEX_INITIALIZER;

static FILE *log_file = NULL;


/* ============================================================
 * LOGGING
 * ============================================================
 */

static void log_message(
    const char *level,
    const char *format,
    ...
)
{
    va_list args;

    va_start(args, format);

    fprintf(stderr, "[%s] ", level);
    vfprintf(stderr, format, args);
    fprintf(stderr, "\n");

    if (log_file != NULL)
    {
        va_list copy;

        va_copy(copy, args);

        fprintf(log_file, "[%s] ", level);
        vfprintf(log_file, format, copy);
        fprintf(log_file, "\n");

        fflush(log_file);

        va_end(copy);
    }

    va_end(args);
}


/* ============================================================
 * SIGNAL HANDLING
 * ============================================================
 */

static void signal_handler(int signal_number)
{
    (void)signal_number;

    running = 0;

    if (server_fd >= 0)
    {
        shutdown(server_fd, SHUT_RDWR);
    }
}


/* ============================================================
 * SERIAL CONFIGURATION
 * ============================================================
 */

static int configure_serial(int fd)
{
    struct termios tty;

    if (tcgetattr(fd, &tty) != 0)
    {
        log_message(
            "ERROR",
            "tcgetattr failed: %s",
            strerror(errno)
        );

        return -1;
    }

    /*
     * Raw mode.
     *
     * This is important because this is a transparent
     * byte bridge. Linux must not modify CR/LF or other
     * characters.
     */
    cfmakeraw(&tty);

    /*
     * 115200 baud.
     */
    cfsetispeed(&tty, B115200);
    cfsetospeed(&tty, B115200);

    /*
     * 8 data bits
     * No parity
     * 1 stop bit
     */
    tty.c_cflag &= ~PARENB;
    tty.c_cflag &= ~CSTOPB;
    tty.c_cflag &= ~CSIZE;

    tty.c_cflag |= CS8;

    /*
     * Enable receiver.
     */
    tty.c_cflag |= CREAD;

    /*
     * Local connection.
     */
    tty.c_cflag |= CLOCAL;

    /*
     * No hardware flow control.
     */
    tty.c_cflag &= ~CRTSCTS;

    /*
     * Non-blocking read configuration.
     */
    tty.c_cc[VMIN] = 0;
    tty.c_cc[VTIME] = 1;

    if (tcsetattr(fd, TCSANOW, &tty) != 0)
    {
        log_message(
            "ERROR",
            "tcsetattr failed: %s",
            strerror(errno)
        );

        return -1;
    }

    tcflush(fd, TCIOFLUSH);

    return 0;
}


/* ============================================================
 * OPEN SERIAL
 * ============================================================
 */

static int open_serial_device(void)
{
    int fd;

    fd = open(
        SERIAL_DEVICE,
        O_RDWR | O_NOCTTY | O_NONBLOCK
    );

    if (fd < 0)
    {
        log_message(
            "ERROR",
            "Cannot open %s: %s",
            SERIAL_DEVICE,
            strerror(errno)
        );

        return -1;
    }

    if (configure_serial(fd) != 0)
    {
        close(fd);
        return -1;
    }

    log_message(
        "INFO",
        "Serial opened: %s @ 115200 8N1",
        SERIAL_DEVICE
    );

    return fd;
}


/* ============================================================
 * CLOSE SERIAL
 * ============================================================
 */

static void close_serial(void)
{
    pthread_mutex_lock(&serial_state_mutex);

    if (serial_fd >= 0)
    {
        close(serial_fd);
        serial_fd = -1;

        log_message(
            "INFO",
            "Serial device closed"
        );
    }

    pthread_mutex_unlock(&serial_state_mutex);
}


/* ============================================================
 * GET SERIAL FD
 * ============================================================
 */

static int get_serial_fd(void)
{
    int fd;

    pthread_mutex_lock(&serial_state_mutex);

    fd = serial_fd;

    pthread_mutex_unlock(&serial_state_mutex);

    return fd;
}


/* ============================================================
 * ENSURE SERIAL IS OPEN
 * ============================================================
 */

static int ensure_serial_open(void)
{
    int fd;

    pthread_mutex_lock(&serial_state_mutex);

    if (serial_fd >= 0)
    {
        fd = serial_fd;

        pthread_mutex_unlock(&serial_state_mutex);

        return fd;
    }

    fd = open_serial_device();

    if (fd >= 0)
    {
        serial_fd = fd;
    }

    pthread_mutex_unlock(&serial_state_mutex);

    return fd;
}


/* ============================================================
 * SEND ALL
 *
 * Used for TCP transmission.
 * ============================================================
 */

static int send_all(
    int fd,
    const unsigned char *buffer,
    size_t length
)
{
    size_t total_sent = 0;

    while (total_sent < length && running)
    {
        ssize_t n = send(
            fd,
            buffer + total_sent,
            length - total_sent,
            MSG_NOSIGNAL
        );

        if (n > 0)
        {
            total_sent += (size_t)n;
            continue;
        }

        if (n < 0 && errno == EINTR)
        {
            continue;
        }

        return -1;
    }

    return 0;
}


/* ============================================================
 * REMOVE CLIENT
 * ============================================================
 */

static void remove_client(int fd)
{
    pthread_mutex_lock(&clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        if (clients[i].active &&
            clients[i].fd == fd)
        {
            clients[i].active = 0;
            clients[i].fd = -1;

            log_message(
                "INFO",
                "Client slot %d released",
                i
            );

            break;
        }
    }

    pthread_mutex_unlock(&clients_mutex);
}


/* ============================================================
 * BROADCAST SERIAL DATA
 *
 * Every connected client receives the same serial bytes.
 * ============================================================
 */

static void broadcast_serial_data(
    const unsigned char *buffer,
    size_t length
)
{
    pthread_mutex_lock(&clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        if (!clients[i].active)
        {
            continue;
        }

        int fd = clients[i].fd;

        if (send_all(fd, buffer, length) != 0)
        {
            log_message(
                "WARN",
                "Failed to send serial data to client fd=%d",
                fd
            );

            /*
             * We don't close the client here because
             * its own client thread owns the connection.
             */
        }
    }

    pthread_mutex_unlock(&clients_mutex);
}


/* ============================================================
 * SERIAL READER THREAD
 *
 * Hardware -> TCP
 * ============================================================
 */

static void *serial_reader_thread(void *argument)
{
    (void)argument;

    unsigned char buffer[BUFFER_SIZE];

    log_message(
        "INFO",
        "Serial reader thread started"
    );

    while (running)
    {
        int fd = ensure_serial_open();

        if (fd < 0)
        {
            usleep(SERIAL_RETRY_MS * 1000);
            continue;
        }

        ssize_t n = read(
            fd,
            buffer,
            sizeof(buffer)
        );

        if (n > 0)
        {
            /*
             * Completely transparent:
             *
             * Whatever came from hardware goes to TCP.
             */
            broadcast_serial_data(
                buffer,
                (size_t)n
            );

            continue;
        }

        if (n == 0)
        {
            /*
             * Nothing available.
             */
            usleep(1000);
            continue;
        }

        if (errno == EINTR)
        {
            continue;
        }

        if (errno == EAGAIN ||
            errno == EWOULDBLOCK)
        {
            usleep(1000);
            continue;
        }

        log_message(
            "ERROR",
            "Serial read error: %s",
            strerror(errno)
        );

        close_serial();

        usleep(SERIAL_RETRY_MS * 1000);
    }

    log_message(
        "INFO",
        "Serial reader thread stopped"
    );

    return NULL;
}


/* ============================================================
 * CLIENT THREAD
 *
 * TCP -> Hardware
 * ============================================================
 */

static void *client_thread(void *argument)
{
    int client_fd = *(int *)argument;

    free(argument);

    unsigned char buffer[BUFFER_SIZE];

    log_message(
        "INFO",
        "Client thread started fd=%d",
        client_fd
    );

    while (running)
    {
        ssize_t n = recv(
            client_fd,
            buffer,
            sizeof(buffer),
            0
        );

        if (n > 0)
        {
            /*
             * IMPORTANT:
             *
             * No protocol parsing.
             * No line parsing.
             * No command parsing.
             *
             * Send exactly what TCP received.
             */

            pthread_mutex_lock(
                &serial_write_mutex
            );

            int fd = ensure_serial_open();

            if (fd < 0)
            {
                pthread_mutex_unlock(
                    &serial_write_mutex
                );

                log_message(
                    "ERROR",
                    "Serial unavailable for client fd=%d",
                    client_fd
                );

                continue;
            }

            size_t total_written = 0;

            while (total_written < (size_t)n)
            {
                ssize_t written = write(
                    fd,
                    buffer + total_written,
                    (size_t)n - total_written
                );

                if (written > 0)
                {
                    total_written +=
                        (size_t)written;

                    continue;
                }

                if (written < 0 &&
                    errno == EINTR)
                {
                    continue;
                }

                if (written < 0 &&
                    (errno == EAGAIN ||
                     errno == EWOULDBLOCK))
                {
                    usleep(1000);
                    continue;
                }

                log_message(
                    "ERROR",
                    "Serial write error: %s",
                    strerror(errno)
                );

                close_serial();

                break;
            }

            pthread_mutex_unlock(
                &serial_write_mutex
            );

            continue;
        }

        if (n == 0)
        {
            log_message(
                "INFO",
                "Client fd=%d disconnected",
                client_fd
            );

            break;
        }

        if (errno == EINTR)
        {
            continue;
        }

        log_message(
            "WARN",
            "TCP receive error fd=%d: %s",
            client_fd,
            strerror(errno)
        );

        break;
    }

    shutdown(
        client_fd,
        SHUT_RDWR
    );

    close(client_fd);

    remove_client(client_fd);

    log_message(
        "INFO",
        "Client thread stopped fd=%d",
        client_fd
    );

    return NULL;
}


/* ============================================================
 * ADD CLIENT
 * ============================================================
 */

static int add_client(int fd)
{
    int slot = -1;

    pthread_mutex_lock(&clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        if (!clients[i].active)
        {
            clients[i].active = 1;
            clients[i].fd = fd;

            slot = i;

            break;
        }
    }

    pthread_mutex_unlock(&clients_mutex);

    return slot;
}


/* ============================================================
 * CREATE TCP SERVER
 * ============================================================
 */

static int create_server(void)
{
    int fd;

    fd = socket(
        AF_INET,
        SOCK_STREAM,
        0
    );

    if (fd < 0)
    {
        log_message(
            "ERROR",
            "socket() failed: %s",
            strerror(errno)
        );

        return -1;
    }

    int yes = 1;

    setsockopt(
        fd,
        SOL_SOCKET,
        SO_REUSEADDR,
        &yes,
        sizeof(yes)
    );

    struct sockaddr_in address;

    memset(
        &address,
        0,
        sizeof(address)
    );

    address.sin_family = AF_INET;

    address.sin_addr.s_addr =
        htonl(INADDR_ANY);

    address.sin_port =
        htons(TCP_PORT);

    if (bind(
            fd,
            (struct sockaddr *)&address,
            sizeof(address)) < 0)
    {
        log_message(
            "ERROR",
            "bind() failed on port %d: %s",
            TCP_PORT,
            strerror(errno)
        );

        close(fd);

        return -1;
    }

    if (listen(
            fd,
            TCP_BACKLOG) < 0)
    {
        log_message(
            "ERROR",
            "listen() failed: %s",
            strerror(errno)
        );

        close(fd);

        return -1;
    }

    log_message(
        "INFO",
        "TCP server listening on port %d",
        TCP_PORT
    );

    return fd;
}


/* ============================================================
 * MAIN
 * ============================================================
 */

int main(void)
{
    /*
     * Ignore SIGPIPE.
     *
     * Otherwise send() to a disconnected TCP client
     * could terminate the entire bridge.
     */
    signal(SIGPIPE, SIG_IGN);

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        clients[i].fd = -1;
        clients[i].active = 0;
    }

    log_file = fopen(
        LOG_FILE,
        "a"
    );

    if (log_file == NULL)
    {
        fprintf(
            stderr,
            "WARNING: cannot open %s: %s\n",
            LOG_FILE,
            strerror(errno)
        );
    }

    log_message(
        "INFO",
        "========================================"
    );

    log_message(
        "INFO",
        "Starting serial_bridge"
    );

    log_message(
        "INFO",
        "TCP     : %d",
        TCP_PORT
    );

    log_message(
        "INFO",
        "Serial  : %s",
        SERIAL_DEVICE
    );

    log_message(
        "INFO",
        "Baud    : 115200"
    );

    log_message(
        "INFO",
        "Clients : %d",
        MAX_CLIENTS
    );

    log_message(
        "INFO",
        "========================================"
    );

    server_fd = create_server();

    if (server_fd < 0)
    {
        return EXIT_FAILURE;
    }

    pthread_t reader_thread;

    if (pthread_create(
            &reader_thread,
            NULL,
            serial_reader_thread,
            NULL) != 0)
    {
        log_message(
            "ERROR",
            "Cannot create serial reader thread"
        );

        close(server_fd);

        return EXIT_FAILURE;
    }

    /*
     * Accept clients.
     */
    while (running)
    {
        struct sockaddr_in client_address;

        socklen_t address_length =
            sizeof(client_address);

        int client_fd = accept(
            server_fd,
            (struct sockaddr *)&client_address,
            &address_length
        );

        if (client_fd < 0)
        {
            if (errno == EINTR)
            {
                continue;
            }

            if (!running)
            {
                break;
            }

            log_message(
                "ERROR",
                "accept() failed: %s",
                strerror(errno)
            );

            continue;
        }

        char client_ip[INET_ADDRSTRLEN];

        inet_ntop(
            AF_INET,
            &client_address.sin_addr,
            client_ip,
            sizeof(client_ip)
        );

        int slot = add_client(client_fd);

        if (slot < 0)
        {
            log_message(
                "WARN",
                "Maximum %d clients reached; rejecting %s:%d",
                MAX_CLIENTS,
                client_ip,
                ntohs(client_address.sin_port)
            );

            const char *message =
                "Maximum clients reached\r\n";

            send(
                client_fd,
                message,
                strlen(message),
                MSG_NOSIGNAL
            );

            close(client_fd);

            continue;
        }

        log_message(
            "INFO",
            "Client connected: %s:%d fd=%d slot=%d",
            client_ip,
            ntohs(client_address.sin_port),
            client_fd,
            slot
        );

        int *fd_argument =
            malloc(sizeof(int));

        if (fd_argument == NULL)
        {
            remove_client(client_fd);
            close(client_fd);

            continue;
        }

        *fd_argument = client_fd;

        if (pthread_create(
                &clients[slot].thread,
                NULL,
                client_thread,
                fd_argument) != 0)
        {
            log_message(
                "ERROR",
                "Cannot create client thread"
            );

            remove_client(client_fd);
            close(client_fd);
            free(fd_argument);

            continue;
        }

        /*
         * The client thread cleans itself up.
         */
        pthread_detach(
            clients[slot].thread
        );
    }

    running = 0;

    log_message(
        "INFO",
        "Shutting down serial_bridge"
    );

    if (server_fd >= 0)
    {
        close(server_fd);
        server_fd = -1;
    }

    close_serial();

    pthread_join(
        reader_thread,
        NULL
    );

    pthread_mutex_lock(&clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        if (clients[i].active)
        {
            shutdown(
                clients[i].fd,
                SHUT_RDWR
            );

            close(
                clients[i].fd
            );

            clients[i].active = 0;
            clients[i].fd = -1;
        }
    }

    pthread_mutex_unlock(&clients_mutex);

    if (log_file != NULL)
    {
        fclose(log_file);
        log_file = NULL;
    }

    return EXIT_SUCCESS;
}