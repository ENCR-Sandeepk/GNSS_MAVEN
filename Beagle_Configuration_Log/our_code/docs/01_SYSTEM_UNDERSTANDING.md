# BeaglePlay GNSS System — Complete Understanding
*(Written for someone with zero BeaglePlay/Linux-embedded knowledge)*

Source of facts: `doc/beagle_full_inventory.txt` + `doc/application_details_20260808_155422.txt`
(both captured from the live board on 2026-08-08) and the recovered C sources in `GNSS_FW/`.

---

## PART 1 — The 5 concepts you must know first

**1. What is a "service" (systemd unit)?**
A service is just *"a program Linux starts automatically at boot and restarts if it dies."*
Each one is described by a small text file ending in `.service`. Think of it as a Windows
Service. The file says: which program to run, as which user, when to start it, and whether
to restart it on crash.

- Stock Debian services live in `/lib/systemd/system/` → **ignore these**, they came with the OS.
- **Custom (our project) services live in `/etc/systemd/system/`** → these are the ones that matter.

Key commands:
```bash
systemctl status  <name>     # is it running?
systemctl restart <name>     # restart it
journalctl -u <name> -f      # watch its live log output
cat /etc/systemd/system/<name>.service    # see what it actually runs
```

**2. What is `/dev/ttyS0`?**
A **serial port** (UART) — a physical wire pair carrying bytes to/from a chip. The GNSS
receiver and the microcontroller each connect to one. Linux exposes them as files:
`/dev/ttyS0`, `/dev/ttyS3`, etc. Only ONE program can hold a serial port at a time.

**3. What is a "bridge"?**
The contractor's key trick: a small C program opens the serial port and *also* listens on a
TCP network port. Anything the serial device says gets sent to network clients, and anything
a network client sends goes out the serial port. **This is why your Java app never touches
hardware** — it just opens a TCP socket to `localhost:2000` / `:2001`.

**4. What is GPIO?**
Individual electrical pins the CPU can set HIGH/LOW — used to switch power on/off to the
GNSS module and the modem, and to drive LEDs.

**5. What is the modem / `eth2`?**
A Quectel 4G modem plugged into USB. Put into "ECM mode", it pretends to be a network card,
so Linux sees a new interface called `eth2` that has internet. All the board's internet
goes out through it.

---

## PART 2 — THE ARCHITECTURE (the single most important diagram)

```
   GNSS receiver (K803)                       Microcontroller (MCU)
          |                                            |
     /dev/ttyS0  (serial wire)                   /dev/ttyS3  (serial wire)
          |                                            |
  +---------------------+                    +---------------------+
  |  serial_bridge      |  CONTRACTOR        |   mcu_server        |  CONTRACTOR
  |  listens TCP :2000  |  (no source)       |   listens TCP :2001 |  (no source)
  +---------------------+                    +---------------------+
          |                                            |
          +---------------- TCP ----------+------------+
                                          |
                            +-------------------------------+
                            |  GNSS_Rover_Base.jar   YOURS  |
                            |  (started by gnss.service)    |
                            +-------------------------------+
                                          |
                          RTCM -> NTRIP caster, CSV -> FTP

  +----------------------+   +------------------+   +------------------+
  | gnss_gui_server      |   |  gnss_gpio       |   |  watchdog        |
  | = your server.c      |   |  CONTRACTOR      |   |  CONTRACTOR      |
  | web UI on TCP :8080  |   |  power + LEDs    |   |  kicks /dev/     |
  | YOURS                |   |  (gpiochip3)     |   |  watchdog1       |
  +----------------------+   +------------------+   +------------------+

  Modem (Quectel EG25-G) -> USB -> eth2 -> internet for the whole board
     modem_init.sh  (once at boot) : AT+QCFG="usbnet",1   -> creates eth2
     quectel_check  (every 10 s)   : keep eth2 up, redial if no IP
     modem_manager  (every 60 s)   : write modem_status.txt for the UI
```

**The one-line summary:** your Java app talks TCP to two small C bridge programs, which
talk serial to the two pieces of hardware. Everything else is support (power, LEDs,
internet, watchdog, web UI).

---

## PART 3 — Every custom service, explained

