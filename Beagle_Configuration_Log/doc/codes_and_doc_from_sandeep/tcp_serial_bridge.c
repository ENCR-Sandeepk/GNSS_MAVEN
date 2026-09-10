/*
 * Generic TCP <-> Serial Bridge
 *
 * Usage:
 *
 *   tcp_serial_bridge <tcp_port> <serial_device>
 *
 * Examples:
 *
 *   tcp_serial_bridge 2000 /dev/ttyS0
 *   tcp_serial_bridge 2001 /dev/ttyS3
 *
 * Baud rate:
 *   115200
 *
 * Maximum clients:
 *   8
 */

#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <termios.h>
#include <unistd.h>

#define MAX_CLIENTS     8
#define TCP_BACKLOG     16
#define BUFFER_SIZE     4096

#define SERIAL_RETRY_MS 2000

typedef struct
{
    int fd;
    int active;
    pthread_t thread;
} Client;

static volatile sig_atomic_t running = 1;

static int server_fd = -1;
static int serial_fd = -1;

static int tcp_port;
static char serial_device[256];

static Client clients[MAX_CLIENTS];

static pthread_mutex_t clients_mutex =
    PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t serial_mutex =
    PTHREAD_MUTEX_INITIALIZER;

static pthread_mutex_t serial_state_mutex =
    PTHREAD_MUTEX_INITIALIZER;


/* ============================================================
 * LOG
 * ============================================================
 */

static void log_msg(
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

    va_end(args);
}


/* ============================================================
 * SIGNAL
 * ============================================================
 */

static void signal_handler(int sig)
{
    (void)sig;

    running = 0;

    if (server_fd >= 0)
    {
        shutdown(server_fd, SHUT_RDWR);
    }
}


/* ============================================================
 * SERIAL SETUP
 * ============================================================
 */

static int configure_serial(int fd)
{
    struct termios tty;

    if (tcgetattr(fd, &tty) < 0)
        return -1;

    /*
     * Completely raw byte mode.
     */
    cfmakeraw(&tty);

    /*
     * 115200 baud.
     */
    cfsetispeed(&tty, B115200);
    cfsetospeed(&tty, B115200);

    /*
     * 8N1.
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
     * Ignore modem control.
     */
    tty.c_cflag |= CLOCAL;

    /*
     * No RTS/CTS.
     */
    tty.c_cflag &= ~CRTSCTS;

    tty.c_cc[VMIN] = 0;
    tty.c_cc[VTIME] = 1;

    if (tcsetattr(fd, TCSANOW, &tty) < 0)
        return -1;

    tcflush(fd, TCIOFLUSH);

    return 0;
}


/* ============================================================
 * OPEN SERIAL
 * ============================================================
 */

static int open_serial(void)
{
    int fd = open(
        serial_device,
        O_RDWR | O_NOCTTY | O_NONBLOCK
    );

    if (fd < 0)
    {
        log_msg(
            "ERROR",
            "Cannot open %s: %s",
            serial_device,
            strerror(errno)
        );

        return -1;
    }

    if (configure_serial(fd) < 0)
    {
        close(fd);

        log_msg(
            "ERROR",
            "Cannot configure %s",
            serial_device
        );

        return -1;
    }

    log_msg(
        "INFO",
        "Opened %s @ 115200 8N1",
        serial_device
    );

    return fd;
}


/* ============================================================
 * ENSURE SERIAL
 * ============================================================
 */

static int ensure_serial(void)
{
    int fd;

    pthread_mutex_lock(&serial_state_mutex);

    if (serial_fd >= 0)
    {
        fd = serial_fd;

        pthread_mutex_unlock(&serial_state_mutex);

        return fd;
    }

    fd = open_serial();

    if (fd >= 0)
        serial_fd = fd;

    pthread_mutex_unlock(&serial_state_mutex);

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

        log_msg(
            "INFO",
            "Serial closed"
        );
    }

    pthread_mutex_unlock(&serial_state_mutex);
}


