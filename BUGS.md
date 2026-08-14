# Bugs

Tracked defects for Claude Cooldown. Each has a stable ID (`CCBG-N`) — use it in
commits. IDs never change or get reused; only status moves. Feature work lives in
[ROADMAP.md](ROADMAP.md) (`CCRM-N`).

**Status legend:** `Open` · `In progress` · `Fixed` · `Won't fix`
**Severity:** `High` (data loss / crash / wrong numbers) · `Medium` · `Low`

---

## Open

### CCBG-3 · Credits Visibility — credits card ignores extra-usage being switched off
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

### CCBG-11 · Ring Face Clutter — four stacked lines crowd the small ring's bore
- **Status:** Fixed (2026-08-13) · needs on-device verification
- **Severity:** Low (legibility, no wrong numbers)
- **Symptom:** Observed on the Fold 7 outer screen, 2026-08-13, by the user: the
  CCRM-39 (Ring Widget) face reads as cluttered. Four lines stack inside the ring's
  bore — profile name, percentage, countdown, exact reset — and the fourth
  ("today at 9:10 pm") runs nearly wall to wall inside the bore, so the ring's inner
  edge crowds the text on both sides. Evidence:
  `release/screenshots/widget-ring-clutter-fold-outer.png`.
- **Detail:** the four lines are fixed regardless of the widget's size, so the bore
  has to hold all of them however small it gets; nothing drops out as the face
  shrinks. The exact reset stamp is the weakest of the four — the countdown above it
  already answers "when", and the in-app card and the notification both carry the
  exact time — which makes it the obvious candidate to drop, move under the ring, or
  gate on available height.
- **Not a regression from CCRM-43 (Bar Pace Marks):** the pace tick is drawn on the
  ring's stroke, outside the bore, and doesn't touch the text stack.
- **Worse than reported, at the size that matters.** The provider declares this widget
  **2×2, minimum 110×110 dp** (`ring_widget_info.xml`), so small is the intended shape and
  the 178×240 placement is the outlier. At 110×110 the bore holds about 51 dp of text and
  the stack needs about 71 — it doesn't crowd, it **overflows**, with the reset line
  crossing the ring stroke. Nobody had looked at the face at its own declared size.
- **Fixed — the bore holds the percentage and nothing else.** Profile, countdown and exact
  reset all moved outside the ring, which is what frees the ring to reach the edge of the
  face and stay legible small. [WidgetFace.kt](app/src/main/java/com/robin/claudeusage/widget/WidgetFace.kt)'s
  new `ringFaceLayout` picks a size class from the face: 110×110 → an 81 dp ring, 21 sp
  number, countdown under it; 150×150 → 108 dp with the account name above; 178×240 →
  140 dp (**capped** — past that a solo ring is a poster, not a gauge) with the exact reset
  as a second line under; wide and short → the lines in a column beside the ring. Pinned by
  `WidgetFaceTest`. Wireframe rev B approved 2026-08-13.

### CCBG-10 · Mini-Rings Emptiness — two rings marooned in a mostly empty face
- **Status:** Fixed (2026-08-13) · needs on-device verification
- **Severity:** Low (wasted space, no wrong numbers)
- **Symptom:** Observed on the Fold 7 outer screen, 2026-08-13, by the user: on an
  account with only two windows, the CCRM-40 (Mini-Rings Widget) face is mostly empty
  — two small rings sit in wide gutters with a dead band of roughly a third of the
  height beneath them. Evidence:
  `release/screenshots/widget-mini-rings-empty-fold-outer.png`.
- **Detail:** two independent causes, both in `widget/MiniRingsWidget.kt`. The ring is
  drawn at a **fixed 56 dp** (`ringBitmap(context, 56f, 5.5f, …)`) no matter how much
  room the face has; and the columns divide the **full width** between however few
  rows `windowRows` returns, so two rings on a 4×2 face spread to the quarter points
  and leave the middle empty. An account with four windows fills the same face
  reasonably, which is why this didn't show up in the wireframe.
