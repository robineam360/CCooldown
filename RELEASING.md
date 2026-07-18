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
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk Release/CCooldown.apk
```

(The app ships as a debug-signed APK for sideloading. All installs must keep the same
signing — mixing machines that build the APK changes the debug key and forces users to
uninstall first.)

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

## 5. Optional: GitHub Release

For a nicer download page, publish the APK as a GitHub Release: repo page →
Releases → "Draft a new release" → choose the `v0.8` tag → attach
`Release/CCooldown.apk` → publish. The in-repo copy still works either way.