| # | Service | Runs | Who wrote | Source? | What it does |
|---|---|---|---|---|---|
| 1 | `gnss.service` | `/opt/gnss/start_gnss.sh` → `java -jar /home/debian/GNSS_Rover_Base.jar` | **you** | yes | Your main app |
| 2 | `gnss_gui_server.service` | `/home/debian/gnss_gui_server` | **you** | yes (`server.c`) | Web UI on :8080 |
| 3 | `serial-bridge.service` | `/usr/local/bin/serial_bridge` | contractor | **NO** | `/dev/ttyS0` ⇄ TCP **2000** (GNSS receiver) |
| 4 | `mcu_server.service` | `/usr/local/bin/mcu_server` | contractor | **NO** | `/dev/ttyS3` ⇄ TCP **2001** (microcontroller) |
| 5 | `gnss_gpio.service` | `/opt/gnss/gnss_gpio` | contractor | **YES** (recovered) | Power on GNSS+modem, LEDs, reboot button |
| 6 | `gnss-watchdog.service` | `/opt/gnss/watchdog` | contractor | **NO** | Kicks `/dev/watchdog1`; if it stops → **board resets** |
| 7 | `ip-manager.service` | `/opt/gnss/ip_manager` | contractor | **YES** (recovered) | Applies static IP; **restarts gnss.service** |
| 8 | `quectel-check.service` | `/usr/local/bin/quectel_check` (loop 10 s) | contractor | **NO** | Keeps modem internet alive |
| 9 | `modem_init.service` | `/usr/local/bin/modem_init.sh` | contractor | yes (shell) | One AT command → ECM mode → creates `eth2` |
| 10 | `modem_status.service` + timer | `/usr/local/bin/modem_manager` (60 s) | contractor | **NO** | Writes `modem_status.txt` |
| 11 | `gnss-wifi-setup.service` | `/home/debian/wifi_setup/wifi_setup` | contractor | **YES** | Sets WiFi AP name from serial number |
| 12 | `gnss-wifi-connect.service` | `/home/debian/wifi_setup/wifi_connect` | contractor | **YES** | Joins upstream WiFi for internet |
| 13 | `net-timesync.service` | `timedatectl set-ntp true; hwclock -w` | contractor | yes (inline) | Sync clock at boot |
| 14 | `daily-timesync.timer` | same, daily 08:00 | contractor | yes (inline) | Daily clock sync |
| 15 | `daily-reboot.timer` | `systemctl reboot` | contractor | yes (inline) | ⚠️ **Reboots the board daily** |
| 16 | `bring-up-eth2.service` | `/sbin/ifup eth2` | contractor | yes (inline) | ❌ Permanently failed (harmless) |

---

## PART 4 — ⚠️ SIX THINGS THAT WILL SURPRISE YOU

**1. `ip_manager` restarts your Java app EVERY HOUR.**
Log evidence, repeating hourly at :28 →
```
ip_manager[728]: RUN: ip addr add 192.168.10.100/24 dev eth0
ip_manager[728]: RUN: systemctl restart gnss.service
```
This kills and restarts your app once an hour even when nothing changed. If you have ever
seen unexplained hourly interruptions in logging or NTRIP streaming — **this is why.**

**2. The board reboots itself every day.**
`daily-reboot.timer` → `OnCalendar=*-*-* 16:06:00`. The description says "1 AM" but the
actual schedule is **16:06 (4:06 PM)**. Someone changed it and never updated the text.

**3. The hardware watchdog can reset the board.**
`/opt/gnss/watchdog` must keep petting `/dev/watchdog1`. If you stop that service (or the
CPU is starved), the SoC **hard-resets the board**. Be careful during development.

**4. Port 2000 is `/dev/ttyS0`, NOT `/dev/ttyS4`.**
Correction to the earlier assumption. `/dev/ttyS2` is a **login console — never touch it**.
`/dev/ttyS4`–`ttyS9` are completely free — that is where to attach anything new.

**5. There is a hidden shared-memory channel.**
`mcu_server` creates `/beagleplay_uart2_shm` (and `serial_bridge` creates `/beagleplay_shm`)
with semaphores. That is a *second*, undocumented way data can flow out of these programs.
Must check who reads it before rewriting.

