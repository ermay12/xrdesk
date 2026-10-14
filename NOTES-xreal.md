# XREAL IMU + input forwarding — progress log

Backup of v3 (before this work): /home/droid/xrdesk-backups/v3-20261014-175216

## Log
- 2026-10-14T17:52:16Z started; backup made.

## Device identification (confidence: HIGH for IDs/transport, MEDIUM for axis mounting)
- "XREAL XBX A01+" = xbx a01+ (X by XREAL sub-brand, launched 2026; retail model P1012). Wired USB-C DP display glasses.
  Retail/model: https://www.bestbuy.com/product/xreal-xbx-a01-ar-glasses-w-48-degree-fov-120hz-display-w-sound-by-bose-for-iphone-steam-rog-mac-pc-android-ios-black/CZTVG22LQH , https://www.xreal.com/us/xbxa01
- USB: VID 0x3318, PID 0x0442 (a01+), 0x0440 (a01). IMU = HID interface 1, MCU = HID interface 0, IMU max payload 64.
  Source: wheaney/nrealAirLinuxDriver @61ecd4cc interface_lib/src/hid_ids.c:38-73 (gitlab.com/wheaney/nrealAirLinuxDriver),
  which is the xrealInterfaceLibrary submodule of wheaney/XRLinuxDriver @36e2139d.
- Transport is HID (Air-family protocol), NOT the One-series TCP 169.254.2.1:52998:
  XRLinuxDriver @36e2139d src/devices/xreal.c:136-150 (xreal_uses_hid_transport true for 0x0440/0x0442);
  README.md:27 lists "xbx a01+" as supported in XR driver v2.11.x ("requires testing/tuning").
- NO official XREAL protocol docs exist; everything below is community reverse engineering.

## IMU HID protocol (Air family; used unchanged for A01/A01+ by the reference driver)
Source: nrealAirLinuxDriver @61ecd4cc interface_lib/src/imu_hid.c
- Command frame (L118-147): [0]=0xAA head, [1..4]=CRC32 LE, [5..6]=len LE (=3+datalen), [7]=msgid, [8..]=data.
  CRC32 over bytes starting at the len field, `len` bytes. CRC = standard reflected CRC-32 (table 0x77073096..., init ~0, final ~)
  = java.util.zip.CRC32 (crc32.c:27-102). Sent length = 8+datalen (L131-146).
- Start stream: msgid 0x19 with data {0x01}; stop: {0x00} (L18, L173-181). Reply: next packet with msgid 0x19 (byte 7).
- Sample packet, 64 bytes (struct L28-49): [0..1] signature 01 02, [2..3] temp, [4..11] u64 timestamp (ns),
  [12..13] gyro multiplier (i16 LE), [14..17] gyro divisor (i32 LE), [18..26] gyro x,y,z (i24 LE signed),
  [27..28] acc mul, [29..32] acc div, [33..41] acc x,y,z (i24), [42..43] mag mul (BE), [44..47] mag div (BE),
  [48..53] mag xyz, [54..57] checksum (not validated by reference), [58..63] padding.
  Init packet signature AA 53 (L243-251); unknown signatures skipped (L253-255).
- Gyro value = raw * mul / div (L278-280), unit DEGREES/SECOND: fed straight into Fusion AHRS
  (xrDeviceKit @586520b4 src/device_imu.c:825-851), whose API takes deg/s (xioTechnologies/Fusion @b607fc32 Fusion/FusionAhrs.c:320,339).
  Timestamp is ns (device_imu.c:818-819 divides delta by 1e9). Reference sample rate 1000 Hz (xreal_air_devices.c:29).
- Independent confirmation of layout + mapping: badicsalex/ar-drivers-rs @56bfa489 src/nreal_air.rs:469-485.

## A01/A01+ MCU heartbeat bootstrap (required, gates the IMU stream)
Source: nrealAirLinuxDriver @61ecd4cc interface_lib/src/mcu_hid.c:54-62, 64-75, 119-146, 234-362; hid_ids.c:89-104; include/mcu_hid.h:47
(bootstrap credited to taowen/ar-glass-lib, repo now 404).
- MCU frame (64 max): [0]=0xFD, [1..4]=CRC32 LE over len bytes from [5], [5..6]=len LE (=17+datalen),
  [7..14]=u64 timestamp (0), [15..16]=msgid LE u16, [17..21]=reserved 0, [22..]=data. Sent length 22+datalen.
