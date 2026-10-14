# XR Desk settings menu — progress log

Started 2026-10-14T18:47Z. Backup of pre-change source: ~/xrdesk-backups/v4-20261014-184707

## Plan
- Pure-Java gesture logic (shake, flick/N monitors, roll filter, touch mapping) + JVM tests
- Dialog menu (monitors 1-4, swipe up/down vs left/right, roll smoothing), fallback shortcut keys
- Settings in SharedPreferences, start options override

## Log
- Pure logic in src/com/droid/xrdesk/motion: HeadGestures (shake + flicks, N monitors, swipe axis,
  left/right commit delay), RollFilter (leaky integrate, deadband, clamp, snap/quantize to 0), DesktopTransform
  (rotation about desktop center, inverse mapping, layer matrix). MotionTest: 87 checks pass at 200 Hz and 1000 Hz.
  Mutation check: removing the commit delay, disabling shake, swapping flick sign, or removing wrap-around each
  make tests fail (8/12/16/4 failures) — the tests are discriminating.
- Decisions: wrap-around (2 monitors keep the old up-up toggle). Shake defaults: 1.5 rad/s, 3 alternating strokes
  of >=6 deg, within 1 s, <=300 ms between strokes. Flicks unchanged (2.5 rad/s, 12 deg, 300 ms, cooldown 800 ms).
  Left/right: flick commits 150 ms after its stroke ends; a reversal >= min(shake, flick) rate cancels it.
  Known: a fast "yes" nod reads as a down flick (PREVIOUS) in up/down mode.
- XREAL roll: viewer back = +raw y (ar-drivers-rs nreal_air.rs:483, XrealBeam README mount). RateRing carries roll.
  XrealProtocolTest now 69 checks.
- Public SDK has no SurfaceControl.Transaction.setMatrix; the hidden one exists on Android 17 (probed:
  setMatrix(SurfaceControl,float x4) and setMatrix(SurfaceControl,Matrix,float[])). Using the 4-float one through an
  exact-typed MethodHandle (no boxing/alloc per frame) + public setPosition. Test-only start option rolltest=<deg>
  forces a fixed roll so the argument order/direction and the input mapping can be checked by screenshot + tap.
- Built: SettingsMenu (Dialog), DesktopActivity generalized to 1-4 desktops (add/remove at the end; layers released on
  the stab thread after a new snapshot is published), gestures/roll wired in, menu shortcuts F12 / BUTTON_MODE.
- Device tests (build with compact menu), phone gyro, inputlog=1:
  * Saved menu settings survive a restart (user had set leftright + roll on; startup log showed them).
  * Real shake on the phone logged "menu opened (shake)" (user handling the phone) — on-device shake detection works.
  * F12 opens; keys NOT forwarded while open; first D-pad press focuses the current monitor count (focus ring visible).
  * Keys/controller: Right+Enter -> monitors 3 (display created); Down, Left, BUTTON_A -> swipe updown; Down, A -> roll off.
  * Calculator started on desktop 3 (display 48); tapping "2" removed desktop 3 and Calculator moved to display 0.
  * Taps on chips change swipe; BUTTON_B / Escape / BUTTON_MODE close; forwarding resumes after close.
  * Held touch when the menu opens: DOWN forwarded, then one ACTION_CANCEL, rest swallowed.
- Roll on device (fixed rolltest=10 deg, stabilization off): screenshot shows the desktop turned CLOCKWISE about its
  center (as designed for a positive angle); SurfaceFlinger toDisplayTransform = ROT_INVALID (arbitrary rotation
  applied); a tap at the screen point computed by DesktopTransform.toScreen (835,923) mapped back to desktop
  (786.2,928.6) and opened the app drawer => the hidden setMatrix argument order and the input mapping agree.
  Turning roll Off in the menu: transform back to translation only (tx=121 ty=54); screenshot level.
- Live monitor changes with continuous log capture: 2 (remove 3), 1 (remove 2), 3 (create 2,3), 2 (remove 3), all
  applied. (An earlier run's last tap on "2" didn't register; logs were lost to the phone's fast-rotating log buffer.
  Re-test was interrupted by a VM restart.)
- Per-monitor memory: XR Desk's own meminfo stays ~64 MB graphics / ~100 MB PSS regardless of monitor count, so the
  desktops' buffers aren't attributed to the app; the menu's "~24 MB" is a 3-buffer estimate (2182x972x4x3).
- User's own settings before my device tests (startup log): monitors 2, swipe leftright, roll on. Restore those.
- After the VM restart: source builds, tests 69 + 87 pass; installed dex == build. Re-test of rapid taps (2,3,2,3,2 at
  2.5 s) all applied — the earlier missed tap didn't reproduce. Mouse scroll forwarded with the menu closed
  (mapped 300,300 -> 179,246), not forwarded while open.
- Cleanup: restored the USER's pre-test settings (monitors 2, swipe leftright, roll on) and cleared my inputlog=1
  launch option; relaunched via LaunchActivity (log: settings: monitors 2, swipe leftright, roll on); Terminal in front.
