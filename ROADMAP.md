# Roadmap

Future work for Claude Cooldown. Each item has a stable ID (`CCRM-N`) — use it in
commits and notes. IDs never change or get reused; only status and priority move.

Sections are ordered by when we intend to build them. **Within a section, items are
in priority order.** Bugs live in [BUGS.md](BUGS.md) (`CCBG-N`), not here.

**Status legend:** `Planned` · `Needs design` · `In progress` · `Blocked` · `Done` · `Dropped`

---

## Next — small, high value, ready to build

### CCRM-1 · Show usage credits used / total available
- **Status:** Done (2026-07-27)
- **Why:** Percentages answer "how close am I to the limit"; credits answer "how much
  have I actually spent". They're **currency, not a token count** — Claude Code renders
  the same thing as `Usage credits · $9.57 of $50.00` with a bar.
- **Spike result:** the fields were in the payload all along, just never parsed — the
  spike was worth running, since the roadmap's guess at *which* fields was wrong. The
  response carries the figures **twice**:
  - `spend` — `used`/`limit` as `{amount_minor, currency, exponent}`, plus a rounded
    integer `percent`, a `severity`, and an `enabled` flag. Richer; preferred.
  - `extra_usage` — `{monthly_limit, used_credits, currency, decimal_places}`, the same
    numbers in minor units with one shared exponent. Kept as a fallback.
- **Shipped:** `SpendCredits` in `data/Models.kt` (+ `UsageData.credits`), parsed by
  `UsageParser.creditsFrom()` preferring `spend`; `Fmt.money()` in `ui/Palette.kt`;
  its own card on the main screen directly below the 7-day card.
- **Card layout** — deliberately the same shape as the 5-hour card: `Usage credits`
  and `$5.99 / $100.00` on the left, `6% used` bold on the right, themed bar under
  them, remaining-balance line below. The middle amount takes the row's weight so it
  ellipsizes on narrow screens rather than pushing the percentage off.
- **Percentage** is computed from the minor units and **rounded, not truncated** —
  $5.99 of $100 reads 6%, where the window bars' `toInt()` would say 5%. The server's
  own `spend.percent` agrees (6).
- **Visibility rule:** render whenever `limit > 0`. A spent-out balance still shows a
  full bar, with the trailing line switching to red; only accounts with no credit
  budget at all hide the section.
- **Toggles:** `creditsVisible(profile)` (per-profile, default on) and
  `creditsOnWidgets()` (global, default **off**) in `UsageCache`, both under a
  **Usage credits** settings section. Widgets need both — hiding a profile's credits
  hides them everywhere.
- **Widgets:** a `Credits · $5.99 / $100.00 — 6%` bar in the **large** `UsageWidget`
  bucket only. Medium and small are already full; a fourth bar there would clip.
  `LabeledBar` gained an optional `valueText` so credits can pass the rounded figure.
- **Tests:** `UsageParserTest` pins the parse, the rounding, over-limit clamping, and
  the rendered strings against the captured 2026-07-27 payload, both payload shapes,
  and the no-credits case. Needed a real `org.json` on the test classpath (Android's
  is a stub in unit tests).
- **Also shipped:** `BarWidget` offers **Usage credits (pay-as-you-go)** at placement,
  rendering the rounded percentage, the bar, and `$5.99 / $100.00 · $94.01 left`. Not
  gated on `creditsOnWidgets` — that switch is about crowding layouts that show
  something else, and this content was chosen explicitly.
- **Not done:** the pinned notification still shows windows only — it's already tight
  on space (see CCRM-3).
