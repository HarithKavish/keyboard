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
already has it. So one key is kept for the life of the app.

That key is the ecosystem's shared release key, the one every app in
`HarithKavish/store` is signed with. It lives in the owner's secret vault
(`secretctl`, `STORE_ANDROID_*`) and reaches this repository only as the
secrets below. Do not generate a new one.

Keep any copy of the keystore out of the repository — `.gitignore` already excludes
`*.jks`, `*.keystore` and `keystore.properties`. Losing it means the app can
never be updated again, only republished under a new application id.

### Build with it locally

Create `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Then `./gradlew assembleRelease`.

### Build with it in CI

`publish-store.yml` reads five repository secrets and refuses to run if any is
missing:

| Secret | What it holds |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the keystore, base64 |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_ALIAS` | the key alias |
| `ANDROID_KEY_PASSWORD` | the key password |
| `STORE_REPO_PAT` | a token that can push a branch to `HarithKavish/store` and open a pull request there |

The workflow decodes the keystore into the runner's temp directory, builds,
verifies the APK really is signed with `apksigner`, and deletes the decoded file
whether or not the build succeeded.

## Publishing

`publish-store.yml`, run by hand from the Actions tab, does the whole release:
it reads the latest release tag, bumps the patch version, builds and signs,
tags a GitHub Release, and opens a pull request into `HarithKavish/store`'s
`development` adding `apps/keyboard/mobile/android/keyboard-vX.Y.Z.apk`. The pull
request merges itself once the store's gate passes, and the build reaches
store.harithkavish.com when `development` is promoted to `main`.

Nothing is committed back here: the version is set in the runner's workspace
from the latest release tag (the first release ships the `versionName` in
`app/build.gradle.kts`), because protected branches take changes only through
pull requests. The store generates `latest.json` from the filename when it
deploys, so nothing here writes a manifest.
