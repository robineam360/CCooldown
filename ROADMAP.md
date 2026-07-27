# Roadmap

Future work for Claude Cooldown. Each item has a stable ID (`CCRM-N`) — use it in
commits and notes. IDs never change or get reused; only status and priority move.

Sections are ordered by when we intend to build them. **Within a section, items are
in priority order.** Bugs live in [BUGS.md](BUGS.md) (`CCBG-N`), not here.

**Status legend:** `Planned` · `Needs design` · `In progress` · `Blocked` · `Done` · `Dropped`

---

## Next — small, high value, ready to build

### CCRM-1 · Show usage credits used / total available
- **Status:** Blocked — needs one raw payload to confirm the fields exist
- **Why:** Percentages answer "how close am I to the limit"; raw credits answer "how
  much have I actually spent".
- **Spike result (2026-07-27):** the assumption that "the numbers are likely already in
  the API response we parse" is **not confirmed**. `UsageParser` reads only `percent`,
  `resets_at`, `severity` (from `limits[]`) and `utilization` (from the `five_hour` /
  `seven_day` fallback shapes) — nothing credit-shaped is parsed today. Whether credits
  are *present in the payload but ignored* is still unknown and can't be checked from
  the dev machine (no token here, by design).
- **How to unblock — no code needed:** the full response body is already cached per
  profile (`UsageCache.rawJson`) and Settings already renders it: tap the version
  number in **About** 7× to unlock **Debug → "Show last raw response"**. Read off
  whether any credit/quota fields exist, then either build against them or drop this
  item as unserveable.
- **Approach (once confirmed):** parse the fields in `data/Models.kt`, surface used /
  total on the main screen and pinned notification. **Only render when total-remaining
  is non-zero** (hide for unlimited/plan-less states so we never show a misleading
  "0 of 0").

### CCRM-2 · Configurable persistent-notification tap action
- **Status:** Done (2026-07-27)
- **Why:** One tap should go where the user wants — straight into Claude, or into this
  app for the full breakdown.
- **Shipped:** `pinnedTapTarget` pref ("app" default / "claude") in `UsageCache`;
  `PinnedNotification.tapIntent()` builds the `contentIntent` from it and falls back to
  our own `MainActivity` whenever the Claude app can't be resolved. Chip selector under
  **Settings → Pinned notification**, with an inline note when Claude isn't installed.
  No manifest change needed — `QUERY_ALL_PACKAGES` is already declared for the browser
  picker, so `getLaunchIntentForPackage` works on API 30+.
- **Package id:** `com.anthropic.claude`, confirmed against the Play Store listing
  (`play.google.com/store/apps/details?id=com.anthropic.claude`).

---

## Needs design — decide the shape before building

### CCRM-3 · Unified theming system for widgets & notifications
- **Status:** Needs design
- **Combines the old "widget themes", "5h widget", and "bigger notification" items.**
  They were three overlapping asks; building theming three separate times is how we'd
  end up with three inconsistent looks. Define **one** set of theme tokens (palette +
  layout + size), then have every surface draw from it.
- **Why now:** The pinned-notification percentage is genuinely too small today — that's
  the most-felt problem in this cluster.