- **Open question:** `spend.enabled` / `extra_usage.is_enabled` are parsed past, not
  acted on. If someone turns extra usage off while keeping a non-zero limit, we still
  draw the bar. Revisit if that state turns out to be reachable.

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
- **Status:** Phase 1 done (2026-07-27) · phases 2-3 still need design
- **Phase 1 shipped — notification styles.** `pinnedStyle` pref, chip selector under
  **Settings → Pinned notification**, four options, default unchanged (`gauge`) so
  nobody's notification moves under them:
  - `gauge` — the original ring.
  - `number` — `drawNumberTile()` puts a solid plate in the large-icon slot with the
    digits at 58% of the bitmap (44% at three digits so `100%` still fits). Roughly
    twice the old number for no platform risk.
  - `progress` — no bitmap: `setProgress()` plus the percentage leading the title.
  - `big` — `notif_big_number{,_expanded}.xml` under `DecoratedCustomViewStyle`, 32sp
    collapsed / 44sp expanded. **32, not the 40 first sketched:** the collapsed content
    area is short and taller text clips on some skins. The bar is a drawn bitmap rather
    than a tinted `ProgressBar`, which sidesteps the RemoteViews tint API differences.
    This is the one style that wants testing on more than one phone.
  - Rejected: a giant-number *expanded-only* panel. It spent the most space on the
    7-day window, which matters less than the 5-hour one.
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

### CCRM-12 · Readable trend chart
- **Status:** Done (2026-07-29)
- **Why:** The sparkline was 44dp of bare line — no scale, no plot points, no marker
  for the present. You couldn't read a value off it or tell where "now" was.
- **Shipped** (`ui/Sparkline.kt`, 96dp canvas):
  - a dot on every real fetch, so polling gaps show instead of being smoothed away;
  - a hairline and larger marker on the newest sample, directly labelled;
  - an **even-pace diagonal** from (start, 0%) to (reset, 100%) — above it means
    usage is outrunning the window;
  - dashed guides at 80/90/100% in the alert colours from `Palette.barColor`, so the
    chart and the notification thresholds agree, labelled in a right-hand gutter;
  - a 14% area fill under the observed curve;
  - the projection as a dashed tail to a hollow, labelled endpoint;
  - an x axis with window start, `now`, and reset — clock time for an hours-long
    window, **dates** for a days-long one (a 7-day window starts and ends on the same
    weekday, so `Fmt.dayTime` printed the same label twice; `Fmt.dayMonth` added);
  - burn rate appended to the caption (`· 0.4%/h`).
- **No more silent absence:** when a gate fails the block says which — "Not enough
  history in this window yet to chart a pace" or "Usage hasn't moved enough yet to
  project a pace". Silence was indistinguishable from a bug, which is exactly how
  CCBG-2 stayed hidden.
- **Open question:** the even-pace diagonal makes the **Days elapsed** bar largely
  redundant — same comparison, done visually. Worth removing that bar.
- **Judgement call to revisit:** a window at 0% with no movement still draws a mostly
  empty chart. It does show where `now` sits in the window, but it may be noise.

### CCRM-11 · Quick Settings tile shows the 5-hour reset
- **Status:** Done (2026-07-27)
- **Why:** The tile spent its one subtitle line on the 7-day percentage. The 5-hour
  reset is the number that changes what you do next.
- **Shipped:** `tileSubtitle` pref — `countdown` ("resets in 2h 14m", default) or
  `clock` ("resets 4:12 PM"), chips under **Settings → Quick Settings tile**. The
  countdown reads better; the clock can't go stale, because the tile only recomputes
  in `onStartListening()` and a shade left open freezes the countdown. `Fmt.timeOnly()`
  added for the day-less rendering, honouring the 24-hour setting.
- **Dynamic icon:** the tile icon is now drawn per-reading and fills as the window
  burns, in whichever status-bar icon style is set. The drawing moved to
  `ui/UsageIcon.kt`, shared with `PinnedNotification`.
- **No theme colour on the tile:** Android tints QS tile icons itself, exactly as it
  does status-bar icons, so the icon is an alpha mask — level shows through fill only.
  The theme and warning colours can't reach it. Left to the system default.
- **Also fixed:** the `startActivityAndCollapse(Intent)` lint error — the deprecated
  overload is still the only option below API 34 and minSdk is 31, so it's suppressed
  with a note rather than removed.

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