**6. The WiFi access point is currently broken.**
`hostapd`, `iwd` and `NetworkManager` are all fighting over the same radio; `iwd` is
segfaulting. AP passphrase is hardcoded `gnss1234`, SSID stuck at `GNSS-Device-Unconfigured`
(because the serial number isn't programmed).

---

## PART 5 — What source we HAVE vs MUST REWRITE

### ✅ Source recovered (in `GNSS_FW/`)
| Program | Best copy | Notes |
|---|---|---|
| `gnss_gpio` (power/LEDs/button) | `GNSS_FW/master/gnss_gpio_main.c` | 6 copies exist; `master/` is newest. **But the deployed binary is newer still** — it contains the string `"Button pressed for 5 seconds!"` which is in NO source file. So even this is one edit behind. |
| `gnss_on/off/restart` | `GNSS_FW/master/*.c` | Tiny GPIO utilities |
| `ip_manager` | `GNSS_FW/ip_manager.c` | The hourly-restart culprit |
| `wifi_setup` + `serial_reader` | `GNSS_FW/wifi_setup/` | Shares the serial-number crypto with your `server.c` |
| `wifi_connect` | `GNSS_FW/wifi_setup/wifi_connect.c` | Has a live `nmcli` bug — easy first fix |

### ❌ NO SOURCE — must reverse-engineer or rewrite
| Program | Original filename (from binary) | Full spec recovered |
|---|---|---|
| `serial_bridge` | `server.c` (contractor's, not yours) | `/dev/ttyS0` @115200 → TCP 2000, 10 clients, pthreads, shm `/beagleplay_shm` + `/sem_empty`,`/sem_full`. Sends receiver config: `unlogall`, `interfacemode compass compass on`, `log com1 gpgga ontime 1`, `interfacemode auto auto on`, `saveconfig`. **Has DWARF debug info → best decompile target.** |
| `mcu_server` | `uart2_server.c` | `/dev/ttyS3` @115200 → TCP 2001, shm `/beagleplay_uart2_shm` + `/sem_empty1`,`/sem_full1`. Protocol: `$COMMAND,"ARG"` → reply `$1000`. Forked from `serial_bridge`. |
| `quectel_check` | `quectel_check.c` | Scans `/dev/ttyUSB*`, sends `AT+QNETDEVCTL=1,1,1`, `ip link set eth2 up`, `dhclient eth2`. **No modem-type detection exists** — hardcoded Quectel. |
| `modem_manager` | `modem_manager.c` | Reads `/dev/ttyUSB3`, writes `modem_status.txt` + `/var/log/modem_full_log.txt` |
| `watchdog` | `watchdog.c` | Opens `/dev/watchdog1`, kicks forever |
| `uart_app`, `tcp_client`, `sdcard_flasher` | — | Manual diagnostic tools |

### ❌ Doesn't exist AT ALL
**Time sync from the modem.** You believed the contractor wrote this. I searched every file
and every binary for `AT+CCLK`, `AT+QLTS`, `settimeofday`, `hwclock` — **zero hits**. Time is
synced only by plain NTP over the internet (`net-timesync` / `daily-timesync`). If you need
modem-based time (for units with no internet), it **must be written from scratch**.

---

## PART 6 — Good news for reverse-engineering

1. **All binaries are `not stripped`** → real C function names survive. `nm` gives you the
   original function list of every program.
2. **`serial_bridge` was built with `-g3` (DWARF debug info)** → a decompiler can recover
   near-source-level structure including local variable names and line numbers.
3. **The contractor's build path is embedded:** `/home/niraj/eclipse-workspace/serial_bridge/Debug`
   → developer name "niraj", Eclipse CDT, cross-compiler `aarch64-linux-gnu-gcc` (Linaro 7.5.0).
4. **`serial_bridge` and `mcu_server` are the SAME code** with different constants. Reverse
   one → you have 80% of the other.
5. **`/home/debian/.bash_history`** is the single highest-value artifact — it shows exactly
   how the contractor compiled and installed everything.
6. Someone already started: `/home/debian/serial_bridge_strings.txt` and
   `serial_bridge_symbols.txt` exist on the board.

---

## PART 7 — Next step

Run `board_capture/capture_everything.sh` on the board (instructions in
`02_HOW_TO_CAPTURE.md`). It produces ONE `.tar.gz` with every unit file, every binary
(+ symbol/string analysis + DWARF dump), all remaining source, the bash history, and
root-level runtime state. That gives us the authoritative, current picture — the files in
`GNSS_FW/` may be stale.
