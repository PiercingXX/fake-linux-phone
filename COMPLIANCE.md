# Release compliance

This document records the licensing work required for a distributable
FakeLinux APK. It is not a substitute for advice from a qualified lawyer.

## Project license

FakeLinux's original source code is offered under GPL-3.0-only. This choice is
intended to remain compatible with the Termux:X11 code incorporated into the
same application. The full license is in [LICENSE](LICENSE).

Source files that already carry an upstream license notice keep that notice and
license. Nothing in the FakeLinux license replaces or removes third-party
copyrights.

## Current binary inventory

The following SHA-256 values identify the binary inputs as of 2026-11-04:

| File | SHA-256 |
|---|---|
| `libXlorie.so` | `2f114286f021aca57c7065ee49511160e67e77fb8ce07ff90debb95927f13bc6` |
| `libandroid-shmem.so` | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |
| `libproot-loader.so` | `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04` |
| `libproot.so` | `1f2e9eb2f1070cdd7681227c62689923bcbf6f9c9863bba59d0b9ad379b82b39` |
| `libsocket_hook.so` | `7f6f32b5b8cb1aabe451456e9489b130f82ac626e7673efb053b2949b727a09d` |
| `libtalloc.so` | `777badd62d21865ea3ef7f5c1095f062b4a87c6aaab3287d43a5c3c9c83cb572` |
| `app/assets/bootstrap-aarch64.zip` | `b6706d470a3e3fcf7cd5c056757c25abd0f61687a40f90ce809289efcc6969fd` |

`libXlorie.so` is the upstream DroidDesk build with the embedded package path
`/data/data/<old id>` binary-patched (NUL padded) to `/data/data/com.piercingxx.linux`.
`libsocket_hook.so` is rebuilt from `app/assets/socket_hook.c` with NDK r28
(`aarch64-linux-android28-clang -shared -fPIC -O2 -ldl -llog`).
`bootstrap-aarch64.zip` is the unmodified Termux release
`bootstrap-2026.07.12-r1+apt.android-7` and is fetched by
`scripts/fetch-bootstrap.sh`, not committed.

Changing any listed artifact requires updating this table and its corresponding
source record.

## Required before the next public APK release

- [x] Add the GPL-3.0 project license.
- [x] Add attribution, non-affiliation, and third-party notices.
- [x] Record current bundled binary hashes.
- [x] Preserve the license documents already included inside the bootstrap.
- [ ] Record the exact Termux:X11 commit, submodule commits, local patches, and
  commands used to build `libXlorie.so`.
- [ ] Publish the preferred source form and reproducible build process for every
  bundled native binary.
- [ ] Identify the exact source and license for `libandroid-shmem.so` and
  `libtalloc.so`.
- [ ] Rebuild the Termux bootstrap and packages for
  `com.piercingxx.linux` rather than relocating binaries built for
  `com.termux`.
- [ ] Publish a machine-readable bootstrap manifest containing package names,
  versions, architectures, source URLs, license identifiers, build revisions,
  and SHA-256 hashes.
- [ ] Make the complete corresponding source for the exact APK available beside
  the APK release, including build scripts and installation information.
- [x] Remove the bundled wallpaper (the rice ships piercing-dots backgrounds).
- [ ] Generate Flutter, Dart, Gradle, AndroidX, and native dependency notices as
  part of every release build.
- [ ] Add an in-app legal/notices screen. This is intentionally deferred because
  the current licensing pass must not change application code.
- [ ] Obtain a maintainer/legal review before describing a release as compliant.

An unchecked item is not satisfied merely because an upstream repository is
public. The source offered for a binary must match the binary actually
distributed and include the scripts and patches needed to build it.

## Release artifact procedure

For each release:

1. Build all bundled native components from recorded source revisions.
2. Build the custom-package Termux bootstrap from recorded package recipes.
3. Generate dependency and license manifests.
4. Build the APK from a clean tagged checkout.
5. Record hashes for the APK, bootstrap, and native libraries.
6. Publish the APK, matching source archive/tag, build instructions, notices,
   and hashes from the same release page.
7. Retain source availability for as long as required by the applicable
   licenses.

## Repository and trademark statement

The project may describe compatibility with Termux and Termux:X11, but must not
represent itself as an official Termux release. Do not use third-party logos,
signing identities, or artwork without permission.
