#!/bin/bash
# Installs MiniWin and turns on Android's hidden freeform window mode. Run once with the phone plugged in.
set -uo pipefail
cd "$(dirname "$0")"
APK=build/MiniWin.apk
[ -f "$APK" ] || ./build.sh

if ! adb get-state >/dev/null 2>&1; then
    cat <<'MSG'
No phone found. On the phone:
  1. Settings > About phone > tap "OS version" 7 times
  2. Settings > Additional settings > Developer options, then turn on:
       - USB debugging
       - Install via USB
       - USB debugging (Security settings)
  3. Plug in the cable and tap Allow on the phone, then run this again.
MSG
    exit 1
fi

echo "Phone: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"

if ! adb install -r "$APK"; then
    echo "Install failed. Check that 'Install via USB' is on and accept any prompt on the phone."
    exit 1
fi

failed=0
for kv in enable_freeform_support=1 force_resizable_activities=1 enable_non_resizable_multi_window=1; do
    key="${kv%%=*}"; value="${kv#*=}"
    adb shell settings put global "$key" "$value" 2>&1 | grep -q . && failed=1
    actual="$(adb shell settings get global "$key" | tr -d '\r')"
    echo "  $key = $actual"
    [ "$actual" = "$value" ] || failed=1
done
if [ "$failed" = 1 ]; then
    echo "The phone refused those settings. Turn on 'USB debugging (Security settings)' in Developer options, then run this again."
    exit 1
fi

# Permissions: draw over apps, plus Xiaomi's own "open windows from the background" switch.
adb shell appops set com.miniwin SYSTEM_ALERT_WINDOW allow
adb shell appops set com.miniwin 10021 allow >/dev/null 2>&1 || true
adb shell pm grant com.miniwin android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

read -r -p "A restart is needed to apply this. Restart the phone now? [Y/n] " answer
case "$answer" in
    n*|N*) echo "Restart the phone yourself before using app windows." ;;
    *) adb reboot; echo "Restarting. After that, open MiniWin on the phone." ;;
esac