- **It also overflows at its own declared minimum.** At 250×110 dp — what
  `mini_rings_widget_info.xml` asks for — a fixed 56 dp ring plus both text lines don't fit,
  so the countdown is clipped. The emptiness and the clipping are the same bug seen from
  two ends: a ring that ignores the face.
- **Fixed — the ring follows the space, and the row stops spreading.** `miniRingsLayout` in
  [WidgetFace.kt](app/src/main/java/com/robin/claudeusage/widget/WidgetFace.kt) computes the
  diameter from the column width and row height (ceiling **88 dp**, floor 36), scales the
  stroke and the percentage with it, and caps the column so one or two rings centre as a
  group instead of sitting at the quarter points. On a short face the countdown drops
  first, then the title — the ring never gives way. The row cap also drops **four → three**
  (`windowRows(data, max = 3)`), which is what allows a ceiling that high; the other caller
  keeps four. On the reported face the ring goes 56 → 88 dp. Pinned by `WidgetFaceTest`.
  Wireframe rev B approved 2026-08-13.

### CCBG-8 · Sonnet Cap Fallback — model caps vanish silently if the `limits` array is absent
- **Status:** Fixed (2026-08-07)
- **Fix as specced below**, plus one extension the captured payloads justify:
  when `limits` yields no caps, `UsageParser.parse()` now falls back to **both**
  attested flat siblings — `seven_day_sonnet` (OpenQuota reads it) *and*
  `seven_day_opus`, which is not a guess: it appears (as `null`) in our own 2026-07-27
  captured payload, so the field exists in the schema. No other `seven_day_*` model
  field is attested anywhere, and none was added.
- **Tests, which were the point:** a flat-fields-only payload (the older schema — what a
  server-side rollback would send) must yield both caps with their percents, and
  `realPayload` — `limits` present, `seven_day_opus: null` — must keep an empty cap
  list, pinning that a null flat field never materialises a cap. 85 tests, 0 failures.
- **Severity was:** Open · **Low** · latent, never observed
- **Found:** 2026-08-04, reading OpenQuota's Claude mapper (`src-tauri/src/providers/claude/
  mapper.rs`) alongside ours during the CCRM-21…38 review. Not a live failure — a gap in the
  fallback path.
- **What's wrong:** `UsageParser.parse()` reads the `limits` array first and derives the
  session window, the weekly window and every `weekly_scoped` model cap from it. When
  `limits` is missing it falls back to the flat sibling fields — but only two of them:

  ```
  if (session == null) session = windowFrom(root.optJSONObject("five_hour"))
  if (weekly == null)  weekly  = windowFrom(root.optJSONObject("seven_day"))
  ```

  There is no fallback for **`seven_day_sonnet`**, which OpenQuota reads directly as its
  Sonnet window. So if `limits` ever disappears from the payload, the two main bars degrade
  correctly and the model caps **silently become an empty list** — no cap rows on the main
  screen, no cap alerts, and no error anywhere, because `UsageData` with a session and a
  weekly window is a perfectly valid parse.
- **Why it matters despite being latent:** the schema is undocumented and carries transient
  experiment fields — the parser's own doc comment says so. `limits` is the newer shape; the
  flat fields are the older one. A rollback on Anthropic's side is exactly the scenario the
  fallback exists for, and it's the scenario where this half-works. The failure is also the
  hardest kind to notice: a *missing* row reads as "this account has no model caps", which is
  a legitimate state for some accounts.
- **Fix:** add `if (caps.isEmpty()) windowFrom(root.optJSONObject("seven_day_sonnet"))` as a
  `ModelCap("Sonnet", …)`, and cover it in `UsageParserTest` with a payload that has the flat
  fields and no `limits` — the test matters more than the two lines, since the whole point is
  that nothing currently proves the fallback path is complete.
- **Check while in there:** whether any other `weekly_scoped` model has a flat sibling field
  (an Opus- or Fable-shaped `seven_day_*`). Only `seven_day_sonnet` is confirmed to exist, via
  OpenQuota reading it; the rest is unknown and shouldn't be guessed at.

### CCBG-6 · Credits Denominator — the bar measures the monthly limit, not the balance that would actually stop you
- **Status:** **Won't fix (2026-08-07)** — until the server populates `spend.balance`.
  The one open question is answered, and the answer is no: **our subscription OAuth
  bearer does not authenticate on `claude.ai/api/…`.** Probed on-device through the
  Settings → Debug endpoint probe, both profiles, same result:
  `GET https://claude.ai/api/organizations` → **HTTP 403**,
  `{"type":"error","error":{"type":"permission_error","message":"Invalid authorization",
  "details":{"error_visibility":"user_facing","error_code":"account_session_invalid"}}}`
  (request ids `req_011CdoXhGxQMXVmApE7kF1Kt` Personal, `req_011CdoXw5TjibmHrYrm1dUG6`
  Work). That is a clean JSON auth rejection from the API itself — not a WAF shape, so
  no UA-variant retry applies; the endpoint wants a claude.ai browser *session*
  (`account_session_invalid`), which we don't have and shouldn't fake. `/api/bootstrap`
  returned 200 with anonymous content (feature-gate config), consistent with a
  session-gated API. This was the outcome the entry named as possible.
