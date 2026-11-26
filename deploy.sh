#!/usr/bin/env bash
#
# Build the debug APK and install it on the connected phone.
#
# Usage:
#   ./deploy.sh              build, install, and launch
#   ./deploy.sh --build      build only (no device needed)
#   ./deploy.sh --install    install the existing APK without rebuilding
#   ./deploy.sh --logs       install, launch, then stream logcat
#   ./deploy.sh --wireless   reconnect wireless adb, then build and install
#   ./deploy.sh --unlock     clear a stale build lock left by a killed build
#
set -euo pipefail

# --- Environment -------------------------------------------------------------
# Pick up the Android toolchain even when the shell has not sourced .bashrc.
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk/jdk-21.0.13.1+2}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

ADB="$ANDROID_HOME/platform-tools/adb"
APP_ID="com.adhd.routineplanner.debug"
APK="app/build/outputs/apk/debug/app-debug.apk"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

# --- Build lock --------------------------------------------------------------
# Android Studio and this script share app/build/. If both build at once Gradle
# fails with a lock error. Take an exclusive lock so we either wait politely or
# tell you who is holding it.
LOCK_FILE="$PROJECT_DIR/.gradle-build.lock"
LOCK_WAIT="${DEPLOY_LOCK_WAIT:-120}"

acquire_build_lock() {
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$LOCK_FILE"
        if ! flock -n 9; then
            warn "Another Gradle build is running (Android Studio?)."
            info "Waiting up to ${LOCK_WAIT}s for it to finish..."
            if ! flock -w "$LOCK_WAIT" 9; then
                fail "Still locked after ${LOCK_WAIT}s. Close the build in Android Studio, or run: ./deploy.sh --unlock"
            fi
            ok "Lock acquired"
        fi
    else
        # No flock (unusual on Fedora). Fall back to a pid file.
        if [ -f "$LOCK_FILE" ] && kill -0 "$(cat "$LOCK_FILE" 2>/dev/null)" 2>/dev/null; then
            fail "Another build is running (pid $(cat "$LOCK_FILE")). Remove $LOCK_FILE if that is wrong."
        fi
        echo $$ > "$LOCK_FILE"
        trap 'rm -f "$LOCK_FILE"' EXIT
    fi
}

release_build_lock() {
    if command -v flock >/dev/null 2>&1; then
        flock -u 9 2>/dev/null || true
        exec 9>&- 2>/dev/null || true
    else
        rm -f "$LOCK_FILE"
    fi
}

# --- Colours -----------------------------------------------------------------
if [ -t 1 ]; then
    RED=$'\e[31m'; GREEN=$'\e[32m'; YELLOW=$'\e[33m'; BLUE=$'\e[34m'; RESET=$'\e[0m'
else
    RED=""; GREEN=""; YELLOW=""; BLUE=""; RESET=""
fi

info()  { echo "${BLUE}==>${RESET} $*"; }
ok()    { echo "${GREEN}==>${RESET} $*"; }
warn()  { echo "${YELLOW}==>${RESET} $*"; }
fail()  { echo "${RED}==>${RESET} $*" >&2; exit 1; }

# --- Argument parsing --------------------------------------------------------
MODE="deploy"
for arg in "$@"; do
    case "$arg" in
        --build)    MODE="build" ;;
        --install)  MODE="install" ;;
        --logs)     MODE="logs" ;;
        --wireless) MODE="wireless" ;;
        --unlock)   MODE="unlock" ;;
        -h|--help)
            sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) fail "Unknown option: $arg (try --help)" ;;
    esac
done

# --- Wireless reconnect ------------------------------------------------------
# adb's mDNS discovery usually reconnects a paired phone on its own. If a saved
# address exists, try it as a fallback. Pair once with ./pair-wireless.sh.
reconnect_wireless() {
    info "Waiting for wireless adb (mDNS discovery)"
    for _ in 1 2 3 4 5; do
        if "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then
            ok "Device found automatically"
            return 0
        fi
        sleep 1
    done

    local addr_file="$PROJECT_DIR/.wireless-address"
    if [ -f "$addr_file" ]; then
        local addr
        addr="$(cat "$addr_file")"
        info "Trying saved address $addr"
        "$ADB" connect "$addr" || true
        sleep 1
        return 0
    fi

    warn "No device found. Run ./pair-wireless.sh once to pair."
    return 1
}

# --- Device check ------------------------------------------------------------
require_device() {
    local devices
    devices="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
    if [ -z "$devices" ]; then
        echo
        fail "No device connected.

  Wireless:  ./pair-wireless.sh   (first time)
             ./deploy.sh --wireless (after a reboot)
  USB:       plug in the phone with USB debugging enabled"
    fi
    ok "Device: $(echo "$devices" | head -1)"
}

# --- Build -------------------------------------------------------------------
build_apk() {
    acquire_build_lock
    info "Building debug APK"
    ./gradlew :app:assembleDebug --console=plain
    [ -f "$APK" ] || fail "Build finished but $APK is missing."
    ok "APK: $APK ($(du -h "$APK" | cut -f1))"
    release_build_lock
}

# --- Install -----------------------------------------------------------------
install_apk() {
    [ -f "$APK" ] || fail "No APK at $APK. Run ./deploy.sh --build first."
    info "Installing on device"
    "$ADB" install -r "$APK"
    ok "Installed $APP_ID"
}

# --- Launch ------------------------------------------------------------------
launch_app() {
    info "Launching app"
    "$ADB" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 \
        || warn "Could not launch automatically; open the app from the launcher."
    ok "Launched"
}

# --- Main --------------------------------------------------------------------
case "$MODE" in
    unlock)
        if [ -f "$LOCK_FILE" ]; then
            rm -f "$LOCK_FILE"
            ok "Removed $LOCK_FILE"
        else
            ok "No lock file present"
        fi
        exit 0
        ;;
    build)
        build_apk
        ;;
    install)
        require_device
        install_apk
        launch_app
        ;;
    wireless)
        reconnect_wireless
        require_device
        build_apk
        install_apk
        launch_app
        ;;
    logs)
        require_device
        build_apk
        install_apk
        launch_app
        info "Streaming logcat (Ctrl+C to stop)"
        "$ADB" logcat -v color --pid="$("$ADB" shell pidof -s "$APP_ID" 2>/dev/null || echo '')" 2>/dev/null \
            || "$ADB" logcat -v color
        ;;
    deploy)
        require_device
        build_apk
        install_apk
        launch_app
        ;;
esac

ok "Done."