/* ============================================================
 * TCP SEND
 * ============================================================
 */

static int send_all(
    int fd,
    const unsigned char *data,
    size_t length
)
{
    size_t sent = 0;

    while (sent < length && running)
    {
        ssize_t n = send(
            fd,
            data + sent,
            length - sent,
            MSG_NOSIGNAL
        );

        if (n > 0)
        {
            sent += n;
            continue;
        }

        if (n < 0 && errno == EINTR)
            continue;

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

            break;
        }
    }

    pthread_mutex_unlock(&clients_mutex);
}


/* ============================================================
 * SERIAL -> ALL TCP CLIENTS
 * ============================================================
 */

static void broadcast(
    const unsigned char *data,
    size_t length
)
{
    pthread_mutex_lock(&clients_mutex);

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        if (!clients[i].active)
            continue;

        send_all(
            clients[i].fd,
            data,
            length
        );
    }

    pthread_mutex_unlock(&clients_mutex);
}


/* ============================================================
 * SERIAL READER
 * ============================================================
 */

static void *serial_reader(void *arg)
{
    (void)arg;

    unsigned char buffer[BUFFER_SIZE];

    log_msg(
        "INFO",
        "Serial reader started"
    );

    while (running)
    {
        int fd = ensure_serial();

        if (fd < 0)
        {
            usleep(
                SERIAL_RETRY_MS * 1000
            );

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
             * No parsing.
             *
             * Hardware bytes are forwarded
             * exactly as received.
             */
            broadcast(
                buffer,
                (size_t)n
            );

            continue;
        }

        if (n < 0 &&
            errno == EINTR)
        {
            continue;
        }

        if (n < 0 &&
            (errno == EAGAIN ||
             errno == EWOULDBLOCK))
        {
            usleep(1000);
            continue;
        }

        if (n < 0)
        {
            log_msg(
                "ERROR",
                "Serial read error: %s",
                strerror(errno)
            );

            close_serial();
        }
    }

    return NULL;
}


/* ============================================================
 * TCP CLIENT THREAD
 * ============================================================
 */

