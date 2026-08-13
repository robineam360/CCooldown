# Releasing an update

The routine for shipping a new version of CCooldown to GitHub. Colleagues install by
downloading the APK from the **Releases page** (the app's *Check for updates* button and
the README both link to `releases/latest`). The APK is **no longer committed to the repo**
— it ships only as a release asset. So every release is: bump, build, commit source, push,
publish release.

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
# Signed APK lands at app/build/outputs/apk/release/app-release.apk — upload it in step 5.
# (No longer copied into release/ or committed.)
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

## 3. Update the docs

The PDFs are generated — never edit them. The sources are `release/docs/src/guide.html`
and `brochure.html`; edit those, then rebuild with one command:

```bash
./release/docs/build.sh
```

It reads `versionName` from `app/build.gradle.kts` and writes three files into
`release/docs/`:

| Output | What it's for |
|---|---|
| `CCooldown-User-Guide-v<ver>.pdf` | the 13-page guide the README and release notes link to |
| `CCooldown-Brochure.pdf` | the 2-page pitch |
| `CCooldown-whats-new-v<ver>.png` | **guide page 3 as a single image** — paste under a Slack post |

The one-pager is *sliced out of* `guide.html` at build time, not authored separately, so
it can't drift. That's deliberate: don't add a fourth "what's new" document — the guide
page already is one.

**⚠️ Read every page you changed in the built PDF.** Each `.page` is a fixed A4 box with
`overflow:hidden`, so content that doesn't fit is **silently clipped, not reflowed**. In
v1.1 four pages overflowed and one lost an entire callout before anyone noticed. Adding a
row to a table, or a card to a grid, is enough to do it.

Also update by hand when the UI or features change:

- `release/USER-GUIDE.md` — the markdown guide (its own version header + changelog)
- `README.md` — feature bullets, and **both PDF links** if the version in the guide's
  filename changed
- `release/docs/src/shots/` — screenshots. Retire any that show a removed feature; a
  figure that contradicts the text is worse than no figure. v1.1's blue-accent
  "themeable" figure is currently absent for this reason — reshoot it with a blue theme
  if you want it back.

## 4. Commit and push

```bash
git add -A          # source + docs only — the APK is gitignored now
git commit -m "v0.8 — <one line on what changed>"
git tag v0.8
git push && git push --tags
```

## 5. GitHub Release

Publish the APK to the Releases page (the README's download link points at
`releases/latest`, so this is what colleagues actually install from):

```bash
gh release create v0.8 app/build/outputs/apk/release/app-release.apk \
  --title "CCooldown v0.8" \
  --notes "<one line on what changed>"
```

The APK is distributed **only** as this release asset — the README, USER-GUIDE, and the
app's *Check for updates* button all point at `releases/latest`, so publishing the release
is what actually ships the update to colleagues.
