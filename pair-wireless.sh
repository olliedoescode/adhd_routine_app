#!/usr/bin/env bash
#
# Pair this machine with your phone over Wi-Fi for wireless debugging.
#
# On the phone (Android 11+):
#   Settings > Developer options > Wireless debugging > On
#   Tap "Pair device with pairing code"
#   Note the IP:port and the 6-digit code shown
#
# Then run this script and enter those values when prompted.
#
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -x "$ADB" ]; then
    echo "adb not found at $ADB. Is the Android SDK installed?" >&2
    exit 1
fi

# Accept "ip:port" only. Catches the common typo of a dot before the port.
validate_addr() {
    local addr="$1" label="$2"
    if [[ ! "$addr" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}:[0-9]{1,5}$ ]]; then
        echo "Invalid $label '$addr'. Expected the form 192.168.1.42:37123 (colon, not dot)." >&2
        return 1
    fi
}

echo "On your phone: Settings > Developer options > Wireless debugging"
echo "Tap 'Pair device with pairing code' and enter the details below."
echo

read -rp "Pairing IP:port (e.g. 192.168.1.42:37123): " PAIR_ADDR
validate_addr "$PAIR_ADDR" "pairing address" || exit 1
read -rp "6-digit pairing code: " PAIR_CODE

echo
echo "==> Pairing with $PAIR_ADDR"
"$ADB" pair "$PAIR_ADDR" "$PAIR_CODE"

echo
echo "==> Checking whether the device connected automatically (mDNS)..."
sleep 2
if "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
    echo "==> Connected automatically. No connect address needed."
    "$ADB" devices
    echo
    echo "    Deploy with: ./deploy.sh"
    exit 0
fi

echo "==> Not connected automatically. Enter the CONNECT address."
echo "    It is on the main Wireless debugging screen (not the pairing dialog)"
echo "    and usually uses a different port."
read -rp "Connect IP:port (e.g. 192.168.1.42:41234): " CONNECT_ADDR
validate_addr "$CONNECT_ADDR" "connect address" || exit 1

"$ADB" connect "$CONNECT_ADDR"
echo "$CONNECT_ADDR" > "$PROJECT_DIR/.wireless-address"

echo
echo "==> Paired. Saved $CONNECT_ADDR to .wireless-address"
echo "    Deploy with: ./deploy.sh"
echo "    After a reboot: ./deploy.sh --wireless"
"$ADB" devices
