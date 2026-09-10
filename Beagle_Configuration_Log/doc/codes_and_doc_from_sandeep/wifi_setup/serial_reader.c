/*
 * serial_reader.c
 * ----------------
 * Reads and decrypts the device serial number from ./gnss/.sys_cal.dat
 * This file can be compiled into any project that needs the serial number.
 *
 * The encryption uses XOR + Base64 with a keyed signature for tamper detection.
 * The key and signature seed MUST match those in server.c.
 *
 * Build:
 *   Linux:   gcc your_code.c serial_reader.c -o your_app
 *   Windows: gcc your_code.c serial_reader.c -o your_app.exe
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "serial_reader.h"

/* ---- These MUST match the values in server.c ---- */
#define SERIAL_FILE          "./gnss/.sys_cal.dat"
#define SERIAL_ENCRYPT_KEY   "EncDite$ecR3tK#y!2026"
#define SERIAL_SIGNATURE_SEED 0xA5C3E1D7

/* ---- Base64 decode lookup table ---- */
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

static int sr_base64_decode(const char *in, char *out, int max_out) {
    int len = 0;
    unsigned int buf = 0;
    int bits = 0;
    while (*in && *in != '=' && len < max_out - 1) {
        unsigned char c = b64_table[(unsigned char)*in++];
        if (c == 64) continue;
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

static void sr_xor_decrypt(const unsigned char *in, int in_len,
                            unsigned char *out, const char *key) {
    int key_len = (int)strlen(key);
    int i;
    for (i = 0; i < in_len; i++) {
        out[i] = in[i] ^ (unsigned char)key[i % key_len];
    }
}

static uint32_t sr_compute_signature(const char *serial, const char *key) {
    uint32_t hash = SERIAL_SIGNATURE_SEED;
    int key_len = (int)strlen(key);
    int i;
    for (i = 0; serial[i]; i++) {
        hash ^= ((uint32_t)(unsigned char)serial[i]) << ((i % 4) * 8);
        hash = (hash << 7) | (hash >> 25);
        hash += (uint32_t)(unsigned char)key[i % key_len];
        hash *= 0x5BD1E995;
    }
    return hash;
}

/*
 * read_device_serial()
 *   Returns:  1 = success,  0 = not found,  -1 = tampered
 */
int read_device_serial(char *serial_out, int max_len) {
    FILE *fp;
    char line[512];
    char *colon;
    const char *b64_part;
    const char *sig_hex;
    uint32_t stored_sig, computed_sig;
    char decoded_enc[256];
    unsigned char plaintext[256];
    int dec_len, copy_len;

    if (!serial_out || max_len < 2) return 0;

    /* Read file */
    fp = fopen(SERIAL_FILE, "r");
    if (!fp) return 0;  /* file not found = serial not programmed yet */

    if (!fgets(line, (int)sizeof(line), fp)) {
        fclose(fp);
        return 0;
    }
    fclose(fp);

    /* Trim trailing whitespace */
    {
        int len = (int)strlen(line);
        while (len > 0 && (line[len-1] == '\n' || line[len-1] == '\r' || line[len-1] == ' '))
            line[--len] = '\0';
        if (len == 0) return 0;
    }

    /* Split: "base64data:SIGNATURE" */
    colon = strrchr(line, ':');
    if (!colon) return -1;  /* no signature = tampered or corrupt */
    *colon = '\0';
    b64_part = line;
    sig_hex  = colon + 1;

    /* Parse stored signature */
    if (sscanf(sig_hex, "%8X", &stored_sig) != 1) return -1;

    /* Base64 decode the encrypted data */
    dec_len = sr_base64_decode(b64_part, decoded_enc, (int)sizeof(decoded_enc));
    if (dec_len <= 0) return -1;

    /* XOR decrypt */
    sr_xor_decrypt((const unsigned char *)decoded_enc, dec_len, plaintext, SERIAL_ENCRYPT_KEY);
    plaintext[dec_len] = '\0';

    /* Verify signature */
    computed_sig = sr_compute_signature((const char *)plaintext, SERIAL_ENCRYPT_KEY);
    if (computed_sig != stored_sig) return -1;  /* TAMPERED */

    /* Copy to output */
    copy_len = (dec_len < max_len - 1) ? dec_len : max_len - 1;
    memcpy(serial_out, plaintext, (size_t)copy_len);
    serial_out[copy_len] = '\0';

    return 1;
}