# Bugs

Tracked defects for Claude Cooldown. Each has a stable ID (`CCBG-N`) — use it in
commits. IDs never change or get reused; only status moves. Feature work lives in
[ROADMAP.md](ROADMAP.md) (`CCRM-N`).

**Status legend:** `Open` · `In progress` · `Fixed` · `Won't fix`
**Severity:** `High` (data loss / crash / wrong numbers) · `Medium` · `Low`

---

## Open

_Nothing open._

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
- **Follow-up:** nothing clears history any more. `HistoryStore.clear()` and
  `SessionLog.clear()` are now callerless. If a genuine account switch should
  start clean, that needs its own explicit affordance (or the account-comparison
  variant below) — worth a roadmap item rather than re-coupling it to sign-out.
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
