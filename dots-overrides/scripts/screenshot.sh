#!@PREFIX@/bin/bash
# GitHub.com/PiercingXX
# Usage: screenshot.sh select|window|full   -> ~/Pictures/Screenshots
set -u
dir="${XDG_PICTURES_DIR:-$HOME/Pictures}/Screenshots"
mkdir -p "$dir"
file="$dir/$(date +%F-%H%M%S).png"
case "${1:-select}" in
    select) scrot -s -f "$file" ;;
    window) scrot -u "$file" ;;
    full)   scrot "$file" ;;
    *) echo "Usage: $(basename "$0") select|window|full" >&2; exit 2 ;;
esac
if [ -f "$file" ]; then
    command -v xclip >/dev/null 2>&1 && xclip -selection clipboard -t image/png -i "$file"
    command -v notify-send >/dev/null 2>&1 && notify-send -t 1500 "Screenshot saved" "$file"
fi
