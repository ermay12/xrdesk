# XR Desk

An Android app that adds advanced screen stabilization to XREAL xbx a01+ glasses, and lets you
use your phone as a pointer-style mouse and keyboard.

> [!NOTE]
> This app was completely vibecoded with Opus 5.5.
>
> I only provided design direction and testing.

## Demos

### Stabilization

[![Watch the demo](https://img.youtube.com/vi/kCf_8hHDI9E/maxresdefault.jpg)](https://www.youtube.com/watch?v=kCf_8hHDI9E)

### Mouse and keyboard

The cursor looks normal in the glasses but doesn't show up in screen recordings, so I turned on
some developer settings to show where it would be.

[![Watch the demo](https://img.youtube.com/vi/WlOdrEsqxr8/maxresdefault.jpg)](https://www.youtube.com/watch?v=WlOdrEsqxr8)

### Screenshots

<p>
  <img src="docs/main-screen.png" width="270" alt="Main screen: Picture size and position, Stabilization settings, Switch to 3D, Start Stabilization, Recenter and Mouse">
  <img src="docs/mouse-screen.png" width="270" alt="Mouse screen: Back, Center cursor and Hold for 3D at the top, the mouse and scroll speed sliders, Reverse scroll direction, Right click, Left click and the scroll strip">
  <img src="docs/typing-screen.png" width="270" alt="Typing: the phone's keyboard open below Right click, Left click and the scroll strip">
</p>

## What it does

- **Steadier picture**: the picture stays put when you move your head a little, then drifts back
  to center.
- **Phone as a mouse**: point your phone to move the cursor; click, drag and scroll.
- **Phone as a keyboard**: type on the glasses with your phone's keyboard, autocorrect included.
- **Picture size and position**: shrink or zoom the whole desktop and move it where it's easiest
  to see.
- **3D videos**: switch the glasses to 3D and back.

## What you need

- **XREAL xbx a01 or a01+ glasses**, plugged into the phone with USB-C. Other glasses aren't
  supported yet.
- **An Android 14 or newer phone that shows a desktop on the glasses.** XR Desk is made and
  tested on a Pixel 9a with Android 17. It uses hidden parts of Android, so other phones may not
  work, and a phone update may break it.
- **[Shizuku](https://shizuku.rikka.app/)**, a free app. To move the picture and control the
  glasses, XR Desk needs permissions that normal apps don't get. Shizuku gives them through
  Android's own debugging access, without rooting the phone.

## Install

1. **Install Shizuku** from
   [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) or
   [GitHub](https://github.com/RikkaApps/Shizuku/releases). Open it and start it with
   **Wireless debugging**; Shizuku shows you how (you'll need to turn on Developer options).
   **Start Shizuku again each time you restart your phone.**
2. **Install XR Desk.** On your phone, download the latest `xrdesk-<version>.apk` from
   [Releases](https://github.com/ermay12/xrdesk/releases/latest) and open it. If asked, allow your
   browser or file manager to install apps.
3. **Plug in the glasses and open XR Desk.** Allow it to use Shizuku, show notifications and
   access the glasses. For the glasses, choose **Always allow**, so you aren't asked every time.

## How to use

XR Desk keeps running, with its notification, until you unplug the glasses. Open it again each
time you plug them back in.

### Stabilization

- Tap **Start Stabilization** to turn it on (it's off when XR Desk opens) and
  **Stop Stabilization** to turn it off. **Recenter** brings the picture back in front of you.
  These buttons are also in XR Desk's notification.
- Tap **Stabilization settings** to adjust it:
  - **Drift back to center**: how many seconds the picture takes to come back. A large number,
    like 400, keeps it in place until you tap **Recenter**.
  - **Max side-to-side rotation** and **Max up-and-down rotation**: how far you can turn your
    head before the picture follows you.
  - **Drift back curve**: how the picture moves on its way back (_Speed up_, the default,
    _Smooth_, _Exponential_ or _Linear_).

### Picture size and position

Tap **Picture size and position** and watch the glasses as you adjust.

- Drag up and down on **Size** to shrink the desktop (down to 50%) or zoom in (up to 200%, which
  cuts off its edges).
- Drag on **Move** to move it.
- Both work like a trackpad, and the phone ticks as you drag, so you don't need to look at it.
- **Center picture** puts it back in the middle; **Full size** sets it back to 100%. **Back**
  returns to the main screen.

This works with or without stabilization. A smaller picture keeps its edges in view while
stabilizing, but small text gets a little softer.

### Mouse and keyboard

Tap **Mouse**, the big button at the bottom, easy to find with your thumb while wearing the
glasses.

- **Point** the top edge of the phone to move the cursor. **Center cursor** brings it back to
  the middle.
- Click with **Left click** and **Right click**. To drag, hold **Left click** and turn the phone.
- **Scroll**: drag up and down on the scroll strip. Turn on **Reverse scroll direction** to drag
  the page like on a touch screen.
- The **Mouse speed** and **Scroll speed** sliders set how far the cursor moves when you turn
  the phone, and how far a drag scrolls.
- **Hold for 3D**: hold it for half a second to switch to 3D. It then says **Hold for 2D**, to
  switch back. See [3D videos](#3d-videos).
- **Back** at the top leaves the mouse screen. The phone's own Back gesture doesn't, so you can't
  leave by accident.

**Typing:** on the mouse screen, click a text box on the glasses. Your phone's keyboard opens
with a short buzz, and what you type goes to the glasses. Typing ends when the keyboard closes;
click the text box again to bring it back.

### 3D videos

This is for side-by-side 3D videos: the left half of the picture goes to your left eye and the
right half to your right eye, each stretched to full width. The whole desktop is split the same
way, so find and start the video in 2D first.

1. With the mouse, open the video and make it full screen.
2. Switch to 3D: tap **Switch to 3D** on the main screen, hold **Hold for 3D** on the mouse
   screen, or press the glasses' menu button. The glasses go dark for a few seconds, then the
   phone buzzes twice.
3. Pause and skip with the media controls in your phone's notifications, or with the mouse. The
   cursor shows in one eye at a time: it starts in the left, and moves to the right eye when you
   push it past the right edge. Leave the mouse screen to hide it.
4. Switch back to 2D the same ways, or with **Switch to 2D** in XR Desk's notification.

In 3D, stabilization only corrects up-and-down head movement, and the picture is full size and
centered. Your size and position come back in 2D. The glasses always start in 2D when plugged in.

> [!WARNING]
> Without XR Desk, don't use 3D from the glasses' own menu. It switches to a kind of 3D that
> phones like the Pixel 9a can't send, and you lose the picture until you unplug the glasses.

### The glasses' buttons

Once you open XR Desk, the glasses' **menu button** switches between 2D and 3D (see
[3D videos](#3d-videos)), and their **volume buttons** change the phone's media volume (hold to
keep changing it). This works whether or not you're stabilizing, but not during a switch between
2D and 3D.

The glasses' own menu (brightness and so on) isn't available until you unplug them.

## Building from source

Developers: see [BUILDING.md](BUILDING.md) for how to build XR Desk and how it works.

## License

XR Desk is free software under the [GNU General Public License v3.0](LICENSE) or later. The APK
includes the [Shizuku API library](https://github.com/RikkaApps/Shizuku-API) (Apache License 2.0).
