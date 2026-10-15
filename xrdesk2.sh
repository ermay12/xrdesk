#!/usr/bin/env bash
# Build, install and run XR Desk 2 on the phone connected over adb.
#
#   ./xrdesk2.sh build                compile build/xrdesk2.apk
#   ./xrdesk2.sh test                 run the JVM unit tests (no device needed)
#   ./xrdesk2.sh install              build and install (stops XR Desk 2 first)
#   ./xrdesk2.sh start                start stabilizing (needs Shizuku running)
#   ./xrdesk2.sh stop                 stop; the glasses' picture goes back to normal
#   ./xrdesk2.sh pause | recenter     same as the notification buttons
#   ./xrdesk2.sh options [key=value...]  save settings (used from the next start); none = defaults
#   ./xrdesk2.sh log                  follow the app's log
#
# options (defaults in parentheses):
#   focal     pixels the picture slides per radian of head rotation (2262)
#   drift     seconds to drift back to center (1.0);  max  largest slide in px (600)
#   predict   look-ahead in ms (8);  deadband  ignored rotation speed in rad/s (0.004)
#   fill      picture size as a fraction of the display, 0.5-1 (1.0); below 1 leaves room to slide
#   display   display id to stabilize (first external display)
#   imuswap   1 = swap horizontal and vertical;  imuinvx / imuinvy  1 = reverse that direction
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.droid.xrdesk2
SDK=sdk
APK=build/xrdesk2.apk

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
    apksigner sign --ks debug.keystore --ks-pass pass:android --out "$APK" build/aligned.apk
    echo "built $APK"
}

unit_tests() {
    rm -rf build/test && mkdir -p build/test
    javac --release 11 -d build/test src/com/droid/xrdesk2/xreal/*.java src/com/droid/xrdesk2/motion/*.java \
        test/com/droid/xrdesk2/xreal/*.java test/com/droid/xrdesk2/motion/*.java
    java -cp build/test com.droid.xrdesk2.xreal.XrealProtocolTest
    java -cp build/test com.droid.xrdesk2.motion.StabilizerTest
}

service_action() {
    adb shell am startservice -n "$PKG/.StabilizerService" -a "com.droid.xrdesk2.action.$1" >/dev/null
}

case "${1:-}" in
    build) build ;;
    test) unit_tests ;;
    # Stopping XR Desk 2 makes the helper put the display back before the update.
    install) build && adb shell am force-stop "$PKG" && adb install -r "$APK" ;;
    start) adb shell am start -n "$PKG/.MainActivity" --display 0 --ez start true >/dev/null
        sleep 3; adb logcat -d -s XRDesk2 | tail -20 ;;
    stop) adb shell am force-stop "$PKG" ;;
    pause) service_action PAUSE ;;
    recenter) service_action RECENTER ;;
    options) shift; adb shell "am start -n $PKG/.MainActivity --display 0 --es options '$*'" >/dev/null ;;
    log) adb logcat -s XRDesk2 ;;
    *) sed -n '2,19p' "$0"; exit 1 ;;
esac
