# MiniWin

Opens Android apps and websites in small floating windows over other apps. Made for a Redmi 13C and works on Android 8 and later.

## Install

Download `MiniWin.apk` from the [latest release](../../releases/latest) and open it on your phone.

Floating apps need Android's freeform mode, which is hidden. To turn it on, plug the phone into a computer with USB debugging on and run:

```bash
./install-on-phone.sh
```

Then restart the phone. The floating browser works without this step.

## Use

- A thin line sits on the edge of the screen. Tap it twice, or swipe inward twice, to open the menu.
- Pick an app or type a website, and choose Tiny, Small, Medium or Large.
- Hold the line for half a second and drag it to move it, or to switch sides.
- Turn the line off with **Side handle: on** in the menu, or **Stop** in the notification.

On Xiaomi phones, go to Settings > Apps > MiniWin and:

- allow "Display pop-up windows while running in the background"
- turn on Autostart
- set battery saver to "No restrictions"

## Build

You need the Android SDK (build-tools 35.0.0, platform android-35) and a JDK. No Gradle needed.

```bash
./build.sh
```

The APK lands in `build/MiniWin.apk`. The first build makes a new signing key, `miniwin.keystore`, which is not in this repo.