- **Approach — design first, then phase the build:**
  1. **Tokens.** Extend `ui/Palette.kt` into a small theme model (named themes →
     colors, corner/opacity, text scale). One source of truth for widgets + notifications.
  2. **Phase 1 — big-number notification theme** *(ship first; solves the real pain).*
     A settings option exposing notification themes, including one that renders the
     percentage as large as possible, plus a couple of visually distinct variants.
     Lives in `notify/PinnedNotification.kt`.
  3. **Phase 2 — widget themes.** Multiple visual themes for `widget/UsageWidget.kt`
     and `widget/BarWidget.kt`, **including a transparent theme**. Selected in
     `widget/WidgetConfigActivity.kt`.
  4. **Phase 3 — dedicated 5-hour widget.** A widget built around the 5-hour window:
     large centre percentage with a circular fill ring, offered as a few variants
     (one with Claude's mascot inside the ring, plus other themed options).
- **Open questions:** how many themes is enough (avoid a settings zoo); does the mascot
  asset need licensing sign-off before it ships in a widget.

---

## Later — larger, still on the path

### CCRM-4 · Widget long-press quick-edit
- **Status:** Planned
- **Why:** Reconfiguring a placed widget shouldn't mean removing and re-adding it.
- **Approach:** Wire the widget's long-press/reconfigure entry point to relaunch
  `widget/WidgetConfigActivity.kt` pre-filled with that instance's current settings
  (Personal ↔ Work, 5-hour ↔ 7-day, theme from CCRM-3). Uses the standard
  `APPWIDGET_CONFIGURE` "reconfigure existing widget" flow. Depends on CCRM-3 for the
  theme control.

### CCRM-5 · Work section as its own pinned notification
- **Status:** Planned
- **Why:** Today only Personal can be pinned. Heavy Work users want both windows live
  at a glance.
- **Approach:** `notify/PinnedNotification.kt` and `Alerts.kt` already ID-namespace
  Work (`+100`); extend the pinned notification to run one instance per enabled
  profile, each with its own channel/icon/style so they're distinguishable in the
  status bar. Add per-profile "pin this" toggles in settings.

### CCRM-6 · More than two accounts
- **Status:** Needs design
- **Why:** `Profile` is a hard-coded `PERSONAL`/`WORK` enum ([Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt)),
  wired through credentials, cache keys, notification IDs, widgets and alerts. Users
  with 3+ accounts (multiple work orgs, side projects) can't be served.
- **Approach:** Move from a fixed enum to a dynamic profile registry (stable
  string keys + user labels). Touches `CredentialStore`, `UsageCache`, `HistoryStore`,
  the `+100` notification-ID scheme, widget config, and every `Profile.entries` loop.
  Non-trivial — scope it as its own milestone, ideally before CCRM-7 so iOS inherits
  the flexible model instead of the 2-slot one.

---

## Bookends — major efforts, gated on the above

### CCRM-7 · iOS version
- **Status:** Planned · gated → v2.0
- **Why / when:** Build only after most of the above has settled, so we port a stable
  design rather than a moving one. Targeted for v2.0.
- **Approach:** New iOS app. Port the model/repository layer conceptually; reuse the
  API/OAuth learnings. WidgetKit for widgets; iOS Live Activities / notifications are
  the closest analog to the Android pinned notification (there's no true always-on
  persistent notification, so expect a design adaptation here).

### CCRM-8 · Desktop Mac menu-bar app
- **Status:** Planned · gated (last) · **new repo**
- **Why / when:** After everything above. A native Mac menu-bar app showing usage
  progress. New app/repo — carries over learnings from this project, not built here.
- **Approach:** Separate repo. Menu-bar item with the usage ring; reuse the API/OAuth
  approach proven here.

---

## Someday / Maybe

### CCRM-9 · Claude news in the unused space (free sources only)
- **Status:** Idea — needs a free, stable source
- **Context:** Personal and Work each have ~half a page free below the refresh button.
  The old plan was a Twitter/X feed (see CCRM-10) — dropped as paid/fragile. The
  *goal* still stands: surface Claude-related news there.
- **Approach — only if a free, durable source exists:** e.g. Anthropic's news/blog
  **RSS/Atom** feed, or a GitHub releases feed — things with a stable contract that
  won't break every few weeks or require payment. Setting to toggle on/off and pick
  the source. **Do not build against anything that needs a paid API or scraping.**
  Until such a source is confirmed, this stays an idea.

---

## Dropped

### CCRM-10 · Twitter/X feed in unused space
- **Status:** Dropped
- **Why:** Reading a public X timeline reliably now needs a paid API tier, and
  scrape-based approaches break constantly. Not worth coupling the app's stability to
  it. Superseded by CCRM-9 (free-source news only).
