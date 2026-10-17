# Building and hacking on XR Desk

Everything here is for developers. To just use XR Desk, see the [README](README.md).

## Toolchain

XR Desk builds without Gradle or Android Studio, using plain command-line tools. On Debian or
Ubuntu:

```sh
sudo apt-get install -y --no-install-recommends adb openjdk-21-jdk-headless aapt apksigner zipalign curl unzip
./xrdesk.sh setup                 # once: downloads sdk/ and libs/ (about 150 MB) and checks them
```

Builds need about 2 GB of free RAM. `sdk/` and `libs/` aren't in the repo (Google's SDK license
doesn't allow redistributing `android.jar`); `setup` fetches them from where they're published
and checks their SHA-256 sums:

| File | Source |
|---|---|
| `sdk/android.jar` | `android.jar` from https://dl.google.com/android/repository/platform-37.2_r01.zip |
| `sdk/android-34-res.jar` | `android.jar` from `platform-34-ext12_r01.zip`, same URL path (Debian's aapt can't read API 37) |
| `sdk/r8.jar` | https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar |
| `libs/shizuku-*-13.1.5.jar` | `classes.jar` from the `dev.rikka.shizuku` api/provider/aidl/shared 13.1.5 AARs on Maven Central |

## Build, install and run

Connect the phone with adb (USB, or **Developer options → Wireless debugging**: pair once with
`adb pair`, then `adb connect <ip>:<port>`), and start Shizuku on the phone. Then:

```sh
./xrdesk.sh test                  # JVM unit tests, no phone needed
./xrdesk.sh install               # build a debuggable APK and install it
./xrdesk.sh start                 # start stabilizing (same as Start in the app)
./xrdesk.sh stop | pause | recenter
./xrdesk.sh mouse                 # open the mouse screen
./xrdesk.sh options drift=3 maxyaw=800   # save settings, applied right away; none = defaults
./xrdesk.sh log                   # follow the log (tag XRDesk)
```

Run `./xrdesk.sh` without arguments for the full list of commands and settings. `stop`
force-stops the app; the shell helper notices and puts the glasses' picture back.

Development builds are signed with the checked-in `debug.keystore` and are debuggable (so
`adb shell run-as com.droid.xrdesk` works); release builds use a different key. Android won't
update an app signed with one key to the other, so uninstall first when switching between a
release install and a development build.

## Releases

```sh
./xrdesk.sh release               # build/xrdesk-<versionName>.apk, not debuggable
```

The release key is not in the repo: `~/.android/xrdesk-release.jks`, with its password in
`~/.android/xrdesk-release.pass` (override with `XRDESK_KEYSTORE` and `XRDESK_KEYSTORE_PASS_FILE`).
Keep both backed up: updates to installed releases must be signed with the same key. To publish,
bump `versionCode` and `versionName` in `AndroidManifest.xml`, build, then:

```sh
gh release create v<versionName> build/xrdesk-<versionName>.apk --title "XR Desk <versionName>"
```

## How it works

```
glasses' IMU (USB HID) ──> StabilizerService ──offset per frame──> ProjectionHelper (shell, via Shizuku)
                            motion/Stabilizer                        moves the glasses' display projection
phone's rotation sensor ──> MouseActivity ──moves, clicks, scroll──> MousePointer (in ProjectionHelper)
                                                                      injects mouse events, draws the cursor
```

### Stabilization

- `StabilizerService` (a foreground service) reads the glasses' gyro over USB HID with
  `XrealImuSource` (about 800 samples/s; protocol and sources in `xreal/` and
  [NOTES-xreal.md](NOTES-xreal.md)), and turns head rotation into a picture offset with
  `motion/Stabilizer`, at most once per frame.
- `Stabilizer` integrates the rotation and pulls the offset back to center along the chosen
  curve: accelerate (the default) or linear (both straight toward center), smooth (a critically
  damped spring) or exponential (a leaky integrator). Each direction has its own largest offset;
  all reach halfway back from the larger one in `drift × ln 2`. Plain Java, tested in `test/`.
