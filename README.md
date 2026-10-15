# XR Desk

Stabilizes the whole picture of XREAL xbx a01/a01+ glasses on Android. Apps run normally on
the glasses' desktop; XR Desk shifts the display's output against small head movements, so
the picture seems to stay put, and lets it drift back to center. Windows, taskbar and mouse
cursor all move together, and nothing is redrawn. It also turns the phone into a mouse and
keyboard for the glasses. Tested on a Pixel 9a running Android 17.

<p>
  <img src="docs/main-screen.png" width="270" alt="Main screen: Stop, Pause, Recenter and Mouse, the drift time, max rotation and drift curve">
  <img src="docs/mouse-screen.png" width="270" alt="Mouse screen: Back, Center cursor, Center screen, mouse speed, Right and Left click, the scroll strip and Type">
</p>

## How it works

- `StabilizerService` (foreground service) reads the glasses' IMU over USB (`XrealImuSource`)
  and turns head rotation into an offset (`motion/Stabilizer`), once per frame.
- `ProjectionHelper` runs as the shell user through Shizuku. It moves the display rect of the
  glasses' SurfaceFlinger projection by that offset, and puts the original back when the app
  stops or dies.

## Setup (this Linux VM)

```sh
sudo apt-get install -y --no-install-recommends adb openjdk-21-jdk-headless aapt apksigner zipalign
```

`sdk/` and `libs/` are checked in. Builds need about 2 GB of free RAM. On the phone, Shizuku
must be running (start it again after each reboot). To recreate `sdk/` and `libs/`:

| File | Source |
|---|---|
| `sdk/android.jar` | `android.jar` from https://dl.google.com/android/repository/platform-37.2_r01.zip |
| `sdk/android-34-res.jar` | `android.jar` from `platform-34-ext12_r01.zip`, same URL path (Debian's aapt can't read API 37) |
| `sdk/r8.jar` | https://dl.google.com/android/maven2/com/android/tools/r8/8.13.25/r8-8.13.25.jar |
| `libs/shizuku-*-13.1.5.jar` | `classes.jar` from the `dev.rikka.shizuku` api/provider/aidl/shared 13.1.5 AARs on Maven Central |

## Build and run

```sh
./xrdesk.sh test       # JVM unit tests
./xrdesk.sh install    # build and install
./xrdesk.sh start      # or open XR Desk on the phone and press Start
./xrdesk.sh stop | pause | recenter | mouse | log
```

The first start asks for Shizuku permission and for access to the glasses. Choose
**Always allow** for the glasses so the question doesn't come back after replugging.

The app's screen and its notification have Start/Stop, Pause and Recenter. XR Desk stops
when the glasses are unplugged; press Start again after plugging them back in. Settings are
listed at the top of `xrdesk.sh`.

## Mouse screen

**Mouse** on the app's screen turns the phone into a mouse for the glasses, with or without
stabilizing. Point with the top of the phone; volume down (or Center cursor) recenters the cursor.
Right and Left click sit at the bottom left; on the right, a scroll strip (drag down to scroll
down) above the **Type** button. The cursor freezes while a button is held, so a press doesn't
turn into a drag, and the Mouse speed slider sets how far it moves per degree.

**Typing:** click a text box on the glasses and Gboard opens on the phone, typing straight into
it (with autocorrect); the controls move up above it, and typing ends when the keyboard closes.
Back closes the keyboard (otherwise the system Back does nothing on this screen; the Back button
at the top leaves). **Type** switches typing mode by hand, for apps that don't open the keyboard
themselves. Android can't open the keyboard for another app's text box, so after closing it,
click the text box again.

The shell helper injects the mouse events and draws Android's arrow as a trusted overlay on the
glasses (an untrusted layer over the click point makes Android drop the clicks). While the mouse
screen is open, the glasses' IME policy is "fall back to the phone"; after a click, focus stays on
the glasses for up to 0.6 s while the helper checks whether a keyboard opened (IME touch region
and visible IME insets), then typing mode starts or focus returns to the phone.
