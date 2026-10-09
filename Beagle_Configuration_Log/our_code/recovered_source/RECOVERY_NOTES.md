# Recovery Notes — serial_bridge / mcu_server

Reverse-engineered from DWARF debug info + ELF strings of the aarch64 binaries
captured in `beagle_capture_20260810_142109`.

- **serial_bridge** — original source `server.c`, build target `server_simplex`,
  `comp_dir = /home/niraj/eclipse-workspace/serial_bridge/Debug`. Compiled `-g3 -O0`
  with full `.debug_info` **and** `.debug_line` → high recovery fidelity.
- **mcu_server** — original source `uart2_server.c`. Only a symbol table + strings
  were available (no user `.debug_info`); nm proves the identical 3-function set,
  so it is `serial_bridge.c` with the known constant deltas applied.

## Recovered symbols & constants

| Symbol / constant | Value | Source | Confidence |
|---|---|---|---|
| `PORT` | 2000 (mcu: 2001) | DWARF macro dump / strings | High |
| `SERIAL_PORT` | "/dev/ttyS0" (mcu: /dev/ttyS3) | DWARF macro / strings | High |
| `SHM_NAME` | "/beagleplay_shm" (mcu: /beagleplay_uart2_shm) | DWARF macro / strings | High |
| `SHM_SIZE` | 1024 | DWARF macro dump | High |
| `SEM_EMPTY` | "/sem_empty" (mcu: /sem_empty1) | DWARF macro / strings | High |
| `SEM_FULL` | "/sem_full" (mcu: /sem_full1) | DWARF macro / strings | High |
| `MAX_CLIENTS` | 10 | Inferred from `clients[10]` (DWARF array bound) | High |
| `BUFFER_SIZE` | 2024 | Inferred from `buf`/`temp`/`b`/`buffer` `[2024]` arrays | High (value certain; macro name inferred) |
| `struct shared_data` | `{ char message[256]; }` | DWARF `DW_TAG_structure_type` server.c:36 | High |
| global `buf` | `char[2024]` @0x412228, server.c:48 | DWARF | High |
| global `temp` | `char[2024]` @0x412a10, server.c:49 | DWARF | High |
| global `response` | `char[1024]` @0x413200, server.c:51 | DWARF | High |
| global `client_ip` | `char[16]` @0x412210, server.c:52 | DWARF | High |
| global `ip_address` | `char[17]` @0x412198, server.c:53 | DWARF | High |
| global `serial_fd` | `int` @0x412224, server.c:56 | DWARF | High |
| global `sfd` | `int` @0x412220, server.c:56 | DWARF | High |
| global `cfd` | `int` @0x4131f8, server.c:56 | DWARF | High |
| global `clients` | `int[10]` @0x4121b8, server.c:59 | DWARF | High |
| global `client_mutex` | `pthread_mutex_t` @0x4121e0, server.c:60 | DWARF | High |
| `htmlheader` | `char[97]` (extern, other TU, file 17:11) | DWARF | Not used here — belongs to another module; omitted |

### Functions (source order from `.debug_line` low_pc)

| Function | Signature | decl_line | Confidence |
|---|---|---|---|
| `serial_reader` | `void *serial_reader(void *arg)` | 135 | Med (skeleton High) |
| `tcp_to_serial` | `void *tcp_to_serial(void *arg)` | 157 | Med (skeleton High) |
| `main` | `int main(void)` | 187 | Med (skeleton High) |

Locals recovered from DWARF (fbreg offsets): `serial_reader` → `buf[2024]`(136),
`n`(138), `i`(141), `sent`(143). `tcp_to_serial` → `client_fd`(158), `buf`(159),
`n`(161), `i`(170). `main` → `b[2024]`, `buffer[2024]`, `tty termios`, `commands[6]`,
`len`, `server`/`client_addr sockaddr_in`, `opt`, label `abc`(355), `fd`, `shm_ptr`,
`sem_empty`, `sem_full`, `serial_thread`, `added`, `arg int*`, `t pthread_t`.

## Certain vs reconstructed

**Certain (High):** every name, type, constant, struct layout, the function set,
their source ordering, and the statement-level control-flow skeleton (which lines
are reads/loops/socket calls) — all derived directly from `.debug_info` +
`.debug_line`, cross-checked against the recovered literal strings ("Serial port %s
opened successfully.", "socket is created..", "Listening on TCP port %d...",
"Commands sent successfully.", "HOST IP Address=", "0.0.0.0", the five GNSS config
commands, and all error strings).

**Reconstructed (Med/Low)** — marked `// RECONSTRUCTED` in source:
- Exact per-statement expressions (register logic was not disassembled).
- `commands[6]` has 6 slots but only 5 config strings were recovered; slot 6 is
  modelled as a `NULL` sentinel. **Low** — the real 6th element (if any) is unknown.
- CRLF terminator + `usleep` between config commands. **Low** — not provable from DWARF.
- serial→client broadcast on write failure (client not dropped). **Med**.
- `free(arg)` / `malloc(int)` client-arg passing convention. **Med** — DWARF shows
  `arg` is `int*` (main local, fbreg -48) passed to the thread; heap handoff is the
  standard idiom but the binary could use a fixed buffer.
- SHM/semaphore **runtime use**. **Low** — DWARF proves the objects are created in
  `main` (shm_open/ftruncate/mmap, two sem_open), but `serial_reader` broadcasts
  straight to sockets, so the shared-memory/semaphore path appears to be a secondary
  or vestigial IPC channel. `shm_ptr`, `sem_empty`, `sem_full` are `main` locals, not
  globals, so the threads as reconstructed do not touch them.
- `label abc` at server.c:355 — a `goto` target inside the socket-setup region;
  its retry/error semantics were not reconstructed (accept-loop `continue` used
  instead). **Low**.
- mcu_server GNSS config commands are **vestigial** copy-paste (per ground-truth).

## How to verify the Low-confidence parts against the running binary

Run on the board (aarch64) or with a cross objdump:

```
objdump -d --start-address=0x401250 --stop-address=0x4017e4 serial_bridge   # main
objdump -d --start-address=0x40108c --stop-address=0x401168 serial_bridge   # serial_reader
objdump -d --start-address=0x401168 --stop-address=0x401250 serial_bridge   # tcp_to_serial
```

- **6th `commands[]` element / CRLF / delay:** disassemble main lines 224–240
  (0x40132c–0x4013f0); inspect the `commands` initialiser in `.data`/`.rodata`
  and the `write()` argument setup to confirm terminator bytes and any `usleep`.
- **SHM/sem usage:** `grep` the disassembly of `serial_reader`/`tcp_to_serial` for
  references to `shm_ptr`/`sem_wait`/`sem_post`; if none, the SHM path is confirmed
  vestigial. Also `strace -f ./server_simplex` and watch for `sem_wait`/`sem_post`.
- **client-arg handoff:** check main 0x401784–0x4017c0 for a `malloc` call before
  `pthread_create`, vs. passing `&cfd` directly.
- **write-error client drop:** inspect serial_reader 0x4010f0–0x401140 for a branch
  on the `write()` return that clears `clients[i]`.
- **label `abc`:** disassemble around 0x40152c to see what jumps back to it.

## Build

```
# serial_bridge (target name matches original: server_simplex)
gcc serial_bridge.c -o server_simplex -pthread -lrt

# mcu_server
gcc mcu_server.c -o mcu_server -pthread -lrt
```
