# CCRM-6 (Multi-Account) — a dynamic profile registry

> Implementation plan. Decisions in this file are settled: the registry over a third enum
> slot, and unconfigured profiles losing their tab. Phase 0 (wireframe approval) gates
> everything else.

## Context

The ask is a third Claude account. That is already CCRM-6 (Multi-Account) in
[ROADMAP.md](ROADMAP.md), status *Needs design*, scheduled next after the current
small-items batch, with the concrete driver recorded as real ("the user needs a third
Claude account tracked", decided 2026-08-19). CCRM-6 flags one open fork: a cheap third
enum slot versus a proper registry. **Decision taken: the registry.** It costs more now
and is the version that doesn't get rebuilt when account four arrives.

Today `Profile` is a two-constant enum
([Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt)) wired through
credentials, cache keys, notification IDs, alarm request codes, widgets, tiles and
alerts. The good news from exploration: **persistence is already profile-count-agnostic**
— every store namespaces by `profile.key` (a string), there is no Room/DataStore/proto
schema anywhere, and ~20 call sites already loop `Profile.entries`. The work is a type
change plus a call-site sweep plus four genuine two-profile hardcodes.

Second decision taken: **unconfigured profiles no longer get a tab.** `ProfileTabs` and
`HistoryScreen` switch to `configuredProfiles()`, so someone with one account sees no tab
strip at all and a new account appears the moment its token lands. Signing in stays in
Settings.

## The four real two-profile hardcodes

| Site | Problem |
|---|---|
| [Alerts.kt:505-506](app/src/main/java/com/robin/claudeusage/alerts/Alerts.kt#L505-L506) | `notifId` gives `+100` only to `WORK`; any third profile collides with Personal's IDs and silently overwrites its notifications. |
| [Conditions.kt:81](app/src/main/java/com/robin/claudeusage/notify/Conditions.kt#L81) | `panelFor` folds `Profile.entries.first { it != profile }` — exactly one "other" profile. A third account's re-auth/stale/expiry/event strips would surface nowhere. |
| [UsageTileService.kt:103-105](app/src/main/java/com/robin/claudeusage/tile/UsageTileService.kt#L103-L105) + [AndroidManifest.xml:63-83](app/src/main/AndroidManifest.xml#L63-L83) | QS tiles are statically declared services — Android will not let this be driven by a runtime list. |
| [SettingsScreen.kt:1829, 1913](app/src/main/java/com/robin/claudeusage/SettingsScreen.kt#L1829) | Debug/probe toggles flip-flop `PERSONAL`↔`WORK`; a third profile is unreachable. Dev-tools only. |

Plus copy at [SettingsScreen.kt:328](app/src/main/java/com/robin/claudeusage/SettingsScreen.kt#L328)
("both profiles") and `profile.ordinal` used as a stable identity at
[PingScheduler.kt:134](app/src/main/java/com/robin/claudeusage/ping/PingScheduler.kt#L134)
and [UsageTileService.kt:93](app/src/main/java/com/robin/claudeusage/tile/UsageTileService.kt#L93).

## Phase 0 — wireframe, and wait for approval

CLAUDE.md rule 2 applies: this changes the accounts screen, the tab strip, the pinned
panel and the widget picker. The review HTML goes in the shared `design/` folder, not in
chat, and questions come one at a time. Nothing below gets built until the wireframe is
approved.

States the wireframe must show — not just the happy path:

1. **Accounts section**: 2 accounts (today's look), 3 accounts, the `+ Add account` row,
   and per-card **Remove account** with a confirmation naming exactly what goes (token,
   cached usage, the 8-day history *and* the year-long session log).
2. **Does the separate "Profile names" section survive?** Names become registry-owned, so
   that section now duplicates something that belongs in the account card. Recommend
   folding rename into the card; needs a decision on the wireframe.
3. **Tab strip**: 0 configured (fallback state), 1 configured (no strip), 2, 3, and 4 —
   including the narrow cover-screen width where a fixed `TabRow` stops fitting and has to
   become scrollable.
4. **Pinned "Show profile" chips** at 3 and 4 — the current `Row` does not wrap.
5. **Pinned panel folding 2+ other profiles** against `MAX_STRIPS = 3`: which strips win
   and what the `+ n more` line reads as.
6. **Widget config profile picker** at 4 entries; **History screen** tabs.
7. **QS tile picker** entries, given the static-label constraint in Phase 3.

## Phase 1 — the registry

`Profile` stops being an enum and becomes a value type, which keeps the ~40 call sites
that only read `profile.key` / `profile.label` compiling unchanged:

```kotlin
data class Profile(val key: String, val slot: Int, val label: String)
```

- **`key`** — stable string namespacing every store (`"personal"`, `"work"`, `"p3"`…).
  Never reused, never renumbered.
- **`slot`** — stable small int allocated once from a persisted monotonic counter, never
  reused. Sole source of the notification-ID offset, PendingIntent request codes and alarm
  request codes. Slots 0 and 1 **must** stay pinned to `personal`/`work` or every placed
  widget, armed alarm and posted notification on existing installs breaks.
- **`label`** — the user's name for it (16 chars, as today).

New `ProfileRegistry(context)` alongside `UsageCache`, on its own `"profiles"`
SharedPreferences file (one JSON array + a `nextSlot` int):

- `all(): List<Profile>` in stored order · `byKey(key: String?): Profile?` ·
  `first(): Profile` (the `Profile.fromKey` fallback, now registry-backed)
- `add(label): Profile` — mints `key = "p$nextSlot"`, `slot = nextSlot++`
- `rename(key, label)` · `remove(key)`
- **Seed migration**, idempotent, run on first read: emit `personal`/slot 0 and
  `work`/slot 1, taking labels from the existing `customLabel` prefs and falling back to
  "Personal"/"Work". Seed *both* unconditionally — existing installs have both slots
  whether or not Work has a token.

`UsageCache.profileLabel(profile)` stays as the read path used by ~40 sites, but resolves
through the registry by key so a rename is visible to a `Profile` captured earlier in a
composition. `setProfileLabel` delegates to `registry.rename`.

**No data migration.** `CredentialStore.k()` and `UsageCache.k()` keep the legacy
unprefixed-Personal exception, restated as `if (profile.key == "personal")`.
`HistoryStore`/`SessionLog` already derive filenames from `profile.key`.

## Phase 2 — call-site sweep

- All 20 `Profile.entries` sites → `registry.all()`. Composables read it via
  `LocalContext`; receivers/services/`UsageRepository` already hold a context. Add
  `UsageRepository.profiles()` next to the existing
  [`configuredProfiles()`](app/src/main/java/com/robin/claudeusage/data/UsageRepository.kt#L102)
  so the repo stays the single entry point for UI code.
- `Alerts.notifId` → `kind + profile.slot * 100`. Kinds top out at 31
  (`PACE_WEEKLY_KIND`), so the ×100 stride is safe, and slots 0/1 reproduce today's IDs
  exactly — no notification churn on upgrade.
- `PingScheduler.pendingIntent` → `base + profile.slot`;
  `BaseUsageTileService` PendingIntent request code → `profile.slot`.
- `Conditions.panelFor` → fold **every** other profile: `registry.all().filter { it != profile }`,
  each one's re-auth/stale/expiry strips prefixed with its label and its `foldedEvents`
  merged into the same newest-first sort. Keep the existing ordering contract (faults ·
  events · warnings · update last) and the `MAX_STRIPS` overflow count.
- `ProfileTabs` ([MainActivity.kt:364](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L364))
  and `HistoryScreen` ([:60](app/src/main/java/com/robin/claudeusage/HistoryScreen.kt#L60))
  → `configuredProfiles()`, hiding the strip entirely at one profile and falling back to
  `registry.first()` at zero. `RingWidget`'s `multiProfile` label test already counts
  configured profiles and needs no change beyond the source list.
- `Shortcuts.publish` → configured profiles first, then "Refresh now", **capped** at
  `ShortcutManager.maxShortcutCountPerActivity` (typically 5). Today's unbounded
  `entries.map` silently overflows at four accounts.
- Debug section and `EndpointProbe` → cycle `registry.all()` instead of the two-way flip.
- Copy: "both profiles" → "every profile".

## Phase 3 — Quick Settings tiles, and the one documented limit

Static declaration is an OS constraint, so tiles get a **fixed pool of four**, bound to
*slots* rather than enum constants:

- Keep the `PersonalTileService` / `WorkTileService` **class names** — renaming a declared
  service breaks tiles the user has already placed — but rebind them to slots 0 and 1.
- Add `Slot2TileService` / `Slot3TileService` plus two manifest `<service>` blocks.
- A tile whose slot has no profile reports `Tile.STATE_UNAVAILABLE`.
- `android:label` is static and cannot follow a rename, so the two new entries read
  "Claude account 3" / "Claude account 4" in the tile picker. The runtime `tile.label`
  keeps using the live profile label, as it does today.

**Limit to record in the roadmap:** accounts beyond the fourth work on every surface
except a QS tile.

## Phase 4 — removal semantics

`remove(key)` is the part that can leave orphans, and it is the only genuinely
destructive path here. In order: cancel ping + verify alarms → cancel the slot's
notification ID range → clear credentials → clear the cache keys → delete the history and
session JSONL files → repoint `pinnedProfile` and any `WidgetPrefs` entry aimed at the
dead key → republish shortcuts → refresh widgets and the pinned panel. Behind an explicit
confirmation naming what goes.

This gives [`HistoryStore.clear()`](app/src/main/java/com/robin/claudeusage/data/HistoryStore.kt#L45)
and `SessionLog.clear()` their **first callers** — both are noted as callerless in CCRM-14
(Clear History). Removal partly overlaps CCRM-14, and the "switching the account behind a
profile slot" case CCRM-14 exists for is now served by remove-then-add. Note the overlap
in CCRM-14 rather than closing it — CCRM-14 is still the answer for clearing history
*without* dropping the account.

Because slots are never reused, a stale placed widget or tile can never inherit a new
account's data.

## Files

**New:** `data/ProfileRegistry.kt`, additions in
[UsageTileService.kt](app/src/main/java/com/robin/claudeusage/tile/UsageTileService.kt),
`app/src/test/java/com/robin/claudeusage/data/ProfileRegistryTest.kt`.

**Core:** [Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt),
[UsageCache.kt](app/src/main/java/com/robin/claudeusage/data/UsageCache.kt) (`k()`,
`profileLabel`, `pinnedProfile`, `WidgetPrefs.profileFor`),
[CredentialStore.kt](app/src/main/java/com/robin/claudeusage/data/CredentialStore.kt) (`k()`),
[UsageRepository.kt](app/src/main/java/com/robin/claudeusage/data/UsageRepository.kt),
[Alerts.kt](app/src/main/java/com/robin/claudeusage/alerts/Alerts.kt),
[Conditions.kt](app/src/main/java/com/robin/claudeusage/notify/Conditions.kt),
[PingScheduler.kt](app/src/main/java/com/robin/claudeusage/ping/PingScheduler.kt),
[AndroidManifest.xml](app/src/main/AndroidManifest.xml).

**Sweep (mechanical, same pattern):**
[MainActivity.kt](app/src/main/java/com/robin/claudeusage/MainActivity.kt),
[SettingsScreen.kt](app/src/main/java/com/robin/claudeusage/SettingsScreen.kt) (the
largest — accounts, names, per-profile alerts, pinned picker, debug, probe),
[HistoryScreen.kt](app/src/main/java/com/robin/claudeusage/HistoryScreen.kt),
[Shortcuts.kt](app/src/main/java/com/robin/claudeusage/Shortcuts.kt),
[work/Polling.kt](app/src/main/java/com/robin/claudeusage/work/Polling.kt),
[widget/WidgetConfigActivity.kt](app/src/main/java/com/robin/claudeusage/widget/WidgetConfigActivity.kt),
[widget/RingWidget.kt](app/src/main/java/com/robin/claudeusage/widget/RingWidget.kt).

**Docs:** CCRM-6 (Multi-Account) status → *In progress*, with the registry decision, the
four-tile cap and the shortcut cap recorded; cross-references added to CCRM-14 (Clear
History), CCRM-31 (Combined Total) and CCRM-5 (Per-Profile Notification), all of which
say "both accounts" today.

## Verification

**Unit** (`./gradlew test`) — new `ProfileRegistryTest`: seed is idempotent and pins
slots 0/1 to `personal`/`work`; `nextSlot` never reissues a slot after a remove;
rename/`byKey`/fallback; and `notifId` produces no collision across four slots for every
kind in use (1–8, 10+n, 30, 31).

**Device** (Fold 7 — note wireless adb ports rotate, so re-pair before assuming a stale
port):

1. Upgrade over an existing 2-account install: both accounts intact, labels preserved,
   placed widgets still pointing at the right account, armed pings still armed.
2. Add a third account, sign in, confirm: third tab appears, usage fetches, history file
   `usage-history-p2.jsonl` created, a third QS tile is placeable.
3. Pinned panel on the third profile: the other two profiles' strips fold in, prefixed
   with their labels, `+ n more` correct at overflow. Verify in the **Huge number** style
   first — it is the style actually in use.
4. Fire an alert on the third profile with the panel off: it posts standalone and does not
   cancel Personal's notification (the old ID collision).
5. Sign out of Work only: its tab disappears, the third stays, no crash.
6. Remove the third account: no orphan notification, no orphan alarm, JSONL files gone,
   any widget aimed at it falls back cleanly, tile reports unavailable.
7. Narrow cover screen at 3 and 4 accounts: tab strip and pinned chips behave as
   wireframed.
