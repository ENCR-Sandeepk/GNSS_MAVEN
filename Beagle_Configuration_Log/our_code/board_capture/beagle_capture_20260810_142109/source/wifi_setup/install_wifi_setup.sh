#!/bin/bash
# ============================================================
# install_wifi_setup.sh
# Run this script on the BeaglePlay to install everything.
# Usage: sudo bash install_wifi_setup.sh
#
# Installs TWO services:
#   1. gnss-wifi-setup   - Sets AP SSID from device serial number
#   2. gnss-wifi-connect - Connects wlan0 to external WiFi for internet
# ============================================================

set -e

APP_DIR="/home/debian/wifi_setup"
SERVER_DIR="/home/debian"
SERVICE_AP="gnss-wifi-setup"
SERVICE_CLIENT="gnss-wifi-connect"

echo "======================================"
echo " GNSS WiFi Setup - Installer"
echo "======================================"

# Step 1: Compile both programs
echo ""
echo "[1/7] Compiling wifi_setup (AP SSID from serial)..."
cd "$APP_DIR"
gcc wifi_setup.c serial_reader.c -o wifi_setup
echo "      Built: $APP_DIR/wifi_setup"

echo ""
echo "[2/7] Compiling wifi_connect (WiFi client for internet)..."
gcc wifi_connect.c -o wifi_connect
echo "      Built: $APP_DIR/wifi_connect"

# Step 3: Make executable
echo ""
echo "[3/7] Setting permissions..."
chmod +x wifi_setup wifi_connect
echo "      Done."

# Step 4: Copy systemd services
echo ""
echo "[4/7] Installing systemd services..."
cp gnss-wifi-setup.service /etc/systemd/system/${SERVICE_AP}.service
echo "      Copied ${SERVICE_AP}.service"
cp gnss-wifi-connect.service /etc/systemd/system/${SERVICE_CLIENT}.service
echo "      Copied ${SERVICE_CLIENT}.service"

# Step 5: Enable services
echo ""
echo "[5/7] Enabling services for auto-start at boot..."
systemctl daemon-reload
systemctl enable ${SERVICE_AP}.service
echo "      ${SERVICE_AP} enabled."
systemctl enable ${SERVICE_CLIENT}.service
echo "      ${SERVICE_CLIENT} enabled."

# Step 6: Test AP setup service
echo ""
echo "[6/7] Testing AP setup service (${SERVICE_AP})..."
timeout 10 systemctl start ${SERVICE_AP}.service || echo "      Note: Service timed out or failed"
echo ""
echo "--- ${SERVICE_AP} Status ---"
systemctl status ${SERVICE_AP}.service --no-pager || true

# Step 7: Start WiFi client connect service
echo ""
echo "[7/7] Starting WiFi client connect (${SERVICE_CLIENT})..."
systemctl start ${SERVICE_CLIENT}.service
sleep 3
echo ""
echo "--- ${SERVICE_CLIENT} Status ---"
systemctl status ${SERVICE_CLIENT}.service --no-pager || true

echo ""
echo "--- Service Logs ---"
echo ""
echo ">> ${SERVICE_AP}:"
journalctl -u ${SERVICE_AP}.service --no-pager -n 10 || true
echo ""
echo ">> ${SERVICE_CLIENT}:"
journalctl -u ${SERVICE_CLIENT}.service --no-pager -n 10 || true

echo ""
echo "======================================"
echo " Installation complete!"
echo ""
echo " Two services installed:"
echo ""
echo " 1. ${SERVICE_AP}"
echo "    Sets WiFi AP name from device serial number"
echo "    Status : systemctl status ${SERVICE_AP}"
echo "    Logs   : journalctl -u ${SERVICE_AP}"
echo "    Run    : sudo ./wifi_setup"
echo ""
echo " 2. ${SERVICE_CLIENT}"
echo "    Connects wlan0 to external WiFi for internet"
echo "    Status : systemctl status ${SERVICE_CLIENT}"
echo "    Logs   : journalctl -u ${SERVICE_CLIENT}"
echo "    Run    : sudo ./wifi_connect"
echo "======================================"