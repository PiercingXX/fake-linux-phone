#!@PREFIX@/bin/bash
# GitHub.com/PiercingXX
# Small settings menu for the phone build (the Arch settings menu does not apply here).
set -u
choice=$(printf '%s\n' \
    "Update packages" \
    "Change wallpaper" \
    "Reload i3" \
    "Restart i3" \
    "Keybinds" \
    "Exit i3" | rofi -dmenu -i -p "FakeLinux") || exit 0

case "$choice" in
    "Update packages") exec kitty -e bash -c 'pkg update -y && pkg upgrade -y; echo; read -rp "Done. Enter to close." _' ;;
    "Change wallpaper")
        img=$(ls "$HOME"/Pictures/backgrounds/* 2>/dev/null | rofi -dmenu -i -p "Wallpaper") || exit 0
        cp -f "$img" "$HOME/.config/wall.png" && feh --bg-fill --no-fehbg "$HOME/.config/wall.png" ;;
    "Reload i3")  i3-msg reload ;;
    "Restart i3") i3-msg restart ;;
    "Keybinds")   xdg-open "$HOME/.scripts/Control-Scripts/keybinds.html" ;;
    "Exit i3")    i3-msg exit ;;
esac
