# FakeLinux

i3 on a Pixel. Native Termux prefix, embedded X server, no root, no VNC.

A Flutter app that unpacks a Termux userspace into its own private prefix,
installs i3 with the PiercingXX rice, and draws it through an embedded
Termux:X11 server. Built for a Pixel 9 on GrapheneOS. Any arm64 Android 9+
phone should run it.

> Not tested on a Pixel 9 yet. Built and inspected on a workstation; the first
> device run is the real test. See [GrapheneOS notes](docs/GRAPHENEOS.md).

## What you get

- **i3 + i3bar/i3status**, same keys, colors and gaps as
  [piercing-dots](https://github.com/PiercingXX/piercing-dots)
- **kitty, rofi, dunst, feh, scrot** under the rice
- **Neovim, yazi (with plugins), tmux, starship, fastfetch, fzf, zoxide, eza,
  bat, ripgrep, fd**: the CLI core from the linux-mod mini profile
- **Colemak** via `setxkbmap`, caps lock as backspace
- Android apps listed in rofi, so `Mod+Space` launches them too
- Optional apps (Firefox, Code OSS, Node) from the in-app catalog

Everything runs in Termux's userspace on the phone's own kernel. No emulation.

## What it does not get

- The hardware half of the mini profile (firmware, bluez, TTY boot) has no
  meaning on a phone. Dropped.
- No GPU acceleration on Tensor. Mali has no open Vulkan path in Termux, so
  rendering is Mesa llvmpipe. Adreno phones get Turnip/Zink.
- No Hyprland. It is Wayland-only and the embedded server is X11. i3 is the
  closest thing in piercing-dots that works here.
- No Arch settings menu. `Mod+S` opens a small phone menu instead.

## Build

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=$HOME/tools/jdk-21.0.12.1+1
scripts/fetch-bootstrap.sh          # stock Termux bootstrap, sha256 pinned
scripts/sync-dots.sh                # optional: refresh the rice from ../piercing-dots
cd app && flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

Without `app/android/key.properties` the release build is signed with the
debug key. See [RELEASING.md](RELEASING.md).

## Keys

`Mod` is Super.

| Keys | Action |
|---|---|
| `Mod+W` | kitty |
| `Mod+T` | kitty + tmux |
| `Mod+Z` | yazi |
| `Mod+V` | nvim |
| `Mod+C` | Firefox |
| `Mod+Space` | launcher (rofi) |
| `Mod+S` | phone menu: update, wallpaper, reload |
| `Mod+/` | keybind sheet |
| `Mod+Q` | close window |
| `Mod+H/J/K/L` | focus, `+Shift` move, `+Alt` resize |
| `Mod+1..0` | workspace, `+Shift` send window |
| `Mod+F` / `Mod+Shift+F` | float / fullscreen |
| `Mod+Shift+C` / `R` | reload / restart i3 |
| `Print`, `Shift+Print`, `Mod+Print` | screenshot: select, window, full |

## Layout

```
app/                         Flutter app + Android runtime (Kotlin)
  android/.../runtime/       LinuxRuntime, PiercingProfile (installs the rice)
  android/.../assets/fakelinux/dots/   vendored rice, unpacked into ~ on first launch
dots-overrides/              phone-specific files that win over piercing-dots
scripts/sync-dots.sh         refresh dots/ from a piercing-dots checkout
scripts/fetch-bootstrap.sh   download + verify the Termux bootstrap
docs/                        GrapheneOS notes
```

User edits to nvim, kitty, yazi and the shell survive app updates. The i3,
i3status, dunst and `~/.scripts` files are app-owned and are rewritten when the
profile version changes; the previous copy is kept as `*.bak`.

## License

GPL-3.0-only. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

Derived from DroidDesk (GPL-3.0) by orailnoor, which incorporates Termux:X11.
Their copyright notices stay in the source as the GPL requires.
