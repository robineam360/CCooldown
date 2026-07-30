# Roadmap

Future work for Claude Cooldown. Each item has a stable ID (`CCRM-N`) — use it in
commits and notes. IDs never change or get reused; only status and priority move.

Sections are ordered by when we intend to build them. **Within a section, items are
in priority order.** Bugs live in [BUGS.md](BUGS.md) (`CCBG-N`), not here.

**Status legend:** `Planned` · `Needs design` · `In progress` · `Blocked` · `Done` · `Dropped`

---

## Next — small, high value, ready to build

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

## Needs design — decide the shape before building

### CCRM-17 · Window Pings — start a 5-hour window on a schedule
- **Status:** Needs design · **spike DONE, feature is viable** — mechanism proven end
  to end 2026-07-30; what remains is a product call on posture and defaults, not a
  technical unknown
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
- **Status:** Planned · small
- **Why:** The pace chart's warning half — the amber overshoot fill, the wash over the
  above-pace region, and the bold warning-coloured readout — has **never rendered on real
  hardware**. Every window on every account we have sits below pace, so it has only ever
  been seen in a wireframe. It shipped in v1.1 unobserved.
- **Approach:** either wait for a window to genuinely cross (Work's 5-hour window has been
  climbing at ~18%/h, so this may happen on its own), or add a debug-only override that
  forces the chart to plot a synthetic above-pace series so the state can be inspected and
  screenshotted on demand. The latter is worth having regardless — it's the only way to
  screenshot the state for the docs.
- **Then:** capture it for the guide, which currently illustrates only the below-pace case.

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

---

## Bookends — major efforts, gated on the above

### CCRM-7 · iOS — an iOS version of the app
- **Status:** Planned · gated → v2.0
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
  **Unverified risk:** the token endpoint's WAF blocks empty and browser-shaped
  User-Agents; `URLSession`'s default is library-shaped and should pass, but set an
  explicit `CCooldown/<version>` UA and confirm a real exchange before shipping —
  see API-CONTRACT §2.
- **Sync discipline once there are two clients:** keep `CCRM`/`CCBG` here as the single
  ID space; label every issue `layer:core` (must reach both) or `layer:platform` (stays
  local); when a core bug is found, **add a failing fixture to `contract/` first** so
  both clients go red until fixed. Android at v1.1 is the reference implementation —
  where behaviour is disputed, it is correct until the spec is deliberately changed.
- **Parity is bandwidth-bound, not architecture-bound.** Android leads; Mac follows and
  may lag. Say so publicly rather than implying parity we won't sustain.

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
