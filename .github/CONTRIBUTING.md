# Contributing to CCooldown

Thanks for wanting to help. This is a small, deliberately scoped project — most of the
rules below exist to keep it that way. The full working agreements live in
[CLAUDE.md](../CLAUDE.md); this page is the contributor-facing summary.

## Scope — read this before proposing anything

- **Android only.** The Mac client is a separate repo by deliberate decision — see the
  CCRM-8 (Mac Menu-Bar) entry in [ROADMAP.md](../ROADMAP.md) for the full reasoning,
  which centres on the GitHub `releases/latest` stream being repo-wide. Don't add
  another platform's code here.
- **Do not fork this repo to start another client** — start clean and copy the shared
  contract in. The contract (API spec, behaviour spec, and language-neutral test
  fixtures) lives in the Mac repo under `contract/`; a new client implements against
  the fixtures rather than inheriting an Android codebase it can't use.
- **Claude usage only.** Adding other AI providers is not a roadmap gap; it's out of
  scope.
- ROADMAP.md's appendix records what deliberately does *not* port from similar apps —
  read it before proposing something that was already ruled out.

## Wireframe before UI

Anything that changes what the user sees — a screen, card, widget layout, notification
style, chart element, settings section, or a visible change to an existing one — needs
a **wireframe reviewed and approved before implementation starts**. An ASCII sketch or
a described layout with real numbers is enough; show the states (empty, error, at 100%,
above pace, narrow width), not just the happy path, and wait for an explicit approval —
silence isn't one. Pure logic, parsing, scheduling, tests, docs, and bug fixes that
restore an already-approved design are exempt. Details in [CLAUDE.md](../CLAUDE.md) §2.

## Tracker IDs carry their epic name

Every reference to a tracker ID carries its epic name in brackets on first use in any
message, commit body, or document paragraph — `CCRM-17 (Window Pings)`, never a bare
`CCRM-17`. Feature work is `CCRM-N` in [ROADMAP.md](../ROADMAP.md); defects are
`CCBG-N` in [BUGS.md](../BUGS.md). New IDs are allocated from the highest existing
number in the relevant file, and IDs are never reused or renumbered. Commit *subjects*
use the `feat(CCRM-17): …` convention and need no epic name. Details in
[CLAUDE.md](../CLAUDE.md) §1.

## Building and testing

Requirements: JDK 17, Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug        # APK lands in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests — must pass before you open a PR
```

Pure logic gets unit tests in the existing style (see `PingScheduleTest`,
`SignInExpiryTest`). Match the surrounding code — comment density, naming, Kotlin
idiom.

## Bugs, features, and feedback

- Bugs → open an issue with the **Bug report** template (app version and phone
  model/skin matter — some behaviour, like the `big` notification style, is
  skin-dependent).
- Feature ideas → the **Feature request** template, after a pass over ROADMAP.md and
  its appendix.
- Prefer email? "Share feedback" in the app's About section reaches <robin@eam360.com>.
- Anything security-related → [SECURITY.md](SECURITY.md), **never** a public issue,
  and never with a real token in it.

## Releasing

Contributors don't release; [RELEASING.md](../RELEASING.md) documents the process for
the maintainer. The app is sideload-only — the APK ships exclusively as a GitHub
release asset, never committed to the repo.
