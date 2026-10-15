# XR Desk

Proof-of-concept stabilized desktop for XR glasses on Android. XR Desk hosts 1–4 real Android
desktops (virtual displays) in its own window and slides them against head motion, always
drifting back to center. Head motion comes from the XREAL xbx a01+ IMU, or the phone's gyro.
Tested on a Pixel 9a running Android 17; not yet tested with the glasses.

## One-time setup (this Linux VM)

```sh
sudo apt-get install -y --no-install-recommends adb openjdk-21-jdk-headless aapt apksigner zipalign unzip curl
```

Builds need about 2 GB of free RAM. `sdk/` and `libs/` are already in place. To recreate them:

| File | Source |
|---|---|
| `sdk/android.jar` | `android.jar` from https://dl.google.com/android/repository/platform-37.2_r01.zip |
| `sdk/android-34-res.jar` | `android.jar` from `platform-34-ext12_r01.zip`, same URL path (Debian's aapt can't read API 37) |
| `sdk/r8.jar` | https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar |
| `libs/shizuku-*-13.1.5.jar` | `classes.jar` from the `dev.rikka.shizuku` api/provider/aidl/shared 13.1.5 AARs on Maven Central |

## Connect to the phone

Turn on **Developer options → Wireless debugging**. Pair once with **Pair device with pairing
code**; use split screen, because the code popup closes if you switch apps. The VM's gateway
address is the phone.

```sh
PHONE=$(ip route | awk '/default/{print $3}')
adb pair $PHONE:<pairing-port> <code>   # once
adb connect $PHONE:<port>               # port shown on the Wireless debugging screen
```

## Build and deploy

```sh
./xrdesk.sh test                  # JVM unit tests, no phone needed
./xrdesk.sh install               # build, stop the app, install
./xrdesk.sh start [key=value...]  # start with shell access over adb
./xrdesk.sh stop | log
```

Or tap the XR Desk icon. This needs Shizuku running, and the first launch asks for Shizuku
permission. Save options for icon launches with `./xrdesk.sh options key=value...`. All options
are listed at the top of `xrdesk.sh`.

Installing restarts XR Desk, and apps on its desktops move to the phone screen. If this Terminal
is running on an XR desktop, move it to the phone screen first so the VM isn't interrupted:
`adb shell am display move-stack <terminal-task-id> 0`.

## Controls

- **Head flick** up/down (or left/right, set in the menu): next/previous monitor.
- **Head shake** ("no"), **F12**, or a controller's **Home** button: settings menu (monitors, swipe direction, roll smoothing).
- **Phone Vol+**: stabilization on/off. **Vol−**: next monitor.
- Touch, mouse, keyboard and controller input go to the visible desktop.

## Code

- `src/com/droid/xrdesk/`
  - `DesktopActivity`: desktops, stabilization, input forwarding
  - `SettingsMenu`
  - `XrealImuSource`
  - `ShellInstrumentation`: borrows shell permissions
  - `LaunchActivity` and `ShellLaunchService`: Shizuku launch
- `motion/` and `xreal/`: plain-Java logic, tested in `test/`
- `NOTES-xreal.md` and `NOTES-menu.md`: protocol sources, design decisions, test logs
