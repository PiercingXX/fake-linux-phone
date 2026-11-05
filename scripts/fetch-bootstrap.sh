#!/bin/bash
# GitHub.com/PiercingXX
#
# Download the stock Termux aarch64 bootstrap into app/assets/ and verify it.
# The app relocates it into its own private prefix at first launch, so the file
# is unmodified upstream output and is not committed to this repo.

set -euo pipefail

TAG="bootstrap-2026.07.12-r1+apt.android-7"
SHA256="b6706d470a3e3fcf7cd5c056757c25abd0f61687a40f90ce809289efcc6969fd"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/app/assets/bootstrap-aarch64.zip"

if [ -f "$out" ] && echo "$SHA256  $out" | sha256sum -c --quiet 2>/dev/null; then
    echo "bootstrap already present and verified"
    exit 0
fi

url="https://github.com/termux/termux-packages/releases/download/${TAG//+/%2B}/bootstrap-aarch64.zip"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fL --retry 3 -o "$tmp" "$url"
echo "$SHA256  $tmp" | sha256sum -c --quiet || { echo "checksum mismatch for $url" >&2; exit 1; }
mv "$tmp" "$out"
trap - EXIT
echo "bootstrap ready: $out"
