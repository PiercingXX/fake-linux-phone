# Releasing FakeLinux

The app checks `https://api.github.com/repos/PiercingXX/fake-linux-phone/releases/latest`
when it opens (at most every 6 hours) and offers the update in-app. A release
reaches users only if it follows these steps.

## One-time: release key

Every release must be signed with the same key, otherwise Android rejects the
in-app update. Create it once and keep it (and its passwords) safe; share it
privately with other maintainers. Never commit it.

```bash
mkdir -p ~/.fakelinux
keytool -genkeypair -v -keystore ~/.fakelinux/fakelinux-release.jks \
  -alias fakelinux -keyalg RSA -keysize 4096 -validity 10000
```

Then create `app/android/key.properties` (git-ignored):

```properties
storeFile=$HOME/.fakelinux/fakelinux-release.jks
storePassword=<store password>
keyAlias=fakelinux
keyPassword=<key password>
```

CI can use the environment variables `FAKELINUX_KEYSTORE`,
`FAKELINUX_KEYSTORE_PASSWORD`, `FAKELINUX_KEY_ALIAS` and `FAKELINUX_KEY_PASSWORD`
instead. Without a key the release build falls back to the debug key; such an
APK installs fine but cannot update a published release.

Releases up to v1.1.0 were signed with a debug key, so users on those versions
must install the first key-signed release manually once. From then on updates
install from inside the app.

## Each release

1. Bump `version:` in `app/pubspec.yaml`, both parts: `1.2.0+3` (name + build number).
2. Build: `cd app && flutter build apk --release`
3. Create a GitHub release on `PiercingXX/fake-linux-phone` with tag `v1.2.0` (must match the
   version name) and attach `app/build/app/outputs/flutter-apk/app-release.apk`.
   The first `.apk` asset is what the app downloads.
4. The release notes are shown in the in-app update sheet; keep them short.

Drafts and pre-releases are ignored by the update check.
