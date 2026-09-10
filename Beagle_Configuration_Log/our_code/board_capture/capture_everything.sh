#!/bin/bash
# =====================================================================
#  BeaglePlay FULL CAPTURE  (run as: sudo bash capture_everything.sh)
#
#  Purpose: pull EVERYTHING needed to take over the contractor's work:
#    - every systemd unit file (the "what runs at boot" definitions)
#    - every custom binary (so we can decompile / reverse them)
#    - every source file still on the board
#    - the developer's shell history (shows how he built things)
#    - live runtime state captured as ROOT (so root-owned sockets and
#      serial ports are visible -- the earlier capture missed these)
#
#  Output: /home/debian/beagle_capture_<date>.tar.gz  (one file to copy off)
# =====================================================================

set -u
OUT=/home/debian/beagle_capture_$(date +%Y%m%d_%H%M%S)
mkdir -p "$OUT"/{units,binaries,source,logs,runtime}
LOG="$OUT/00_capture_log.txt"
exec > >(tee -a "$LOG") 2>&1

echo "=== BeaglePlay capture started: $(date) ==="
echo "=== Running as: $(id) ==="

# ---------------------------------------------------------------------
# 1. ALL systemd unit files (custom ones live in /etc/systemd/system)
# ---------------------------------------------------------------------
echo; echo "########## 1. SYSTEMD UNITS ##########"
cp -a /etc/systemd/system/*.service "$OUT/units/" 2>/dev/null
cp -a /etc/systemd/system/*.timer   "$OUT/units/" 2>/dev/null
ls -la /etc/systemd/system/ > "$OUT/units/_listing.txt" 2>&1
echo "--- enabled/disabled state ---"
systemctl list-unit-files --type=service --no-pager > "$OUT/units/_unit_states.txt" 2>&1
systemctl list-timers --all --no-pager            > "$OUT/units/_timers.txt" 2>&1
systemctl list-units --type=service --state=running --no-pager > "$OUT/units/_running.txt" 2>&1
systemctl --failed --no-pager                     > "$OUT/units/_failed.txt" 2>&1
echo "Units copied: $(ls -1 "$OUT/units"/*.service 2>/dev/null | wc -l) service files"

# ---------------------------------------------------------------------
# 2. THE CUSTOM BINARIES  (no source exists for most of these!)
# ---------------------------------------------------------------------
echo; echo "########## 2. CUSTOM BINARIES ##########"
for f in \
    /opt/gnss/gnss_gpio /opt/gnss/watchdog /opt/gnss/ip_manager \
    /opt/gnss/start_gnss.sh /opt/gnss/javaprogram.txt \
    /usr/local/bin/mcu_server /usr/local/bin/serial_bridge \
    /usr/local/bin/quectel_check /usr/local/bin/modem_manager \
    /usr/local/bin/modem_init.sh \
    /home/debian/gnss_gui_server \
    /home/debian/gnss_on /home/debian/gnss_off /home/debian/gnss_restart \
    /home/debian/tcp_client /home/debian/uart_app /home/debian/sdcard_flasher
do
    if [ -e "$f" ]; then
        cp -a "$f" "$OUT/binaries/" 2>/dev/null
        echo "COPIED : $f  ($(stat -c%s "$f") bytes, $(stat -c%y "$f" | cut -d. -f1))"
    else
        echo "MISSING: $f"
    fi
done

# For each binary: type, symbols, strings -> helps rebuild the source
echo; echo "--- analysing binaries (symbols + strings) ---"
for b in "$OUT"/binaries/*; do
    [ -f "$b" ] || continue
    n=$(basename "$b")
    case "$n" in *.sh|*.txt) continue;; esac
    { echo "=== FILE TYPE ==="; file "$b"
      echo; echo "=== FUNCTION SYMBOLS ==="; nm -C "$b" 2>/dev/null | grep -iE " [TtWw] " | head -100
      echo; echo "=== INTERESTING STRINGS ==="
      strings -n 4 "$b" | grep -iE "/dev/|tty|port|socket|listen|baud|gpio|chip|line|AT\+|shm|sem_|\.c$|%s|%d|error|fail|open|thread|APN|eth[0-9]|watchdog|/home/|/opt/|/usr/" | sort -u | head -200
    } > "$OUT/binaries/${n}_analysis.txt" 2>&1
    echo "analysed: $n"
done

# serial_bridge has DWARF debug info -> richest reverse-engineering target
if [ -f /usr/local/bin/serial_bridge ]; then
    echo "--- serial_bridge DWARF (has debug info!) ---"
    objdump --dwarf=info /usr/local/bin/serial_bridge > "$OUT/binaries/serial_bridge_DWARF.txt" 2>&1
    objdump --dwarf=decodedline /usr/local/bin/serial_bridge > "$OUT/binaries/serial_bridge_lines.txt" 2>&1
    echo "DWARF dumped ($(wc -l < "$OUT/binaries/serial_bridge_DWARF.txt") lines)"
fi

# ---------------------------------------------------------------------
# 3. ANY SOURCE CODE STILL ON THE BOARD
# ---------------------------------------------------------------------
echo; echo "########## 3. SOURCE CODE ON BOARD ##########"
find / -xdev \( -name "*.c" -o -name "*.h" -o -name "*.sh" \) \
     -not -path "/usr/include/*" -not -path "/usr/share/*" -not -path "/usr/src/*" \
     -not -path "/proc/*" -not -path "/sys/*" -not -path "/snap/*" \
     -not -path "/var/lib/docker/*" -newermt "2024-01-01" 2>/dev/null \
     > "$OUT/source/_all_source_paths.txt"
echo "source files found: $(wc -l < "$OUT/source/_all_source_paths.txt")"
mkdir -p "$OUT/source/wifi_setup" "$OUT/source/home" "$OUT/source/optgnss"
cp -a /home/debian/wifi_setup/*  "$OUT/source/wifi_setup/" 2>/dev/null
cp -a /home/debian/*.c /home/debian/*.h "$OUT/source/home/" 2>/dev/null
cp -a /opt/gnss/*.sh /opt/gnss/*.c "$OUT/source/optgnss/" 2>/dev/null
cp -a /home/debian/serial_bridge_strings.txt /home/debian/serial_bridge_symbols.txt "$OUT/source/" 2>/dev/null

# ---------------------------------------------------------------------
# 4. THE DEVELOPER'S SHELL HISTORY  (how he built & installed everything)
# ---------------------------------------------------------------------
echo; echo "########## 4. SHELL HISTORY ##########"
cp -a /home/debian/.bash_history "$OUT/logs/bash_history_debian.txt" 2>/dev/null
cp -a /root/.bash_history        "$OUT/logs/bash_history_root.txt"   2>/dev/null
echo "history lines (debian): $(wc -l < "$OUT/logs/bash_history_debian.txt" 2>/dev/null || echo 0)"
echo "history lines (root)  : $(wc -l < "$OUT/logs/bash_history_root.txt"   2>/dev/null || echo 0)"

# ---------------------------------------------------------------------
# 5. LIVE RUNTIME STATE  -- as ROOT this time (previous capture missed these)
# ---------------------------------------------------------------------
echo; echo "########## 5. RUNTIME STATE (as root) ##########"
{
echo "===== PROCESSES ====="                 ; ps auxww
echo; echo "===== LISTENING SOCKETS (with owner) ====="; ss -tulpn
echo; echo "===== WHO HOLDS EACH SERIAL PORT ====="    ; fuser -v /dev/ttyS* /dev/ttyUSB* /dev/watchdog* 2>&1
echo; echo "===== lsof ON SERIAL/WATCHDOG ====="       ; lsof /dev/ttyS0 /dev/ttyS3 /dev/ttyUSB0 /dev/ttyUSB1 /dev/ttyUSB2 /dev/ttyUSB3 /dev/watchdog1 2>&1
echo; echo "===== SHARED MEMORY (mcu_server uses this!) ====="; ls -la /dev/shm/
echo; echo "===== NETWORK INTERFACES ====="   ; ip -d addr; echo; ip route
echo; echo "===== GPIO LINES IN USE ====="    ; gpioinfo 2>&1
echo; echo "===== SERIAL DEVICES ====="       ; ls -la /dev/ttyS* /dev/ttyUSB* /dev/serial/by-id/ 2>&1
echo; echo "===== USB DEVICES ====="          ; lsusb -v 2>/dev/null | grep -iE "^Bus|idVendor|idProduct|iProduct|iManufacturer"
echo; echo "===== MODEM STATUS FILE ====="    ; cat /home/debian/modem_status.txt 2>&1
} > "$OUT/runtime/runtime_state.txt" 2>&1
echo "runtime state captured"

# ---------------------------------------------------------------------
# 6. CONFIG FILES + LOGS
# ---------------------------------------------------------------------
echo; echo "########## 6. CONFIG + LOGS ##########"
mkdir -p "$OUT/logs/gnss_config"
cp -a /home/debian/gnss/config/* "$OUT/logs/gnss_config/" 2>/dev/null
cp -a /etc/hostapd/hostapd.conf  "$OUT/logs/" 2>/dev/null
cp -a /etc/udev/rules.d/*        "$OUT/logs/" 2>/dev/null
journalctl -b --no-pager > "$OUT/logs/journal_thisboot.txt" 2>&1
for svc in gnss gnss_gpio gnss-watchdog gnss_gui_server mcu_server serial-bridge \
           quectel-check ip-manager modem_init modem_status gnss-wifi-connect gnss-wifi-setup; do
    journalctl -u "$svc" -n 300 --no-pager > "$OUT/logs/journal_${svc}.txt" 2>&1
done
echo "logs captured"

# ---------------------------------------------------------------------
# 7. PACKAGE IT UP
# ---------------------------------------------------------------------
echo; echo "########## 7. PACKAGING ##########"
cd /home/debian || exit 1
tar czf "${OUT}.tar.gz" -C /home/debian "$(basename "$OUT")" 2>/dev/null
chown debian:debian "${OUT}.tar.gz" 2>/dev/null
echo
echo "======================================================"
echo " DONE.  Copy this ONE file to your PC:"
echo "   ${OUT}.tar.gz   ($(du -h "${OUT}.tar.gz" 2>/dev/null | cut -f1))"
echo
echo " From your Windows PC run:"
echo "   pscp -pw beagle debian@192.168.10.100:${OUT}.tar.gz ."
echo "======================================================"
