# Roadmap

Future work for Claude Cooldown. Each item has a stable ID (`CCRM-N`) — use it in
commits and notes. IDs never change or get reused; only status and priority move.

Sections are ordered by when we intend to build them. **Within a section, items are
in priority order.** Bugs live in [BUGS.md](BUGS.md) (`CCBG-N`), not here.

**Status legend:** `Planned` · `Needs design` · `In progress` · `Blocked` · `Done` · `Dropped`

---

## Next — small, high value, ready to build

### CCRM-20 · Wide Chart — one profile at full width, and a chart you can touch
- **Status:** Done (2026-08-04) · successor to CCRM-12
- **Verified on the Fold 7's inner screen** (1968×2184 @ 420dpi = **750×832dp**, which
  confirms the ~750dp figure this file has been asserting, and that MEDIUM is the right
  class for it). Chart went from ~335dp to **~655dp** wide — the 2× the table below
  predicted. Tap, tap-to-clear, and long-press scrub all confirmed against real history;
  the pager hand-off confirmed by swiping the chart and landing on Work.
- **Why the wide layout is wrong today:** unfolding the Fold makes the chart *smaller*.
  `ProfileTabs` early-returns to `ProfilePanes` at any `twoPane` width
  ([MainActivity.kt:281](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L281)),
  which splits the window into two scrolling profile columns. Measure both screens:

  | | chart width |
  |---|---|
  | Cover (~410dp): column − 20dp padding ×2 − card 16dp ×2 | ~338dp |
  | Inner (~750dp): pane 375dp − 20dp padding ×2 − card 16dp ×2 | ~335dp |

  You pay the fold and the thing you actually read doesn't grow at all. That's true
  regardless of the use-case argument, and it's the real defect.
- **Decision: one profile, full width, swipe for the other.** This reverses the note at
  [MainActivity.kt:279](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L279) —
  "both accounts at once is the whole point of the app". It isn't, in practice: the
  question you open the app with is "how much have I got left *right now*", about the one
  account you're about to spend. Both-at-once is a comparison you rarely make, and paying
  for it in halved chart width on the only screen wide enough to draw a good chart is the
  wrong trade. A compact both-accounts summary strip was considered and rejected as a
  half-measure; the tab strip already says which profile you're on, and a swipe reaches
  the other. **Both accounts at once still exists on the home screen** — that's what the
  widgets are for, and they're the surface where the glance actually happens.