- **Shipped anyway — the inert remnant, so the fix arms itself** (2026-08-07):
  `SpendCredits.balanceMinor: Long?` parsed from `spend.balance` (null in every payload
  ever captured; absence ≠ zero), and `bindingRemainingMinor` =
  `min(monthly remainder, balance)` — the balance alone when uncapped, the remainder
  alone while the balance is absent. Every "left" figure (`MainActivity` card,
  `UsageWidget` large bucket, `BarWidget` credits mode) now reads the binding figure.
  With no balance it equals `remainingMinor` in every state, so **today's rendering is
  unchanged — pinned by test, not by inspection**; the day the server populates
  `spend.balance`, the binding constraint lights up without an app update. The balance
  *line* from the agreed display fix is deliberately **not** built — it would be a
  visual state that cannot be observed on any device, which is the CCRM-15 (Above-Pace
  Verification) failure mode. 6 new `UsageParserTest` cases (83 total, 0 failures)
  cover both captured payloads' null balance, binding in all four states, and a
  hypothetical populated `spend.balance`.
- **Reopen when:** `spend.balance` stops being null on `/api/oauth/usage` (the parser
  and binding maths are already live — what remains then is the balance line, per the
  agreed three-row display fix below), or Anthropic exposes the balance to OAuth
  clients some other way.
- **Severity:** Medium, and it worsens on its own — see the divergence note below
- **Symptom:** The credits meter always reads `$X / $100.00`, because the denominator is
  the *monthly spend limit*. The Claude app's own Usage tab shows a third figure we don't
  read at all — a **Balance** — and it is the number that actually runs out.
- **Confirmed against the Claude Android app, 2026-08-03.** Its Usage → Credits section
  showed three rows where we show one:

  | Row | Value |
  | --- | --- |
  | This month | `$2.97 of $100.00 spent` |
  | Monthly spend limit | `100 credits` |
  | Balance | `91.04 credits` |

- **What the arithmetic settles:** our captured 2026-07-27 payload had `used` = `$5.99`,
  and August's spend is `$2.97`. `100 − 5.99 − 2.97 = 91.04` **exactly**. So the balance
  is a **cumulative pot that does not reset monthly**, while `spend.used` / "This month"
  does. The monthly limit caps a month; the balance is the money that exists. Real
  headroom is `min(monthly limit − used, balance)` — today `min(97.03, 91.04)`.
- **Why it gets worse rather than staying cosmetic:** the balance only ever decreases
  while the monthly counter resets, so the two diverge monotonically. At a balance of 8
  credits our card would still read `$2.97 of $100.00 · 3% used · $97.03 left` — a full
  bar's worth of headroom that isn't there. The bug is mildest on the day it's filed.
