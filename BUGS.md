# Bugs

Tracked defects for Claude Cooldown. Each has a stable ID (`CCBG-N`) — use it in
commits. IDs never change or get reused; only status moves. Feature work lives in
[ROADMAP.md](ROADMAP.md) (`CCRM-N`).

**Status legend:** `Open` · `In progress` · `Fixed` · `Won't fix`
**Severity:** `High` (data loss / crash / wrong numbers) · `Medium` · `Low`

---

## Open

### CCBG-4 · Threshold alerts re-fire every poll — `resets_at` is not stable
- **Status:** Open
- **Severity:** High (repeat notifications; the dedup that exists doesn't work)
- **Symptom:** once a window is past its lowest threshold (80% for the session, 90%
  weekly), the usage alert re-fires on **every poll** — every 15 min on the default
  interval — instead of once per window. `notify()` sets no `setOnlyAlertOnce`, so each
  repeat re-alerts with sound/vibration rather than quietly updating in place.
- **Root cause:** the server **recomputes `resets_at` per request**; it is not a stored
  constant. Measured on the Work account 2026-07-30, five polls 60s apart inside one
  window:

  ```
  2026-07-30T09:19:59.913124+00:00  -> 1785403199913
  2026-07-30T09:19:59.625243+00:00  -> 1785403199625
  2026-07-30T09:20:00.333280+00:00  -> 1785403200333
  2026-07-30T09:20:00.950515+00:00  -> 1785403200950
  2026-07-30T09:20:00.698040+00:00  -> 1785403200698
  ```

  ~1.3s of spread, all five distinct. `checkThresholds`
  ([Alerts.kt:253](app/src/main/java/com/robin/claudeusage/alerts/Alerts.kt#L253)) uses
  `resetsAt.toEpochMilli()` as the window identity and compares it exactly:

  ```kotlin
  if (cache.alertKey(profile, keyName) != windowKey) {
      cache.setAlertState(profile, keyName, windowKey, 0)   // wipes "already notified"
  }
  val alreadyNotified = cache.alertThreshold(profile, keyName)   // reads back 0
  ```

  The key differs on every poll, so the state is wiped every poll, `alreadyNotified`
  is always 0, and `crossed` re-selects the lowest threshold the percentage has passed.
- **Fix — tolerance, NOT truncation.** Truncating to the minute is the obvious move and
  is *wrong*: the observed jitter straddles the `09:20:00` boundary, so polls 1–2 would
  truncate to `09:19` and 3–5 to `09:20` — still unstable, just less often, which is a
  worse bug because it looks fixed. Compare with a tolerance instead, the way
  [Projection.kt:30](app/src/main/java/com/robin/claudeusage/data/Projection.kt#L30)
  already does (`tolerance = windowLengthMs / 4`). Consecutive windows are a full window
  apart, so anything from a minute upward separates them cleanly while absorbing ~1.3s
  of noise. Consider `setOnlyAlertOnce` as belt-and-braces.
- **Not affected:**
  - `Projection` / `HistoryStore` — already tolerance-bound (`abs(r - resetAtMs) <= tol`),
    which is why CCBG-2's fix didn't surface this.
  - `checkReset` ([Alerts.kt:212](app/src/main/java/com/robin/claudeusage/alerts/Alerts.kt#L212))
    — `lastSeen != key` is now true every poll, but the third condition
    `Instant.ofEpochMilli(lastSeen).isBefore(Instant.now())` still guards it: while a
    window is open its reset time is in the future, so the rollover branch can't fire.
    **Narrow residual risk:** a poll landing within ~1s of the true boundary could see
    jitter push `lastSeen` into the past and spuriously log a closed window + reset
    notification. Low probability at 15-min polling, and the tolerance fix removes it
    too — worth fixing in the same pass.
- **Found via:** the CCRM-17 ping spike, which snapshotted usage either side of an
  inference call and showed `resets_at` moving when nothing had changed.

### CCBG-3 · Credits card ignores extra-usage being switched off
- **Status:** Open
- **Severity:** Low (misleading display, no data loss) — and possibly unreachable
- **Symptom:** Suspected, not observed. The credits card renders whenever
  `limit > 0`, so an account that has *switched extra usage off* while keeping a
  non-zero monthly limit would still be shown a credits bar as though it were live.
- **Detail:** the payload carries `spend.enabled` and `extra_usage.is_enabled`, and
  `UsageParser` reads neither — see `creditsFrom()` in
  [Models.kt](app/src/main/java/com/robin/claudeusage/data/Models.kt). The render gate is
  the `limitMinor > 0` check in `MainActivity`, `UsageWidget` and `BarWidget`.
- **Why it's still open rather than fixed:** both flags read `true` on every account we
  can see, so the disabled-with-a-limit state has never been observed. Gating on a flag
  whose behaviour we can't verify risks hiding the card from people who should see it —
  the worse failure. Fix it when someone can actually produce the state.
- **Fix when confirmed:** treat `enabled == false` as "no credits" in `SpendCredits`, and
  add a `UsageParserTest` case with the real payload for that state.

---

## Fixed

### CCBG-2 · Trend chart and projection almost never appeared
- **Status:** Fixed (2026-07-29)
- **Severity:** High (a whole feature silently dead, and it looked like flakiness)
- **Symptom:** The burn-rate sparkline and the "At this pace…" line showed up
  seemingly at random — present on Personal, absent on Work, then gone from both —
  despite ~8 days of recorded history for each profile.
- **Root cause:** `Projection.sessionSamples`/`weeklySamples` bound history points to
  a window by **exact `resets_at` equality**. The server slides that timestamp
  forward on nearly every poll: the on-device diagnostic measured **561 distinct
  7-day `resets_at` values across 672 history points**, with only **1** matching the
  live window. A chart needs 2. So it was broken continuously; the times it did
  render were luck — two consecutive polls happening to report an identical value.
- **Fix:** bind by proximity instead. A point belongs to the window when
  `|point.resetAt − window.resetAt| ≤ windowLength / 4`. Unambiguous because a
  *genuine* reset moves `resets_at` by a full window length — four times the
  tolerance — so the previous window can never leak into the current one. This also
  rescues the ~1,300 points already on disk, which normalising on write would not.
- **Checked and ruled out:** the same drift could have fired false reset pings and
  written bogus `SessionLog` records via `Alerts.checkReset`, since it treats
  `lastSeen != key` as a rollover. It's saved by the
  `Instant.ofEpochMilli(lastSeen).isBefore(Instant.now())` guard — during drift the
  old reset time is still in the future. History bars and reset pings were unaffected.
- **Also:** with binding fixed, a window carries hundreds of points, so
  `estimate()`'s first-to-last slope became the weak link (one early burst set the
  pace for the whole window). It's now a least-squares fit over every sample, still
  anchored on the latest reading so the dashed tail meets the point the chart draws.
- **Diagnostic kept:** Settings → Debug → **Trend samples** reports bound vs distinct
  counts per window. `distinct` above 1 for a live window is the signature of drift.

### CCBG-1 · Usage history is wiped on sign-out / re-auth
- **Status:** Fixed (2026-07-27) — took the preferred option below: dropped
  `historyStore.clear(profile)` from `clearCredentials()`. Both stores are now
  untouched by the credential lifecycle, so `HistoryStore` and `SessionLog` stay
  consistent, and stale points age out via the existing 8-day prune.
- **Follow-up:** nothing clears history any more — `HistoryStore.clear()` and
  `SessionLog.clear()` are both callerless. A genuine account switch needs its own
  explicit affordance rather than re-coupling it to sign-out; filed as
  [CCRM-14](ROADMAP.md).
- **Severity:** High (silent data loss)
- **Symptom:** After clearing credentials and signing back in, the accumulated usage
  history/trend for that profile is gone.
- **Root cause:** `clearCredentials()` unconditionally deletes the history file:
  [UsageRepository.kt:71](app/src/main/java/com/robin/claudeusage/data/UsageRepository.kt#L71)
  calls `historyStore.clear(profile)`. But history is stored per **profile slot** in
  `filesDir` ([HistoryStore.kt](app/src/main/java/com/robin/claudeusage/data/HistoryStore.kt)),
  keyed by `profile.key` — it isn't tied to the token/account at all. So clearing
  credentials to re-authenticate the *same* account needlessly destroys local trend data.
- **Fix — decide the intended contract, then:**
  - **Preferred:** decouple history from the credential lifecycle. Drop
    `historyStore.clear(profile)` from `clearCredentials()`. On re-sign-in to the same
    slot, history simply continues (windows self-identify by `resetsAt`, so stale points
    age out on their own via the 8-day prune).
  - **If we want a clean slate only on a genuine account switch:** keep history on
    "Re-sign in", and clear it *only* when the newly signed-in account differs from the
    previous one (compare an account/subscription identifier), not on every clear.
  - Note the inconsistency: `clearCredentials()` does **not** clear `SessionLog` (the
    long-term per-window peak log), only `HistoryStore`. So after a clear, the history
    *bars* may survive while the raw sparkline is gone. Whatever contract we pick,
    apply it to both stores so they stay in sync.
- **Note:** also relevant to CCRM-6 (multi-account) — a dynamic profile registry should
  define history ownership per stable key from the start.