- `ProjectionHelper` runs as the shell user through Shizuku. It finds the glasses' physical
  display token (`DisplayControl` from `services.jar`, as scrcpy does) and shifts the display
  rect of the display's SurfaceFlinger projection by the offset
  (`SurfaceControl.Transaction.setDisplayProjection`). Every window, the taskbar and the cursor
  move together, and nothing is redrawn. It reads the display manager's own projection from
  `dumpsys display` and puts it back when the app stops, or dies (`linkToDeath`).

### Mouse and keyboard

- `MouseActivity` reads the phone's game rotation vector (Android's gyro + accelerometer fusion)
  and turns where the phone's top edge points (heading and elevation, relative to where it
  pointed when centered) into a cursor position. A click lock freezes the cursor while a button
  is held, so the jolt of pressing doesn't turn a click into a drag.
- `MousePointer` (in the shell helper) injects standard mouse events into the glasses' display
  and draws Android's own arrow pointer as a layer on the display's layer stack, so it's
  stabilized with the picture. The layer is a *trusted overlay*: Android drops touches on a
  window that another app's surface covers, and the arrow covers the very pixel being clicked.
- Typing: while the mouse screen is open, the glasses' keyboard policy is "show it on the phone"
  (`IWindowManager.setDisplayImePolicy`). Android has one input focus, and a click on the glasses
  moves it there; after a click, focus stays there for up to 0.9 s while the helper checks
  whether a keyboard opened (IME touch region and visible IME insets). If one did, typing mode
  starts; otherwise focus goes back to the phone, so its volume keys reach the mouse screen.
  Android moves focus a little after the press, and a text box asks for the keyboard on release
  only if its window has focus by then, so `MousePointer` holds a release until focus has
  reached the glasses (polling `IActivityTaskManager.getFocusedRootTaskInfo`) plus a margin,
  at most 0.3 s after the press. Without that, quick clicks rarely opened the keyboard.
  The keyboard covers the mouse screen without resizing it (that screen isn't its target), so
  the helper also reports the IME insets frame, and the controls are padded to end above it.

### Non-public Android APIs used

All from the shell helper, which isn't subject to hidden API restrictions: `DisplayControl`,
`SurfaceControl.Transaction.setDisplayProjection`, `setLayerStack` and `setTrustedOverlay`,
`InputManagerGlobal.injectInputEvent` with `MotionEvent.setDisplayId` and `setActionButton`,
`IWindowManager` (`setDisplayImePolicy`, `getCurrentImeTouchRegion`, `getWindowInsets`) and
`IActivityTaskManager` (`focusTopTask`, `getFocusedRootTaskInfo`). They can change in any Android release.

## Layout

| Path | What |
|---|---|
| `src/com/droid/xrdesk/MainActivity.java` | Main screen: start/stop, settings |
| `src/com/droid/xrdesk/StabilizerService.java` | Foreground service: IMU in, offsets out; runs for stabilizing or the mouse screen |
| `src/com/droid/xrdesk/ProjectionHelper.java` | Shizuku user service (shell): display projection, keyboard policy, focus |
| `src/com/droid/xrdesk/Helper.java` | Client side of the helper's Binder protocol |
| `src/com/droid/xrdesk/MouseActivity.java` | Mouse screen: pointing, buttons, scrolling, typing mode |
| `src/com/droid/xrdesk/MousePointer.java` | Mouse event injection and the cursor layer (in the helper) |
| `src/com/droid/xrdesk/XrealImuSource.java` | XREAL IMU over USB HID |
| `src/com/droid/xrdesk/motion/Stabilizer.java` | Head rotation to picture offset, drift curves |
| `src/com/droid/xrdesk/xreal/` | XREAL protocol, gyro bias filter, sample ring |
| `src/com/droid/xrdesk/Options.java` | Settings, one `key=value` string |
| `test/` | JVM tests for `motion/` and `xreal/` |
| `xrdesk.sh` | Build, install, run, release |
