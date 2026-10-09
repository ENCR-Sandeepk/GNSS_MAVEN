/*
 * serial_reader.h
 * ---------------
 * Helper to read the device serial number from the encrypted storage file.
 * The serial number is stored encrypted in ./gnss/.sys_cal.dat by the HTTP server.
 *
 * Usage:
 *   #include "serial_reader.h"
 *
 *   char serial[64];
 *   int result = read_device_serial(serial, sizeof(serial));
 *   if (result == 1) {
 *       printf("Serial: %s\n", serial);   // e.g. "GNSS-RB-20260042"
 *   }
 *
 * Return values:
 *    1 = success, serial_out contains the decrypted serial number
 *    0 = file not found (serial not yet programmed)
 *   -1 = file tampered / integrity check failed
 *
 * Build:
 *   Linux:   gcc your_code.c serial_reader.c -o your_app
 *   Windows: gcc your_code.c serial_reader.c -o your_app.exe
 */

#ifndef SERIAL_READER_H
#define SERIAL_READER_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * read_device_serial()
 *   Reads, decrypts, and verifies the device serial number.
 *
 *   serial_out : buffer to receive the plaintext serial string (null-terminated)
 *   max_len    : size of serial_out buffer (recommend at least 64)
 *
 *   Returns:  1 = success,  0 = not found,  -1 = tampered
 */
int read_device_serial(char *serial_out, int max_len);

#ifdef __cplusplus
}
#endif

#endif /* SERIAL_READER_H */