- **What one column buys:** ~678dp of chart on the inner screen, 2× today.
- **Approach:**
  1. Delete the `twoPane` branch in `ProfileTabs` and the `ProfilePanes` composable with
     it. Tabs + `HorizontalPager` then serve every width. `WidthClass.twoPane` stays —
     `HistoryScreen` still uses it.
  2. New `ChartColumnMaxWidth = 760.dp` in `ui/Adaptive.kt`, passed to the pager's
     `ContentColumn`. Deliberately unconditional: on a phone it never binds, so there's no
     branch to reason about. It **overrides the `ContentMaxWidth = 640` reasoning**
     ([Adaptive.kt:56](app/src/main/java/com/robin/claudeusage/ui/Adaptive.kt#L56)) for
     MAIN only — that cap protects bars and prose from stretching, and here the chart is
     the payload. The doc comment has to say so, or the next reader will "fix" it back.
  3. Size the chart to the window instead of a hardcoded 192dp
     ([MainActivity.kt:627](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L627)):
     `(width * 0.35f).coerceIn(180.dp, 300.dp).coerceAtMost(windowHeight * 0.45f)`.
     678×192 is 3.5:1 and reads as letterboxed. `ProvideWidthClass` already sits in a
     `BoxWithConstraints`, so it can publish `LocalWindowHeight` beside the width for
     free — and that height clamp is what stops a **landscape phone** (also MEDIUM width,
     but ~400dp tall) getting a chart taller than its window.
- **Touch: tap to inspect, long-press to scrub.** The chart has no `pointerInput` at all
  today. Tap selects the nearest sample and shows a callout; tapping it again clears it.
  - **Load-bearing refactor:** every coordinate lives inside the `Canvas` lambda, so a hit
    test can't ask "which sample is at x=340?". Extract `plotRight`/`plotTop`/`plotBottom`/
    `x(t)`/`y(pct)`/`nearestSample(px)` into a plain `SparkGeometry(size, density,
    windowStartMs, windowEndMs)`. The draw pass builds it from `size`; the gesture handler
    builds it from a size captured via `onSizeChanged`. Same function of the same inputs,
    so they cannot disagree — and `nearestSample` becomes unit-testable with no Compose UI.
  - **Snap to real samples, never interpolate.** The chart exists to make polling gaps
    visible ([Sparkline.kt:33](app/src/main/java/com/robin/claudeusage/ui/Sparkline.kt#L33));
    a readout that invented a value inside a gap would undo the feature.
  - **Why the scrub is long-press-gated.** A plain horizontal drag would fight the
    `HorizontalPager` for the pointer on the cover screen.
    `detectDragGesturesAfterLongPress` claims the pointer only after the press, so
    ordinary swipes still page. Haptic tick on engage, so the mode change announces
    itself. Two separate `pointerInput` modifiers, drag registered first.
  - **Do not use `detectTapGestures` here** — found on device, 2026-08-04. It calls
    `down.consume()` unconditionally, and an ancestor sees pointer events only *after*
    its descendants, so that single consume stopped the `HorizontalPager` ever starting:
    swiping across the chart did nothing while swiping across the usage bar 20dp above
    it paged to the other profile. Gating the scrub behind a long press was meant to
    protect paging, and the tap handler broke it anyway. Replaced with a hand-rolled
    `awaitEachGesture` + `waitForUpOrCancellation()` that never consumes the down; a
    null return means the pager (or the vertical scroll, or our own scrub) claimed the
    gesture, and a real up within touch slop is the tap.
  - **`longPressFired` flag.** `onDragStart` selects a point but consumes nothing, so a
    long press *released without moving* looked exactly like a tap to the handler above
    and toggled the fresh selection straight back off — haptic, then nothing. The flag
    is cleared on down, set at the long-press timeout, and read on release, which is a
    deterministic order.
  - **Selection stores the sample's timestamp, not its index**, so it survives a poll
    appending a new sample rather than silently sliding to a different point.
  - **Callout** drawn in-canvas like the existing labels: ring on the selected dot, a
    crosshair visually distinct from the "now" hairline, and a two-line pill —
    `14:32` / `47% · +6 vs pace`. The pace delta is the number worth paying a tap for:
    it's the one thing the static chart can't tell you at an *arbitrary* point. Pill
    clamps inside the plot and flips side near the right edge; suppress the "now" label on
    collision, as
    [Sparkline.kt:218](app/src/main/java/com/robin/claudeusage/ui/Sparkline.kt#L218)
    already does for the projection label.
  - **Semantics:** the Canvas has no `contentDescription`. Add a one-line summary of
    latest %, pace and projection while we're in the file.
- **Testing:** `SparkGeometry` gets unit tests (nearest-sample snapping, edge clamping).
  The tap/long-press detector interaction and the pager hand-off are device checks, not
  unit-testable. **CCRM-15's debug synthetic above-pace series earns its keep here** —
  it's the only way to see the callout against the amber overshoot fill, since every
  window on both accounts currently sits below pace.
- **Not in scope, deliberately:** `HistoryScreen` keeps its 5-hour | 7-day side-by-side
  ([HistoryScreen.kt:119](app/src/main/java/com/robin/claudeusage/HistoryScreen.kt#L119)) —
  that's two *different* bar charts using the width, not one chart halved. Settings keeps
  `hasTwoColumns`. A two-column MAIN layout at ≥1100dp (real tablet, where each column
  would still be ≥500dp) is a later question — at 750dp it would put us straight back to
  ~345dp columns, which is the bug being fixed.

### CCRM-1 · Credits Display — show usage credits used / total available
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
- **Known gap:** `spend.enabled` / `extra_usage.is_enabled` are parsed past, not acted
  on — filed as [CCBG-3](BUGS.md).

### CCRM-2 · Notification Tap Target — configurable persistent-notification tap action
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

### CCRM-16 · Sign-in Expiry Accuracy — correct the token-family expiry when renewal dies early
- **Status:** Planned · small
- **Why:** For a native sign-in we don't know when the refresh-token family actually
  expires — the token response omits it, so we display sign-in time + a flat 30-day
  guess (`OAuthSignIn.ESTIMATED_FAMILY_MS`,
  [OAuthSignIn.kt:39](app/src/main/java/com/robin/claudeusage/data/OAuthSignIn.kt#L39)).
  If Anthropic shortens family lifetime, the account card keeps showing a confident
  "expires around <date>" weeks after renewal has already started failing. The estimate
  is only ever revised downward on a *pasted* token; a native slot deliberately keeps
  its clock through rotation ([UsageRepository.kt:300](app/src/main/java/com/robin/claudeusage/data/UsageRepository.kt#L300)),
  so nothing corrects it.
- **Trigger for filing:** Claude Code desktop now periodically re-verifies identity for
  resumable sessions (passkey prompt, seen 2026-07-30). That's session-level and does
  **not** touch our phone-minted family — the widget was unaffected — but it signals
  session lifetimes tightening for the same `client_id`, so the 30-day guess is worth
  making self-correcting before it silently goes wrong.
- **Approach:** treat a `REAUTH_NEEDED` (or a `firstRefreshFailAt` streak crossing
  `STUCK_REFRESH_MS`) that lands materially before the estimate as evidence the estimate
  is wrong: stop rendering "expires around" as a date and say renewal has stopped
  working, with the observed sign-in→death interval. Persisting that interval as the new
  estimate for subsequent sign-ins would make the app learn the real cap — worth doing
  only if we see it happen more than once, since one revocation isn't a lifetime.
- **Also:** the ~30-day figure has never been verified against an actual expiry. A
  debug line showing the age of the current family (sign-in → now) would tell us the
  real number the first time a family dies of old age rather than revocation.
- **Not in scope:** anything about passkeys at authorize time. Sign-in runs in a Custom
  Tab, so a WebAuthn challenge on `claude.com/cai/oauth/authorize` is handled by the
  browser and its credential provider, not by us — no app change needed, as long as the
  user's passkey is reachable from the phone. Only revisit if the authorize page starts
  requiring a factor a Custom Tab can't satisfy; then the fallback is the existing
  copy-the-desktop-sign-in path (README §"If the phone can't complete the sign-in").

### CCRM-4 · Widget Quick-Edit — reconfigure a placed widget by long-press
- **Status:** Planned · small · **prerequisite for CCRM-3 phase 2**
- **Moved up from *Later* on 2026-07-30.** CCRM-3 phase 2 gives each widget a layout, a
  background and an accent. A cosmetic setting you can only change by deleting the widget
  and adding it again is worse than not shipping it, so this now leads that work rather
  than trailing it.
- **Why:** Reconfiguring a placed widget shouldn't mean removing and re-adding it.
- **Approach:** Wire the widget's long-press/reconfigure entry point to relaunch
  `widget/WidgetConfigActivity.kt` pre-filled with that instance's current settings
  (Personal ↔ Work, the bar kind, and the CCRM-3 layout/background/accent). Half the work
  is already done and unused: `WidgetPrefs` is keyed by `appWidgetId`, but `ConfigScreen`
  ignores it and hardcodes its initial state
  ([WidgetConfigActivity.kt:102](app/src/main/java/com/robin/claudeusage/widget/WidgetConfigActivity.kt#L102)),
  so the screen needs to read back rather than gain storage. Uses the standard
  `APPWIDGET_CONFIGURE` "reconfigure existing widget" flow, which also needs
  `android:widgetFeatures="reconfigurable"` in both `usage_widget_info.xml` and
  `bar_widget_info.xml`.
- **Also:** the confirm button reads "Add widget" unconditionally — it needs to say
  "Save changes" when reconfiguring, and the screen wants a "use my defaults" escape back
  to the CCRM-3 defaults.
- **Was:** "Depends on CCRM-3 for the theme control." Inverted — CCRM-3 depends on this.

---

<!--
CCRM-21 … CCRM-38 came out of a 2026-08-04 review of OpenQuota
(github.com/deviffyy/OpenQuota, MIT, Tauri 2 + Rust + Svelte) — a desktop tray app for
Windows/Linux/macOS that aggregates ten AI coding providers. Its Claude payload mapping is
a near-sibling of ours (same `five_hour` / `seven_day` / `limits[]` / `extra_usage`), so the
transferable value is *product surface and behaviour*, not data access. Read the "Not
portable" note at the end of this file before mining it again.

**Dropped from that review deliberately:** its whole multi-provider premise (Codex, Cursor,
Copilot, Antigravity, OpenCode, Devin, Grok, OpenRouter, Z.ai). Claude Cooldown is a Claude
app. Even setting intent aside, six of those read local CLI credential files that don't
exist on a phone, and the two that *would* port (OpenRouter and Z.ai, both plain pasted API
keys) would still make this a different product. Not filed, not an open question.
-->

### CCRM-21 · Pace Alerts — warn on the projection, not just the absolute percent
- **Status:** Done (2026-08-07) · wireframe approved same day
- **Shipped:** ladder and transition rules as pure functions in `data/Projection.kt`
  (`paceSeverity`, `paceSatisfied`, `paceStep`), evaluated per window per profile from
  `Alerts.checkPace` on every poll; new `pace_alerts` channel; Settings → Alerts block
  with a master switch (default on) and the three milestone toggles. All five guards
  below implemented and pinned by 12 cases in `PaceTest` (97 tests, 0 failures) —
  primed-never-fires, drift-vs-real-reset via `Projection.sameWindow`, hysteresis
  re-arm, young-window suppression (1% of period min 60s, and under 5% used), and
  delivery-failure rollback. One notification id per window, so an escalation replaces
  in place; the most severe newly-fired milestone is the headline. Milestone maths:
  Will Run Out = projection crosses 100% before the reset; Cutting It Close =
  projected ≥85% at reset (`PACE_CLOSE_AT_RESET`); Almost Out = under 10% of quota
  left (`PACE_ALMOST_OUT_USED`). A null projection (not enough signal) can never be
  CLOSE or RUNNING_OUT — no projection, no verdict about the future.
- **Deliberately not shipped:** `alwaysShowPacing` — a display preference, not an
  alerting one; it belongs with CCRM-22 (Used or Left)'s display-token batch. Flagged
  at wireframe review and approved out.
- **Settings block seen on hardware** (2026-08-07, Fold 7 inner screen, two-column
  settings): renders per the approved wireframe — master on with the three milestone
  rows, and the master-off state dims all three and disables their switches. **Still
  unobserved: a real milestone notification.** CCRM-15 (Above-Pace Verification)'s
  synthetic above-pace series is the way to fire one on demand; until that exists the
  first Will Run Out in the wild is the check.
- **Was:** Planned · medium · **highest-value item from the OpenQuota review**
- **Why:** Our alerts only fire on absolute thresholds — `sessionAlertThresholds` 80/95,
  weekly 90, model caps 90 ([UsageCache.kt:134](app/src/main/java/com/robin/claudeusage/data/UsageCache.kt#L134)).
  That tells you where you *are*, never where you're *heading*. Burning a 5-hour window in
  40 minutes is the situation worth interrupting someone for, and at 35% used we say
  nothing. We already compute the whole projection for CCRM-12's chart and
  `Projection` — the number exists and never leaves the screen.
- **Three milestones, separately toggleable** (OpenQuota's `Milestone`, and its copy is
  good):
  - **Almost Out** — under 10% of the window remaining.
  - **Cutting It Close** — projected to finish *near* the limit.
  - **Will Run Out** — projected past 100% before the reset.
- **Severity ladder:** `Untracked` → `Healthy` → `Close` → `RunningOut` → `Spent`, from
  `used / elapsedFraction` — the same maths our chart's even-pace diagonal already draws,
  which is why the chart and the alerts will agree for free.
- **Copy the state machine, not just the thresholds.** `src-tauri/src/pacing.rs` has five
  guards that each exist because of a way this feature gets annoying, and re-deriving them
  from scratch would mean re-learning them from the user's notification shade:
  1. **A `primed` flag** — the first observation of a window never alerts. Otherwise
     enabling the feature (or a reboot, or any process death) fires the full backlog for a
     window that was already at 95% and which the user already knows about.
  2. **Dedupe keyed on `resets_at`**, cleared when the reset genuinely advances — the same
     lesson as [CCBG-4](BUGS.md), so it must go through `Projection.sameWindow` rather than
     comparing timestamps, or drift will re-fire it every poll.
  3. **Hysteresis** — a milestone re-arms only when severity actually *drops* past it, so a
     window hovering on a boundary doesn't alternate.
  4. **Young-window suppression** — no projection until 1% of the period (min 60s) has
     elapsed, and none at all under 5% used. A 5-hour window at 2% after four minutes
     projects to 150% and means nothing.
  5. **Rollback on delivery failure** — if the notification doesn't post, un-fire the
     milestone so it retries rather than being silently lost.
- **Also:** an `alwaysShowPacing` pref (their `always_show_pacing`) for whether the pace
  readout shows always or only once it's saying something.
- **Lands in:** `alerts/Alerts.kt` beside the existing threshold evaluation, with the
  ladder and the transition rules in `data/Projection.kt` as pure functions so they're
  unit-testable the way `PingSchedule` is.
- **Keep the absolute thresholds.** These are a second, orthogonal signal — "close to the
  wall" vs "moving too fast" — exactly the split CCRM-12 made deliberately across two
  visual channels. Don't replace one with the other.

### CCRM-22 · Used or Left — one global "consumed vs remaining" preference
- **Status:** Planned · small
- **Why:** The app only ever says "47% used". Half the people who look at this want "53%
  left", and it's the same number. OpenQuota ships `UsageDisplay::{Used, Left}` and
  defaults to **Left**, which is worth noting — they think remaining is the more natural
  reading of a quota, and they may be right.
- **Approach:** one token, read by every render site rather than reimplemented per surface:
  the main screen bars, the credits card, both widgets, the pinned notification, the tile,
  and the chart's callout. Belongs in `ui/Palette.kt`/`Fmt` next to the 24-hour setting.
  Default **Used**, so nobody's existing reading flips under them.
- **Watch:** the warning colours stay keyed on *used* percent regardless of the display
  mode, or a red bar will sit next to "8% left" and read as backwards.

### CCRM-23 · Reset Display — countdown or clock time, on every surface
- **Status:** Planned · small · pairs with CCRM-22
- **Why:** We already built this and scoped it to one surface — `tileSubtitle`, countdown
  vs clock, from CCRM-11. The reasoning there (the countdown reads better; the clock can't
  go stale) applies to the whole app, and the widgets and notification currently have no
  say.
- **Approach:** generalise `tileSubtitle` into a global `resetDisplay` token; the tile keeps
  reading it and stops owning it. Mostly deletion.
- **Steal one detail:** OpenQuota's countdown collapses to "Resets soon" under five
  minutes instead of counting down the last seconds — a widget that refreshes every 15
  minutes has no business rendering "resets in 43s".

### CCRM-26 · Quick Links — the Anthropic status page and the usage dashboard
- **Status:** Planned · small
- **Why:** When the app shows a network error the first question is "is it me or is it
  them", and we make the user leave the app to find out. OpenQuota puts two links on every
  provider card: `status.anthropic.com` and `claude.ai/settings/usage`.
- **Approach:** two buttons on the account card, and surface the status link *specifically*
  in the network-error state (see CCRM-27) rather than only in a settings list. Scheme-check
  before launching an intent, as their `ProviderLink::visible()` does — https/http only.
- **Also useful:** `claude.ai/settings/usage` is the authority our numbers are derived from,
  so a "check the real dashboard" escape hatch is worth having whenever someone disputes a
  reading.

### CCRM-38 · Plan Tier — show the rate-limit multiplier, not just the plan
- **Status:** Planned · small
- **Why:** We store and display `subscriptionType` — "pro", "max"
  ([UsageRepository.kt:228](app/src/main/java/com/robin/claudeusage/data/UsageRepository.kt#L228),
  shown on the account card at [SettingsScreen.kt:604](app/src/main/java/com/robin/claudeusage/SettingsScreen.kt#L604)).
  We never read `rate_limit_tier`. A Max 5x and a Max 20x have very different windows and
  currently render identically, which makes the label almost decorative.
- **Approach:** OpenQuota composes the two into **"Pro 5x"** by pulling the `5x` out of a
  tier string like `default_5x` — split on non-alphanumerics, take the part ending in `x`
  whose stem parses as an integer. Two lines of parsing.
- **Verify first:** we haven't confirmed `rate_limit_tier` is present in *our* token
  response (we read `subscriptionType` off the same object). Check before designing the
  label, and fall back to the bare plan when it's absent.

### CCRM-29 · Display Mode — light/dark override, and a "follow system" time format
- **Status:** Planned · small
- **Two independent gaps, both one-liners:**
  - **Theme mode.** We read `isSystemInDarkTheme()` with no override
    ([MainActivity.kt:170](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L170)).
    OpenQuota has `ThemePreference::{System, Light, Dark}`. Note the chart's dark-mode
    opacities are chosen per-mode (CCRM-12), so a forced mode has to drive *that* decision
    too, not just the Material colour scheme — the light-mode 7% wash over near-black is
    invisible, which is the whole reason those separate opacities exist.
  - **Time format.** `use24hTime` is a boolean defaulting to **false**, so a phone in a
    24-hour locale shows 12-hour time until the user finds the switch. OpenQuota's
    `TimeFormatPreference::{System, TwelveHour, TwentyFourHour}` defaults to System.
    Android gives us `DateFormat.is24HourFormat(context)` for the System case.
- **Migration:** existing installs keep their explicit boolean; only fresh installs get
  System. Silently switching someone's clock format on upgrade is worse than the default
  being wrong.

### CCRM-28 · Auto Update Check — check in the background, and let a version be dismissed
- **Status:** Planned · small
- **Why:** Our update check is a button someone has to think to press
  ([SettingsScreen.kt:1206](app/src/main/java/com/robin/claudeusage/SettingsScreen.kt#L1206)).
  CCRM-8 established that this checker is **the only channel we have for reaching installed
  clients** — the app is sideload-only, so there's no store to notify anyone. A channel that
  only opens when the user volunteers is close to no channel.
- **Approach** (OpenQuota's `updateSchedule.ts`, which is small and sensible):
  - `autoCheckUpdates` toggle, default on.
  - A startup delay (~10s) so a launch is never blocked on a network call.
  - A 6-hour interval, with `lastUpdateCheckAt` persisted so a restart doesn't re-check —
    the remaining delay is computed from the last *successful* check.
  - `dismissedUpdateVersion`, so declining an update silences that version only and the
    next one still gets through.
- **Ride the existing WorkManager poll** (`work/Polling.kt`) rather than adding a scheduler;
  this wants none of CCRM-17's exactness.
- **Do not auto-install.** Their signed auto-updater has no sideload-safe equivalent, and
  silently swapping an APK is not something this app should do. Notify and link the release.

### CCRM-27 · Error Taxonomy — typed failures, each with the fix rather than the symptom
- **Status:** Planned · small-to-medium
- **Why:** We keep the failure as a bare string and print it: `Status: ${snapshot.lastStatus}`
  ([MainActivity.kt:541](app/src/main/java/com/robin/claudeusage/MainActivity.kt#L541)).
  That's an HTTP code or an exception name in front of someone who wants to know what to do
  next.
- **Approach:** a sealed error kind on the fetch result — OpenQuota's set is
  `authentication` / `permission` / `rateLimited` / `network` / `invalidResponse` /
  `credentialStorage` / `storage` / `internal` (their `localData` has no analogue here) —
  each mapped to copy that **names the fix**. Read their `ClaudeError` variants directly:
  every one is phrased as an instruction, and the difference in tone from ours is the point
  of the exercise.
- **Plus structured notice rows:** an in-card notice with an info/warning tone, instead of a
  status line, so the remediation sits where the broken thing is. Their
  `detectionNoticeDismissed` pattern also covers dismissible first-run guidance.
- **Already right, keep it:** we retain the last-good snapshot and show the error *beside*
  it rather than replacing the data — the same call OpenQuota makes explicitly ("a refresh
  error can coexist with a retained last-good snapshot"). Don't regress that while
  refactoring the error path.

### CCRM-30 · Estimate Honesty — mark inferred numbers as inferred
- **Status:** Planned · small
- **Why:** Every metric in OpenQuota carries an `estimated` flag and an optional
  `sourceNote`, and the UI renders both — so a number the app *inferred* never wears the
  same confidence as a number the server *reported*. We currently render several inferences
  in the same weight as measured values:
  - the projection and burn rate (`Projection`, CCRM-12);
  - the ~30-day refresh-family expiry, which is a flat guess and has never been observed
    (`OAuthSignIn.ESTIMATED_FAMILY_MS` — this is precisely CCRM-16's complaint, and the two
    items should be built together);
  - the credits percentage, which we compute from minor units rather than take from
    `spend.percent` (CCRM-1) — that one is *more* accurate than the server's, so the note
    should say so rather than hedge.
- **Approach:** a flag plus a one-line provenance string on the model, surfaced as a subtle
  marker in the app and expanded in a long-press or an info row. Widgets and the
  notification are too tight — they get nothing.

### CCRM-32 · Reduce Motion — honour the system animation setting
- **Status:** Done (2026-08-13)
- **Why:** An accessibility floor we don't currently meet. Users who set animations off at
  the OS level mean it.
- **Approach:** read `Settings.Global.ANIMATOR_DURATION_SCALE` (0 means off) and collapse
  animation durations to zero rather than swapping to a different easing — OpenQuota's
  `springMotion(reducedMotion)` does exactly this, and keeping the same curve means only one
  visual behaviour to reason about. Affects the pager, the chart's entry animation, and the
  bar fills.
- **Shipped:** `Motion` in `ui/Adaptive.kt` — pure `reduced`/`collapse` verdicts plus the
  one `ANIMATOR_DURATION_SCALE` read — and a `reduceMotion()` composable that re-reads it
  every composition, never cached, so flipping the setting mid-session takes effect on the
  next press. Wired to the one Compose-driven animation the audit actually found: the tab
  press's `animateScrollToPage`, which becomes `scrollToPage` (the same page turn at zero
  duration). The chart has no entry animation and the bar fills don't animate — a full
  `animateFloatAsState`/`tween`/`spring` sweep confirmed there was nothing else to collapse,
  and adding animations just to reduce them would be backwards. The pager's finger-driven
  swipe settle stays: direct manipulation isn't the motion this setting removes, and the
  framework governs its own animators by the same scale already. Verdicts pinned by
  `MotionTest` (garbage scales fail towards stillness; the unset 1f default keeps motion).

### CCRM-33 · App Shortcuts — launcher long-press entries
- **Status:** Planned · small
- **Why:** OpenQuota's global keyboard shortcut has no Android equivalent, but the intent —
  reach the number without navigating — maps cleanly onto launcher shortcuts.
- **Approach:** static/dynamic shortcuts for **Personal**, **Work** and **Refresh now**,
  deep-linking into the right pager page. Complements the Quick Settings tile (CCRM-11)
  rather than duplicating it: the tile is for the shade, shortcuts are for the home screen.
- **Note:** shortcut labels should follow the user's renamed profile labels
  (`UsageCache.profileLabel`), which means they're dynamic shortcuts, not static XML.

### CCRM-34 · Diagnostics Log — widen the ping log into a general app log
- **Status:** Planned · small
- **Why:** CCRM-17 just built `ping/PingLog.kt` with a pullable in-app view, and it earned
  its keep immediately. Everything else the app does in the background — polls, widget
  updates, alert delivery, token renewals — is invisible unless it's attached to logcat.
  For a sideload-only app with an email feedback channel, "paste your log" is the only
  realistic way to diagnose someone else's phone.
- **Approach:** generalise `PingLog` into a levelled ring-buffer log with a category string
  (OpenQuota's `LogLevel::{Error, Warn, Info, Debug}` is a *user-facing setting*, which is
  the right call — default Info, Debug only when someone is chasing something). Keep the
  existing pull-to-view UI and add share/export.
- **Hard rule:** never log tokens, authorization headers, or the `code_verifier`. The v0.14
  history scrub is the precedent — this is a public repo and logs get pasted into emails.

### CCRM-36 · Repo Hygiene — the `.github/` directory we don't have
- **Status:** Planned · small
- **Why:** The repo is public and has no `.github/` at all. OpenQuota's set is the standard
  one and costs an afternoon:
  - **`SECURITY.md`** pointing at GitHub private vulnerability reporting, with an explicit
    "do not include real credentials or tokens in a report". **This is the one not to skip**
    — given what this app holds and the v0.14 scrub, the failure mode is someone opening a
    public issue containing a working OAuth token.
  - **`CONTRIBUTING.md`** — including the "do not fork this repo to start another client"
    rule that currently only exists inside CCRM-8.
  - Structured **issue templates** (bug / feature) and a **PR template**.
  - **`dependabot.yml`** for Gradle and Actions.
- **Note:** issue templates should ask for the app version and the phone/skin, since CCRM-3
  phase 1 already flagged the `big` notification style as skin-dependent.

---

## Needs design — decide the shape before building

### CCRM-17 · Window Pings — start a 5-hour window on a schedule
- **Status:** **Built and the premise is confirmed** (2026-07-31) · opt-in, off by
  default · one device test outstanding (a real 4am alarm in Doze)

- **HOW THE SERVER PICKS A WINDOW — measured, and this is what the feature rests on.**
  Two on-device observations on Personal:

  | Ping | Previous window ended | Gap | Resulting window |
  |---|---|---|---|
  | 2026-07-30 20:08 | 19:59 | 9 min | `[19:59 → 00:59]` |
  | 2026-07-31 07:09 | 00:59 | 6h 10m | `[07:00 → 12:00]` |

  So: **ping soon after a window expires and it chains** — backdated to that expiry.
  **Ping after a real idle gap and you get a fresh window, anchored at your message
  truncated down to the hour.** That is exactly what this feature needs: a 04:00 ping
  after an idle night yields `[04:00 → 09:00]`, and each renewal fires at the previous
  expiry and continues the chain — 09:00–14:00, 14:00–19:00, 19:00–24:00.
  - The competing hypothesis was a **fixed 5-hour grid** you can't move, which would
    have made pinging a no-op: you'd get those boundaries whether you pinged or not.
    It predicted `[05:59 → 10:59]` for the second test and is refuted.
  - **Correction to an earlier note here:** a single Work reading of `09:20:00` was read
    as "boundaries follow your message, so they never land on the hour". That was
    under-determined — it was almost certainly a *chained* window. Fresh windows do
    truncate to the hour.
  - **This relaxes the exact-alarm requirement.** Hour truncation means a ping at 04:03
    still yields `[04:00 → 09:00]`, so punctuality buys tolerance in *minutes*, not
    seconds. Exact alarms are still worth having — crossing an hour boundary costs a
    full hour — but the earlier "3 minutes late costs you all day" framing was wrong.
- **Shipped:**
  - `ApiClient.sendPing()` — `POST /v1/messages`, Haiku, `max_tokens 1`, body `"hi"`.
    No UA rule (the endpoint has no UA gate) and no Claude Code system preamble.
  - `data/PingSchedule.kt` — all the scheduling rules as pure, framework-free logic so
    they're testable: skip/ping/stop, the next alarm time, the cutoff, the renewal
    bound, and `windowMoved()`. 16 tests in `PingScheduleTest`.
  - `ping/PingScheduler.kt` — `AlarmManager.setExactAndAllowWhileIdle`, one alarm per
    profile, degrading to inexact (and saying so in the UI) when the permission is
    absent. `ping/PingAlarmReceiver.kt` re-decides at fire time, then re-arms;
    `PingBootReceiver` re-arms after a reboot.
  - `UsageRepository.sendWindowPing()` — sends, then **re-reads usage to verify a window
    actually opened**. A 200 that didn't move `resets_at` is reported as a failure.
  - `UsageCache` ping prefs, per profile, `pingEnabled` defaulting to **false**.
  - `Alerts.notifyPingFailed` on its own `ping_alerts` channel, `IMPORTANCE_HIGH`.
    Silent on success.
  - Settings → **Window pings**: account chips, master switch, first-ping time,
    renewals (None/1/2/3), never-ping-after, the planned slots, the live real boundary,
    an exact-alarm warning with a grant button, the last outcome, and **Test ping now**.
  - `UsagePollWorker` re-arms the chain after every poll, since `resets_at` only
    changes when we poll.
- **Design decisions worth keeping:**
  - **Per profile, off by default on both.** A ping spends real quota on an automated
    request, so enabling it on a Team account stays the user's explicit act.
  - **The chain follows the observed `resets_at`, never anchor + 5h.** A session the
    user starts at 03:00 owns 03:00–08:00; the 04:00 ping is skipped and the next is
    armed for 08:00, not the configured 09:00. Fixed wall-clock alarms would stay an
    hour out of phase all day.
  - **Lateness is surfaced, not hidden** — "fired 6m late" — because the window really
    did shift by that much.
  - **A failed attempt does not consume a renewal**; it retries at 1/3/8 minutes and
    then notifies.
  - **`windowMoved` needs a minute of movement**, not inequality. `resets_at` drifts
    ~1.3s with nothing happening (CCBG-4), so an exact test would call drift a success
    and the feature's own safety check would be the thing lying.
- **Verified on-device 2026-07-31:** a ping opens a window from cold, and the boundary
  truncates to the hour (both in the table above).
- **Still to verify:** that a 4am alarm actually fires on time in Doze on the Fold 7,
  and that the deferred verification (CCBG-5) reports success once rather than
  spuriously. Needs an unattended overnight run — see the ping log below.
- **Fixed along the way:** [CCBG-5](BUGS.md) — verification ran inline, so a working
  ping reported failure and then entered the *send*-retry backoff, firing roughly four
  pings where one was wanted. Send and verify are now separate alarms.
- **Not done:** no widget or tile surface for pings, and no battery-optimization
  exemption prompt — `setExactAndAllowWhileIdle` should be enough, so that only gets
  added if real drift shows up.
- **Why:** The 5-hour window starts on your first message, so its boundaries are an
  accident of when you happened to start working. Pinging on a schedule makes them a
  choice: 4am–9am, 9am–2pm, 2pm–7pm, 7pm–midnight is four windows covering 20h, all
  aligned to the user's day instead of to whenever they first opened a terminal.
  An unused window costs nothing against the 7-day budget, so an early ping is close
  to free.
- **Mechanism:** reading usage does not start a window — only a billed inference call
  does. So a ping is `POST https://api.anthropic.com/v1/messages` with the same bearer
  we already hold, `anthropic-beta: oauth-2025-04-20`, `anthropic-version: 2023-06-01`,
  cheapest model, `max_tokens: 1`, body `"hi"`. `OAuthSignIn.SCOPE` already requests
  `user:inference` ([OAuthSignIn.kt:35](app/src/main/java/com/robin/claudeusage/data/OAuthSignIn.kt#L35)),
  so no re-sign-in is needed.

- **Spike part 1 — the edge gate (DONE, 2026-07-30, tokenless):** `/v1/messages` has
  **no User-Agent gate**, unlike the token endpoint. Fixed-bogus-bearer probes across
  `claude-code/2.1.214`, `okhttp/5.4.0`, curl-default and empty UAs *all* returned an
  identical clean `401 authentication_error` — `"OAuth access token is invalid."` —
  i.e. every shape reached real auth validation rather than the opaque
  `429 rate_limit_error` WAF block that cost us five rounds in v0.12. Presence or
  absence of `anthropic-beta` made no difference, nor did adding the Claude Code
  system preamble. An `x-api-key` control returned a *different* error
  (`"invalid x-api-key"`), confirming the OAuth bearer path is distinct and live.
  **Consequence:** `ApiClient` needs no third UA rule — a ping can go out on OkHttp's
  default UA, and the two-opposite-UAs comment at the top of the file stays a
  two-endpoint story.
- **Spike part 2 — the token half (DONE, 2026-07-30, WORK/Team account):** a real ping
  returned **HTTP 200**.
  1. **`user:inference` IS honoured for a third-party caller.** No 403 — unlike the
     `setup-token` route, which 403'd on `user:profile` (v0.11 notes). Response was a
     normal `message` object, `stop_reason: max_tokens`.
  2. **The Claude Code system preamble is NOT required.** The plain body succeeded on
     the first attempt, so the `"You are Claude Code…"` system block never had to be
     tried. Don't send it.
  3. **Cost is invisible.** 8 input tokens, 1 output; `percent` read 55 both before and
     after, i.e. below the reporting granularity. An early ping really is ~free.
  4. **UA is irrelevant here** (see part 1) — the ping went out on curl's default UA,
     standing in for the app's `okhttp/<ver>`.
  - **Still unobserved:** a window was already open (55%, `resets_at` 09:20 UTC), so
    "ping with nothing open actually starts one" wasn't directly demonstrated. Everything
    else points to yes — it's the documented model and the app's own `"Starts when a
    message is sent"` copy — but it's inference, not observation. Confirm on a cold
    morning before shipping.
- **Windows do NOT round to the hour** — `resets_at` came back `09:20:00`, so boundaries
  follow the first message, not the clock. Good news for the 4/9/2/7 plan (a 04:00 ping
  really can yield an 09:00 boundary) but it means **the slots are only as clean as the
  alarm is punctual**: fire at 04:03 and you own 04:03–09:03 forever after. Two
  consequences: exact alarms are mandatory (below), and the UI must display the *actual*
  boundaries from `resets_at` rather than the idealised times the user configured —
  otherwise the app lies to them by a few minutes, all day.
  - Granularity is not fully pinned: `:20:00` is consistent with truncation to the
    minute, or to 5/10/20 minutes. One sample can't separate those. Worth a second
    reading whenever a window starts naturally.

- **Schedule model — chain off `resets_at`, don't use fixed wall-clock alarms.**
  Fixed 4/9/2/7 alarms desynchronise the first time the user works off-grid: a session
  started at 3am owns 3–8am, the 4am ping lands *inside* it and does nothing, and every
  later slot is an hour out of phase for the rest of the day. Instead:
  - **Anchor** at a user-set first-ping time (4am).
  - **Guard:** before pinging, check for an open window via `session.resetsAt`
    ([Models.kt:9](app/src/main/java/com/robin/claudeusage/data/Models.kt#L9)). If one is
    open, skip the ping and re-arm for its actual `resetsAt`.
  - **Chain:** after each successful ping, re-arm at the *observed* `resetsAt`, never
    at anchor + 5h.
  - **Stop:** at a user-set renewal count (1–3, the user's "renew or not") or a
    wall-clock cutoff (midnight). Both, probably — whichever comes first.
  This yields exactly the 4/9/2/7 slots on a clean day and self-corrects on a messy one.
- **Scheduling primitive:** `work/Polling.kt`'s WorkManager is inexact and drifts in
  Doze — fine for refreshing a widget, wrong here, because a renewal that fires 20
  minutes late leaves a 20-minute hole in the coverage the feature exists to provide.
  Needs `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM`/
  `USE_EXACT_ALARM` (API 31+), plus a battery-optimization exemption prompt for
  reliability. `USE_EXACT_ALARM`'s Play policy restrictions don't bite us — the app is
  GitHub-sideload only.
- **Verify, don't assume.** After each ping, refresh usage and confirm `resetsAt`
  actually moved. A silent 4am failure is the worst outcome in the feature: the user
  wakes at 8am *believing* they have a fresh window and doesn't. Notify on failure,
  stay silent on success. Retry with short backoff if the device was offline.
  - **The "moved" test must be tolerance-based** — an exact comparison would report
    success even when the ping did nothing. `resets_at` is recomputed server-side and
    drifts ~1.3s between polls with no change at all (see
    [CCBG-4](BUGS.md)), so require a move on the order of minutes, not milliseconds.
    A real new window jumps ~5h; anything under a minute is noise. Getting this
    backwards would make the feature's own self-check the thing that lies.
  - Same tolerance applies to the pre-ping "is a window already open?" guard.
- **Make it legible:** record ping-started windows in `SessionLog` so history can
  distinguish them from organic ones. Otherwise the history bars quietly fill with
  0%-used windows and stop meaning anything.
- **Posture:** this moves the app from *reading* telemetry to *consuming* subscription
  inference from a third-party client — a step further than the Feb-2026 ToS
  clarification the v0.7 legal check already put us on the wrong side of (which is why
  we're sideload-only and never Play Store). Same account, same quota, same call the
  user's own client makes; but it should ship **opt-in and off by default**, with the
  toggle stating plainly what it sends and on whose quota. Not a silent background
  behaviour.
- **Naming:** "Ping" reads like a connectivity check. Prefer **Start window** or
  **Reserve window**.

### CCRM-3 · Unified Theming — one theming system for widgets & notifications
- **Status:** Phase 1 done (2026-07-27) · phase 2 designed (2026-07-30) · phase 3 needs design
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
  end up with three inconsistent looks. Define **one** set of tokens, then have every
  surface draw from it.
- **Two axes, deliberately separate — the design decision of 2026-07-30.** The four
  phase-1 options are not themes, they are **layouts**: `gauge`/`number`/`progress`/`big`
  change what is drawn, not what colour it is. "Transparent" is the opposite — a **token**
  with no layout consequence. Keeping them in one undifferentiated list is exactly how we
  arrive at the settings zoo this item used to worry about. So:
  - **Tokens** — accent, bar shape, background mode, text contrast, text scale. One set,
    app-wide, in `ui/Palette.kt`. Every surface reads them; none defines its own.
  - **Layouts** — what a surface draws. Scope differs per surface: **global** for the
    notification (there is only one), **per widget instance** for widgets (two widgets may
    legitimately differ — a transparent number on one page, solid bars on another),
    **fixed** for the in-app screen.
- **The in-app screen gets tokens only — no per-card configuration.** Decided 2026-07-30.
  It is the one surface with the room and the full context, so it should be the *reference*
  rendering of the token set rather than another thing to style. Its real complaint is
  length, not looks — two 192dp charts stand between the 5-hour bar and the credits card —
  and that is density, not theming. Not filed: if the scroll ever becomes the top
  complaint, one "trend charts: always / when they project / off" chip is the whole fix.
- **Settings scope, capped up front:** widgets get **four** controls — layout, background,
  accent, and which elements show (profile name / refresh icon / "updated 4m ago" footer).
  Nothing else. Settings holds the **defaults** a newly placed widget inherits; `WidgetPrefs`
  holds per-instance overrides; one **"apply to all N widgets"** action exists because
  changing the accent must not mean long-pressing every widget in turn.
- **Previews are schematic on purpose.** A Glance composable cannot be rendered inside the
  config activity, so any preview is a second renderer that will drift from the first.
  Better an obvious diagram than a subtly wrong mockup.
- **Approach — build order:**
  1. **Tokens.** Extend `ui/Palette.kt` into a theme model (accent, bar shape, background
     mode, text contrast, text scale). Model only — no UI, no behaviour change. Mirror the
     token names into `BEHAVIOR-SPEC.md` (the shared contract — see CCRM-8) so a second
     client inherits the same vocabulary instead of inventing one.
  2. **Prerequisite: CCRM-4**, before any cosmetic option exists. Shipping a look you can
     only change by deleting and re-adding the widget is the worst possible discoverability
     for a cosmetic feature. **This inverts the old dependency** — CCRM-4 gates phase 2,
     not the reverse.
  3. **Phase 2a — "huge number" widget layout** *(ship first: cheapest, and it proves the
     split).* A port of `notif_big_number.xml` to `widget/BarWidget.kt` — weighted left
     column (label / bar / reset) with the percentage trailing, bold and large. `BarWidget`
     is already this content; the percentage just moves out of `HeaderRow`. Needs **no
     bitmap**: Glance's `LinearProgressIndicator` takes a `ColorProvider`, which is the very
     thing whose RemoteViews inconsistency forced the notification to draw its bar by hand.
     No new provider, no new drawing code.
     - **`BarWidget` must leave `SizeMode.Single`.** Today a 4×1 and a 2×1 render
       identically; a big number has to know its width. Move to `SizeMode.Responsive` with
       width buckets (~30sp narrow / 38sp medium / 46sp wide).
     - **No spans in a Glance `Text`,** so `drawNumberTile()`'s superscript `%` at 0.42× is
       unavailable. Use a bottom-aligned `Row` of two `Text`s instead.
     - **Three digits step down 0.77×** — the same ratio `drawNumberTile()` already uses
       (0.60 → 0.46), for the same reason.
     - **The number takes the warning colour** along with the bar. Unlike the chart, which
       deliberately splits two different risk signals across two channels (CCRM-12), this is
       one signal with no channel to conflict with — and a 7dp bar is a weak place to say
       "97%".
     - **The percentage still truncates**, per
       `BEHAVIOR-SPEC` §2 (see CCRM-8). Considered unifying it with the
       credits card's rounding and **rejected 2026-07-30**: truncation never overstates, so
       a window at 99.7% must read 99 rather than claim a limit the user has not reached —
       and at 46sp that matters more, not less. The visible consequence is that windows and
       credits can differ by a point on the same screen; that is the intended trade.
     - Narrow buckets drop the trailing clock time first, keeping the countdown.
  4. **Phase 2b — background token.** Solid / translucent / none, across both widget
     providers. **The translucent middle option is the one most people actually want:**
     `Color.Transparent` is one line, but `GlanceTheme.colors.onSurface` is chosen by system
     dark mode, not by the wallpaper behind that particular widget, so light text on a light
     wallpaper reads as a bug. Hence the paired text-contrast control (auto / light / dark).
  5. **Phase 2c — Settings → Widgets.** The defaults block plus "apply to all N".
  6. **Phase 3 — ring layout.** Large centre percentage in a circular fill ring, as a
     **layout option on the existing providers, not a third provider** — another entry in
     the launcher's widget picker is a real cost, and only earns it if the ring needs its own
     square default size. Glance has no Canvas, so this one *does* need a bitmap: extract the
     drawing so CCRM-13 shares that extraction rather than repeating it. Cap dimensions and
     cache per size bucket — a RemoteViews transaction has a size limit.
- **Verification:** every layout wants looking at on real hardware before it is called done
  (CCRM-15 exists because a state shipped unobserved), then a figure in `Release/docs`.
- **Open questions:** does the mascot asset need licensing sign-off before it ships inside a
  widget ring — phase 3 only, nothing earlier needs it. *(Resolved: "how many themes is
  enough" — the four-control cap above.)*

### CCRM-25 · Card Layout — reorder cards, hide rows, and move the rest behind "more"
- **Status:** Needs design · medium · **needs a call on CCRM-3's in-app decision first**
- **Why:** The main screen is a fixed vertical list and its real complaint is length — CCRM-3
  says so directly: "Its real complaint is length, not looks — two 192dp charts stand between
  the 5-hour bar and the credits card — and that is density, not theming." That entry then
  deferred the fix to a hypothetical single chip ("trend charts: always / when they project /
  off"). OpenQuota's answer is better and more general.
- **What OpenQuota does:** every metric carries three independent properties — `enabled`
  (show at all), a `MetricSection` of `AlwaysVisible` or `OnDemand` (above or behind a
  "more" disclosure), and a position in a drag-to-reorder list. Its normalization then
  guarantees **at least one enabled metric stays always-visible**, so the screen can never be
  configured into blankness. That invariant is the part people forget to build.
- **The decision this needs:** CCRM-3 ruled that "the in-app screen gets tokens only — no
  per-card configuration", on the grounds that it should be the *reference* rendering of the
  token set. **This item argues that ruling was about the wrong axis.** Visibility and order
  are not styling: they don't create a second look to keep consistent, and they're the direct
  fix for the density complaint CCRM-3 itself raised and punted. If that reading is rejected,
  the fallback is CCRM-3's one-chip version and this item closes.
- **If it proceeds:**
  - Order and visibility per profile, since Personal and Work are used differently.
  - Their `MAX_PINS_PER_PROVIDER = 2` maps onto **which readings reach the pinned
    notification and the tile** — both surfaces are space-starved and currently hardcode
    their content (CCRM-1 notes the notification is "already tight on space").
  - Bundle `DensityPreference::{Default, Compact}` — a compact mode is a one-token answer to
    the same complaint and doesn't need the reorder UI to ship first.
- **Interacts with CCRM-6:** a dynamic profile registry would change what "per profile"
  means here. Cheaper to build this on the two-slot model and migrate than to wait.

### CCRM-31 · Combined Total — one aggregate reading across both accounts
- **Status:** Needs design · medium
- **Why:** Nothing in the app answers "how much have I spent in total". Personal and Work are
  always shown separately — and CCRM-20 deliberately went further, giving each profile the
  full width and pointing at the widgets for both-at-once. That decision was about *windows*,
  which genuinely aren't comparable across accounts. **Money is.**
- **What OpenQuota does:** a donut ring with one slice per provider, a period switcher (Today
  / Yesterday / 30 days) and a metric switcher (cost / cost-per-million-tokens / tokens),
  with a `MINIMUM_SPEND_SLICE_SHARE = 0.025` floor so a tiny slice stays visible rather than
  collapsing to a hairline.
- **What ports:** the ring and the slice-floor, over `SpendCredits` (CCRM-1) — two slices,
  Personal and Work, against the combined limit. **Not** the period or metric switchers:
  those are driven by local token logs we don't have (see the not-portable note below), and
  our credits figure is a running monthly total, not a per-day series.
- **Open questions:**
  - Where does it live — its own card at the top of each profile, a third pager page, or the
    History screen? A third pager page reopens the CCRM-20 question about what the pager is
    for, so probably not.
  - What happens when only one account has a credit budget, or the two are in different
    currencies. `SpendCredits` carries `currency` per account and we have never seen anything
    but USD — so the honest behaviour is to refuse to sum across currencies and show them
    separately, not to assume.
  - **Gated on [CCBG-6](BUGS.md).** If the credits denominator is wrong, an aggregate of two
    wrong denominators is worse than not shipping — it looks more authoritative and is no
    more correct.
- **Shares the ring drawing** with CCRM-3 phase 3 and CCRM-13. Whichever lands first owns the
  Canvas-to-bitmap extraction.

---

## Later — larger, still on the path

### CCRM-12 · Trend Chart — make the trend chart readable
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
- **Follow-up shipped the same day — pace made the point of the chart:**
  - Both charts **192dp**, and the 5-hour one always draws, even flat. A chart that
    appears and disappears moves everything below it; a stable position is worth more
    than avoiding an empty state.
  - The even-pace diagonal is now the **limit line**, not decoration: 2.5dp at 0.85
    alpha with long dashes, in the 80% warning yellow so it reads as the same class of
    object as the threshold guides. Legend moved to the top-left — the one corner
    neither the curve nor the diagonal occupies, since both start bottom-left — with a
    bottom-right fallback for the rare heavy-usage-very-early case.
  - **The area fill splits at the diagonal:** usage colour below, warning colour above.
    The overshoot wedge shows *when* the crossing happened and by how much, which
    recolouring the whole curve would flatten into a single verdict — and which would
    also contradict the usage bar right above the chart, coloured by absolute percent.
  - Above the diagonal carries a faint wash. **Dark mode gets its own opacities**
    (wash .07→.10, overshoot .30→.34, below-fill .18→.20): 7% red over near-black is
    invisible. Nothing here is an automatic light-mode flip.
  - Curve colour still means **absolute usage**, from the same `barFill` as the bar
    above it. The chart carries two different risk signals — how close to the wall
    (guides) and whether you'll reach it (diagonal) — and they don't share a channel.
  - Pace readout replaces the retired **Days elapsed** bar: `N points below even
    pace` / `On even pace` / `N points above even pace`, the last in the warning
    colour and bold. A **±3 point dead zone** (`PACE_DEAD_ZONE`) stops the verdict and
    its colour flipping every poll while usage sits on the line.
- **Days elapsed retired** from the app card, the large `UsageWidget`, and
  `BarWidget`'s options. `daysElapsedWindow` became `Palette.elapsedPercent(window,
  windowLengthMs)`, generalised off 7 days so the 5-hour chart gets the same readout.
  Widgets already placed as `"days"` fall through to the 7-day bar, which is what the
  figure was derived from.

### CCRM-14 · Clear History — let the user clear usage history
- **Status:** Planned
- **Why:** CCBG-1 decoupled history from the credential lifecycle, which was right — but
  it left *nothing* able to clear it. `HistoryStore.clear()` and `SessionLog.clear()` are
  both callerless. Someone genuinely switching the account behind a profile slot has no
  way to start clean, and their new account inherits the old one's trend line.
- **Approach:** an explicit, confirmed action rather than a side effect of anything else —
  "Clear usage history" under the account card or Settings → Usage history, with a
  confirmation naming what goes (the 8-day sample history *and* the year-long session log,
  which must be cleared together or the bars and the sparkline disagree).
- **Deliberately not:** wiring it back into "Clear credentials". That's what CCBG-1 was.

### CCRM-15 · Above-Pace Verification — verify the above-pace chart state on a device
- **Status:** **Observed 2026-08-04** · synthetic-series override still worth building
- **Why:** The pace chart's warning half — the amber overshoot fill, the wash over the
  above-pace region, and the bold warning-coloured readout — had **never rendered on real
  hardware**. Every window on every account sat below pace, so it had only ever been seen
  in a wireframe. It shipped in v1.1 unobserved.
- **It crossed on its own, exactly as this entry guessed it might.** Caught while
  verifying CCRM-20 on the Fold's inner screen: the **Work** 5-hour window was at 32% with
  pace at 24%, and every warning element rendered — the wash over the above-pace region,
  the amber overshoot fill from the point the curve crossed the diagonal, and CCRM-20's tap
  callout picking up the warning colour for `32% · +8 vs pace`. Captured as
  `Release/screenshots/chart-above-pace-work-fold-inner.png`.
- **No defect found in it.** A label collision was filed off this screenshot and
  retracted the same day — the two labels are separated by construction, see
  [CCBG-7](BUGS.md). Worth recording that the state came up *clean*, since the entry was
  written on the assumption that an unobserved state is probably hiding something.
- **The one blemish**, well under bug threshold: a 100% projection's `~100%` label is
  drawn across the pace diagonal and its own dashes. A surface-coloured backing, like
  CCRM-20's callout uses, would settle it. Not filed.
- **Still worth building: the debug-only synthetic above-pace series.** This sighting was
  luck, and it's *not* repeatable — the window resets and Work drops back below pace. The
  override remains the only way to inspect the state on demand, which matters for any
  future change to the warning colours, and for re-capturing the guide shot on a device
  whose window isn't obliging.
- **Then:** the guide still illustrates only the below-pace case, and the capture above is
  usable for the swap as-is.

### CCRM-13 · Chart Widget — standalone chart widget
- **Status:** Planned · gated on the in-app chart proving itself
- **Why:** The trend chart answers "will I run out before the reset" better than any
  bar, and that's worth having on the home screen without opening the app.
- **Approach:** A new widget rendering `UsageSparkline`'s content, with the window
  (5-hour ↔ 7-day) and profile chosen at placement, like `BarWidget`. Glance has no
  Canvas, so the chart has to be drawn to a bitmap and shown via `Image` — the same
  approach `PinnedNotification` already uses for its panel, so the drawing code wants
  extracting from the Compose Canvas into something both can call.
- **Depends on:** CCRM-4 would let a placed instance be reconfigured without
  re-adding it.
- **Shares its extraction with CCRM-3 phase 3.** The ring layout needs the same
  Canvas-to-bitmap escape hatch for the same reason. Whichever ships first should do the
  extraction properly — one drawing surface both can call — rather than solving it twice.

### CCRM-11 · Tile Reset Time — Quick Settings tile shows the 5-hour reset
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

### CCRM-5 · Per-Profile Notification — Work section as its own pinned notification
- **Status:** Planned
- **Why:** Today only Personal can be pinned. Heavy Work users want both windows live
  at a glance.
- **Approach:** `notify/PinnedNotification.kt` and `Alerts.kt` already ID-namespace
  Work (`+100`); extend the pinned notification to run one instance per enabled
  profile, each with its own channel/icon/style so they're distinguishable in the
  status bar. Add per-profile "pin this" toggles in settings.

### CCRM-6 · Multi-Account — more than two accounts
- **Status:** Needs design
- **Why:** `Profile` is a hard-coded `PERSONAL`/`WORK` enum ([Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt)),
  wired through credentials, cache keys, notification IDs, widgets and alerts. Users
  with 3+ accounts (multiple work orgs, side projects) can't be served.
- **Approach:** Move from a fixed enum to a dynamic profile registry (stable
  string keys + user labels). Touches `CredentialStore`, `UsageCache`, `HistoryStore`,
  the `+100` notification-ID scheme, widget config, and every `Profile.entries` loop.
  Non-trivial — scope it as its own milestone, ideally before CCRM-7 so iOS inherits
  the flexible model instead of the 2-slot one.

### CCRM-24 · Share Card — share a usage snapshot as an image
- **Status:** Planned · medium · **gated on the Canvas-to-bitmap extraction**
- **Why:** People screenshot this app today. A composed card is better than a crop of a
  screenshot, and unlike on desktop, sharing is a first-class Android surface — this feature
  is a better fit here than in the app it's being copied from.
- **What OpenQuota does:** `src/lib/shareCard.ts` is 757 deliberate lines — a 360pt-wide card
  rendered at **4× scale**, with the quota bars, their pace labels, a trend sparkline and the
  spend ring, all drawn from the same palette as the live UI.
- **Approach:** draw to a bitmap, write to cache, hand out a `content://` URI via
  `FileProvider` and `ACTION_SEND`. **This is the third caller for the same extraction**
  CCRM-13 and CCRM-3 phase 3 both need (Glance has no Canvas; `PinnedNotification` already
  works this way) — three callers is enough to justify doing it properly once rather than
  three times.
- **Privacy, decided up front:** the card must not carry the account's email, the plan tier,
  or anything identifying beyond the profile's own label — the user is about to post it. A
  render-then-preview step before the share sheet, so nothing leaves without being seen.
- **Nice detail worth keeping:** the 4× scale. A card that looks crisp in a chat thread has to
  be drawn well above display density.

### CCRM-37 · Contract Tests — fail the build when copy or colour drifts
- **Status:** Planned · medium
- **Why:** The sharpest idea in the OpenQuota repo, and it lands on a problem we already
  named. CCRM-8 asks for exactly this discipline across clients — "when a core bug is found,
  add a failing fixture to `contract/` first so both clients go red until fixed" — and
  CCRM-15 exists *because a visual state shipped unobserved*. Right now nothing mechanical
  stops Android drifting from `BEHAVIOR-SPEC`.
- **What OpenQuota does — three distinct kinds:**
  - `verify-provider-registry-contract.js` greps named source files and **fails CI if an
    identifier is hardcoded outside the registry**. Our analogue is the threshold and
    breakpoint constants: `Palette.barColor`'s 80/90/100, the alert thresholds, and
    `PACE_DEAD_ZONE` should exist in exactly one place, and a test should prove it.
  - `uiLanguage.test.ts` asserts against **raw component source** that the type scale, the
    warning colour on the critical marker, and specific Settings labels haven't changed. Ugly
    and effective: it catches the class of change that compiles, renders, and is wrong.
  - `visual-parity.test.ts` renders each icon and asserts its **exact brand fill**.
- **What we'd build:** unit tests pinning the `BEHAVIOR-SPEC` §2 and §5 numbers (the
  truncate-never-round rule for windows, the rounding rule for credits — CCRM-3 phase 2a
  settled that they deliberately disagree, which is exactly the kind of decision that gets
  "fixed" by a future reader), plus the dark-mode chart opacities from CCRM-12, which are
  currently four magic numbers with a comment.
- **Then the bigger prize:** run the Android parser against the Mac repo's `contract/fixtures/`
  instead of its own captured payload. CCRM-8 flags this as not done and notes it "would make
  parser drift between clients impossible to miss".

### CCRM-35 · Layout Reset — undo a customization mistake
- **Status:** Planned · small · **gated on CCRM-25** (nothing to reset before then)
- **Why:** Any reorder/hide UI needs a way back, and "put it back how it was" is not something
  a user can reconstruct by hand once they've dragged six things.
- **Approach:** OpenQuota's `reset_provider()` restores one account's layout to defaults while
  **preserving its enabled and detected state** — the reset is scoped to layout, not identity,
  which is the distinction that makes it safe. Then `customizationHistory.ts` snapshots the
  previous layout so the reset itself can be undone, gated behind a confirmation sheet.
- **Fits an existing pattern:** CCRM-14 already specifies a confirmed destructive action for
  clearing history. Same component, and worth building it once for both.

---

## Bookends — major efforts, gated on the above

### CCRM-7 · iOS — an iOS version of the app
- **Status:** Planned · **behind CCRM-8 (Mac), reordered 2026-07-30** · gated → v2.0
- **No longer the next platform.** Mac goes first. iOS is held up by **distribution, not
  readiness** — the full argument lives in CCRM-8 and isn't repeated here, but the short
  version is that sideloading is a first-class path on Android and a dead end on Apple's
  phone, so the same app reaches far fewer people for far more work. Nothing about the
  design below is in doubt; only its position in the queue changed.
- **What would move it back up:** a distribution route that doesn't depend on App Store
  review — most plausibly the EU's alternative-distribution regime, or Anthropic
  sanctioning third-party clients so review stops being a coin toss.
- **Why / when:** Build only after most of the above has settled, so we port a stable
  design rather than a moving one. Targeted for v2.0.
- **Approach:** New iOS app. Port the model/repository layer conceptually; reuse the
  API/OAuth learnings. WidgetKit for widgets; iOS Live Activities / notifications are
  the closest analog to the Android pinned notification (there's no true always-on
  persistent notification, so expect a design adaptation here).

### CCRM-8 · Mac Menu-Bar — desktop Mac menu-bar app
- **Status:** Planned · **next platform** · **new repo** — decided 2026-07-30
- **Reordered ahead of CCRM-7.** Mac now comes before iOS. iOS is parked on
  distribution, not on readiness: sideloading is a first-class path on Android and a
  dead end on Apple's phone (App Store review would read the Claude Code OAuth
  impersonation as unauthorised use of a third-party service; TestFlight external
  testing needs Beta App Review; ad-hoc caps at 100 devices/year with annually
  expiring profiles; a free-account sideload re-signs every 7 days). The Mac has no
  such gate — a notarised direct download works — and it has real background
  execution, so threshold alerts and polling behave properly. Same audience, a
  fraction of the friction.
- **Product shape:** menu-bar-first, mirroring the Android widget/app split.
  - **Menu-bar icon** — the always-visible gauge, the Mac's answer to the Android
    home-screen widget and the pinned notification. Fills as the 5-hour window burns.
  - **Click/hover preview** — a popover with the 5-hour, 7-day and per-model bars plus
    countdowns. The glanceable layer.
  - **Full window on open** — details, history, charts and settings, same information
    architecture as the Android app.
- **New repo, not this one.** The decisive reason is the release stream:
  [UpdateCheck.kt:25](app/src/main/java/com/robin/claudeusage/data/UpdateCheck.kt#L25)
  polls `releases/latest`, which GitHub resolves **repo-wide** regardless of tag
  naming. Publish a Mac release here and it becomes "latest" for every installed
  Android client; `normalize()` only strips a leading `v`, so a `mac-v0.1` tag splits
  to a non-numeric first component, compares as 0, and every phone silently reports
  "you're up to date" forever — while the update checker is the only channel we have
  for telling people to update. Already-shipped v1.1 clients cannot be patched out of
  this. Secondary reasons: no shared build tooling (Gradle/JDK vs Xcode/SwiftPM), two
  signing stories, and a public README/docs set framed entirely around Android.
- **Shared code is deliberately *not* the plan.** Only ~510 of 6,770 lines are
  Android-free, and they are Kotlin against OkHttp/`org.json` — unusable from Swift.
  Real sharing would mean a KMP core (Ktor + kotlinx.serialization + an expect/actual
  settings layer), i.e. rewriting the most empirically fragile code we own — the
  byte-identical authorize-URL encoding and the usage parser — on a shipped app. Poor
  trade, and the menu bar needs Swift regardless.
- **Shared *contract* instead — written 2026-07-30, lives in the Mac repo under
  `contract/`.** Platform-neutral spec and test data, deliberately **not** kept here:
  this repo stays Android-only. Three parts: `API-CONTRACT.md` (endpoints, the
  two-opposite-User-Agents rule, PKCE details, token semantics, backoff),
  `BEHAVIOR-SPEC.md` (thresholds, colour breakpoints, projection maths, history
  bucketing, alert dedupe), and `fixtures/` (captured payloads with expected parse
  results, plus projection and history cases, all language-neutral JSON). A new client
  implements against the fixtures rather than rediscovering the schema. **Do not fork
  this repo to start another client** — start clean and copy the contract in.
  - If a third client ever appears, split `contract/` into its own repo and submodule
    it into each. With two clients and one developer that was over-engineering.
  - The Android client does **not** currently run against these fixtures — its own
    `UsageParserTest` still holds the captured payload separately. Wiring Android to
    the shared fixtures would make parser drift between clients impossible to miss;
    worth doing if the two ever disagree.
- **Platform mapping:** Keychain for tokens (replaces `EncryptedSharedPreferences`),
  `ASWebAuthenticationSession` for the OAuth browser trip, `NSBackgroundActivityScheduler`
  or a `launchd` agent for polling (replaces WorkManager),
  `NSStatusItem` + `NSPopover` for the menu bar, `UNUserNotificationCenter` for alerts.
  **The WAF risk is resolved (2026-07-31):** an explicit `CCooldown/1.0` UA on the token
  endpoint is accepted — a real code exchange returned 200 on the Work/Team account and
  the Mac app has been polling since. Useful new fact for both clients: the gate is not
  an allow-list of known libraries, it rejects *recognisable* shapes (`claude-code`,
  browser-like, `curl`) and empty. Recorded in API-CONTRACT §2.
- **Sync discipline once there are two clients:** keep `CCRM`/`CCBG` here as the single
  ID space; label every issue `layer:core` (must reach both) or `layer:platform` (stays
  local); when a core bug is found, **add a failing fixture to `contract/` first** so
  both clients go red until fixed. Android at v1.1 is the reference implementation —
  where behaviour is disputed, it is correct until the spec is deliberately changed.
- **Parity is bandwidth-bound, not architecture-bound.** Android leads; Mac follows and
  may lag. Say so publicly rather than implying parity we won't sustain.

### CCRM-18 · Mac Desktop Widget — a WidgetKit widget on the macOS desktop
- **Status:** Planned · gated on CCRM-8's menu bar working · **Mac repo**
- **Why:** macOS 14+ lets a widget be dragged out of Notification Center onto the
  desktop. That's the closest Mac analogue to the Android home-screen widget, and the
  original point of the project — usage visible without opening anything.
- **Why it's attractive here and wasn't on iOS:** WidgetKit widgets normally live on an
  opportunistic timeline budget (~40-70 reloads/day), which is what made the iOS story
  weak (CCRM-7). On the Mac that constraint doesn't bind: the menu-bar app is **always
  running**, so it writes each fresh reading into a shared container and calls
  `WidgetCenter.shared.reloadAllTimelines()`. The widget is then exactly as fresh as the
  app's own polling, with no dependence on its own budget.
- **Approach:** a WidgetKit extension target in the Mac app. Needs three things that
  don't exist yet, which is why it's gated:
  1. A real `.app` bundle (a bare SwiftPM executable can't host an extension).
  2. An **App Group** container so the app and the widget share the last reading —
     the widget must never fetch or hold a token itself.
  3. **Consistent code signing** across app and extension. The self-signed local
     certificate CCRM-8 needs anyway (to stop the Keychain re-prompting on every
     rebuild) covers this.
- **Sizes:** small (one window's ring + reset), medium (5-hour + 7-day bars), large
  (both windows, per-model caps, credits) — mirroring the Android widget buckets so the
  layouts can be reasoned about once. Reuse `BEHAVIOR-SPEC` §5 breakpoints; desktop
  widgets get a tinted/monochrome treatment in some modes, so don't rely on colour
  alone to carry the warning.
- **Not a replacement for the menu bar.** The menu-bar item is the always-visible
  surface; the widget is a second, larger one. Ship it after the menu bar is solid.

### CCRM-19 · Mac Surface Themes — glass / plain / character, beyond the accent colour
- **Status:** Backlog · **Mac repo** · gated on the main window existing
- **Why:** The 12 accents (BEHAVIOR-SPEC §5) only change one hue. On the Mac the
  *material* of the popover and window is a bigger part of how the app feels than the
  accent is, and it's the one place a Mac client should look like a Mac app rather than a
  port. Three candidates, in ascending order of effort:
  1. **Glass** — the macOS 26 material. Verified present in the MacOSX26.5 SDK:
     `NSGlassEffectView`, `NSGlassEffectContainerView`, `NSGlassEffectViewStyle`
     (`Regular` / `Clear`), plus SwiftUI's `GlassButtonStyle`. **Constraint:** the package
     targets macOS 14, and glass is 26+, so this has to sit behind
     `if #available(macOS 26, *)` with a graceful fall back to the current material —
     or the minimum gets bumped, which is a bigger decision than a theme.
  2. **Plain** — a flat opaque background, no vibrancy or blur. Not just a taste option:
     translucency over a busy wallpaper is the main legibility complaint about menu-bar
     popovers, and a solid panel is the accessible answer. Pairs with
     `NSWorkspace.accessibilityDisplayShouldReduceTransparency`, which should force this
     theme regardless of the setting.
  3. **Character** — a Claude-flavoured skin: mascot glyph in the popover header, warmer
     terracotta surfaces, softer corners.
- **Trademark constraint on the character theme — decide before building.** Shipping
  Anthropic's actual mascot artwork means redistributing their asset from a public repo,
  which is a step beyond the "unofficial client" position the project already occupies
  (see the README's risk notice). Two safe versions: *original* art that evokes the
  aesthetic without copying the asset, or artwork the user draws themselves. Either is
  fine; lifting the official file is the thing to avoid. Personal-use-only lowers the
  practical risk but doesn't change what a public repo is doing.
- **Approach:** extend `DisplaySettings` with a `surfaceTheme` alongside `accent`, and
  resolve material in the view layer only — `CCooldownCore` must stay free of AppKit, so
  the theme is a token the core carries and the view interprets, exactly as `accent`
  works now.
- **Gated on the main window** because a theme picker needs somewhere to live, and
  because judging three materials on the popover alone would be judging them on the
  smallest surface.
- **Android note:** glass has no Android counterpart, and Material You already covers
  dynamic colour there. This one is deliberately platform-specific — it does **not** go
  into the shared `BEHAVIOR-SPEC`, unlike the accent palette.

---

## Someday / Maybe

### CCRM-9 · News Feed — Claude news in the unused space (free sources only)
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

### CCRM-10 · X Feed — Twitter/X feed in unused space
- **Status:** Dropped
- **Why:** Reading a public X timeline reliably now needs a paid API tier, and
  scrape-based approaches break constantly. Not worth coupling the app's stability to
  it. Superseded by CCRM-9 (free-source news only).

---

## Appendix — what does *not* port from OpenQuota

Written 2026-08-04 with CCRM-21 … CCRM-38, so the same ground isn't re-covered. OpenQuota
(github.com/deviffyy/OpenQuota) is a desktop app that reads the developer's own machine. Most
of what looks enviable in it depends on that, and no amount of Android work recovers it.

**Everything downstream of local Claude Code JSONL logs.** OpenQuota scans `~/.claude` (and
`CLAUDE_CONFIG_DIR`) for the CLI's own usage records. A phone has no Claude Code install and
no logs, so all of this is structurally unavailable, not merely unbuilt:
- Today / Yesterday / Last-30-days **token counts**, and the daily series behind them.
- **Estimated spend in dollars** for a subscription account — their headline number.
- The **per-model token breakdown** with variants, and its `unknownModels` reporting.
- The whole **`pricing/`** subsystem: bundled LiteLLM and models.dev snapshots, refetched
  daily, with a compact codec and defaulting rules (cache-write defaults to the input rate,
  cache-read to a tenth of it). Impressive and irrelevant to us.

Our history is percent-over-time from polling (`HistoryStore`, `SessionLog`), which answers a
different question and is the only question a phone client *can* answer. **Do not read
"estimated spend" in their README as something we're missing** — the one money figure we can
show, we already show (`SpendCredits`, CCRM-1), and we get it from the API rather than by
pricing tokens ourselves.

**Desktop-platform features with no Android counterpart:** launch at login, the tray and
menu-bar rendering (`MenuBarStyle::{Text, Bars}`), the global keyboard shortcut (nearest
analogue filed as CCRM-33 (App Shortcuts)), the single-instance contract, webview memory
trimming, XDG autostart, and cryptographically signed auto-installing updates (deliberately
not wanted — see CCRM-28 (Auto Update Check)).

**Their multi-account discovery mechanism** — scanning for separate `CLAUDE_CONFIG_DIR` homes
— is inapplicable. The *model* is still a useful reference for CCRM-6 (Multi-Account):
accounts as their own cards, hidden when the login disappears, and their customization
restored intact when it returns.

**Already covered, checked line by line — no gap:** cache-first render then background
refresh; retaining the last-good snapshot *and* showing the error; per-account rate-limit
backoff (`bumpBackoff`); renameable account labels (`profileLabel`, their "rename account
cards"); model-scoped weekly caps — **our `limits[]` walk is strictly more general than
theirs**, which hardcodes a lookup for `display_name == "Fable"`; the trend chart (CCRM-12
(Trend Chart) is richer than their `UsageTrend.svelte`); notification tap-to-open (CCRM-2
(Notification Tap Target)); and the pace projection maths, where our `Projection` and their
`pacing.rs` independently agree on `used / elapsedFraction`.
