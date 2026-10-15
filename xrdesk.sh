#!/usr/bin/env bash
# Build, install and run XR Desk on the phone connected over adb.
#
#   ./xrdesk.sh build                compile build/xrdesk.apk (debuggable, debug key)
#   ./xrdesk.sh release              compile build/xrdesk-<version>.apk (release key, see BUILDING.md)
#   ./xrdesk.sh test                 run the JVM unit tests (no device needed)
#   ./xrdesk.sh install              build and install (stops XR Desk first)
#   ./xrdesk.sh start                start stabilizing (needs Shizuku running)
#   ./xrdesk.sh stop                 stop; the glasses' picture goes back to normal
#   ./xrdesk.sh pause | recenter     same as the notification buttons
#   ./xrdesk.sh mouse                 open the mouse screen
#   ./xrdesk.sh options [key=value...]  save settings, applied right away; none = defaults
#   ./xrdesk.sh log                  follow the app's log
#
# options (defaults in parentheses):
#   focal     pixels the picture slides per radian of head rotation (2262)
#   drift     seconds to drift back to center (1.0);  max  largest slide in px (600)
#             (both are also set in the app, which shows max as degrees of head rotation)
#   curve     how the picture drifts back (smooth): smooth = starts slowly, speeds up, eases into
#             center; exp = fastest at first, then creeps; linear = constant speed; accel = speeds
#             up, then stops suddenly. All get halfway back from the max offset in drift * 0.69 s.
#             Also a choice in the app.
#   predict   look-ahead in ms (8);  deadband  ignored rotation speed in rad/s (0.004)
#   pointer   mouse screen: cursor pixels per radian the phone turns (focal: the cursor goes
#             where the phone points). Also the Mouse speed slider on the mouse screen (pointer / focal).
#   fill      picture size as a fraction of the display, 0.5-1 (1.0); below 1 leaves room to slide
#   display   display id to stabilize (first external display)
#   imuswap   1 = swap horizontal and vertical;  imuinvx / imuinvy  1 = reverse that direction
set -euo pipefail
cd "$(dirname "$0")"

PKG=com.droid.xrdesk
SDK=sdk
APK=build/xrdesk.apk

# build [release]: a debuggable APK signed with debug.keystore, or a release APK signed with the
# release key (XRDESK_KEYSTORE, default ~/.android/xrdesk-release.jks; its password in
# XRDESK_KEYSTORE_PASS_FILE, default ~/.android/xrdesk-release.pass).
build() {
    local mode=${1:-debug}
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
    local debug=--debug-mode
    [ "$mode" = release ] && debug=
    aapt package -f $debug -M AndroidManifest.xml -I "$SDK/android-34-res.jar" -F build/unsigned.apk
    (cd build/dex && aapt add ../unsigned.apk classes.dex)
    zipalign -f -p 4 build/unsigned.apk build/aligned.apk
    if [ "$mode" = release ]; then
        local version out
        version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
        out=build/xrdesk-$version.apk
        apksigner sign --ks "${XRDESK_KEYSTORE:-$HOME/.android/xrdesk-release.jks}" \
            --ks-pass "file:${XRDESK_KEYSTORE_PASS_FILE:-$HOME/.android/xrdesk-release.pass}" \
            --out "$out" build/aligned.apk
        echo "built $out"
    else
        apksigner sign --ks debug.keystore --ks-pass pass:android --out "$APK" build/aligned.apk
        echo "built $APK"
    fi
}

unit_tests() {
    rm -rf build/test && mkdir -p build/test
    javac --release 11 -d build/test src/com/droid/xrdesk/xreal/*.java src/com/droid/xrdesk/motion/*.java \
        test/com/droid/xrdesk/xreal/*.java test/com/droid/xrdesk/motion/*.java
    java -cp build/test com.droid.xrdesk.xreal.XrealProtocolTest
    java -cp build/test com.droid.xrdesk.motion.StabilizerTest
}

service_action() {
    adb shell am startservice -n "$PKG/.StabilizerService" -a "com.droid.xrdesk.action.$1" >/dev/null
}

case "${1:-}" in
    build) build ;;
    release) build release ;;
    test) unit_tests ;;
    # Stopping XR Desk makes the helper put the display back before the update.
    install) build && adb shell am force-stop "$PKG" && adb install -r "$APK" ;;
    start) adb shell am start -n "$PKG/.MainActivity" --display 0 --ez start true >/dev/null
        sleep 3; adb logcat -d -s XRDesk | tail -20 ;;
    stop) adb shell am force-stop "$PKG" ;;
    pause) service_action PAUSE ;;
    mouse) adb shell am start -n "$PKG/.MainActivity" --display 0 --ez mouse true >/dev/null ;;
    recenter) service_action RECENTER ;;
    options) shift; adb shell "am start -n $PKG/.MainActivity --display 0 --es options '$*'" >/dev/null ;;
    log) adb logcat -s XRDesk ;;
    *) sed -n '2,/^set -euo/p' "$0" | grep '^#'; exit 1 ;;
esac
