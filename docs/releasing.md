# Releasing the app

Pushing a version tag builds a signed APK and publishes it on the repository's Releases page,
where anyone can download it without a GitHub account. The workflow is
`.github/workflows/release.yml`.

## Create the signing key (once)

Android only installs an update if it is signed with the same key as the installed app. Every
release must use the same key, so create it once and keep it safe. If the key is lost, testers
have to uninstall the app before they can install a new version.

`keytool` comes with Java and with Android Studio (on Windows it's in
`C:\Program Files\Android\Android Studio\jbr\bin`). It asks for a password and a name; the
name is shown in the certificate but nowhere in the app.

```
keytool -genkeypair -v -keystore in-kino-release.jks -alias in-kino -keyalg RSA -keysize 4096 -validity 10000
```

Keep `in-kino-release.jks` and its password in a password manager, and make a second copy
somewhere safe. Never commit it; `.gitignore` already excludes `.jks` files.

## Add the key to GitHub (once)

Convert the key file to text and copy it to the clipboard:

- Windows (PowerShell):
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("in-kino-release.jks")) | Set-Clipboard`
- macOS: `base64 -i in-kino-release.jks | pbcopy`
- Linux: `base64 -w0 in-kino-release.jks`

In the repository, go to **Settings** → **Secrets and variables** → **Actions** and add four
repository secrets:

| Name | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The text you just copied |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
| `ANDROID_KEY_ALIAS` | `in-kino` |
| `ANDROID_KEY_PASSWORD` | The same password again (keytool uses one password for both) |

## Publish a release

1. Update `VERSION` and add a matching section to `CHANGELOG.md`.
2. Commit, push, and wait for CI to pass.
3. Tag the commit and push the tag:

   ```
   git tag v0.8.0
   git push origin v0.8.0
   ```

The workflow checks that the tag matches `VERSION`, runs the unit tests, builds and verifies
the signed APK, and publishes a pre-release with the APK, its SHA-256 checksum, and notes made
from `.github/release-notes.md` and the changelog section. To redo a failed release, delete
the release and the tag on GitHub, fix the problem, and push the tag again.