- Open: drain stale input; send each of 0x26(), 0x57(), 0x12(01 00 00 00), 0x02(01 00 00 00), 0x34(), 0x35()
  and wait for any reply with head 0xFD (≤8 reads × 250 ms each); then 0x31 with ASCII "3.1.1" (SDK version
  handshake, "the gate that enables the IMU stream"); then two acknowledged heartbeats 0x1A(u64 LE monotonic ns);
  then a heartbeat 0x1A every 100 ms for as long as the IMU is used.

## Axis mapping (raw IMU -> viewer frame)
- Air: viewer right = -raw x, up = +raw z, back = +raw y. Two independent sources agree:
  ar-drivers-rs nreal_air.rs:479-485 (RUB convention, lib.rs:115-116) and BladeRunner28/XrealBeam @9daef62c README
  "Pose path and the mount" (measured on a wearer's head with the accelerometer).
- A01/A01+: NOT independently verified. The reference driver uses the identical parser for A01 and Air, but the
  IMU's physical mounting in the A01 frame could differ. Default = Air mapping; runtime options imuswap/imuinvx/imuinvy.

## Gyro bias
Port of Fusion FusionOffset (Fusion @b607fc32 Fusion/FusionOffset.c:20-73): threshold 3 deg/s, 5 s stationary
timeout, 0.02 Hz cutoff. XRLinuxDriver applies it to these glasses (device_imu.c:835-837).

## Android access path
- USB host API: UsbManager.openDevice + claimInterface(iface, force=true) + bulkTransfer on the interrupt endpoints,
  writes on the OUT endpoint else HID SET_REPORT controlTransfer(0x21,0x09,0x0200,iface). Shown working on Android 13
  with an XREAL Air by XrealBeam @9daef62c app/src/main/java/com/xman/xrealbeam/XrealImu.java:190-200, 500-535.
- Permission: shell has android.permission.MANAGE_USB (checked on this phone); UsbManager.grantPermission(UsbDevice)
  exists on Android 17 (reflection probe). App adopts shell identity, so it grants itself without a dialog;
  fallback = requestPermission with an explicit mutable PendingIntent.
- Not verified on Android 17 with real glasses: that the kernel/Android allows claiming these HID interfaces.
- 2026-10-14T17:59:25Z research done; findings above.
- 2026-10-14T18:05:14Z implemented XrealProtocol/GyroOffset/RateRing/XrealImuSource, DesktopActivity pose source + input forwarding, display option; build attempted.
- 2026-10-14T18:09:19Z JVM tests: 66 checks, 0 failures (frames match XrealBeam USB-trace frames README:283-288 and python zlib; parser matches independent reader on 20k random packets; garbage/malformed handled; FusionOffset port; ring).
- 2026-10-14T18:17Z on-phone validation (no glasses):
  * imu=xreal, no glasses: "E imu source: phone (imu=xreal, glasses unavailable: no XREAL xbx a01/a01+ attached (start))", no crash, two desktops, phone gyro registered at 5000us.
  * imu=auto default: same fallback at info level. imu=phone: "imu source: phone (imu=phone)", USB untouched.
  * Input (inputlog=1): adb `input` mouse tap/scroll, keyboard text, gamepad BUTTON_A, dpad, joystick MOVE, BACK, plus a
    shell-side injector for mouse HOVER_ENTER/MOVE/EXIT, BUTTON_PRESS/RELEASE (actionButton set) and key repeat with Shift meta:
    24/24 forwarded with original source codes, mouse coords mapped by the layer position, joystick axes unmapped,
    0 injections rejected by InputManager. Screenshots: mouse click opened the desktop's app drawer; typed text reached its search box.
  * Phone Vol+/Vol- (virtual, non-external device) still toggle stabilization / switch desktops and aren't forwarded.
  * display=999 -> SecurityException caught, launched on default display. display=<overlay display 20, 1920x1080> launched
    the task in FREEFORM mode at first (desktop mode on external displays) -> fixed by ActivityOptions.setLaunchWindowingMode(1)
    via reflection; now mode=fullscreen and desktops sized 1728x972 from that display. Moving the task between displays keeps it
    fullscreen and the process alive. The in-activity requestFullscreenMode fallback did not get exercised.
  * Overlay-display developer setting restored to its original empty value afterwards.
- Known limitations / unverified: everything on real glasses (permission grant via shell identity, claiming HID interfaces on
  Android 17, MCU bootstrap acks, sample rate, axis mapping for the a01+ mount, bias filter behaviour); cursor not stabilized;
  layer moves paced by the app vsync (Choreographer), which is the same vsync the platform gives all apps on external displays.
