#!/usr/bin/env bash
# Build, install and run XR Desk on the phone connected over adb.
#
#   ./xrdesk.sh build                 compile build/xrdesk.apk
#   ./xrdesk.sh test                  run the JVM unit tests (no device needed)
#   ./xrdesk.sh install               build and install
#   ./xrdesk.sh start [key=value...]  start with shell access, e.g. drift=0.3 dpi=200
#   ./xrdesk.sh options [key=value...]  save options for app-icon launches (via Shizuku) and launch
#   ./xrdesk.sh stop
#   ./xrdesk.sh log                   follow the app's log
#
# start options (defaults in parentheses):
#   fill      desktop size as a fraction of the screen, drawn 1:1 (0.9)
#   width height   exact desktop size instead of fill;  dpi (240)
#   focal     pixels the desktop slides per radian of rotation (2262)
#   drift     seconds to drift back to center (0.5);  max  largest slide in px (300)
#   predict   look-ahead in ms (8);  deadband  ignored rotation speed in rad/s (0.004)
#   monitors  number of desktops, 1-4 (2);  swipe  updown | leftright (updown)
#   roll      1 = roll smoothing on (0);  rollmax  largest roll correction in degrees (15)
#             monitors/swipe/roll are also set in the menu (saved); a start option overrides the
#             saved value for that session.
#   flick     rad/s of a flick that switches monitors, 0 = off (2.5);  flickangle  degrees (12)
#   flickms   time the flick must happen within (300);  cooldown  ms after a flick or shake (800)
#   shake     rad/s of each head-shake stroke that opens the menu, 0 = off (1.5)
#   shakeangle  degrees each stroke must cover (6);  shakecount  strokes (3);  shakems  within (1000)
#   imu       head-rotation source: auto (glasses if attached, else phone) | xreal | phone (auto)
#   imuswap   1 = swap which head rotation moves the desktop horizontally vs vertically
#   imuinvx   1 = reverse horizontal sliding;  imuinvy  1 = reverse vertical sliding and flick direction
#   imuinvroll  1 = reverse the roll correction
#   display   display id to open XR Desk on (e.g. the glasses); default = current display
#   inputlog  1 = log every forwarded input event (adb logcat -s XRDesk)
# Head gestures: flick up/down (or right/left) = next/previous monitor, wrapping around; shake
# your head ("no") = open/close the menu. The menu also opens with F12 or a controller's
# Home/Guide button (adb: input keyevent KEYCODE_F12). The phone's Vol+ toggles stabilization,
# its Vol- switches to the next monitor. Touch, mouse, keyboard and game controller input is
# forwarded to the visible desktop, except while the menu is open.
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.droid.xrdesk
SDK=sdk
APK=build/xrdesk.apk

build() {
    rm -rf build
    mkdir -p build/classes build/dex
    # libs/ holds the Shizuku client library (classes.jar from its Maven Central AARs).
    local libs
    libs=$(ls libs/*.jar | paste -sd:)
    javac --release 11 -classpath "$SDK/android.jar:$libs" \
        -d build/classes $(find src -name '*.java')
    (cd build/classes && jar cf ../classes.jar .)
    java -cp "$SDK/r8.jar" com.android.tools.r8.D8 --release --min-api 34 \
        --lib "$SDK/android.jar" --output build/dex build/classes.jar libs/*.jar
    # aapt is too old to read the API 37 resource table; attribute IDs are stable.
    aapt package -f -M AndroidManifest.xml -I "$SDK/android-34-res.jar" -F build/unsigned.apk
    (cd build/dex && aapt add ../unsigned.apk classes.dex)
    zipalign -f -p 4 build/unsigned.apk build/aligned.apk
    if [ ! -f debug.keystore ]; then
        keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
            -alias xrdesk -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=XR Desk" >/dev/null
    fi
    apksigner sign --ks debug.keystore --ks-pass pass:android --out "$APK" build/aligned.apk
    echo "built $APK"
}

unit_tests() {
    # Plain-JVM tests for the device-independent code: XREAL protocol, bias filter, sample ring;
    # head gestures, roll filter and touch mapping.
    rm -rf build/test && mkdir -p build/test
    javac --release 11 -d build/test src/com/droid/xrdesk/xreal/*.java src/com/droid/xrdesk/motion/*.java \
        test/com/droid/xrdesk/xreal/*.java test/com/droid/xrdesk/motion/*.java
    java -cp build/test com.droid.xrdesk.xreal.XrealProtocolTest
    java -cp build/test com.droid.xrdesk.motion.MotionTest
}

start() {
    local extras=""
    for kv in "$@"; do
        extras+=" -e ${kv%%=*} ${kv#*=}"
    done
    adb shell am force-stop "$PKG"
    adb logcat -c
    # nohup so the desktop keeps running if this terminal or adb disconnects.
    adb shell "nohup am instrument -w --no-hidden-api-checks$extras $PKG/.ShellInstrumentation >/dev/null 2>&1 &"
    sleep 3
    adb logcat -d -s XRDesk
}

case "${1:-}" in
    build) build ;;
    test) unit_tests ;;
    # Stop first: updating a visible app makes the system relaunch it mid-start.
    install) build && adb shell am force-stop "$PKG" && adb install -r "$APK" ;;
    start) shift; start "$@" ;;
    options) shift; adb shell am force-stop "$PKG" &&
        adb shell "am start -n $PKG/.LaunchActivity --es options '$*'" ;;
    stop) adb shell am force-stop "$PKG" ;;
    log) adb logcat -s XRDesk ;;
    *) sed -n '2,36p' "$0"; exit 1 ;;
esac
