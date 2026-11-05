#!/bin/bash
# GitHub.com/PiercingXX
#
# Refresh the vendored rice in dots/ from a local piercing-dots checkout.
# dots/ is what the app unpacks into the Linux home on first launch, so run
# this and commit whenever piercing-dots changes.
#
#   scripts/sync-dots.sh [path/to/piercing-dots]
#
# Only the pieces that make sense on a phone are copied. The i3 config, the
# i3status config, the phone bashrc and the phone scripts under
# dots-overrides/ are phone-specific and are never overwritten.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="${1:-$here/../piercing-dots}"
dst="$here/app/android/app/src/main/assets/fakelinux/dots"

[ -d "$src/dots" ] || { echo "No piercing-dots checkout at $src" >&2; exit 1; }

mkdir -p "$dst/config" "$dst/home" "$dst/scripts/Control-Scripts" "$dst/backgrounds"

sync_dir() { # sync_dir <from> <to>
    rm -rf "$2"
    mkdir -p "$(dirname "$2")"
    cp -a "$1" "$2"
}

# ~/.config
for d in kitty nvim yazi rofi fastfetch dunst; do
    [ -d "$src/dots/$d" ] && sync_dir "$src/dots/$d" "$dst/config/$d"
done
rm -rf "$dst/config/nvim/.vscode"
# kitty: phone font overrides win because they come last
printf '\ninclude phone.conf\n' >> "$dst/config/kitty/kitty.conf"
[ -f "$src/dots/starship.toml" ] && cp -a "$src/dots/starship.toml" "$dst/config/starship.toml"

# ~ (home)
# Android's asset packager drops dotfiles, so they ship as dot_* and the app renames them.
cp -a "$src/resources/bash/.bashrc" "$dst/home/dot_bashrc.piercingxx"
cp -a "$src/resources/tmux/.tmux.conf" "$dst/home/dot_tmux.conf"
mkdir -p "$dst/home/dot_fzf"
cp -a "$src/resources/fzf/key-bindings.bash" "$src/resources/fzf/completion.bash" "$dst/home/dot_fzf/"

# ~/.scripts/Control-Scripts: only self-contained ones. The rest of the set
# sources lib/common.sh (distro detection, systemd, notify daemons) and has no
# business on Termux; phone versions live in dots-overrides/scripts.
for s in wm-app-launcher.sh keybinds.html; do
    [ -f "$src/resources/.scripts/Control-Scripts/$s" ] && \
        cp -a "$src/resources/.scripts/Control-Scripts/$s" "$dst/scripts/Control-Scripts/$s"
done

# Wallpapers: a short list, the full set is 31 MB
for w in arch_plain_black.png arch_purple.jpg blue_night_moon_over_lake.jpg; do
    [ -f "$src/resources/backgrounds/$w" ] && cp -a "$src/resources/backgrounds/$w" "$dst/backgrounds/$w"
done

# Phone-specific files live in dots/overrides and win over anything above.
if [ -d "$here/dots-overrides" ]; then
    cp -a "$here/dots-overrides/." "$dst/"
fi

echo "dots synced from $src into $dst"
