# How to capture everything from the BeaglePlay board
*Step-by-step. Assumes zero Linux knowledge. Just follow in order.*

Board: `192.168.10.100`  ·  user `debian`  ·  password `beagle`

---

## STEP 1 — Copy the capture script to the board

On your **Windows PC**, open Command Prompt in the folder
`Beagle_Configuration_Log\our_code\board_capture\` and run:

```
pscp -pw beagle capture_everything.sh debian@192.168.10.100:/home/debian/
```

*(`pscp` is PuTTY's file-copy tool — the same one your `deploy.bat` uses.)*

---

## STEP 2 — Open PuTTY and log in

Host `192.168.10.100`, port 22, user `debian`, password `beagle`.

**Turn on logging first** so everything you see is saved to a file:
> In PuTTY, before clicking Open: left panel → **Session → Logging** →
> select **"All session output"** → Log file name: `C:\beagle_session.log` → then Open.

---

## STEP 3 — Run the capture (takes ~2–3 minutes)

Type this in PuTTY:

```bash
cd /home/debian && sudo bash capture_everything.sh
```

It will ask for the password (`beagle`). Let it finish. At the end it prints something like:

```
 DONE.  Copy this ONE file to your PC:
   /home/debian/beagle_capture_20260810_143000.tar.gz  (2.1M)
```

**Copy that exact filename** — you need it in the next step.

---

## STEP 4 — Copy the result back to your PC

Back on Windows (in `board_capture\`), replace the filename with the one printed above:

```
pscp -pw beagle debian@192.168.10.100:/home/debian/beagle_capture_20260810_143000.tar.gz .
```

Then tell me it's done — I'll unpack and analyse it.

---

## What the script collects (and why)

| What | Why we need it |
|---|---|
| Every `.service` / `.timer` file | The definitive list of what runs at boot, and exactly which program each starts |
| Every custom binary | We have no source for most — we must decompile them |
| `nm` symbols + `strings` per binary | Binaries are **not stripped**, so real C function names survive → tells us the program's structure |
| `serial_bridge` DWARF dump | That one binary has full debug info → best chance at near-source recovery |
| All `.c` / `.h` / `.sh` still on the board | Free source we may not have copies of |
| `.bash_history` | Shows exactly how the contractor compiled & installed everything |
| Runtime state **as root** | The earlier capture ran as a normal user, so root-owned serial ports and sockets showed "Permission denied". This fixes that. |
| `/dev/shm/` listing | Reveals the hidden shared-memory channels |
| `gpioinfo` | Which GPIO pins are claimed and by whom |
| Per-service journal logs | Shows what each program actually prints while running |

---

## ⚠️ Safety notes before you run it

1. **The script only READS.** It copies files and runs status commands. It does not stop
   services, change config, or restart anything.
2. **Do not stop `gnss-watchdog.service`** while experimenting — if `/dev/watchdog1` stops
   being petted, the board **hard-resets**.
3. The board **reboots itself daily at 16:06** (`daily-reboot.timer`). If your session dies
   around then, that's why — not your fault.
4. `ip_manager` restarts `gnss.service` **every hour at :28**. Expect your Java app to blip.

---

## Useful commands to learn (run any time)

```bash
# What is running right now?
systemctl list-units --type=service --state=running

# Anything broken?
systemctl --failed

# What does one service actually run?
cat /etc/systemd/system/gnss.service

# Watch a program's live output (Ctrl+C to stop)
journalctl -u gnss.service -f

# Who is holding a serial port?
sudo fuser -v /dev/ttyS0

# Which ports are open, and by which program?
sudo ss -tulpn

# See the real function names inside a binary with no source
nm -C /usr/local/bin/mcu_server | grep " T "

# See the text strings inside a binary (reveals devices, ports, messages)
strings /usr/local/bin/serial_bridge | grep -iE "dev/|port|baud"
```
