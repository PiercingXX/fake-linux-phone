#!@PREFIX@/bin/bash
# GitHub.com/PiercingXX
# Usage: volume.sh inc|dec|mute  (PulseAudio, capped at 100%)
set -u
case "${1:-}" in
    inc)  pactl set-sink-volume @DEFAULT_SINK@ +5% ;;
    dec)  pactl set-sink-volume @DEFAULT_SINK@ -5% ;;
    mute) pactl set-sink-mute @DEFAULT_SINK@ toggle ;;
    *) echo "Usage: $(basename "$0") inc|dec|mute" >&2; exit 1 ;;
esac
vol=$(pactl get-sink-volume @DEFAULT_SINK@ 2>/dev/null | grep -o '[0-9]*%' | head -n1 | tr -d '%')
if [ "${vol:-0}" -gt 100 ]; then pactl set-sink-volume @DEFAULT_SINK@ 100%; vol=100; fi
muted=$(pactl get-sink-mute @DEFAULT_SINK@ 2>/dev/null | awk '{print $2}')
command -v notify-send >/dev/null 2>&1 && \
    notify-send -t 1200 -h string:x-dunst-stack-tag:volume "Volume" "${vol:-?}%${muted:+ (muted: $muted)}"
exit 0