static void *client_worker(void *arg)
{
    int client_fd = *(int *)arg;

    free(arg);

    unsigned char buffer[BUFFER_SIZE];

    log_msg(
        "INFO",
        "Client connected fd=%d",
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
             * The serial write mutex is NOT a command
             * synchronization mechanism.
             *
             * It only prevents two Linux threads from
             * physically interleaving bytes on UART.
             */

            pthread_mutex_lock(
                &serial_mutex
            );

            int fd = ensure_serial();

            if (fd < 0)
            {
                pthread_mutex_unlock(
                    &serial_mutex
                );

                log_msg(
                    "ERROR",
                    "Serial unavailable"
                );

                continue;
            }

            size_t offset = 0;

            while (offset < (size_t)n)
            {
                ssize_t written = write(
                    fd,
                    buffer + offset,
                    (size_t)n - offset
                );

                if (written > 0)
                {
                    offset +=
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

                log_msg(
                    "ERROR",
                    "Serial write failed: %s",
                    strerror(errno)
                );

                close_serial();

                break;
            }

            pthread_mutex_unlock(
                &serial_mutex
            );

            continue;
        }

        if (n == 0)
        {
            log_msg(
                "INFO",
                "Client disconnected fd=%d",
                client_fd
            );

            break;
        }

        if (errno == EINTR)
            continue;

        log_msg(
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
 * TCP SERVER
 * ============================================================
 */

static int create_server(void)
{
    int fd = socket(
        AF_INET,
        SOCK_STREAM,
        0
    );

    if (fd < 0)
        return -1;

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

    address.sin_family =
        AF_INET;

    address.sin_addr.s_addr =
        htonl(INADDR_ANY);

    address.sin_port =
        htons(tcp_port);

    if (bind(
            fd,
            (struct sockaddr *)&address,
            sizeof(address)) < 0)
    {
        log_msg(
            "ERROR",
            "bind port %d failed: %s",
            tcp_port,
            strerror(errno)
        );

        close(fd);

        return -1;
    }

    if (listen(
            fd,
            TCP_BACKLOG) < 0)
    {
        close(fd);

        return -1;
    }

    return fd;
}


/* ============================================================
 * MAIN
 * ============================================================
 */

int main(
    int argc,
    char *argv[]
)
{
    if (argc != 3)
    {
        fprintf(
            stderr,
            "\nUsage:\n"
            "  %s <tcp_port> <serial_device>\n\n"
            "Examples:\n"
            "  %s 2000 /dev/ttyS0\n"
            "  %s 2001 /dev/ttyS3\n\n",
            argv[0],
            argv[0],
            argv[0]
        );

        return EXIT_FAILURE;
    }

    tcp_port = atoi(argv[1]);

    if (tcp_port <= 0 ||
        tcp_port > 65535)
    {
        fprintf(
            stderr,
            "Invalid TCP port\n"
        );

        return EXIT_FAILURE;
    }

    snprintf(
        serial_device,
        sizeof(serial_device),
        "%s",
        argv[2]
    );

    signal(
        SIGPIPE,
        SIG_IGN
    );

    signal(
        SIGINT,
        signal_handler
    );

    signal(
        SIGTERM,
        signal_handler
    );

    for (int i = 0; i < MAX_CLIENTS; i++)
    {
        clients[i].fd = -1;
        clients[i].active = 0;
    }

    log_msg(
        "INFO",
        "================================"
    );

    log_msg(
        "INFO",
        "TCP Serial Bridge"
    );

    log_msg(
        "INFO",
        "TCP port       : %d",
        tcp_port
    );

    log_msg(
        "INFO",
        "Serial device  : %s",
        serial_device
    );

    log_msg(
        "INFO",
        "Baud rate      : 115200"
    );

    log_msg(
        "INFO",
        "Maximum clients: %d",
        MAX_CLIENTS
    );

    log_msg(
        "INFO",
        "Mode           : transparent"
    );

    log_msg(
        "INFO",
        "================================"
    );

    server_fd = create_server();

    if (server_fd < 0)
    {
        log_msg(
            "ERROR",
            "Cannot create TCP server"
        );

        return EXIT_FAILURE;
    }

    pthread_t reader_thread;

    if (pthread_create(
            &reader_thread,
            NULL,
            serial_reader,
            NULL) != 0)
    {
        log_msg(
            "ERROR",
            "Cannot create serial reader"
        );

        close(server_fd);

        return EXIT_FAILURE;
    }

    while (running)
    {
        struct sockaddr_in address;

        socklen_t address_length =
            sizeof(address);

        int client_fd = accept(
            server_fd,
            (struct sockaddr *)&address,
            &address_length
        );

        if (client_fd < 0)
        {
            if (errno == EINTR)
                continue;

            if (!running)
                break;

            continue;
        }

        int slot = add_client(client_fd);

        if (slot < 0)
        {
            log_msg(
                "WARN",
                "Maximum clients reached"
            );

            close(client_fd);

            continue;
        }

        int *fd_ptr =
            malloc(sizeof(int));

        if (fd_ptr == NULL)
        {
            remove_client(client_fd);
            close(client_fd);

            continue;
        }

        *fd_ptr = client_fd;

        if (pthread_create(
                &clients[slot].thread,
                NULL,
                client_worker,
                fd_ptr) != 0)
        {
            remove_client(client_fd);
            close(client_fd);

            free(fd_ptr);

            continue;
        }

        pthread_detach(
            clients[slot].thread
        );
    }

    running = 0;

    log_msg(
        "INFO",
        "Stopping bridge"
    );

    close(server_fd);

    server_fd = -1;

    close_serial();

    pthread_join(
        reader_thread,
        NULL
    );

    pthread_mutex_lock(
        &clients_mutex
    );

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

    pthread_mutex_unlock(
        &clients_mutex
    );

    log_msg(
        "INFO",
        "Bridge stopped"
    );

    return EXIT_SUCCESS;
}