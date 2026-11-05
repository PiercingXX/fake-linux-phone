# GrapheneOS notes

What to flip so the desktop survives on a Pixel running GrapheneOS.

## Install

Sideload the APK: `adb install -r app-release.apk`, or open it from a file
manager that has "Install unknown apps" enabled. The app targets API 28 on
purpose (see below), so Android will warn that it was built for an older
version. Expected.

## Settings that matter

1. **Child process restrictions.** Settings → System → Developer options →
   *Disable child process restrictions*. Without it Android kills the desktop's
   background processes. From a PC:

   ```bash
   adb shell settings put global settings_enable_monitor_phantom_procs false
   adb shell device_config put activity_manager max_phantom_processes 2147483647
   ```

2. **Battery.** App info → Battery → Unrestricted.
3. **Notifications.** Allow them; the foreground service that keeps the
   desktop alive needs one.
4. **Network.** Package installs need it. If you revoked the app's Network
   permission, restore it for setup.

## If something crashes

Run `adb logcat` and look for `SEGV_MTEAERR` or `hardened_malloc`. GrapheneOS
turns on memory tagging for apps. Prebuilt Termux binaries are not all clean
under it. Fix: App info → *Exploit protection compatibility mode*. That weakens
protection for this app only.

## Why API 28

Android 10+ blocks `execve()` on files in an app's data directory for apps that
target API 29 or higher. The Termux userspace lives there, so the app targets 28.
It installs fine on current Android; it just cannot be published on Google Play.
GrapheneOS does not care.

## Graphics

Tensor G4 has a Mali GPU. Mesa renders on the CPU (llvmpipe). Fine for terminals,
neovim and a browser; not for Blender.

## External display

The Pixel 9 can output DisplayPort over USB-C. This app has a native desktop
activity, but the external-display path has not been verified yet.
