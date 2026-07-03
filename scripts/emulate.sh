#!/usr/bin/env bash
# Create/boot a Pixel 9a emulator and install + launch ugly launcher on it.
#
# Uses the Android SDK's own avdmanager/sdkmanager/emulator. If avdmanager is
# missing, install the command-line tools first (Arch: `yay -S
# android-sdk-cmdline-tools-latest`, then this script drops a copy into the SDK).
set -euo pipefail

AVD_NAME="ugly_pixel_9a"
DEVICE="pixel_9a"
SYSIMG="system-images;android-36;google_apis_playstore;x86_64"
PKG="com.uglyos.launcher"
ACTIVITY="$PKG/.MainActivity"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android/sdk}}"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v adb >/dev/null 2>&1 || { echo "adb not found on PATH (pacman -S android-tools)" >&2; exit 1; }
[ -x "$EMULATOR" ] || { echo "emulator not found at $EMULATOR (set ANDROID_HOME)" >&2; exit 1; }

# avdmanager lives inside cmdline-tools. The Arch package installs it under
# /opt; avdmanager resolves its SDK from its own location, so we need a copy
# inside this SDK. Bootstrap one from /opt if the SDK doesn't have it yet.
if [ ! -x "$AVDMANAGER" ]; then
    if [ -x /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
        echo "Installing cmdline-tools into $SDK ..."
        yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
            --sdk_root="$SDK" --install "cmdline-tools;latest" >/dev/null
    else
        echo "avdmanager not found. Install the command-line tools first:" >&2
        echo "  yay -S android-sdk-cmdline-tools-latest" >&2
        exit 1
    fi
fi

# Ensure the system image is present (idempotent; no-op if already installed).
if ! "$SDKMANAGER" --sdk_root="$SDK" --list_installed 2>/dev/null | grep -q "$SYSIMG"; then
    echo "Installing system image $SYSIMG ..."
    yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    "$SDKMANAGER" --sdk_root="$SDK" --install "$SYSIMG"
fi

# 1. Create the AVD if it doesn't exist.
if ! "$EMULATOR" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
    echo "Creating AVD '$AVD_NAME' ($DEVICE)..."
    echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSIMG" -d "$DEVICE"
else
    echo "AVD '$AVD_NAME' already exists."
fi

emu_serial() {
    # Serial of a running emulator whose AVD name matches, if any.
    local s
    for s in $(adb devices | awk '/emulator-.*device$/{print $1}'); do
        if [ "$(adb -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD_NAME" ]; then
            echo "$s"; return 0
        fi
    done
    return 1
}

# 2. Boot it if it isn't already running.
if SERIAL="$(emu_serial)"; then
    echo "Emulator already running ($SERIAL)."
else
    echo "Starting emulator '$AVD_NAME'..."
    "$EMULATOR" -avd "$AVD_NAME" -netdelay none -netspeed full \
        >"$REPO_ROOT/scripts/.emulator.log" 2>&1 &
    echo "  (log: scripts/.emulator.log)"
    echo "Waiting for device to come online..."
    for _ in $(seq 90); do SERIAL="$(emu_serial)" && break || sleep 2; done
    [ -n "$SERIAL" ] || { echo "Emulator failed to come online (see scripts/.emulator.log):" >&2
        tail -n 15 "$REPO_ROOT/scripts/.emulator.log" >&2; exit 1; }
fi
export ANDROID_SERIAL="$SERIAL"

adb wait-for-device
echo "Waiting for boot to complete..."
for _ in $(seq 150); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break || sleep 2
done
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
    echo "Emulator booted but did not finish booting in time." >&2; exit 1; }
adb shell input keyevent 82 >/dev/null 2>&1 || true  # dismiss the lock screen

# 3. Build + install the launcher.
echo "Installing $PKG ..."
(cd "$REPO_ROOT/launcher" && ./gradlew installDebug)

# 4. Make it the default home and launch it.
adb shell cmd package set-home-activity "$ACTIVITY" >/dev/null 2>&1 || true
adb shell am start -n "$ACTIVITY" >/dev/null

echo "ugly launcher is running on $AVD_NAME ($SERIAL)."
