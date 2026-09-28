# Build and release

## Local builds

JDK 17 and the Android SDK, nothing else.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew reportApkSize
```

Debug builds are signed with the local debug keystore and install fine for
testing. They carry the `.debug` application id suffix, so a debug build and a
store build can sit on the same device at once.

## Release signing

A release build is signed only when a keystore is configured. Without one it
still assembles, and produces `app-release-unsigned.apk`, which Android refuses
to install.

**Android identifies an app by its signing certificate.** A build signed with a
different key is, to the system, a different app: installing it over the old one
fails with a signature mismatch, and updates stop working for everyone who
already has it. So one key is generated once and kept for the life of the app.

### Generate the keystore

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias glass-keyboard
```

Keep `release.jks` out of the repository — `.gitignore` already excludes
`*.jks`, `*.keystore` and `keystore.properties`. Losing it means the app can
never be updated again, only republished under a new application id.

### Build with it locally

Create `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=glass-keyboard
keyPassword=...
```

Then `./gradlew assembleRelease`.

### Build with it in CI

`publish-store.yml` reads four repository secrets and refuses to run if any is
missing:

| Secret | What it holds |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | `glass-keyboard` |
| `ANDROID_KEY_PASSWORD` | the key password |
| `STORE_REPO_PAT` | a token that can push to `HarithKavish/store` |

The workflow decodes the keystore into the runner's temp directory, builds,
verifies the APK really is signed with `apksigner`, and deletes the decoded file
whether or not the build succeeded.

## Publishing

`publish-store.yml`, run by hand from the Actions tab, does the whole release:
it reads the latest release tag, bumps the patch version, builds and signs,
tags a GitHub Release, and pushes the APK into `HarithKavish/store` under
`apps/keyboard/mobile/android/keyboard-vX.Y.Z.apk`.

The store's own "Update app manifests" workflow regenerates `latest.json` from
the filename, so nothing here writes a manifest.