- **The blocker, established 2026-08-03 and not a guess.** Both profiles' raw responses
  were captured minutes after the screenshot above. **`"balance":null` on both.** The
  Personal payload pins itself to that exact screenshot — `spend.used.amount_minor: 297`
  = the `$2.97` row, `limits[weekly_all].percent: 24` = the `24% used` row — so this is
  the same account in the same state, showing `91.04 credits` in the Claude app while
  this endpoint reports no balance at all.
  - So it is **not** a shape or denomination question, and not a field we failed to
    parse: the number is absent from the response. The Claude app sources it elsewhere.
  - `can_purchase_credits: false` and `auto_reload: null` on both profiles, so the pot
    is **granted, not purchased** — `balance`/`auto_reload` being null is evidently about
    the *purchase* flow, not about whether a balance exists.
  - Also captured: a batch of new all-null experiment windows (`seven_day_cowork`,
    `nimbus_quill`, `cinder_cove`, `amber_ladder`, …). The parser ignores unknown keys,
    so they cost nothing — but none of them carries a balance either.
- **Rejected: deriving it locally.** `cap.credits − Σ(monthly spend)` would need a spend
  history we don't have, and would be silently wrong for any install that starts
  mid-life — which is every install. A wrong balance is worse than no balance line.
- **Source located 2026-08-04 — the Claude app uses a different API family entirely.**
  Method: pulled `base.apk` from the phone over wireless adb (Claude `1.260721.20`,
  `com.anthropic.claude`) and string-searched `classes*.dex`, the same technique
  [ApiClient.kt](app/src/main/java/com/robin/claudeusage/data/ApiClient.kt) records for
  the token endpoint. Findings:
  - **Zero `api/oauth/` literals in the whole dex.** The app never calls the endpoint we
    call, so there is no OAuth-side sibling to find — that search is closed.
  - Base URL `https://api.claude.ai`; the only usage path is the Retrofit template
    **`organizations/{orgId}/usage`**. Comparable templates
    (`organizations/{organization}/chat_conversations`) match known `claude.ai/api/…`
    URLs, so the full shape is `https://api.claude.ai/api/organizations/{uuid}/usage`.
  - Models: `com.anthropic.claude.api.usage.UsageResponse`,
    `com.anthropic.claude.api.common.SpendSummary`,
    `com.anthropic.claude.api.common.Credits`.
  - Serialized names in the dex include **`balance_credits`** and **`granted_credits`**,
    plus `free_credits_status`, `amount_minor`, `exponent`, `org_spend_cap_reached`,
    `out_of_credits`. `granted_credits` corroborates the granted-pot reading above.
  - **Caveat on the field names:** the dex string table is sorted, so adjacency proves
    nothing about which class owns which name. `balance_credits_after` reads like
    purchase analytics, not a response field. Treat the list as candidates.
  - Also present: `organizations/{organization_uuid}/prepaid/iap/android` — Play-billing
    credit purchase, i.e. the flow the support article's "Add funds" describes.
- **`api.claude.ai` does not exist — the APK constant is dead.** First probe run
  (2026-08-04) returned `HTTP 0 · Unable to resolve host "api.claude.ai": No address
  associated with hostname`. Confirmed off-device: `dig api.claude.ai` returns **no A
  record**, while `claude.ai` and `api.anthropic.com` both resolve — to the same address,
  `160.79.104.10`. So it isn't DNS filtering on our network; that hostname has no
  addresses at all, and the live origin is **`claude.ai`**. `ApiClient.ProbeHost.CLAUDE_AI`
  now points there, pinned by `ProbePathTest`.
- **The question that decided it** (answered 2026-08-07 — see the status above): does our
  subscription OAuth bearer authenticate against `claude.ai/api/…`? It was proven only on
  `api.anthropic.com` with `anthropic-beta: oauth-2025-04-20`, and claude.ai rejects it.
- **Probe control verified:** `/api/oauth/usage` on `api.anthropic.com` returns 200 through
  the probe, so the plumbing is sound and any non-200 elsewhere is a real answer.
