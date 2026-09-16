# Android release signing

The Ludex Android updater installs APKs from GitHub Releases tagged as `android-v<version>`.

For Android to accept an update over an existing release installation, every release APK must be signed by the **same persistent key**. Do not commit that private key to the repository.

## One-time setup

Create and back up a release keystore locally:

```bash
keytool -genkeypair -v \
  -keystore ludex-release.jks \
  -alias ludex \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Keep `ludex-release.jks` in a private backup. Losing it means future APKs cannot update existing Ludex installations.

Add these repository Actions secrets in **Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: base64 of the complete `ludex-release.jks`
- `ANDROID_STORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: normally `ludex`
- `ANDROID_KEY_PASSWORD`: key password

PowerShell helper for the first secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("ludex-release.jks")) | Set-Clipboard
```

Linux/macOS:

```bash
base64 < ludex-release.jks | tr -d '\n'
```

Do not paste the keystore or passwords into issues, commits, chat logs, or the public repository.

## Release flow

When `main` receives an Android change:

1. `.github/workflows/android-release.yml` validates all signing secrets.
2. It builds `:app:assembleRelease`.
3. `apksigner` verifies the resulting APK.
4. The workflow creates tag `android-v<versionName>`.
5. It publishes:
   - `Ludex-Android-<version>.apk`
   - `Ludex-Android-<version>.apk.sha256`
6. The in-app updater reads those releases, downloads the APK, verifies SHA-256, and opens the Android package installer.

The workflow deliberately fails if signing is not configured. A green workflow that silently skipped the APK made the updater look broken while no release existed.

## First migration from CI debug builds

Old CI debug APKs were not signed with the persistent release key. Android does not allow replacing an installed APK with another APK signed by a different certificate.

For the first signed release only:

1. Open Ludex and export the library/sync JSON.
2. Uninstall the debug build if Android rejects the signed APK.
3. Install the signed GitHub Release APK.
4. Import the library again.

After that one-time migration, future signed releases can update in place and retain app data.
