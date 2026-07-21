# Releasing an update

The routine for shipping a new version of CCooldown to GitHub. Colleagues install by
downloading `Release/CCooldown.apk` from the repo, so every release is: bump, build,
copy, commit, push.

## 1. Bump the version

In `app/build.gradle.kts`, increase both:

```kotlin
versionCode = 8        // +1 every release, always
versionName = "0.8"    // what users see in Settings → About
```

Android only offers "update" over an installed app when `versionCode` is higher —
forgetting the bump means colleagues can't install over the old build.

## 2. Build and refresh the APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk Release/CCooldown.apk
```

**Signing (since v0.13).** The app is signed with a permanent release keystore, not the
throwaway debug key. The keystore + its password live in two gitignored files that are
**never committed**: `ccooldown-release.jks` and `keystore.properties` (both at the repo
root). `app/build.gradle.kts` reads them automatically.

- **Back these two files up somewhere safe and permanent.** If you lose them you can never
  publish an update again — every user would have to uninstall and reinstall.
- Because the keystore is stable, every release from v0.13 onward updates in place and keeps
  the user's history and settings. (The one-time exception was v0.12 → v0.13: v0.12 was
  debug-signed with a key that's gone, so that single upgrade required a reinstall.)
- On a fresh clone without the two files, `assembleRelease` still builds but produces an
  **unsigned** APK — restore the keystore files before a real release.

## 3. Update the docs if needed

- `Release/USER-GUIDE.md` — if screens or setup steps changed
- `Release/screenshots/` — if the UI changed visibly
- `README.md` — if features changed

## 4. Commit and push

```bash
git add -A
git commit -m "v0.8 — <one line on what changed>"
git tag v0.8
git push && git push --tags
```

## 5. GitHub Release

Publish the APK to the Releases page (the README's download link points at
`releases/latest`, so this is what colleagues actually install from):

```bash
gh release create v0.8 Release/CCooldown.apk \
  --title "CCooldown v0.8" \
  --notes "<one line on what changed>"
```

The in-repo `Release/CCooldown.apk` copy still works as a fallback either way.