- **Dead ends, so nobody repeats them:** release logcat carries no request URLs; there is
  no DevTools socket to attach to; and `run-as` is refused because the installed build is
  release-signed — so the token cannot be read off the device (by design).
- **Where the denominator comes from:** `spend.limit.amount_minor` in `creditsFrom()`
  ([Models.kt](app/src/main/java/com/robin/claudeusage/data/Models.kt)), fed into
  `SpendCredits.percent` / `remainingMinor` and read by the card in `MainActivity`, the
  large bucket of `UsageWidget`, and `BarWidget`. `extra_usage.monthly_limit` reports the
  same figure on the fallback path — this is the user-set monthly extra-usage cap.
- **Fields in play:**
  - `spend.balance` — the one we want. `null` on both profiles, even with a live balance.
    **Parsed as of 2026-08-07** (the inert remnant above), waiting for the server.
  - `spend.cap` — `{"money": null, "credits": {"amount_minor": 10000, "exponent": 2}}`.
    `cap.credits` = `10000` lines up with the app's **`100 credits`** monthly-limit row,
    while `spend.limit` = `10000` lines up with **`$100.00`**. So credits and money are
    1:1 on this account but reported through different fields — don't collapse them.
- **Fix, decided (agreed 2026-08-03) — do NOT fold the balance into the denominator.** The Claude app shows
  three separate rows rather than one derived ratio, and it's right to: `used / (used +
  balance)` mixes a monthly counter with a cumulative pot and would print a `$94.01`
  that appears nowhere in the payload. Instead:
  - Keep the bar against the monthly limit — that *is* a true monthly ratio, and it
    matches the upstream "This month" row.
  - Add a balance line, and make the trailing "left" figure report **whichever
    constraint binds**: the balance when it is below the monthly remainder, the monthly
    remainder otherwise. The misleading part today is the `left` number, not the bar.
  - Parse into `SpendCredits.balanceMinor: Long?` so absence stays distinguishable from
    zero, and keep `remainingMinor` as-is (`limit − used`) so nothing silently changes
    meaning under existing callers — `MainActivity`, `UsageWidget`, `BarWidget`.
  - Pin it with a `UsageParserTest` case on the real captured payload, asserting both
    the non-null balance and the binding-constraint choice.

### CCBG-9 · Credits Vanish — the whole credits section disappeared when the account had no monthly limit
- **Status:** Fixed (2026-08-04)
- **Severity:** Medium (a whole section silently gone, and a widget asserting something
  false) — no data loss
- **Symptom:** The Usage credits card was **absent from the main screen** despite $2.97 of
  spend, and a `BarWidget` set to *Usage credits* read **"No credits · This account has no
  credit budget"** — untrue: spend was live, `spend.enabled` was `true`, and the Claude app
  showed a 91.04-credit balance.
- **Trigger, confirmed by the user:** they **switched the monthly spend limit off**
  (unlimited). The payload then returns `spend.limit: null`, `spend.cap: null` and
  `extra_usage.monthly_limit: null` while still reporting `spend.used.amount_minor: 297`.
  So this is a **user-reachable setting**, not a server glitch — anyone who turns the cap
  off loses the whole section.
- **Root cause — both branches of `creditsFrom()` bailed on a null limit**
  ([Models.kt](app/src/main/java/com/robin/claudeusage/data/Models.kt)):
  `optJSONObject("limit")` returns null for a JSON `null`, so the preferred `spend` branch
  was skipped even though `used` parsed fine; the fallback then hit
  `if (extra.isNull("monthly_limit")) return null`. `UsageData.credits` came back null and
  every consumer's `limitMinor > 0` render gate failed. Verified before fixing by parsing
  the captured payload: windows fine (`session 15.0`, `weekly 31.0`), `credits` null.
- **The bad assumption:** CCRM-1 (Credits Display)'s visibility rule, "render whenever
  `limit > 0`", equated *having a cap* with *having credits*. An uncapped account has
  credits and no cap.
- **Fix — the limit is optional data, never the existence test.**
  - `SpendCredits.limitMinor` is now `Long?`. `null` (no cap) and `0` (capped at zero) are
    different states and no longer render alike.
  - `percent`, `percentDisplay` and `remainingMinor` are **nullable, and null when
    uncapped**. Nothing is synthesised — a 0% bar would draw empty and read as "plenty of
    headroom" against a ceiling that doesn't exist.
  - New `isReportable` (`hasLimit || usedMinor > 0`) replaces `limitMinor > 0` as the
    render gate in `MainActivity`, `UsageWidget` and `BarWidget`. Accounts with no cap and
    no spend stay hidden — deliberately conservative, since they can't be told from a
    no-credits account without trusting `spend.enabled`, still unverified (CCBG-3
    (Credits Visibility)).
  - **Uncapped rendering:** the card reads `Usage credits · $2.97 spent · No cap` with
    **no bar**, and the trailing line "No monthly spend limit — credits cover you when you
    hit your plan limits". `UsageWidget`'s large bucket swaps its bar for a plain
    `Credits · $2.97 spent · no cap` row; `BarWidget` drops the bar, promotes the amount
    into the headline slot, and its sub-row reads `no monthly cap`.
- **Tests:** 3 new `UsageParserTest` cases pinning the captured 2026-08-04 payload — that
  credits survive a null limit, that no percentage or remainder is invented, and that the
  `extra_usage` fallback behaves identically. Two existing cases updated for the nullable
  contract. `./gradlew testDebugUnitTest` → 77 tests, 0 failures.
- **Found via:** the CCBG-6 (Credits Denominator) endpoint probe's *control* request. The
  probe was built to chase the balance and caught a live defect in its first payload
  instead.
- **Consequence for CCBG-6 (Credits Denominator):** with the cap off, the balance is now
  the **only** ceiling that exists — so the missing balance line is the whole story on this
  account, not a refinement.
- **Device verification:** the uncapped **card** was seen on the phone 2026-08-07 while
  verifying CCBG-6 (Credits Denominator)'s remnant build — `Usage credits · $10.75 spent ·
  No cap`, with the no-limit trailing line, exactly as designed. The two **widget**
  layouts (UsageWidget large row, BarWidget credits mode) are still unobserved; CCRM-15
  (Above-Pace Verification) exists because a visual state shipped unobserved — so look at
  them when one is next placed.

### CCBG-7 · Chart Label Collision — RETRACTED, never a bug
- **Status:** **Invalid — retracted 2026-08-04, same day it was filed.** Kept because IDs
  are never reused, and because the way it was filed is the lesson.
- **Claimed:** that a window projected to 100% drew its `~100%` label on top of the
  `100%` threshold label in the right-hand gutter.
- **Why it cannot happen.** The gutter labels are drawn at `plotRight + 3.dp`
  ([Sparkline.kt:325](app/src/main/java/com/robin/claudeusage/ui/Sparkline.kt#L325)), and
  the projection label's x is clamped to `coerceIn(0f, plotRight - width)`
  ([Sparkline.kt:418](app/src/main/java/com/robin/claudeusage/ui/Sparkline.kt#L418)), so
  its right edge can never pass `plotRight`. The two are separated by construction, in
  every state — not just the one that was inspected.
- **How it got filed:** from a screenshot downscaled to about 90% of its width, in which
  the two labels sit close together and read as overlapping. At full resolution they are
  plainly apart. The lesson is narrow and worth keeping: **verify a pixel-level claim at
  pixel resolution**, and prefer the geometry in the source over a reading of an image.
- **What is actually there**, at much lower severity and not filed as a bug: the `~100%`
  label is drawn across the even-pace diagonal and its own projection dashes, so the
  glyphs have dashed lines running through them. Legible, but not clean. A
  surface-coloured backing behind the text — the same treatment CCRM-20's tap callout
  already uses — would settle it if it ever becomes worth doing.

### CCBG-5 · Ping Verification — a working ping is reported as a failure
- **Status:** Fixed (2026-07-31)
- **Severity:** High (reported the opposite of what happened, and turned one ping into
  several)
- **Symptom:** observed on the Fold 7, 2026-07-30. **Test ping now** on Personal, from a
  genuinely cold state, reported `Ping sent but no window opened — nothing scheduled`.
  The ping had in fact worked — the 5-hour card later showed a live window.
- **Second symptom, worse than the first:** because "no window seen" counted as failure,
  it entered the *send*-retry backoff and pinged again at 1/3/8 minutes. The 2026-07-30
  run sent roughly four pings where one was wanted, and only recorded success at 8:13 pm
  once the window finally became visible. The false report was cosmetic; the ping storm
  was not.
- **Root cause: the usage endpoint lags the inference.** `sendWindowPing` sends the
  ping, immediately re-reads usage, and treats "no window in the response" as failure.
  But the window is not visible that soon.
  - **It is not rate limiting.** That was my first guess and the evidence refutes it:
    the main screen read `Last success: Thu 8:08 pm`, so the post-ping fetch *succeeded*
    — it just still showed no window. (A 429 did appear at 8:09, but that was a later,
    separate refresh triggered by returning to the main screen.)
  - Timeline: 8:06 pm no window · 8:08 pm ping `200`, successful usage read still shows
    no window · 8:41 pm window present, `[7:59 pm → 12:59 am]`.
  - **Measured lag:** invisible seconds after the ping; visible by 8:13 pm, i.e. within
    about **five minutes**. (An earlier reading put it at "no more than 33 minutes"; the
    8:13 pm success narrows it.) The 15s settle in the spike script would not have been
    reliably enough.
- **Consequence: synchronous verification cannot work at all.** Any read taken straight
  after a ping may legitimately show nothing, so "no window yet" carries no information
  about whether the ping succeeded.
- **Fix — send and verify are now separate operations.**
  - `UsageRepository.sendWindowPing()` reports only the *send* outcome (`Sent`,
    `AlreadyOpen`, or a real failure). It records the pre-ping `resets_at` and stamps
    the send time, then stops.
  - `UsageRepository.verifyWindowPing()` runs later from its own alarm
    (`PingScheduler.ACTION_VERIFY`) at +90s, with one re-check at +4min —
    `VERIFY_DELAY_MS`, `VERIFY_RETRY_MS`, `MAX_VERIFY_ATTEMPTS`. Together they reach
    past the observed lag, and a test pins that they do.
  - **`NotYet` / `GaveUp` are not failures and never notify.** Only send failures
    (non-200, auth, network) retry or raise `notifyPingFailed`.
  - A refresh that fails during verification yields `NotYet`, not evidence — the old
    code ignored its own `FetchResult` entirely.
  - **`PingSchedule.tooSoonToSend` / `MIN_SEND_INTERVAL_MS` (10 min)** is a hard floor
    between sends regardless of what the rest of the logic concludes. This is the
    backstop against the storm: worst case is one wasted ping, never a burst.
  - **On giving up, the window counts as started.** Every observation says pings work,
    and the send floor already prevents a burst, so miscounting one window is far
    cheaper than re-pinging in a loop.
- **Also fixed:** the settings status row never refreshed — it read `pingLastResult`
  during composition with nothing to trigger recomposition when the alarm wrote it, so
  it sat on a day-old result while a ping came and went. Now polls a revision counter.
- **Also:** the toggle's subtitle now says that turning it on starts a window
  immediately, which is what it has always done — both 2026-07-30 pings were fired by
  `rearm()` on enable, not by the Test button.
- **Tests:** 4 cases in `PingScheduleTest` (16 → 20) covering the send floor and an
  assertion that the verification window both covers the observed lag and finishes
  inside the send floor. 60 tests, 0 failures.
- **Found via:** the first on-device run of CCRM-17 (Window Pings).
- **Not yet device-verified:** the deferred path has not run on the phone.

### CCBG-4 · Alert Dedup — threshold alerts re-fired every poll because `resets_at` isn't stable
- **Status:** Fixed (2026-07-30)
- **Severity:** High (repeat notifications; the dedup that existed didn't work)
- **Symptom:** once a window was past its lowest threshold (80% session, 90% weekly),
  the usage alert re-fired on **every poll** — every 15 min on the default interval —
  instead of once per window. `notify()` sets no `setOnlyAlertOnce`, so each repeat
  re-alerted with sound rather than quietly updating in place.
- **Root cause:** the same server-side `resets_at` drift as [CCBG-2](#ccbg-2--trend-chart-binding--trend-chart-and-projection-almost-never-appeared),
  in a consumer that fix didn't touch. `checkThresholds` used `resetsAt.toEpochMilli()`
  as window identity and compared it **exactly**:

  ```kotlin
  if (cache.alertKey(profile, keyName) != windowKey) {
      cache.setAlertState(profile, keyName, windowKey, 0)   // wipes "already notified"
  }
  val alreadyNotified = cache.alertThreshold(profile, keyName)   // reads back 0
  ```

  The key differed on every poll, so the state was wiped every poll, `alreadyNotified`
  was always 0, and `crossed` re-selected the lowest threshold already passed.
  Confirmed on the Work account 2026-07-30 — five polls 60s apart inside one unchanged
  window returned five distinct values:

  ```
  2026-07-30T09:19:59.913124+00:00  -> 1785403199913
  2026-07-30T09:19:59.625243+00:00  -> 1785403199625
  2026-07-30T09:20:00.333280+00:00  -> 1785403200333
  2026-07-30T09:20:00.950515+00:00  -> 1785403200950
  2026-07-30T09:20:00.698040+00:00  -> 1785403200698
  ```
- **Fix — tolerance, NOT truncation.** New `Projection.sameWindow(a, b, windowLengthMs)`
  reusing the existing `windowLength / 4` tolerance, so both consumers of `resets_at`
  now share one definition of window identity. `Alerts.checkThresholds` and
  `checkReset` take a `windowLengthMs` and compare through it;
  `Projection.SESSION_MS` / `WEEKLY_MS` are now the single source of truth for the
  lengths the tolerance derives from (`MainActivity` and `SettingsScreen` had their own
  copies — one a `Duration` pair, the other bare literals).
  - **Truncation was the tempting fix and is wrong:** the measured drift straddles the
    `09:20:00` boundary, so minute-truncation still flips between `09:19` and `09:20` —
    unstable less often, which is worse than never, because it looks fixed. Pinned by a
    regression test.
  - **Same-window re-anchoring:** on a matching window the stored key is refreshed to
    the newest reading (threshold preserved), so each comparison spans a single poll
    interval and the slide can't accumulate past tolerance over a 7-day window.
  - **Rejected: `setOnlyAlertOnce`.** Floated as belt-and-braces in the original
    report, but both thresholds share one notification id, so it would have muted the
    legitimate 80% → 95% escalation. The tolerance fix is sufficient on its own.
- **Also fixed:** the narrow `checkReset` race — a poll landing within ~1s of a true
  boundary could see drift push `lastSeen` into the past and spuriously log a closed
  window plus a reset ping. CCBG-2 audited `checkReset` and correctly cleared it for the
  *steady-state* case (the `isBefore(Instant.now())` guard holds while a window is open)
  but not the boundary case, and didn't audit `checkThresholds` at all.
- **Tests:** 5 cases in `ProjectionTest` (14 → 19), including all-pairs over the five
  real measured values and an explicit assertion that minute-truncation would have
  split them. `./gradlew testDebugUnitTest` → 31 tests, 0 failures.
- **Found via:** the [CCRM-17](ROADMAP.md) ping spike, which snapshotted usage either
  side of an inference call and showed `resets_at` moving when nothing had changed.
- **Rule going forward:** never compare `resets_at` with `==`, and never normalise it by
  truncation. Go through `Projection.sameWindow`.

### CCBG-2 · Trend Chart Binding — trend chart and projection almost never appeared
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

### CCBG-1 · History Retention — usage history is wiped on sign-out / re-auth
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
