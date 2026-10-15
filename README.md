# XR Desk 2

Stabilizes the whole picture of XREAL xbx a01/a01+ glasses on Android. Apps run normally on
the glasses' desktop; XR Desk 2 shifts the display's output against small head movements, so
the picture seems to stay put, and lets it drift back to center. Windows, taskbar and mouse
cursor all move together, and nothing is redrawn. Tested on a Pixel 9a running Android 17.

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

`sdk/` and `libs/` are checked in (same sources as `~/xrdesk/README.md`). Builds need about 2 GB
of free RAM. On the phone, Shizuku must be running (start it again after each reboot).

## Build and run

```sh
./xrdesk2.sh test       # JVM unit tests
./xrdesk2.sh install    # build and install
./xrdesk2.sh start      # or open XR Desk 2 on the phone and press Start
./xrdesk2.sh stop | pause | recenter | log
```

The first start asks for Shizuku permission and for access to the glasses. Choose
**Always allow** for the glasses so the question doesn't come back after replugging.

The app's screen and its notification have Start/Stop, Pause and Recenter. XR Desk 2 stops
when the glasses are unplugged; press Start again after plugging them back in. Settings are
listed at the top of `xrdesk2.sh`.

## Mouse screen

**Mouse** on the app's screen turns the phone into a mouse for the glasses, with or without
stabilizing. Point with the top of the phone; volume down (or Center cursor) recenters the cursor.
Right and Left click sit at the bottom left; on the right, a scroll strip (drag down to scroll
down) above the **Type** button. The cursor freezes while a button is held, so a press doesn't
turn into a drag, and the Mouse speed slider sets how far it moves per degree.

**Type** shows the glasses' keyboard on the phone: click a text field on the glasses and Gboard
types straight into it. Tap Type again, or Back, to finish.

The shell helper injects the mouse events and draws Android's arrow as a trusted overlay on the
glasses (an untrusted layer over the click point makes Android drop the clicks). Typing mode sets
the glasses' IME policy to "fall back to the phone" and keeps input focus on the glasses.
