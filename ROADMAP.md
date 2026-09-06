# Roadmap

Future work for Claude Cooldown. Each item has a stable ID (`CCRM-N`) — use it in
commits and notes. IDs never change or get reused; only status and priority move.

Sections are ordered by when we intend to build them. **Within a section, items are
in priority order.** Bugs live in [BUGS.md](BUGS.md) (`CCBG-N`), not here.

**Status legend:** `Planned` · `Needs design` · `In progress` · `Blocked` · `Done` · `Dropped`

---

## Multi-provider — Claude + ChatGPT + Gemini (Antigravity) · **the next thing we build**

**Decided 2026-09-06: the "Claude usage only" scope line is retired.** The app tracks the
5-hour / 7-day usage windows of **three** services — Claude, ChatGPT and Google's Antigravity
(the Gemini subscription) — and nothing else. Not "any AI provider": three named ones, each
with its own colour and its own mark. The 2026-08-04 appendix ruled multi-provider out because
everything enviable in OpenQuota read the developer's own machine. That is still true of *most*
of OpenQuota, but a fresh crawl of its source and of the official CLIs (four sub-agent reports,
kept in `design/research/2026-09-06-*.md`, summarised in the appendix addendum) found that the
two things we actually need — a usage endpoint and a way to mint a token — are plain HTTPS for
ChatGPT, and half-way there for Antigravity. Nothing below reads a local file or a local
process.

**The shape, in one sentence:** CCRM-6 (Multi-Account) already made *the account* the unit
every surface loops over — tabs, widgets, tiles, alerts, history, shortcuts — so a ChatGPT
account is **just another profile with a different fetcher**, and a provider is a **field on
`Profile`**, not a new axis on any screen. The user picks Claude / ChatGPT / Gemini when adding
an account and signs into that one; everything downstream already works per account.

**Review 2026-09-06 — every question answered, recorded here and in the wireframe:**
1. **Provider colour is the account's default accent, overridable per account** the way the
   theme picker works today (the picker is global today; per-account is new — see CCRM-56 (Provider Identity)).
2. **Name: Cooldown.** Package, namespace and repo stay `com.robin.claudeusage` / `CCooldown`.
3. **Icon: the hourglass with three sands**, all three colours now (not two-then-three), the
   colours made more obvious; Antigravity is greyed in the Add-account sheet as "coming".
4. **Official provider logos, not dots**, identify the service on every surface — the user's
   explicit call, overriding the trademark caution in rev A. Colour is for bars and theme.
5. **Antigravity greyed in Add account** until CCRM-55 (Antigravity Account) unblocks.
6. **Repo not renamed.** And a new rule: **a window the account does not have is not shown at
   all** — no placeholder card, no dash, on any surface.
7. **Pace ticks are neutral ink on faces that show more than one account**, resolving the
   Gemini-blue / ChatGPT-green partner clash. Confirmed on rev B.

**Rev B confirmed 2026-09-06** (B1–B3 in the wireframe): logos approved **larger** — 20 dp on
cards and tabs, 14 dp in chips and on the pinned label line, 28 dp in the Add-account sheet;
the per-account accent override and the "Per provider" swatch as drawn; absent windows hidden
everywhere and ink ticks on multi-account faces. **Nothing in this arc is waiting on a design
decision.** The step-by-step execution order lives in [RUNBOOK.md](RUNBOOK.md).

**Build order for fresh sessions** (each item below is written so a fresh Opus or Sonnet
session can build it from the text plus the wireframe, without this conversation):

| Session | Item | Model | Needs Robin? |
|---|---|---|---|
| 1 | CCRM-53 (Provider Model) — pure logic, tests | Sonnet | No |
| 2 | CCRM-54 (ChatGPT Account) part 1 — source, device flow, **payload capture** | Opus | Yes: one sign-in on the phone, own ChatGPT account |
| 3 | CCRM-56 (Provider Identity) — rename, icon, marks, per-account accent, Add-account sheet, hidden windows | Sonnet | Wireframe rev B confirmed first |
| 4 | CCRM-54 part 2 — the ChatGPT account on every surface + CCRM-57 (Provider Plumbing) | Sonnet | Device pass |
| any | CCRM-55 (Antigravity Account) **spike** — terminal on the Mac | any | Yes: Antigravity signed in on the Mac |
| — | Release v1.5: README, guide, brochure (per [RELEASING.md](RELEASING.md)) | — | — |

Sessions 1 and 3 can run in parallel; 2 depends on 1; 4 depends on 1–3.

### CCRM-53 · Provider Model — an account carries which service it tracks
- **Status:** Done (2026-09-06). Added the `Provider` enum, the `provider` field on
  `Profile` with registry persistence (absent → `CLAUDE`), the `data/source/` `UsageSource`
  seam with `ClaudeSource` wrapping the existing `ApiClient`/`UsageParser` code untouched,
  `Credentials.accountId`, `classifyWindow`, the `SpendCredits.unlimited` widening and the
  `[poll][provider:key]` log prefix rule. `./gradlew testDebugUnitTest` green (all existing
  tests unchanged, plus `WindowKindTest`, `SpendCreditsTest`, `ClaudeSourceTest` and the new
  `ProfileRegistryTest` provider cases). The device check (a release build over the live
  install still polling every Claude account with identical `[poll]` lines) is deferred to
  Step 5 of [RUNBOOK.md](RUNBOOK.md), since it needs the phone in hand.
  **Amended 2026-09-06 during CCRM-54 (ChatGPT Account) part 1:** the seam covered the
  *fetch-time* parse in `doFetch` but not the *read-time* one — `Snapshot.data` re-parses
  the cached body on every read and was hardcoded to Anthropic's `UsageParser`. A ChatGPT
  account therefore fetched HTTP 200, recorded "Last success", and showed "No data yet" on
  the tab, every widget, the tile and the pinned notification at once. `Snapshot` now
  carries `provider` (defaulting to `CLAUDE`, so nothing else moved) and parses through
  `Sources.of`, guarded so a provider with no source yet reads as no data rather than
  throwing inside a lazy the whole UI touches. `SnapshotTest` is the regression. The
  lesson generalises: a seam has to cover every place the provider's *format* is read,
  not just every place its *endpoint* is called.
- **Why:** [Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt) is
  `(key, slot, label)`; [ApiClient.kt](app/src/main/java/com/robin/claudeusage/data/ApiClient.kt),
  [OAuthSignIn.kt](app/src/main/java/com/robin/claudeusage/data/OAuthSignIn.kt) and
  `UsageParser` in [Models.kt](app/src/main/java/com/robin/claudeusage/data/Models.kt) are
  Anthropic to the byte. Everything *above* them — the registry, the per-key stores, the
  `configuredProfiles()` loops on every surface — is already provider-agnostic without knowing
  it. This item adds the field and the seam; it changes no behaviour for a Claude account.
- **Build — the enum.** New `data/Provider.kt`:
  ```kotlin
  enum class Provider(val key: String, val displayName: String, val vendor: String, val themeName: String) {
      CLAUDE("claude", "Claude", "Anthropic", "Claude Orange"),
      CHATGPT("chatgpt", "ChatGPT", "OpenAI", "ChatGPT Green"),
      ANTIGRAVITY("antigravity", "Gemini", "Google", "Gemini Blue");
      companion object { fun fromKey(key: String?): Provider = entries.firstOrNull { it.key == key } ?: CLAUDE }
  }
  ```
  `themeName` names a `Palette.options` entry; the two new entries are added in CCRM-56
  (Provider Identity), so until then `Palette.byName` falls back to the first option, which
  is harmless. `vendor` is for error copy ("Couldn't reach OpenAI").
- **Build — the field.** `Profile` gains `val provider: Provider = Provider.CLAUDE` as a
  fourth constructor parameter. **Equality and `hashCode` stay key-only** — a provider is as
  immutable as a key, but the rule that identity is the key must not be diluted. The registry
  persists it as `"v": provider.key` in `ProfileRegistry.encode`, and `decode` reads
  `Provider.fromKey(o.optString("v"))` — **absent → `CLAUDE`**, so every existing install and
  both seeded slots read unchanged with no migration. `ProfileRegistry.add(label, provider)`
  and the pure `Companion.add(state, label, provider)` take the provider; `seed` stays
  Claude-only. `UsageRepository.addProfile(label, provider)` passes it through.
- **Build — the seam.** New package `data/source/`:
  ```kotlin
  /** Everything a poll needs that differs per provider. Sign-in does NOT live here — the two
   *  flows have different shapes (browser + pasted code vs device code) and different UI. */
  interface UsageSource {
      val provider: Provider
      /** GET the usage payload with this provider's headers. */
      fun fetchUsage(creds: Credentials): HttpResult
      /** Redeem the refresh token. */
      fun refresh(creds: Credentials): HttpResult
      /** Token endpoint body → the new credentials plus what rides along (plan, account id). */
      fun parseTokenResponse(body: String, previous: Credentials?): TokenGrant?
      /** Usage body → the shared model. Null only when nothing usable is present. */
      fun parseUsage(body: String): UsageData?
      /** Which HTTP statuses mean "the token is dead", as opposed to "their server is down". */
      fun isAuthFailure(status: Int): Boolean
  }
  data class TokenGrant(val creds: Credentials, val plan: String?, val tier: String?)
  object Sources { fun of(provider: Provider): UsageSource }
  ```
  `ClaudeSource` **wraps the existing code untouched**: `fetchUsage` → `ApiClient.fetchUsage`,
  `refresh` → `ApiClient.refreshToken`, `parseUsage` → `UsageParser.parse`, `isAuthFailure`
  → `status == 401`, `parseTokenResponse` → the exact block `refreshAccessToken` runs today
  (rotated refresh token kept if present, `expires_in` → `expiresAt`). The authorize-URL
  encoding and the two-opposite-User-Agents rule are the most empirically fragile lines we
  own and are **not** generalised; `ApiClient` keeps every function it has. `Sources.of` for
  `CHATGPT` throws `NotImplementedError` until CCRM-54 (ChatGPT Account) lands; for
  `ANTIGRAVITY` until CCRM-55 (Antigravity Account).
- **Build — the repository.** In `UsageRepository`, `doFetch` and `refreshAccessToken` resolve
  `val source = Sources.of(profile.provider)` once and replace the three direct calls:
  `ApiClient.fetchUsage(token)` → `source.fetchUsage(creds.copy(accessToken = token))`;
  `UsageParser.parse(resp.body)` → `source.parseUsage(resp.body)`; the refresh block →
  `source.refresh(creds)` then `source.parseTokenResponse(body, creds)`. The `401` tests become
  `source.isAuthFailure(resp.code)`. The gates, backoff, `saveSuccess`/`saveFailure`,
  `historyStore.record` and alerts are untouched — they never knew the provider.
- **Build — credentials.** `Credentials` gains `val accountId: String? = null` (ChatGPT sends
  it back as a header; Claude never has one). `CredentialStore.load/save/clear` add the
  `k(profile, "accountId")` entry. Nothing else in the store changes; the Claude paste path
  (`parsePasted`) is Claude-only and stays where it is.
- **Build — the window classifier.** Lesson from OpenQuota's `codex/mapper.rs`: classify a
  window by **its duration**, never by which JSON slot it arrived in. New pure helper in
  `Models.kt`:
  ```kotlin
  enum class WindowKind { SESSION, WEEKLY, OTHER }
  fun classifyWindow(lengthSeconds: Long?): WindowKind = when (lengthSeconds) {
      18_000L -> WindowKind.SESSION; 604_800L -> WindowKind.WEEKLY; null -> WindowKind.OTHER; else -> WindowKind.OTHER
  }
  ```
  A weekly-only account puts its weekly limit in `primary_window`; and OpenAI suspended the
  5-hour limit for Plus / Pro / Business on 2026-07-12, so `session` may simply be **absent**.
  A parser must then return a valid `UsageData(session = null, …)`, never a null payload.
- **Build — credits.** `SpendCredits` is money-in-minor-units against a monthly cap. ChatGPT's
  `credits` is `{has_credits, unlimited, balance}` — a balance with no cap and an explicit
  "unlimited". Two small widenings: `val unlimited: Boolean = false`, and `isReportable`
  becomes `!unlimited && (hasLimit || usedMinor > 0L || (balanceMinor ?: 0L) > 0L)`.
  An unlimited account has nothing to measure and, by review decision 6, shows nothing.
- **Build — logging.** `AppLog` lines read `[poll][p2] manual → OK`. Non-Claude accounts log
  `[poll][chatgpt:p3]` — prefix the key with `provider.key` **only when `provider != CLAUDE`**,
  so every existing log line stays byte-identical.
- **Not built here, deliberately:** `UsageData` stays `session / weekly / modelCaps / credits`.
  ChatGPT maps onto it 1:1 (CCRM-54 (ChatGPT Account)). Antigravity's *second* pool has its own 5-hour window,
  which this shape cannot hold; the `lanes` generalisation waits for CCRM-55 (Antigravity
  Account) to unblock rather than being built speculatively.
- **Tests** (`app/src/test/…`): `ProfileRegistryTest` — round-trip with `v`, absent `v` →
  `CLAUDE`, unknown `v` → `CLAUDE`, `add` with a provider; `WindowKindTest` — the two
  constants, null, and 3600; `SpendCreditsTest` (new or in `UsageParserTest`) — unlimited
  hides, a positive balance alone reports, the existing cap/used rules unchanged;
  `ClaudeSourceTest` — `parseTokenResponse` keeps the old refresh token when none is
  rotated, and `isAuthFailure(403) == false`. Every existing test passes unchanged.
- **Acceptance:** `./gradlew testDebugUnitTest` green; a release build installed over the
  live install fetches all three Claude accounts with identical `[poll]` lines; the
  `profiles` prefs file carries `"v":"claude"` on every entry after the first write.
- **Contract is `layer:core`.** The Mac repo's `API-CONTRACT.md` and `fixtures/` gain a
  provider dimension and a captured ChatGPT payload once CCRM-54 (ChatGPT Account) has one. Android leads and
  is the reference implementation, as CCRM-8 (Mac Menu-Bar) established.
- **Interacts with:** CCRM-17 (Window Pings) — Claude-only and ToS-disabled, stays behind
  `provider == CLAUDE`; `ApiClient.ProbeHost` — grows one origin per provider in CCRM-54 (ChatGPT
  Account) and CCRM-55 (Antigravity Account),
  the GET-only rule holds; CCRM-38 (Plan Tier) — `Fmt.tierMultiplier` is Claude's `default_5x`
  grammar and must not run on `plan_type: "pro"` (gated in CCRM-57 (Provider Plumbing)).

### CCRM-54 · ChatGPT Account — Codex device-code sign-in and the two windows
- **Status:** **Done** — part 2 landed 2026-09-06 (368 tests). The device-code sheet
  replaces the debug path with all five states (waiting / expired / denied /
  unavailable / done) and a live `m:ss` countdown; the account card is mark + status
  chip + `PlanChip(plan, tier = null)` with *Sign in with a code* / *Refresh* /
  *Clear*, no "expires around" line and no backup / paste / QR; the main-screen tab
  renders Spark rows as model caps and credits as "$X balance"; a Ring or Bar widget
  configured on a window the account lacks says "No 5-hour window on this account";
  and the pinned headline and the Quick Settings tile both fall back to the 7-day
  window. The debug capture button is gone, and `UsageRepository.captureUsagePayload`
  with it — the fixture is captured and nothing else called it. Copy and the sheet's
  state table are pure and tested (`DeviceCodeCopy`, `Fmt.mmss`, `absentWindowMessage`)
  since this module has no Robolectric. **Not built, and deliberately:** the provider
  mark on the pinned label line exists only in the *Huge number* (`big`) style — the
  other three styles are plain `NotificationCompat` slots with no ImageView to hang it
  on, so giving them a mark means converting them to custom RemoteViews, which the
  wireframe does not show. Filed as a build note, not a defect. See the device pass.
- **Status of part 1** (2026-09-06). `ChatGptSource` +
  `ChatGptUsageParser`, `CodexDeviceSignIn`, `UsageRepository.completeDeviceSignIn` and
  the `ProbeHost.CHATGPT` allowlist entry are built and green (321 tests). **Signed in on
  the phone with a real account and captured a real payload** — the fixture is
  `app/src/test/resources/chatgpt-usage-2026-09.json` (Plus, `user_id` / `account_id` /
  `email` redacted; the live endpoint returns all three). `[poll][chatgpt:p5] auto → OK`
  with no token material, and the account renders 9% / 79% on the tab with correct resets.
  Two source facts settled against `openai/codex` and recorded in code comments: the
  refresh grant is **JSON** (`request_chatgpt_token_refresh` in `login/src/auth/manager.rs`,
  not form-encoded as the research file recorded — the code *exchange* at the same URL is
  form-encoded), and **403 and 404 both mean "keep polling"** in `poll_for_token`, with no
  distinct denied status. `/usercode` did **not** 404, so the loopback fallback is not
  needed and Step 4 stands as written.
- **Confirmed live on the phone, 2026-09-06** (Fold 7, release-signed build over the live
  install): device sign-in completes end to end; the usage body is HTTP 200 and carries
  **both** windows on a Plus account (`limit_window_seconds` 18000 and 604800), so the
  July suspension of the 5-hour limit is not universal and neither branch may be assumed.
  The live payload also carries five keys the documented shape didn't mention —
  `code_review_rate_limit`, `model_usage`, `spend_control`, `promo`,
  `rate_limit_reset_credits` — plus `allowed` / `limit_reached` inside `rate_limit`; none
  is a usage reading and all are ignored. **`credits.balance` arrives as a JSON *string***
  (`"0"`), not the number the documented shape showed; both spellings are pinned by test.
  And the body carries `user_id`, `account_id` and `email`, so the capture button's output
  is personal data — the committed fixture is redacted and a test guards it.
- **Verified in `openai/codex` source, 2026-09-06** (`codex-rs/login/src/device_code_auth.rs`,
  `codex-rs/backend-client/src/client/rate_limit_resets.rs`, `codex-rs/login/src/auth/manager.rs`,
  `codex-rs/login/src/token_data.rs`; full notes in `design/research/2026-09-06-phone-feasibility.md`):
  - **Sign-in is a device-code flow.** `POST https://auth.openai.com/api/accounts/deviceauth/usercode`
    with JSON `{"client_id": "app_EMoamEEZ73f0CkXaXp7hrann"}` → `{device_auth_id, user_code,
    interval}`; show the user `https://auth.openai.com/codex/device` and the code (15-minute
    expiry); poll `POST …/api/accounts/deviceauth/token` with `{device_auth_id, user_code}`
    every `interval` seconds until it returns `{authorization_code, code_verifier}` — **the
    server hands back the PKCE verifier**; exchange at `POST https://auth.openai.com/oauth/token`
    (form-encoded) `grant_type=authorization_code&code=…&redirect_uri=https://auth.openai.com/deviceauth/callback&client_id=…&code_verifier=…`
    → `{id_token, access_token, refresh_token}`. **No localhost, no app link, no pasted
    secret**, completable on a different device. The client id is the Codex CLI's own.
  - **Usage** is `GET https://chatgpt.com/backend-api/wham/usage` with `Authorization: Bearer`,
    `ChatGPT-Account-Id: <id>` when known, `Accept: application/json`. Response: `plan_type`,
    `rate_limit.{primary_window,secondary_window}.{used_percent, reset_at, limit_window_seconds}`
    (`reset_at` is epoch **seconds**; some builds send `reset_after_seconds` instead),
    `credits.{has_credits, unlimited, balance}`, `additional_rate_limits[]` (each
    `{limit_name, rate_limit{…}}`, e.g. Spark), `rate_limit_reached_type`. Generated from an
    OpenAPI spec in that repo — changes are additive and diffable.
  - **Refresh** at the same token endpoint, `grant_type=refresh_token`, `client_id`,
    `refresh_token`. The CLI refreshes 5 minutes before the JWT `exp` and proactively at 8
    days. Failure bodies name `refresh_token_expired` / `refresh_token_reused` /
    `refresh_token_invalidated`. **`refresh_token_reused` is a rotation trap**: never import a
    desktop `auth.json` — the phone mints its own family, as it does for Claude.
  - **Plan and account id come free** from the `id_token` JWT claims under
    `https://api.openai.com/auth` (`chatgpt_plan_type`, `chatgpt_account_id`), no signature
    check needed for our purpose. `PlanType` is `free, go, plus, pro, prolite, team,
    business, enterprise, edu…` with a serde `other` — render unknown tiers title-cased.
- **Build — part 1, the source** (`data/source/ChatGptSource.kt`), constants first:
  `CLIENT_ID`, `ISSUER = "https://auth.openai.com"`, `TOKEN_URL = "$ISSUER/oauth/token"`,
  `USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"`, `USER_AGENT =
  "Cooldown/${BuildConfig.VERSION_NAME} (Android)"` — **honest, ours**. OpenQuota sends its
  own UA and is served; no UA gate is reported, unlike Anthropic's. `fetchUsage` sends the
  three headers above and the UA. `refresh` posts the refresh grant — **check the encoding
  against `manager.rs` at build time**: OpenQuota sends `application/x-www-form-urlencoded`
  and is served; if the CLI sends JSON, match the CLI. `parseTokenResponse` reads
  `access_token`, `refresh_token` (keep the previous one if absent), `expires_in` or, absent
  that, the access token's JWT `exp`; decodes `id_token` for `chatgpt_plan_type` → `plan`
  and `chatgpt_account_id` → `Credentials.accountId`; `tier = null` always.
  `isAuthFailure` → `status == 401 || status == 403` (OpenQuota maps both to token-expired
  before reading the body).
- **Build — part 1, the parser** (`ChatGptUsageParser` in the same file, pure, tested):
  - Collect `rate_limit.primary_window` and `secondary_window`, skipping nulls. For each,
    `classifyWindow(limit_window_seconds)`: `SESSION` → `session`, `WEEKLY` → `weekly`,
    `OTHER` → dropped with a debug log line. If `limit_window_seconds` is absent on both,
    fall back to positional (primary → session, secondary → weekly). `percent =
    used_percent`; `resetsAt = Instant.ofEpochSecond(reset_at)`, else `now + reset_after_seconds`,
    else null; `serverSeverity = null`.
  - `additional_rate_limits[]`: take each entry's **weekly** (`604800`) window as a
    `ModelCap(limit_name.title-cased, window)`. A 5-hour additional window is dropped in this
    item — `ModelCap` is weekly by contract (alerts drift it on the 7-day clock, `windowRows`
    gives it `WEEKLY_MS`); recorded, not solved.
  - `credits`: `SpendCredits(usedMinor = 0, limitMinor = null, exponent = 2, currency = "USD",
    serverSeverity = null, balanceMinor = round(balance × 100), unlimited = unlimited)` when
    `has_credits`; null otherwise. Hidden by `isReportable` when unlimited or zero.
  - `plan_type` → returned alongside (the live value beats the id_token's; store via
    `cache.setTokenMeta(profile, 0L, plan, null)` after a successful fetch).
  - Return null only when no window, no cap and no credits parsed.
  - Not read, deliberately: the `x-codex-*-used-percent` header fallbacks (OpenQuota-only
    resilience for a shape change we have not seen) and `rate_limit_reached_type` (no field
    in `UsageData` yet; revisit if the payload shows it set).
- **Build — part 1, the device flow** (`data/CodexDeviceSignIn.kt`):
  ```kotlin
  object CodexDeviceSignIn {
      data class Started(val deviceAuthId: String, val userCode: String, val intervalSec: Int,
                         val verifyUrl: String, val expiresAtMs: Long, val profileKey: String)
      sealed class Poll { object Pending : Poll(); object Expired : Poll(); object Denied : Poll()
                          data class Granted(val authorizationCode: String, val codeVerifier: String) : Poll() }
      class Unavailable : Exception()  // HTTP 404 from /usercode — OpenAI has the flow switched off
      fun start(profile: Profile): Started          // POST usercode; persists Started to prefs "device_pending"
      fun pending(context): Started?                 // survives process death like OAuthSignIn.pending
      fun poll(started: Started): Poll               // POST token; map statuses exactly as device_code_auth.rs does
      fun exchange(granted: Poll.Granted): HttpResult // POST /oauth/token authorization_code, device redirect_uri
      fun clearPending(context)
  }
  ```
  Which poll statuses mean pending vs denied is read off `device_code_auth.rs` (the
  `poll_for_device_token` loop) at build time and pinned by a unit test. The poll loop runs in
  a `viewModelScope`-style coroutine while the sheet is open, resumes on return to the
  foreground, and gives up at `expiresAtMs`; no WorkManager — 15 minutes does not justify it.
  `UsageRepository.completeDeviceSignIn(profile, granted)` mirrors `completeSignIn`: parse
  the grant via `ChatGptSource.parseTokenResponse`, `credStore.save(stampAdded = true)`,
  `setAuthState(OK)`, `setTokenMeta(profile, 0L, plan, null)`, `setRefreshExpiryEstimated(false)`,
  `setNativeSignIn(true)`, then `doFetch(ignoreGates = true)`. **`refreshExpiresAt` stays 0**:
  OpenAI's family has no fixed life we know, so the card shows no "expires around" line
  (CCRM-16 (Sign-in Expiry Accuracy) rule: no estimate is better than a wrong one).
- **Build — part 1, the capture.** Before any UI polish: a debug-section button "Capture
  ChatGPT payload" on a ChatGPT card, which runs `fetchUsage` and writes the raw body to the
  diagnostics log at DEBUG (the body has no tokens; `plan_type` and percentages only). Robin
  signs in once on his own account; the body becomes
  `app/src/test/resources/chatgpt-usage-2026-09.json` and replaces the synthetic fixture. If
  `/usercode` returns 404 for this client, stop and file it — the fallback (below) moves up.
- **Build — part 2, the surfaces** (all per the CCRM-56 (Provider Identity) wireframe, rev B):
  - **Add account → ChatGPT** opens the **device-code sheet**: the URL, the code in a
    monospace 30 sp line, "Copy code", "Open in browser" (Custom Tab, same picker as Claude),
    a countdown from `expiresAtMs`, and a "Waiting for you to finish…" line. States:
    *waiting*, *expired* (button "Get a new code"), *denied*, *unavailable* (copy names the
    browser fallback), *done* (sheet closes, the new tab appears). Process death mid-flow
    reopens the sheet from `pending()`.
  - **Account card** for a ChatGPT profile: provider mark before the label, `StatusChip` as
    today, `PlanChip(plan, tier = null)` → "Plus" / "Pro" / "Team" title-cased, **no
    multiplier**, no "expires around" line, buttons **Sign in with a code** / **Refresh** /
    **Clear**; the Claude-only *Backup method*, paste and QR paths are not rendered.
  - **Main screen tab**: the 5-hour card and the 7-day card each render **only when their
    window is non-null** (decision 6 — applies to Claude too); Spark rows sit under the 7-day
    card as model caps; the credits card shows **"$12.40 balance"** when `usedMinor == 0 &&
    balanceMinor != null` (new copy branch), nothing when unlimited.
  - **Widgets**: `windowRows` already skips null windows. A Ring or Bar widget configured on a
    window the account no longer has shows one line, "No 5-hour window on this account", in
    the face's small text; the Pace widget hides the absent side of its 5h/7d toggle.
  - **Pinned notification**: headline = `session` if present, else `weekly`; the provider mark
    (12 dp) sits on the label line in every style; a folded strip from another account carries
    *that* account's mark. **Tile**: same headline fallback.
  - **Alerts**: nothing to do — `data.session?.let` already skips a missing window.
- **Fallback sign-in**, built only if the device endpoint 404s: the browser PKCE flow with a
  loopback `ServerSocket` on `127.0.0.1:1455` (only 1455 / 1457 are allowlisted) inside a
  Custom Tab — a phone can serve localhost to its own browser; it needs a
  `network-security-config` cleartext exception for `localhost` and port-contention handling.
- **Rules we bind ourselves to:** never send `x-openai-codex-luna-reserve` (the source
  reserves it for clients that can *apply* Reserve, "not passive account usage readers" — that
  is us); never call `rate-limit-reset-credits/consume` — a **write** that spends a credit;
  `ProbeHost.CHATGPT("https://chatgpt.com")` joins the GET-only allowlist; poll at the existing
  cadence; log status codes only, never tokens, headers or bodies (the capture button is the
  one DEBUG-level exception, body only).
- **Multi-account for free:** every ChatGPT login is a profile. OpenQuota cannot show two
  ChatGPT accounts at all; we get it from CCRM-6 (Multi-Account) without a line of new code.
- **Tests:** `ChatGptUsageParserTest` — the fixture (synthetic from the documented shape
  until the capture replaces it): both windows classified by duration; weekly-alone-in-primary;
  absent `primary_window` → `session == null` and a non-null payload; epoch-seconds and
  `reset_after_seconds`; Spark weekly → `ModelCap("Spark")`, Spark 5-hour dropped;
  `credits.unlimited` → `isReportable == false`; `balance 12.4` → `balanceMinor 1240`;
  junk → null. `CodexDeviceSignInTest` — JWT payload decode (a hand-built unsigned token),
  poll-status mapping, `Unavailable` on 404. `ChatGptSourceTest` — `isAuthFailure(403)`,
  refresh keeps the old refresh token when none is returned, `expires_in` absent → `exp`.
- **Device pass (Fold 7, cover screen, Huge number style):** device-code sheet in all five
  states; the new tab with real numbers; the absent-5-hour case if the plan has it lifted;
  a Ring widget on the absent window; the pinned panel showing the ChatGPT account with a
  Claude strip folded in; Quick Settings tile on the ChatGPT slot; re-sign-in after a forced
  `Clear`; `[poll][chatgpt:pN]` lines in the log with no token material.
- **Disclosure:** the README risk box grows an OpenAI paragraph — same posture as the
  Anthropic one (our own token, the official CLI's client id, an internal endpoint, read-only,
  honest User-Agent), same honesty. Ships with the v1.5 docs.

### CCRM-55 · Antigravity Account — Gemini windows, spike before design
- **Status:** Needs design · **Blocked on a spike** · large · filed 2026-09-06 · after
  CCRM-54 (ChatGPT Account) · shown greyed in Add account meanwhile (review decision 5)
- **What the data is** (OpenQuota `providers/antigravity/*`, corroborated by CodexBar and
  OpenUsage docs; `design/research/2026-09-06-openquota-antigravity.md`):
  `POST https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary` with
  `Authorization: Bearer`, `User-Agent: antigravity`, body
  `{"metadata":{"ideName":"antigravity","extensionName":"antigravity","ideVersion":"unknown","locale":"en"}}`
  returns `response.groups[].buckets[]` with four `bucketId`s — `gemini-5h`, `gemini-weekly`,
  `3p-5h`, `3p-weekly` — each with `remainingFraction` (0–1, **remaining**, so
  `used = (1 − f) × 100`) and `resetTime` (ISO-8601). Two shared pools × two windows: the
  Gemini pool (Pro and Flash draw from one quota) and the "Claude + GPT via Antigravity" pool.
  Fallbacks `fetchAvailableModels` / `retrieveUserQuota` (per model, 5-hour only, pooled by
  **worst** remaining fraction) and `loadCodeAssist` for the plan (**Ultra / Pro / Free**).
  Google returns **no absolute numbers**, only fractions, and the signal is reported to move
  in ~20 % steps. Token refresh is a form POST to `https://oauth2.googleapis.com/token` with
  the installed-app client id **and secret** (`1071006060591-…apps.googleusercontent.com` /
  `GOCSPX-…`, both public by design, both lifted from Google's binary).
- **Why it is not "Planned": two blockers of different kinds.**
  1. **Auth (hard).** Antigravity's Google OAuth client redirects to
     `http://localhost:51121/oauth-callback`, has **no code-paste page** (Google retired the
     OOB flow), and is a client we do not own. Google has been rejecting that very redirect
     since January 2026 ("doesn't comply with Google's OAuth 2.0 policy" — `localhost`
     hostname rather than the `127.0.0.1` literal), and we cannot fix a registration we do
     not control.
  2. **Data (soft).** CodexBar, which runs both the local and the remote path, reports that a
     token minted *outside* an Antigravity session can get an availability-shaped payload —
     every bucket at 100 % — rather than live quota, and that weekly grouping is unreliable
     remotely. Unproven either way from a phone.
- **The spike — a terminal on the Mac, no app code, do it whenever the Mac is free:**
  1. Sign into Antigravity on the Mac. Read its refresh token:
     `security find-generic-password -s gemini -a antigravity -w` (decode a
     `go-keyring-base64:` prefix if present; the JSON's `refresh_token` field).
  2. Quit Antigravity and `agy`. Refresh from a plain client:
     `curl -s https://oauth2.googleapis.com/token -d client_id=… -d client_secret=… -d grant_type=refresh_token -d refresh_token=…`.
  3. `curl -s -X POST https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary -H "Authorization: Bearer …" -H "User-Agent: antigravity" -H "Content-Type: application/json" -d '{"metadata":{"ideName":"antigravity","extensionName":"antigravity","ideVersion":"unknown","locale":"en"}}'`
     with **no IDE running**. Record whether the four buckets carry real fractions or all
     `1.0`. Repeat after a Gemini prompt to see the fraction move.
  4. If real: repeat step 3 from the phone through a debug paste of the refresh token.
  Save the redacted bodies in `design/research/`. The answer picks the auth route:
  - **a. Mac relay.** CCRM-8 (Mac Menu-Bar)'s client owns Antigravity — the Mac is where
    Antigravity lives, where the *richest* source (the local language server's
    `RetrieveUserQuotaSummary`) is reachable, and where a loopback redirect is
    uncontroversial — and relays a normalised snapshot to the phone. Best data, sidesteps the
    auth blocker, but needs a phone↔Mac channel that **does not exist**; if chosen, that is
    its own item.
  - **b. Refresh-token paste.** Sign in on a computer once, paste the refresh token into the
    phone; the phone refreshes forever after (Google refresh tokens for published apps do not
    expire on a schedule). Ugly onboarding, fine steady state; the existing Claude paste path
    is the precedent. **Best phone-only option.**
  - **c. Loopback `127.0.0.1:51121` in a Custom Tab.** Technically the same trick as the
    ChatGPT fallback; bets on a redirect Google is already flagging for a client we do not
    own. One policy sweep from dying.
  - Rejected: scraping `?code=` out of the failed page's address bar.
- **Not Gemini CLI / Code Assist.** Google stopped serving individuals, AI Pro and AI Ultra
  through it on **2026-06-18** (official deprecation notice) and points them at Antigravity;
  only Code Assist Standard / Enterprise remain, which is not our audience. Its open-source
  client is the **first-party reference for the `cloudcode-pa` protocol** and has the one
  Google paste-page precedent (`authWithUserCode`, redirect
  `https://codeassist.google.com/authcode`) — on a different client id. Read it; do not ship
  it.
- **States to wireframe when unblocked, every one new to this app:** **not started** (a
  5-hour window with no usage has *no reset time at all*; the rails gauge has no such
  state); **unknown** (`resetTime` present, fraction absent — never 0 % or 100 %); **an
  untouched pool** (a Gemini-only user gets a Claude + GPT pair regardless — by decision 6 a
  pool whose every lane is known-zero is **not shown**); a **stepped signal** against our
  continuous pace line (suppress the verdict for quantised input rather than let it flap);
  and the Pro plan's **weekly-empties-so-the-5-hour-refresh-does-nothing** interaction — the
  thing Pro users are confused about and the strongest product argument for building this.
- **Model impact:** the second pool's 5-hour window forces the `lanes` generalisation
  deferred in CCRM-53 (Provider Model). Do it then, with the real payload in hand.
- **ToS posture:** the highest of the three. Disclose it as plainly as the Anthropic box does.

### CCRM-56 · Provider Identity — Cooldown: the name, the three-sand hourglass, the marks and the accents
- **Status:** Done (2026-09-06) — name, icon (rev C: 1.3x scale on review), marks (Claude/Gemini
  paths from Simple Icons, ChatGPT from Wikimedia Commons — all cross-checked against a real
  source rather than recalled), three-level accents, Add-account sheet, hidden windows. Device
  pass and the two narrative-only refinements below are open follow-ups, not blockers · wireframe
  rev B `design/provider-identity-wireframe.html` approved 2026-09-06 (decisions 1–7 and B1–B3
  recorded at its foot) · gated every visible part of CCRM-54 (ChatGPT Account) · keep the
  wireframe until the device pass is signed off.
- **What cannot change — settle this first:** `applicationId com.robin.claudeusage`. Changing
  it is a **different app** to Android: every install, placed widget, tile, credential and
  year of history is lost, and the update checker can never reach the old installs. Package,
  namespace, repo and the `UpdateCheck` URL stay exactly as they are.
- **Build — 1, the name.** `res/values/strings.xml`: `app_name` → `Cooldown`; the three
  widget descriptions → "Cooldown — …". Copy sweep (each is a literal today):
  `MainActivity.kt:272` top-bar title → "Cooldown"; `SettingsScreen.kt:1855` About title →
  "Cooldown", and the About body's disclaimer → *"Unofficial. Not affiliated with, endorsed
  by, or supported by Anthropic, OpenAI or Google. "Claude" is a trademark of Anthropic, PBC.
  "ChatGPT" is a trademark of OpenAI. "Gemini" and "Antigravity" are trademarks of Google
  LLC."*; `SettingsScreen.kt:424` tap-target labels → `"app" to "Cooldown"`, and the second
  option becomes the **pinned account's provider app** (`"provider" to "${provider.displayName} app"`;
  packages: Claude → the constant `PinnedNotification.claudeLaunchIntent` uses today, ChatGPT
  → `com.openai.chatgpt`, Gemini → `com.google.android.apps.bard`; the existing stored value
  `"claude"` reads as `"provider"`); `SettingsScreen.kt:2572` log e-mail subject;
  `widget/UsageWidget.kt:206` → "Cooldown · $profileLabel"; `alerts/Alerts.kt:80/85/112/158`
  channel descriptions and the re-auth title lose "Claude" ("a usage window is nearly
  exhausted", "a usage window has reset", "A newer Cooldown release…", "$label: Cooldown needs
  re-auth"); `HistoryScreen.kt:289` → "as you use the account"; `tile/UsageTileService.kt:46/99`
  and the manifest's four static tile labels → "Cooldown account 1…4", live label →
  `"$profileLabel"` alone, or `"${provider.displayName} · $profileLabel"` when the configured
  accounts span more than one provider. The Claude sign-in guide copy (`:1243, :1422, :1518,
  :2191–2300, :2636`) is *about* Claude's sign-in and stays — it is only shown on a Claude
  card. `UsageCache.themeColorName()`'s default string is handled in step 4.
- **Build — 2, the icon (option A, all three colours).** Three vector edits, the geometry of
  `ic_launcher_foreground.xml` otherwise kept:
  - `ic_launcher_background.xml`: the warm radial → a **slate radial** (`#3A4356` at 0 % →
    `#242B38` at 60 % → `#12161E` at 100 %, light top-left), so the three colours sit on a
    neutral ground. The foreground's container circle (`r=31`, `#C4622D`) → `#1E2430`.
  - **Lower bulb = three sands.** Wrap three horizontal slabs in a `<group>` with a
    `<clip-path>` equal to the lower-glass interior
    (`M45,71 c0,-9 7,-12 9,-17 c2,5 9,8 9,17 z`): blue `#4285F4` from y 66.5 to 71, green
    `#10A37F` from 62.5 to 66.5, orange `#D97757` from 58.5 to 62.5 — the mound rises to
    ~58.5 (today's `69.5`→`61.5` mound is replaced). The **falling grain and the top sand turn
    orange** (`#D97757` → `#E59980` gradient): the Claude window is the one pouring. Cream
    glass and caps unchanged.
  - `ic_launcher_monochrome.xml`: the single silhouette gains the three slabs as separate
    paths with `fillAlpha` 1.0 / 0.65 / 0.35 (orange, green, blue) — the launcher tints the
    layer, alpha survives, so "three" survives without colour. `drawable/ic_launcher.xml`
    (About screen) redrawn to match. Preview at 48 dp before committing: the three bands must
    each be at least 3 dp tall on a 48 dp tile or the middle one vanishes.
  - **Rev C, confirmed on the 48 dp render (2026-09-06):** all the geometry above stays
    exactly as specified, but the whole glyph now sits inside a `<group android:scaleX="1.3"
    android:scaleY="1.3" android:pivotX="54" android:pivotY="54">` in all three colour/mono
    files — Robin found the unscaled render too small/padded at 48 dp. Container effectively
    r≈40, intentionally past the nominal 66dp safe circle; accepted as a tradeoff since modern
    launcher masks are lenient in practice.
- **Build — 3, the marks (review decision 4: logos, not dots).** `ui/ProviderMark.kt`:
  `@Composable fun ProviderMark(provider: Provider, size: Dp = 16.dp, tint: Color? = null)`
  drawing `drawable/ic_provider_claude.xml`, `ic_provider_chatgpt.xml`,
  `ic_provider_gemini.xml`; `providerMarkRes(provider): Int` for RemoteViews and Glance. The
  three drawables are **hand-traced vector paths of each company's public mark** (Anthropic's
  Claude starburst, OpenAI's knot, Google's Gemini spark), unmodified in shape, single-colour
  so `tint` works; each file's header comment links the brand page it was traced from and its
  usage terms. Rev A proposed a coloured dot to stay clear of trademarks; the user chose the
  marks for legibility — identifying a service by its mark alongside a "not affiliated"
  notice is how OpenQuota ships the same three glyphs. (CCRM-45 (Tracker Icon)'s constraint
  was about Anthropic's *mascot* artwork, which stays out.) Placement and sizes, as approved (B1 — bumped from 16 / 12 / 24):
  **20 dp** before the label on account cards and tab strips (6 dp gap); **14 dp** in the
  pinned panel's "Show profile" chips, on the pinned label line in every style (a new
  `ImageView` in the Huge-number layout row; drawn into the panel bitmap for the gauge style),
  in widget labels when `multiProfile` and in the widget config picker; **28 dp** in the
  Add-account sheet. Tiles cannot carry it (icon slot is the gauge) — the tile's label rule in
  step 1 covers it. Status-bar and QS gauge glyphs untouched. **Landed:** cards, tab strips
  (Main + History), the pinned Huge-number label line (both collapsed and expanded layouts),
  the widget config picker, the Add-account sheet, and Settings' own "Show profile" chip row
  (the pinned notification's profile picker — not literally inside the panel bitmap, but the
  same 14dp chip treatment). **Deferred, follow-up:** the mark in the four bar-face widgets'
  own `"5-hour · $profileLabel"`-style labels (baked into a plain string passed to a shared
  bar-row composable — needs that row to take a leading icon slot first) and on the pinned
  panel's own folded condition strips (decision 7's "carries its mark in ink" — needs
  `Conditions.Condition` to carry a `Provider` through several construction sites). Neither
  blocks CCRM-54 (ChatGPT Account) part 2.
- **Build — 4, the accents (review decision 1).** Today `themeColor` is one global pref
  read at eight sites (`MainActivity:178`, `PinnedNotification:99`, `BarWidget:71`,
  `RingWidget:84`, `UsageWidget:107`, `MiniRingsWidget:84`, `PaceWidget:112`,
  `WidgetConfigActivity:117`). It becomes a **three-level resolution**:
  ```kotlin
  object Palette {
      const val PER_PROVIDER = "Per provider"          // new global pseudo-option
      // new options, appended: ThemeOption("ChatGPT Green", 0xFF10A37F, 0xFF19C39A)
      //                        ThemeOption("Gemini Blue",  0xFF4285F4, 0xFF8AB4F8, PACE_VIOLET_LIGHT, PACE_VIOLET_DARK)
      fun accentName(cache: UsageCache, profile: Profile): String =
          cache.accountAccent(profile)                              // 1. per-account override, if set
              ?: cache.themeColorName().takeIf { it != PER_PROVIDER } // 2. global choice, if not "Per provider"
              ?: profile.provider.themeName                          // 3. the provider's colour
  }
  ```
  `UsageCache.accountAccent(profile): String?` / `setAccountAccent(profile, name?)` at
  `k(profile, "accent")` (add to `LEGACY_PROFILE_KEYS`). **Migration without a flag:** on
  first read, if the `themeColor` key is **absent** the user never chose a colour → return
  `PER_PROVIDER` (Claude accounts render Claude Orange, pixel-identical to today); if present,
  their explicit choice stays the global override for every account. The eight call sites
  become `Palette.accentName(cache, profile)`; the app's `MaterialTheme.primary` follows the
  **selected tab's** account on Main and History, and the global/default on Settings
  (`PER_PROVIDER` → `Palette.DEFAULT` there). `WidgetConfigActivity` themes from its initial
  profile. Two visible additions: the Settings theme grid gains a first swatch **"Per
  provider"** (a tri-colour dot) ahead of Material You; and the account card's ⋮ menu gains
  **Accent colour…**, opening the same grid with a first swatch **"Provider colour (default)"**
  that clears the override. Material You stays a global-only option.
  **Decision 7 (confirmed B3):** faces that draw more than one account (Mini-Rings, the pinned
  panel's folded strips) draw pace ticks in **neutral ink**; single-account faces keep the theme's
  cool partner via `Palette.paceColor(accentName, dark)`.
- **Build — 5, the Add-account sheet.** `+ Add account` opens a `ModalBottomSheet` with
  three rows (28 dp mark, `displayName`, one line): Claude — "Signs in through your browser,
  then paste the code back — as before."; ChatGPT — "Shows a short code to type at
  auth.openai.com — on this phone or any other device."; Gemini (Antigravity) — **disabled,
  55 % alpha**, "Not available yet — Google's sign-in for Antigravity can't finish on a phone.
  Coming when it can." Picking a row calls `repo.addProfile(label = null, provider)`,
  `Shortcuts.publish`, dismisses, and **immediately starts that provider's sign-in** on the
  new card (Claude: `beginSignIn()`; ChatGPT: the device sheet). No name-first dialog, as
  CCRM-6 (Multi-Account) settled.
- **Build — 6, hidden windows (review decision 6).** In `ProfileScreen`, the 5-hour `Card`
  renders only when `data.session != null`, the 7-day `Card` only when `data.weekly != null
  || data.modelCaps.isNotEmpty()`; with neither and no credits, one line: "This account
  reports no usage windows right now." Same rule in the History screen's 5h/7d toggle, the
  Pace widget's toggle, and the pinned headline / tile fallback (spelled out in CCRM-54
  (ChatGPT Account) part 2). Applies to Claude accounts too; today they draw "—".
- **Rejected from OpenQuota, deliberately:** a per-provider *layer* (their one-stack
  dashboard, one card per provider) — accounts stay the unit, no providers screen; a
  **cross-provider combined percentage** — CCRM-31 (Combined Total) is a spend share across
  Claude accounts, and averaging a ChatGPT window with a Claude window is meaningless;
  auto-detecting installed tools — nothing to detect on a phone. **Adopted:** hide what is
  not signed in (we already drop unconfigured tabs); per-provider brand colour on every
  reading; their three brand values (Claude `#DE7356` → ours stays `#D97757`).
- **Tests:** `SurfaceTokensTest` / a new `AccentResolutionTest` — the three-level rule,
  absent-key → `PER_PROVIDER`, present-key → global wins, override wins over both;
  `ThemeModeTest` unchanged; `ContractTest` (CCRM-37 (Contract Tests)) learns the four trademark lines.
- **Device pass:** launcher tile at 48 dp in light and dark, themed (monochrome) under a
  Material You launcher; About screen; Settings grid with the new first swatch; ⋮ → Accent
  colour on one account and the widget for that account following it; the Add-account sheet
  with the greyed third row; a Claude account with the 7-day card hidden (simulate with a
  fixture in the debug faces activity).
- **Docs ride with the release:** README hero and disclaimer, guide and brochure regenerate
  for v1.5 when the first ChatGPT account ships.

### CCRM-57 · Provider Plumbing — the long tail of Claude-only assumptions
- **Status:** **Done** 2026-09-06, in the same session as CCRM-54 (ChatGPT Account)
  part 2 · filed 2026-09-06. Every item below is built. Two of them turned out to be
  **already satisfied and were verified rather than changed**: the diagnostics prefix
  (CCRM-53 (Provider Model) shipped `[poll][chatgpt:pN]` and CCRM-56 (Provider Identity)
  the log subject), and the sign-in expiry gate — `Conditions.expiry` and
  `Alerts.checkUpcomingExpiry` already return on `refreshExpiresAt <= 0`, and
  `SignInExpiry.line` yields `Line.None` for a ChatGPT-shaped sign-in, now pinned by a
  test. `SignInExpiry.line` itself deliberately keeps no `refreshExpiresAt > 0` guard: a
  Claude sign-in that died before the estimate was ever stamped is a real case the
  existing tests cover, and an early return would swallow it.
- **Contract tests, scope:** the CCRM-57 slice landed as `ContractCopyTest` — three
  provider names, three vendors, the four trademark lines in the About disclaimer, and
  each mark drawable's ownership header. **CCRM-37 (Contract Tests) stays Planned:** its
  registry-contract grep and visual-parity assertions are not built. The README's
  "unofficial" notice still names Anthropic alone and is deliberately *not* asserted —
  it is rewritten in the v1.5 release step, and a test failing until then is noise.
- **Beyond the listed items, same class, fixed in passing:** `sendWindowPing` and
  `PingScheduler.reschedule` now refuse a non-Claude profile at the data layer rather
  than trusting the UI never to offer it, and the main screen's "No <label> account
  yet" card no longer tells a ChatGPT account to tap Claude's sign-in button.
- **Error taxonomy** (CCRM-27 (Error Taxonomy)): `ErrorKind.title`/`short` are fixed strings
  naming Anthropic. Make them functions of the provider — `fun title(p: Provider)`, `fun
  short(p: Provider)` — substituting `p.vendor` ("Couldn't reach OpenAI — check your
  connection, or see if it's them."), and `AUTH`'s fix names the flow ("re-sign in from
  Settings" is right for both). Persisted kinds are keys, so nothing stored changes.
- **Quick Links** (CCRM-26 (Quick Links)): per provider — Claude: as today; ChatGPT:
  `https://status.openai.com` and `https://chatgpt.com/#settings`; Gemini: Google Cloud
  status and `https://antigravity.google`. A `Provider → List<QuickLink>` table, tested.
- **Plan chip:** `PlanChip(plan, tier)` calls `Fmt.tierMultiplier(tier)` — pass `tier = null`
  for non-Claude accounts so Claude's `default_5x` grammar never runs on `"pro"`. No
  multiplier is invented for OpenAI (OpenQuota's `prolite → "Pro 5x"` has no source) or
  Google.
- **Window Pings** (CCRM-17 (Window Pings)): the section and the per-account toggle render
  only for `provider == CLAUDE` (no inference endpoint we would send to; ToS-disabled anyway).
- **Sign-in expiry** (CCRM-16 (Sign-in Expiry Accuracy)): the 30-day estimate is Anthropic's;
  the "expires around" line, the expiry strip and the expiry alert are gated on
  `refreshExpiresAt > 0`, which a ChatGPT account never sets.
- **Diagnostics** (CCRM-34 (Diagnostics Log)): the `[poll][chatgpt:pN]` prefix from CCRM-53
  (Provider Model); the log e-mail subject from CCRM-56 (Provider Identity).
- **Contract tests** (CCRM-37 (Contract Tests)): the copy-drift test learns three provider
  names, three vendors and four trademark lines.
- **Probe allowlist:** `ProbeHost.CHATGPT` (CCRM-54 (ChatGPT Account)) — GET-only rule unchanged.

---

## Next — small, high value, ready to build

### CCRM-39 · Ring Widget — small face, one window as a pace-marked ring
- **Status:** Done (2026-08-13) · verified on the Fold 7, 2026-08-19
- **Provenance:** the Mac's CCRM-18 [Desktop] small face + CCM-49 [Desktop] pace marks,
  via `ANDROID-WIDGET-HANDOVER.md`; wireframe approved 2026-08-13 (rev 2 — the stale
  pill overlays the ring's bottom edge) in `design/widget-wireframes.html`.
- **What:** 2×2 provider ("Ring" in the picker), one profile + one window from the
  config flow. Ring hero with everything inside it: profile caps-label (only when both
  profiles are signed in), truncated percent, compact countdown ("soon" under 5 min),
  exact reset clock. Pace marks per CCM-49: neutral tick at `elapsedPercent`, full-red
  segment from tick to fill tip gated by `percent > elapsed + PACE_DEAD_ZONE` — the
  identical comparison the chart wash makes, so the surfaces can't disagree.
- **Where:** `ui/RingGeometry.kt` (pure math, `RingGeometryTest`) + `ui/RingRenderer.kt`
  (bitmap painter) + `widget/RingWidget.kt` + `widget/WidgetFace.kt` (state table +
  copy, `WidgetFaceTest`). `RingRenderer` is the shared ring surface —
  `PinnedNotification.drawGauge` and `UsageIcon`'s ring should migrate onto it when next
  touched, which is the CCRM-3 (Unified Theming) phase-3 extraction done for rings.
- **Cadence:** redraw on every poll + a 15-minute redraw-only WorkManager tick
  (`WidgetRedrawWorker`, self-cancelling) + a one-shot redraw at the reset moment when
  it's <20 min out. No minute alarms — "soon" is what makes that honest.
- The Mac's `needsAppUpdate` state is deliberately omitted: Glance renders in the app
  process from the same APK, so snapshot-vs-binary skew can't happen here.

### CCRM-40 · Mini-Rings Widget — medium face, every window as battery-style rings
- **Status:** Done (2026-08-13) · verified on the Fold 7, 2026-08-19
- **Provenance/wireframe:** same record as CCRM-39 (Ring Widget).
- **What:** 4×2 provider ("Mini-rings"), one profile, ignores the configured window.
  Header (profile · "Updated Xm ago" · ↻), then payload-ordered columns — session,
  weekly, model caps — **capped at four** so the fixed windows always survive. Each
  ring carries its own tick: session against the 5-hour clock, weekly **and every
  model cap** against the 7-day clock — 41% reading calm next to 84% reading hot in
  one glance is the whole feature. No prose reset line; density is the point.
- **Where:** `widget/MiniRingsWidget.kt`, sharing `RingRenderer`/`WidgetFace`.

### CCRM-41 · Pace Widget — large face, the pace story with an on-face 5h/7d toggle
- **Status:** Done (2026-08-13) · verified on the Fold 7, 2026-08-19
- **Provenance/wireframe:** same record as CCRM-39 (Ring Widget). Supersedes
  CCRM-13 (Chart Widget), which stays as the historical sketch.
- **What:** 4×3 provider ("Pace chart"). Header window title + a segmented 5h/7d
  toggle **on the face itself** (`ToggleWindowAction` writes the same `w<id>.window`
  key the config screen writes — persists, beats the configured window by *being* it,
  and works with the app swiped away). Huge truncated percent; countdown + exact reset
  stacked right; the CCRM-12 (Trend Chart)/CCRM-20 (Wide Chart) chart as a bitmap;
  verdict sentence (amber when above pace; a refused projection prints why); footer
  freshness + ↻.
- **Extraction decision (CCRM-13's "solve it once"):** `widget/ChartBitmap.kt` shares
  the semantic layer by construction — `SparkGeometry` coordinates, `evenPacePercent` +
  `PACE_DEAD_ZONE` wash gate, `Palette.barColor` ladder, `Fmt` stamps — and declines
  pixel-level unification with `UsageSparkline`, whose text/gesture stack is
  Compose-bound (TextMeasurer, scrub, callout). What can drift is cosmetics; what
  can't drift is shared. Recorded here so it isn't re-litigated.

### CCRM-42 · App Icon — the reset-ring identity
- **Status:** Done (2026-08-13) · verified on the Fold 7, 2026-08-19
- **Provenance:** the Mac's CCM-42 [Desktop] icon; `AppIcon.svg` is the master.
  Preview approved 2026-08-13 (`design/icon-preview.html`, deleted after sign-off per
  the design-folder rule).
- **What:** adaptive icon — background = the dark `#242B38→#12161E` gradient
  (`ic_launcher_background.xml`), foreground = track + orange-gradient arc (260° from
  12 o'clock, round caps) at the Mac's ~68% optical weight in the 66dp safe zone,
  plus a dedicated monochrome layer (track 35% alpha, arc solid — "more solid = the
  live part" survives launcher tinting). About-screen `drawable/ic_launcher.xml`
  redrawn to match; the old terracotta `#D97757` icon background colour is retired
  (`values/colors.xml` deleted with it). Status-bar/QS glyphs unchanged — they're
  usage gauges, not the logo.

### CCRM-43 · Bar Pace Marks — pace marks on the bars, and three red toggles
- **Status:** Done and **verified on the Fold 7 outer screen, 2026-08-13**, dark theme,
  both accounts — including the red, which Work's 5-hour window crossed into during the
  pass. What was seen:
  - **Under pace / no mark:** tick at 97% hard against the bar's end and not clipped;
    credits unmarked; a window with no reset clock unmarked. Personal stayed below pace
    throughout and drew no red anywhere — the negative control.
  - **Dead zone:** 61% against 59% elapsed drew the tick alone, and the card's own
    sentence read "On even pace" beside it. The two can't disagree; they share the gate.
  - **Over pace:** at 66% against 61% elapsed (+5) the in-app bar drew red from the pace
    line to the fill's edge — straight vertical split, tick on the boundary, red covering
    the fill's rounded tip — while the card said "4 points above even pace" in amber and
    the chart washed. One verdict, three elements, no drift. The pinned notification
    (switched to Work for the sighting) showed the same at 68% on its 8 dp bar, so
    dropping the `fitXY` stretch worked.
  - **Rings:** the ring and mini-rings faces showed the red arc *beginning on the tick
    with no fill-coloured nub past its tip* — the D7 cap fix, which was invisible before
    this change and is now seen correct.
  - **Owed items closed 2026-08-19:** the light theme, the inner screen, and the three
    toggles flipped against a live red were all verified in the follow-up device pass.
    One thing that stays worth watching: on the 8 dp collapsed notification bar the
    tick is the least legible of the surfaces — present, but only just.
- **Provenance/wireframe:** the Mac's CCM-50 [Panel] bars + CCM-51 [Pace] toggle, via
  `ANDROID-PACE-BARS-HANDOVER.md` (which lives outside this repo — copy it in if this
  line should cite something durable). Wireframe rev B approved 2026-08-13 in
  `design/bar-pace-marks-wireframe.html`, **kept until the on-device pass** as the
  comparison target for it, then deleted per the design-folder rule.
- **What:** the neutral even-pace tick and the red over-pace segment — already shipped
  on the rings in CCRM-39 (Ring Widget) / CCRM-40 (Mini-Rings Widget) — on every bar
  surface: the in-app usage screen, both bar widgets, and the pinned notification
  (collapsed bar and expanded panel). Session measures against 5 hours; the 7-day
  window **and every per-model cap** against 7 days. Credits rows are never marked:
  money has no clock.
- **Geometry:** `ui/BarGeometry.kt`, pure and JVM-tested like `RingGeometry`, sharing
  the one `PACE_DEAD_ZONE` gate (`percent > elapsed + 3.0`, strict) with the chart wash,
  the pace sentence and the rings. `ui/BarRenderer.kt` paints it for the bitmap
  surfaces; the in-app bars draw the same numbers with a Compose overlay.
- **Two deliberate divergences from CCM-50** (decisions of 2026-08-13, worth sending
  back to the Mac repo): the segment has **no minimum width**, so it begins exactly on
  the pace line instead of being inflated to `h` and dragged back behind the tick; and
  the fill→red boundary is a **straight vertical edge**, not a capsule end — the red is
  clipped to the fill's own rounded rect, so it covers the fill's tip and meets the
  base colour on a hard line. `RingRenderer` gained a butt-capped trim pass so the
  rings obey the same rule; before it, their red bulged ~0.35×stroke behind the tick.
- **Also:** the collapsed notification bar stopped being `fitXY`-stretched from a
  `w = h × 80` render — at that aspect the tick squeezed to ~0.19 h and the new vertical
  boundary skewed. It now renders at the same nominal 340 dp the panel assumes.
- **Three toggles, not one** (decision of 2026-08-13, where the Mac ships a single
  control): Settings → Appearance, group "Show red past the pace mark", footnote "Off
  keeps the even-pace tick without the colour.", then **On widgets** / **In-app bars** /
  **Pinned notification**. All default ON, decoded tolerantly (absent → ON). The
  surfaces are read at very different distances, so the appetite for red differs per
  surface. Each gates *only* the red: the tick always draws, and the 80/90/100 severity
  ladder is untouched — this is about pace, not severity.
- **Where:** `ui/BarGeometry.kt`, `ui/BarRenderer.kt`, `ui/RingRenderer.kt`,
  `MainActivity.kt`, `SettingsScreen.kt`, `data/UsageCache.kt`,
  `notify/PinnedNotification.kt`, `widget/UsageWidget.kt`, `widget/BarWidget.kt`,
  `widget/RingWidget.kt`, `widget/MiniRingsWidget.kt`, `widget/FaceBits.kt`.
  Tests: `ui/BarGeometryTest.kt`. Fixtures: every wireframe state, both bar heights,
  the narrow 150 dp case and the red-off states in `DebugFacesActivity` — which also
  discharges the CCRM-15 (Above-Pace Verification) residual for bars.
- The notification's "gauge" style ring still has no pace mark: it predates
  `RingRenderer` and is the standing follow-up noted in `RingRenderer.kt`.

### CCRM-46 · Picker Icons — browser icons in the sign-in "Open with" dialog
- **Status:** Done and **verified on the Fold 7, 2026-08-18** (release-signed build —
  Brave and Samsung Internet rows drew their launcher icons). Wireframe approved
  2026-08-18 during the release-planning review; ID allocated retroactively the same day
  so the commit has a name.
- **What:** each row in the app's own browser picker (sign-in and the CCRM-26
  (Quick Links) usage-dashboard button) shows the browser's launcher icon at 32 dp
  before the label — recognition beats reading when several browsers are installed.
  A browser whose icon fails to load keeps a 32 dp spacer so labels stay aligned.
- **Where:** `SettingsScreen.kt` — `BrowserChoice` gains an `ImageBitmap?`, loaded in
  `installedBrowsers()` via a hand-rolled `Drawable.asIconBitmap()` (core-ktx's
  `toBitmap()` is not a declared dependency).

### CCRM-48 · Status-Bar Gauge — both windows, pace-marked, in the small icon
- **Status:** Done and **verified on the Fold 7, 2026-08-21** (release-signed install
  over the live app; both rings, both pace cuts and the min-fill floor observed on the
  status bar and the QS tile, in both tint directions).
- **Provenance:** the Mac menu-bar gauge (CCooldownMac `GaugeRenderer.swift`
  `renderMulti`/`drawPaceMark`), ported to the always-on notification's status-bar
  icon. Wireframe `design/status-bar-gauge-wireframe.html`, rev B approved 2026-08-21
  with "make the rings as big as possible so the pace mark is clear on both windows".
- **What:** a new **Twin** status-bar icon style — both windows as concentric rings
  (7-day outer at 23/24 of the square, 5-hour inner at 14.5/24, 2.6 dp stroke), each
  carrying a pace mark, no numbers. The mark is the Mac's cleared-gap tick taken to
  its limit: the icon is a tinted alpha mask, so fill and tick are the same ink and a
  drawn tick would vanish on the fill — the mark is a slot **erased through** the
  band (PorterDuff.CLEAR) at `elapsedPercent`, with nothing redrawn inside. Fill past
  the cut = burning faster than even pace. The cut was also added to the existing
  Ring style; Pie / Battery / Number are untouched. The severity ladder cannot exist
  on this surface (the tint strips colour), so ≥100% is shape instead: a notch opens
  at 12 o'clock, making a closed ring read as closed rather than merely long. Honesty
  rules as everywhere: no reset clock → no cut (`RingGeometry.showTick`), no data →
  bare track, never a fake 0%.
- **Where:** `ui/UsageIcon.kt` (`TWIN`, `windowRing`, `eraseSlot`),
  `notify/PinnedNotification.kt` (`drawStatusIcon` now carries the elapsed/weekly
  values), `tile/UsageTileService.kt` (the QS tile shares the bitmap, so it follows
  the same style), `SettingsScreen.kt` (fifth chip; the four existing chips and the
  `ring` default are unchanged, so nobody's icon redraws without them choosing it).
- **Out of scope, deliberately:** the Mac's third per-model element (a later
  revision if wanted), and its "clock hand" variant — illegible at 11–14 dp.

### CCRM-49 · Glyph Legibility — one ring, in colour, at the size it is really drawn
- **Status:** Done and **verified on the Fold 7, 2026-08-21** — dark bar same day,
  light bar in the CCBG-13 (Light Status Bar) verification pass (light accent fill and
  the darker light-mode pace partner sampled exact).
- **Why:** CCRM-48 (Status-Bar Gauge) shipped and could not be read. The cause was a
  wrong assumption about the canvas, found by measuring: the bitmap is 24 dp but the
  status bar fits it into a **~15 dp slot, by width**, so it lands at ~14 dp. Two
  concentric rings therefore arrived as 13.5 dp and 8.5 dp with a 0.9 dp pace cut.
- **Two facts established by an on-device probe** (a 48 × 24 dp bitmap carrying a
  measuring frame, pure RGB stripes and an asymmetric ring), both of which overturned
  an assumption in CCRM-48:
  - **A wide bitmap buys nothing.** 48 × 24 dp rendered at 39 × 20 px = 14.9 × 7.6 dp:
    aspect honoured, same width as the square, half the height. The slot is
    width-constrained, so **square is optimal** and the Mac's side-by-side menu-bar
    layout does not port to Android at all.
  - **Colour survives in the status bar** — pure `#FF0000`/`#00FF00`/`#0000FF` were
    sampled back exactly — **but is stripped in the Quick Settings tile**, which
    flattens to one tint. CCRM-48's "the severity ladder cannot exist here" was true
    of the tile and false of the status bar.
- **What:** the status-bar icon is **one window, one ring, as large as the square
  allows** (22.4 dp across at a 4 dp stroke), with three deliberately different
  treatments: **used** in the severity colour (the very same `Palette.barColor` value
  the notification's gauge uses, so the two can never disagree), **remaining** in a
  mid neutral, and the **pace mark** as a cool `#5BC8FF` line — a hue chosen because
  it can never collide with the warm ladder, keeping the mark "where am I in the
  window" rather than "how bad is it".
- **Colour is an enhancement, never the carrier.** The mark is still a slot *erased*
  through the band, deliberately wider than the line it holds, so a surface that
  flattens the bitmap still shows a gap. `UsageIcon.draw` takes `fillArgb`: the
  notification passes a colour, the tile passes null and keeps the alpha-mask
  rendering. ≥100% keeps its 12 o'clock notch for the same reason.
- **The Twin style is withdrawn** — measured unreadable, and there is no point keeping
  a chip that loses to the default. `UsageCache.pinnedIconStyle()` migrates a stored
  `"twin"` back to `"ring"` so nobody lands on no selection.
- **Where:** `ui/UsageIcon.kt`, `notify/PinnedNotification.kt`,
  `tile/UsageTileService.kt`, `SettingsScreen.kt`, `data/UsageCache.kt`. Wireframe
  `design/status-bar-glyph-legibility-wireframe.html` (rev C, as built), which mocks
  at 14 dp and rasterises to the real 37 px rather than zooming vectors — the mistake
  that let CCRM-48 through.
- **Known limitation:** the 7-day window is not on this glyph. A *level* for it can
  only come out of the 5-hour ring's size, which is what CCRM-48 proved unreadable. A
  *state* marker (a dot in the ring's hollow, shown only past 80%) would be nearly
  free and is unbuilt — needs a wireframe first.

### CCRM-50 · Weekly Flag — the 7-day window as a pace-state dot in the ring's hollow
- **Status:** Done and **verified on the Fold 7, 2026-08-21** — with live data doing the
  arguing: the weekly sat at 16% used but "6 points above even pace", and the yellow dot
  was flagging it in the status bar while a level-threshold dot would have stayed silent.
  Theme partner verified by switching to Blue (spring-green line) and back; tile verified
  showing the mono collapse (full-alpha disc for the yellow rung).
- **Why:** CCRM-49 (Glyph Legibility) left the 7-day window off the status-bar glyph
  because a second *level* can only be bought out of the 5-hour ring's size — the exact
  thing measured unreadable. A **state** needs far fewer pixels, and the ring's hollow
  is empty space already paid for.
- **What:** a 7.5 dp dot (≈4.4 dp on screen) dead-centre in the ring, keyed on **pace,
  not level** — review's call, and the right one: a weekly that crosses 80 on day 6 is
  beyond correcting, while "above pace on day 2" is actionable. The rungs are the app's
  existing pace verdicts, drawn (`RingGeometry.weeklyFlag`, sharing `PACE_DEAD_ZONE`):
  **no dot** below even pace (good news is silence) · **grey** on even pace (±3, the
  sentence's own band) · **yellow** above even pace · **red** at a truncated 100 —
  level, not pace, so it fires even with no reset clock. Orange deliberately absent:
  indistinguishable from yellow at 4.4 dp, and the Amber theme's accent *is* the yellow
  rung already. Honesty: no weekly reading → no dot; no reset clock → no pace verdict,
  never a guessed one (only red possible).
- **Pace line themed too:** each `ThemeOption` now carries a **pace partner**
  (`paceLight`/`paceDark`, read via `Palette.paceColor`) — cyan-blue for warm/neutral
  themes, spring green for Blue/Indigo, violet for Cyan/Teal. Always cool, never a true
  complement: past 80 the fill is the fixed warm ladder whatever the theme, so a warm
  line dies exactly where the glyph matters (demonstrated in the wireframe). "Material
  You" resolves through `byName` to Claude Orange on this surface, so its partner
  follows the same path. Also recorded: the ring fill already followed the theme —
  `Palette.barColor` returns the accent below 80; no change was needed there.
- **Monochrome surfaces** (QS tile, tinting OEMs) collapse the rungs to alpha: grey →
  a 45% disc, yellow/red → full. Which rung is lost; "the weekly needs a look" is kept.
- **Where:** `ui/RingGeometry.kt` (`WeeklyFlag`, pure + tested in `RingGeometryTest`),
  `ui/UsageIcon.kt`, `ui/Palette.kt` (partner colours), `notify/PinnedNotification.kt`,
  `tile/UsageTileService.kt`, `SettingsScreen.kt` (caption). Wireframe
  `design/weekly-flag-dot-wireframe.html` — rev A (usage-threshold) superseded by
  rev B (pace-relative) after review; rev C records the as-built decisions.
- **Accepted knowingly:** steady use rides "on even pace", so the grey dot is often
  present. Grey is deliberately the quietest mark; if it proves noisy the fix is a
  band strictly below the line, not a wider ladder.

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
- **Status:** Built (2026-08-12) · wireframe approved same day
- **Shipped:**
  - `data/SignInExpiry.kt` — the expiry-line decision as a pure function
    (`Estimated` / `Exact` / `RenewalDead` / `None`), keyed on `REAUTH_NEEDED`,
    which already covers both death signals: a direct 400–403 on refresh, and the
    `firstRefreshFailAt` streak crossing `STUCK_REFRESH_MS` (both escalate to that
    state in `UsageRepository`). 11 cases in `SignInExpiryTest` (108 total, 0
    failures) pin the four states, the 48h materially-early split
    (`MATERIALLY_EARLY_MS` — phrasing only, the date is dropped either way), the
    nearest-day rounding, an unstamped sign-in, a death timestamp *before* the
    sign-in (a stale streak must not render a huge interval), a missing estimate
    date, and that a death **after** the estimate passed still renders the dead
    line — the old `refreshExpiresAt > now` gate would have hidden it silently.
  - Account card: `RenewalDead` replaces the date line, in the error colour —
    "Renewal stopped working 9 days after sign-in — earlier than the ~30-day
    estimate. Re-sign in below." / "…~30 days after sign-in — the sign-in likely
    reached its age limit…". The death moment is the fail-streak start, falling
    back to the failure's own timestamp.
  - Debug section: `Sign-in age: Personal 12d (est. ~30d) · Work 5d (exact)` —
    the instrument that finally reads the real family lifetime the first time one
    dies of old age rather than revocation.
- **Deliberately not shipped:** persisting the observed interval as the new
  estimate for subsequent sign-ins — per the entry's own rule, only worth doing
  once the early death has been seen more than once; one revocation isn't a
  lifetime. Pasted-token (exact-date) accounts keep their behaviour unchanged;
  this item is scoped to the estimate.
- **Device verification:** the healthy lines and the debug age row are checkable
  any time; the `RenewalDead` states can't be produced on demand — they'll be
  seen the first time a real family dies (which is also when the debug row pays
  out). Unit tests are the primary pin here, deliberately.
- **Was:** Planned · small
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
- **Status:** Done (2026-08-12) · wireframe approved same day · verified on the
  Fold 7, 2026-08-19 · was the prerequisite for CCRM-3 phase 2, now cleared
- **Shipped:**
  - `android:widgetFeatures="reconfigurable"` in both `usage_widget_info.xml` and
    `bar_widget_info.xml` — minSdk 31, so no version gating.
  - `WidgetPrefs.has(appWidgetId)` — the getters fall back to Personal/`session`, so
    they can't tell "unset" from the defaults; presence of the stored profile key is
    both the add-vs-reconfigure test and the "has an override" probe CCRM-3
    (Unified Theming) will need for its per-instance overrides.
  - `ConfigScreen` seeds its state from `WidgetPrefs` instead of hardcoding it; when
    reconfiguring the title reads **Widget settings** and the button **Save changes**
    (add flow unchanged: "Widget setup" / "Add widget").
  - **Use my defaults** (reconfigure only, semantics decided at wireframe review):
    calls `WidgetPrefs.remove()`, re-renders, closes — the instance truly drops its
    override, so it will follow the CCRM-3 Settings-held defaults automatically once
    those exist. Today it falls back to Personal + 5-hour.
  - Cancel/back on a reconfigure leaves the widget untouched for free — the seeded
    `RESULT_CANCELED` only deletes a widget on the add flow.
  - **Leak fixed while in there:** `WidgetPrefs.remove()` had no callers and neither
    receiver overrode `onDeleted`, so per-id prefs outlived their widget — and
    launchers recycle ids, which would have pre-filled a *new* widget's config with a
    dead one's settings. Both receivers now clean up.
  - The brittle `className?.endsWith("BarWidgetReceiver")` provider check became a
    `ComponentName` comparison.
- **Device-verified 2026-08-19** (nothing here was unit-testable — it's all launcher
  interaction): the long-press reconfigure entry point appearing, the pre-fill,
  save/re-render, cancel harmlessness, "Use my defaults", and the
  delete-then-recycle id case, all confirmed on the Fold 7.
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
- **Status:** Done (2026-08-19) · wireframe rev B approved same day
  (`design/display-tokens-wireframe.html`, kept until the on-device pass) · needs
  on-device verification
- **Shipped:** `usageDisplay` pref ("used" default / "left") in `UsageCache`;
  `Fmt.usageInt`/`usageShort`/`usageWorded` in `ui/Palette.kt` as the one place a
  percentage becomes text — used truncates, left **floors the exact remainder**
  (99.7% used reads 0% left, never the 1% you don't have), over-limit clamps left
  at 0. **Rev B rule, decided at review: every numeric readout flips — nothing is
  exempt.** Cards and SubBars ("53% left"), widget bar labels, the QS tile, the
  Pace widget stat line and the chart callout flip worded; ring/mini-ring bores,
  the gauge ring text, the number-tile plate and the status-bar "number" digits
  flip bare (the ring face's countdown line gains a "left ·" prefix as its
  disambiguator; the `big` notification style gains a 10sp "LEFT" caption under
  the number, GONE in Used mode). Fills, `setProgress`, pace ticks, the ≥100% "!!"
  glyph and `Palette.barColor` stay keyed on used everywhere. Chart axis, guide,
  "now" and projection labels stay used — absolute positions. Credits complement
  the **rounded** display percent (6% used ↔ 94% left; CCRM-3's rounding split
  preserved). Chips under Settings → Appearance re-post the pinned notification
  and refresh widgets on flip. 9 cases in `UsageDisplayTest` (198 total, 0
  failures) pin the floor rule, both boundaries, the clamp, and the copy.
- **Was:** Planned · small
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
- **Status:** Done (2026-08-19) · wireframe approved same day, **Option A:
  primary-first** (`design/display-tokens-wireframe.html`) · needs on-device
  verification
- **Shipped:** global `resetDisplay` token ("countdown" default / "clock") in
  `UsageCache`, migrated by a read-time fallback from the old `tileSubtitle` key —
  the tile keeps its behaviour and stops owning the preference. Option A rule from
  review: **the chosen form leads; surfaces with a second slot keep the other form
  there** (the countdown reads better, the clock can't go stale). Applied: in-app
  `ResetRow` and widget `ResetSubText` swap slot order; the Ring face's
  always-present line takes the chosen form with the other on the layout-gated
  line; the Pace widget swaps its stacked pair; the collapsed notification and the
  QS tile (single-slot) carry the chosen form only; the expanded notification
  header and the panel's 7-day line show both, chosen first (the panel line gains
  the countdown it never had — the one addition to a default rendering).
  `Fmt.relIn` collapses to **"resets soon" inside five minutes** on every surface,
  aligned with `widgetCountdown`. **Deliberately exempt:** the mini-rings face
  keeps its countdown in both modes — its 9sp slot can't carry a day, and a bare
  clock on a 7-day ring wouldn't say which day. Chips moved from the Quick
  Settings tile section to Appearance → "Reset time". 3 cases in
  `ResetDisplayTest` (201 total, 0 failures) pin the soon-collapse boundary.
- **Was:** Planned · small · pairs with CCRM-22
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
- **Status:** Done (2026-08-13) · wireframe approved same day
- **Shipped:** two text buttons on each *signed-in* account card (none when signed out —
  nothing to verify), below a new divider after the Re-sign in/Clear row: "Anthropic
  status" opens `status.anthropic.com` in the default browser, "Usage dashboard" opens
  `claude.ai/settings/usage` through the same per-profile browser picker sign-in uses,
  so it lands in the browser holding *that* account's Claude session. Main screen gets
  "Check Anthropic status" directly under the red `Status:` line, same
  `lastStatus != "OK"` gate — including the no-data-yet state, where it matters most.
  Both URLs are compile-time https constants; `openInBrowser` moved out of `private`
  so MainActivity shares the one launch path, now guarded by `allowedLinkUrl`
  (https/http only, silent no-op otherwise) and pinned by 5 cases in `QuickLinksTest`.
  Text-only buttons — no `material-icons-extended`.
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
- **Status:** Done (2026-08-13)
- **Shipped:** the account card's plan chip now composes in the multiplier — "Max 20x" —
  via a pure render-time parse (`Fmt.tierMultiplier`, pinned by 9 cases in
  `TierMultiplierTest`; "05x" → "5x", a 1x tier renders, anything unrecognised → null and
  the chip falls back to the bare plan). The raw tier string is stored as-is
  (`UsageCache.tier`), read tolerantly off the token JSON on both the sign-in and
  pasted-token paths (`rate_limit_tier` / `rateLimitTier`); the renewal path deliberately
  does not touch it. The verify-first question gets its instrument: sign-in now records
  the token response's key names (never values), shown as a line in the Settings debug
  section, so the next real sign-in settles whether the tier field is in our response.
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
- **Status:** Done (2026-08-19) · wireframe approved same day
  (`design/display-tokens-wireframe.html`) · needs on-device verification
- **Shipped:** two three-way prefs in `UsageCache` — `themeMode`
  ("system"/"light"/"dark") and `timeFormat` ("system"/"12"/"24") — as chip rows
  leading Settings → Appearance, replacing the 24-hour switch. **Theme:** the
  resolved dark flag rides a new `LocalAppDark` (`ui/ThemeMode.kt`, pure
  `resolveDark`/`resolve24h` pinned by `ThemeModeTest` — garbage values fail
  towards the system, per the tolerant-decode house rule); every in-app dark read
  (`barFill`, the bar tick alphas, `Sparkline`'s 0.07/0.10 · 0.18/0.20 · 0.30/0.34
  opacity pairs, History bars, the theme swatches, note cards) goes through
  `appDark()`, so a forced mode drives the chart opacities the roadmap warned
  about — and a `SideEffect` sets `isAppearanceLightStatusBars` from the resolved
  mode, keeping CCBG-13 (Light Status Bar) correct when forced.
  `WidgetConfigActivity` resolves identically; **widgets and the notification
  deliberately keep following the system** — their backdrop isn't ours.
  **Time format:** `use24hTime()` keeps its Boolean shape and resolves internally
  (system → `DateFormat.is24HourFormat`), so its ~40 read sites are untouched.
  Migration per the entry's own rule: an install that ever touched the old
  boolean keeps that explicit choice via a read-time fallback; only installs
  without the old key get System. 203 tests, 0 failures.
- **Was:** Planned · small
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
- **Status:** Done (2026-08-13) · wireframe approved same day
- **Shipped:** the check rides `UsagePollWorker` — no scheduler of its own — behind a
  pure gate in `data/UpdateGate.kt` (`autoCheckUpdates` on and 6h past the last
  *successful* check; a failure never advances the anchor, so the next poll retries).
  A found release posts notification id 40 on the new `update_alerts` channel
  (IMPORTANCE_LOW, silent) at most **once per version ever** — a swipe counts as seen —
  with a "Skip this version" action (`notify/UpdateSkipReceiver`) that silences exactly
  that version; a newer one still notifies. `lastNotifiedVersion` records only when the
  post succeeded (the pace-alert rollback pattern). Tap opens the release page only if
  its URL is https on github.com, else the hardcoded releases page — nothing ever
  downloads or installs. New **Updates** settings section above About with the
  auto-check toggle, the manual "Check for updates" button (moved out of the About
  card), and the last-checked/failed outcome lines; the manual dialog names a skipped
  version. Decision table, notes trimming, gate, and URL fallback pinned by 20 cases
  in `UpdateGateTest` (117 tests, 0 failures).
- **Was:** Planned · small
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
- **Status:** Done (2026-08-19) · wireframe `design/error-and-estimates-wireframe.html`,
  built under the same-day "finish this" blanket instruction (recommended options
  taken; judge at the on-device pass) · needs on-device verification
- **Shipped:** `data/ErrorKind.kt` — six kinds (auth · rateLimited · network ·
  server · invalidResponse · internal), each carrying a remediation-phrased title,
  a widget-length short label, and a severity (only auth/internal render red).
  Produced in `doFetch`/`authFailure` and persisted as `lastStatusKind` beside
  `lastStatus` (`Snapshot` gains the field; pre-upgrade statuses are mapped once by
  `ErrorKind.fromStatus`, pinned string-by-string in `ErrorKindTest`). The main
  screen's bare red "Status:" line became `ErrorNotice` — a tinted row with the
  fix-naming title, the raw status as a small monospace evidence line (rate-limited
  appends the real next-try time from `backoffUntil`), and one action per kind:
  Open Settings (auth), Check Anthropic status (network/server — CCRM-26
  (Quick Links) kept, now scoped), Check for updates (invalidResponse). Widget
  captions (`FooterRow`, `FaceBits.pillText`) and the CCRM-44 (One Surface) stale
  strip show the short label instead of the raw string. **Kept, per the entry's own
  warning:** the last-good snapshot still renders beside the error — `saveFailure`
  never touches `rawJson`. 207 tests, 0 failures.
- **Was:** Planned · small-to-medium
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
- **Status:** Done (2026-08-19) · wireframe `design/error-and-estimates-wireframe.html`,
  built under the same-day "finish this" blanket instruction · needs on-device
  verification
- **Shipped:** `ui/Estimates.kt` — `EstimateLine` (muted trailing ⓘ; tapping the
  line toggles a one-sentence `ProvenanceNote` beneath) applied to the three
  inferred figures: the pace/projection sentence in `TrendBlock` ("a least-squares
  fit anchored on the latest reading — it shifts as new polls land"), the ~30-day
  sign-in expiry line ("Anthropic doesn't report the real expiry…"), and the
  credits percentage — whose note says it is *finer* than the server's rounded
  figure, not a hedge, exactly as this entry asked. `SignInExpiry.Line.Exact`
  stays plain: marking a measurement would hedge it. Chart canvas labels
  unchanged (the "~" already carries them); widgets and the notification get
  nothing (too tight). **One substitution from the wireframe sketch, recorded
  there:** Compose text has no dotted underline and a solid one reads as a link,
  so the marker is the ⓘ glyph — same tap interaction.
- **Was:** Planned · small
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
- **Status:** Done (2026-08-19) · design record
  `design/diagnostics-shortcuts-wireframe.html`, built under the same-day
  "finish this" blanket instruction · needs on-device verification (launcher
  interaction — nothing here is unit-testable)
- **Shipped:** `Shortcuts.kt` — dynamic shortcuts via `ShortcutManagerCompat`
  (dynamic, not static XML, exactly because labels follow
  `UsageCache.profileLabel`): one per profile reusing the `"profile"` extra
  every other entry point already sends, plus **Refresh now**, which opens
  MainActivity with a `refresh` extra that triggers a manual poll of every
  account. Republished on every app launch and on a profile rename. Icons are
  the launcher mark / refresh glyph; per-profile glyphs are a cosmetic
  follow-up if wanted. Publishing is wrapped defensively — some launchers
  ration slots and throw, and a shortcut must never be a crash source.
- **Was:** Planned · small
- **Why:** OpenQuota's global keyboard shortcut has no Android equivalent, but the intent —
  reach the number without navigating — maps cleanly onto launcher shortcuts.
- **Approach:** static/dynamic shortcuts for **Personal**, **Work** and **Refresh now**,
  deep-linking into the right pager page. Complements the Quick Settings tile (CCRM-11)
  rather than duplicating it: the tile is for the shade, shortcuts are for the home screen.
- **Note:** shortcut labels should follow the user's renamed profile labels
  (`UsageCache.profileLabel`), which means they're dynamic shortcuts, not static XML.

### CCRM-34 · Diagnostics Log — widen the ping log into a general app log
- **Status:** Done (2026-08-19) · design record
  `design/diagnostics-shortcuts-wireframe.html`, built under the same-day
  "finish this" blanket instruction · needs on-device verification
- **Shipped:** `diag/AppLog.kt` — levelled (Error/Warn/Info/Debug, tolerant
  decode), categorised (`poll · alerts · auth · ping`), a single lock across the
  background writers, 256KB → keep-last-600-lines trim, same pullable
  external-files location (`app-log.txt`). Minimum level is the user-facing
  `logLevel` pref, default Info. `PingLog` became a thin shim (category "ping",
  INFO) so the ping machinery kept its call sites. New call sites: poll outcomes
  in `UsagePollWorker` (success at Debug — routine stays out of Info), alert
  posts and permission blocks in `Alerts.notify`, token-renewal outcomes in
  `refreshAccessToken` as **status codes only**. The card moved out of the debug
  unlock into a visible Settings → Diagnostics section, with Info/Debug chips
  and a Share button (`ACTION_SEND` plain text, last 400 lines — no
  FileProvider). **Hard rule honoured and stated in the object's contract: never
  tokens, authorization headers, or the `code_verifier`.** Pure pieces (level
  gate, decode, line shape, trim) pinned by `AppLogTest`.
- **Was:** Planned · small
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
- **Status:** Done (2026-08-13)
- **Shipped:** the full `.github/` set, docs only. `SECURITY.md` routes reports through
  GitHub private vulnerability reporting with the explicit "no real credentials, OAuth
  tokens, or authorization headers in a report" rule (the v0.14 scrub named as the
  precedent). `CONTRIBUTING.md` surfaces the rules that lived only in CLAUDE.md and the
  CCRM-8 (Mac Menu-Bar) entry — do-not-fork-for-another-client, wireframe-before-UI,
  tracker-ID naming, Android-only and Claude-only scope, and how to run the tests.
  Issue forms in `ISSUE_TEMPLATE/`: `bug_report.yml` requires app version and phone
  model/skin (the skin-dependent `big` notification style is why), `feature_request.yml`
  points at this file's appendix of ruled-out ideas, and `config.yml` adds the private
  security-advisory and email-feedback contact links. `PULL_REQUEST_TEMPLATE.md` asks
  which CCRM/CCBG item the change serves and whether a wireframe was approved before
  building. `dependabot.yml` watches gradle and github-actions, both weekly.
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

### CCRM-6 · Multi-Account — more than two accounts
- **Status:** **In progress — built 2026-08-21, wireframe approved the same day. The Fold 7
  pass is largely confirmed across two runs, the second with a real third account signed in;
  the four-account tab strip and the alert-collision case are still unobserved.**
  Moved up from *Later* on 2026-08-19 because the user needs a **third Claude account**
  tracked, making it the first item after the batch of
  CCBG-13 (Light Status Bar), CCRM-22 (Used or Left), CCRM-23 (Reset Display), CCRM-29
  (Display Mode), CCRM-27 (Error Taxonomy), CCRM-30 (Estimate Honesty), CCRM-34
  (Diagnostics Log) and CCRM-33 (App Shortcuts).
- **Why:** `Profile` was a hard-coded `PERSONAL`/`WORK` enum, wired through credentials,
  cache keys, notification IDs, widgets and alerts. Users with 3+ accounts (multiple work
  orgs, side projects) couldn't be served — and the driver was real, not hypothetical.
- **The fork is settled: a proper registry**, not a cheap third enum slot. It costs more now
  and is the version that doesn't get rebuilt when account four arrives — the reading CCBG-1
  (History Retention)'s note on history ownership already pointed at.
- **How it landed.** `Profile` became a value type — `data class Profile(key, slot, label)`
  ([Profile.kt](app/src/main/java/com/robin/claudeusage/data/Profile.kt)) — owned by the new
  [ProfileRegistry](app/src/main/java/com/robin/claudeusage/data/ProfileRegistry.kt) on its
  own `"profiles"` prefs file. The three fields are deliberately different kinds of thing:
  - **`key`** namespaces every store (`"personal"`, `"work"`, `"p2"`…). Never reused.
  - **`slot`** is the integer identity Android surfaces need — notification-ID offset,
    PendingIntent and alarm request codes, tile binding — allocated from a persisted
    monotonic counter and **never reused**, so a stale placed widget or tile can never
    inherit a new account's data. **Slots 0 and 1 stay pinned to `personal`/`work`**:
    existing installs have widgets, alarms and posted notifications keyed off those ints.
  - **`label`** is the user's 16-character name, and therefore *not* identity — `Profile`
    equality is the key alone, so a rename can't turn `pinnedProfile == p` false.
- **No data migration.** Persistence was already profile-count-agnostic: every store
  namespaces by `profile.key` and there is no Room/DataStore/proto schema anywhere.
  `CredentialStore.k()` and `UsageCache.k()` keep the legacy unprefixed-Personal exception,
  restated as `profile.key == Profile.LEGACY_KEY`. The seed migration emits both slots
  unconditionally, carrying the old `customLabel` values across, and is gated on a stored
  flag rather than on presence — gated on presence, a deliberately removed Personal would
  resurrect on the next launch.
- **`notifId` is now `kind + slot * 100`.** Kinds top out at 31 (`PACE_WEEKLY_KIND`), so the
  ×100 stride is safe for any slot, and slots 0/1 reproduce the pre-CCRM-6 IDs exactly — no
  notification churn on upgrade. It replaces a `+100 only for WORK` test under which every
  third account silently overwrote Personal's notifications.
- **Unconfigured accounts lose their tab** (settled on the wireframe): `ProfileTabs` and
  `HistoryScreen` read `configuredProfiles()`, so one account shows no tab strip at all and
  a new one appears the moment its token lands. Signing in stays in Settings. Both strips
  stay a fixed `TabRow` up to three accounts and become scrollable at four — three tabs get
  ~133 dp on a 400 dp screen and a 16-character label needs ~110, but four would get 82 dp
  on the cover screen and every label would truncate. One rule at both widths, so the strip
  doesn't change shape when the phone unfolds.
- **`Conditions.panelFor` folds every other account**, not exactly one: each one's
  re-auth/stale/expiry strips carry its label and its folded events merge into the same
  newest-first sort. CCRM-44 (One Surface)'s ordering contract is untouched — faults ·
  events · warnings · update last — and `MAX_STRIPS` **stays 3**: the shown account's own
  faults come first so it can never be crowded out of its own panel, and the panel's height
  is finite however many accounts exist.
- **Rename folded into the account card**, behind a ⋮ overflow alongside **Remove account**,
  and the separate "Profile names" section was deleted — names are registry-owned, so it had
  become a second editor for the same field with no way to grow a row for an account that
  doesn't exist yet. ⋮ also keeps Remove well away from **Clear**: Clear signs out and keeps
  the history (CCBG-1), Remove destroys it, and side by side that is a fat-finger disaster.
- **Two documented caps.** A **fifth account works on every surface except a Quick Settings
  tile** — a tile is a statically declared `<service>` and Android will not let the list be
  driven at runtime, so there is a fixed pool of four bound to *slots* 0–3. The two original
  class names (`PersonalTileService`/`WorkTileService`) are kept and rebound, because
  renaming a declared service breaks tiles the user has already placed; `Slot2TileService`
  and `Slot3TileService` are new, and a tile whose slot has no account reports
  `Tile.STATE_UNAVAILABLE`. `android:label` is static and cannot follow a rename, so the two
  new picker entries read "Claude account 3"/"Claude account 4" while the placed tile shows
  the live label. Separately, **launcher shortcuts are capped** at
  `ShortcutManagerCompat.getMaxShortcutCountPerActivity` (typically 5): accounts fill the
  list in order and "Refresh now" always keeps the last slot. Today's unbounded
  `entries.map` silently overflowed at four accounts.
- **Removal is the only destructive path**, and its ordering is load-bearing: cancel the
  ping and verify alarms → cancel the slot's whole notification ID range → clear credentials
  → clear the cache keys → delete the two JSONL files → *only then* drop it from the registry
  → repoint `pinnedProfile` and any `WidgetPrefs` entry → republish shortcuts → redraw the
  widgets and the pinned panel. Everything above the registry step needs to resolve the key.
  Behind a confirmation naming exactly what goes, including the year-long session log.
  Because slots are never reused there is no window in which a surface can read a *new*
  account's numbers; the worst case is a surface showing nothing, which is correct.
- **Wireframe:** `design/multi-account-wireframe.html` (approved 2026-08-21), with all six
  decisions recorded at the foot of it. Keep until the device pass is signed off.
- **Tests:** `ProfileRegistryTest` — 25 cases covering seed idempotence and the slot-0/1
  pinning, `nextSlot` never reissuing after a remove, rename/lookup/fallback, the JSON
  round-trip and its stale-counter clamp, and `notifId` colliding for no kind in use across
  four slots while reproducing today's IDs for slots 0 and 1.
- **Device pass, Fold 7 cover screen, 2026-08-21 — four of seven steps confirmed** on a
  release-signed build installed over the live 2-account install (labels **Pro**/**Teams**):
  - **Upgrade — pass.** Both accounts intact, both custom labels carried across by the seed
    migration, identical usage (5-hour 34%, 7-day 24%) and an unbroken 12-point weekly
    curve, the pinned notification still posted at id 9100, no crash. Window pings (CCRM-17)
    are ToS-disabled (`pingEnabled` returns a hard `false`), so "armed pings still armed"
    was vacuous on this build — nothing to observe, not something that passed.
  - **Accounts screen — pass.** Two cards unchanged bar the ⋮; **+ Add account** below them;
    the "Profile names" section gone, with POLLING following directly. ⋮ opens
    Rename… / Remove account, the latter in the error colour.
  - **Add + rename — pass.** The third card appeared as "Account 3 · Not signed in", the
    per-account **Account 3 alerts** toggle appeared in the notifications list (proving that
    loop is registry-driven), and ⋮ → Rename… rendered as wireframed — 9/16 counter, and
    the "clear the field to go back to *Account 3*" line naming the real restore value.
    Renamed to "Side project" and the label propagated.
  - **Pinned "Show profile" chips — pass at four.** `Pro · Teams · Side project` on line one
    and `Account 4` wrapped onto line two: the `FlowRow` change works and nothing is clipped
    out of reach, which the old non-wrapping `Row` did. The new copy — "all alerts, from
    every account, fold into this panel" — reads correctly, in the **Huge number** style.
  - **Quick Settings tiles — pass.** All four services declared and queryable with the exact
    picker labels "Claude Personal" / "Claude Work" / "Claude account 3" / "Claude
    account 4", the two original class names preserved.
  - **Removal — pass, including the slot invariant.** The confirmation rendered exactly as
    wireframed and named **Pro** as the fallback. Removing the fourth account logged
    `[account][p3] removed — slot 3 retired, data deleted`; adding another and removing it
    logged `[account][p4] removed — slot 4 retired`. **Slot 3 was not reissued** — the
    on-device confirmation that a stale widget or tile cannot inherit a new account's data,
    and that the fifth-slot account correctly gets no QS tile. No crash either time.
- **Second device pass with a real third account signed in, same evening** (the user signed
  it in; the three accounts are now labelled **Personal**, **Work** and **Product**, keys
  `personal`/`work`/`p2`):
  - **Third tab and its data — pass.** Three tabs in a **fixed `TabRow`** at 411 dp, all
    labels legible at full width, confirming the fixed-up-to-three half of the strip rule.
    The Product tab shows its own fetched 5-hour and 7-day figures, its own window
    boundaries (19–26 Aug against Personal's 21–28), and its own **per-model Fable cap** —
    so the `limits[]` walk works on a registry-minted account.
  - **Storage key — pass.** With the diagnostics log at Debug, a manual refresh logged
    `[poll][personal] manual → OK`, `[poll][work] manual → OK` and **`[poll][p2] manual →
    OK`** — one sweep covering all three, and direct confirmation that the third account's
    key is `p2`, hence `usage-history-p2.jsonl` and `usage-sessions-p2.jsonl`. Automatic
    polls show the same three keys.
  - **History screen — pass.** Three tabs, and Product's tab shows only its own session log
    (one window) against Personal's thirteen, so the per-account file split is real.
  - **Pinned panel folding another account — pass, in the Huge number style.** With the
    panel switched to Product, the headline carried **Product's** own 5-hour figure, reset
    and bar, its 7-day bar and its Fable bar — while the strip between them was
    **another account's** folded pace event. That is the generalised `panelFor` working:
    under the old code "the other profile" was a single arbitrary pick, and it is now every
    other account merged newest-first.
  - **Quick Settings tiles — pass, all four runtime states.** The two pre-existing placed
    tiles kept working after being rebound from enum constants to slots 0/1 —
    `Personal 44%` and `Work 0% · not started`. Adding the two new entries gave
    **`Product 20% · resets in 3h 30m`** on `Slot2TileService` (live label, not the static
    "Claude account 3") and **`Claude account 4 · no account`** on `Slot3TileService` —
    the `STATE_UNAVAILABLE` state, greyed and inert, because slot 3 was retired earlier in
    the pass. The picker listed all four with the exact static labels, the two placed ones
    greyed out.
  - **Shortcuts — pass.** Exactly four dynamic shortcuts: three accounts plus "Refresh now".
    (This device reports `maxShortcutsPerActivity: 15`, so the cap did not bind here — the
    ordering rule is verified, the truncation branch is not.)
  - **Zero crashes** across both passes.
- **Filed off this pass: CCBG-16 (Stale Strip Label)** — a folded event strip keeps the
  account name it fired under, so the panel read `Personal · 5-hour window` above a
  `Pro: 7-day window will run out early` strip. Pre-dates CCRM-6 (the frozen-text store came
  in with CCRM-44 (One Surface)), but CCRM-6 made renaming one tap and gave accounts default
  names people will change, so a near-unreachable state is now easy to hit. Cosmetic.
- **Still unverified, with reasons:**
  - **The `ScrollableTabRow` switch at four accounts.** Needs a fourth real token. Both tab
    strips are still unobserved above three, so this stays a CCRM-15 (Above-Pace
    Verification) risk — the single most important thing to look at next.
  - **An alert on the third account posting standalone without cancelling Personal's** (the
    old ID collision). Not producible on demand: every alert kind needs a real trigger, and
    Product sat at 17–20% of a 5-hour window against 80/90/95 thresholds, with staleness
    six hours away and no expiry inside the seven-day horizon. What *is* established is the
    premise the fix rests on — `Slot2TileService` rendering Product's data proves
    `Product.slot == 2` on device, which is exactly the input `notifId` multiplies — plus
    the unit test covering every kind in use across four slots and slots 0/1 reproducing
    today's IDs.
  - **`MAX_STRIPS` overflow and the "+ n more" line at three accounts.** Only one condition
    was live across all three, so the cap was never approached.
  - **Signing out of Work only.** Skipped at the user's direction: Work is a live account and
    the test costs a re-sign-in.
  - **The widget config picker and widget repointing.** No widgets are placed on this device
    (confirmed by `dumpsys appwidget` and by the user), so there was nothing to observe —
    which also makes step 1's "placed widgets still point at the right account" vacuous here
    rather than passed. The picker's list source (`configuredProfiles()`) is exercised live
    by the tab strip and History, and its `FlowRow` by the pinned chips at four, but the
    screen itself was not opened.
  - **The inner screen's two-column accounts layout.** The phone stayed folded; the inner
    display is powered off then, so it captures black over adb.
- Worth completing before CCRM-7 so iOS inherits the flexible model instead of the 2-slot one.

---

## Needs design — decide the shape before building

### CCRM-51 · Rails Gauge — the Mac's Rails instrument on the status-bar icon
- **Status:** **Built 2026-08-26. Design fully approved (wireframe rev J), 251 unit tests
  green, debug APK assembles. NOT yet verified on the Fold 7** — no device was reachable
  when it was built, so this is not done until it has been looked at. First states to check:
  **no data** (the hairline alone), the **empty** rung on a light bar, and **100%** (the
  spent post over a closed red ring).
- **Why:** CCooldownMac shipped the **Rails** gauge (CCM-59/CCM-60 [Menu Bar]) and handed
  over the spec plus four follow-ups. Rails reads usage as a *length against marks* rather
  than a filled band, so the port puts both apps on one grammar — as CCRM-49 (Glyph
  Legibility) and CCRM-50 (Weekly Flag) already shared the ladder and the weekly flag.
- **Scope:** the status-bar / notification small icon only, in both round styles — **Ring**
  and **Pie**, reusing the existing `pinnedIconStyle()` setting rather than adding a
  preference (also the Mac's K2 decision, reached independently). Pie previously carried **no
  pace mark at all**, so this makes the chip a look rather than a choice between an
  informative icon and an uninformative one. **Bars unchanged** — both specs agree they keep
  the pace post — so the expanded panel is untouched. Battery and Number untouched. The
  rails *bar* cannot go in the status bar: CCRM-49's probe established the slot fits bitmaps
  by width, so a 22 × 4 bar would render at about 15 × 2.7 dp.
- **What it draws**, in order — extent · usage · red slice · hub · needle · spent post:
  - **Hairline** tracing the extent (ring) or a **faint 18% disc** (pie), at Ring's own
    11.2 dp footprint. The Mac's Ø/2−1 inset was rejected: a style chip should not cost 13%
    of the glyph.
  - **Usage** in the severity-ladder colour — the very same `Palette.barColor` value the
    notification's own gauge uses, so glyph and gauge can never disagree. 9% floor so 1%
    still reads as "started".
  - **A clock-hand needle** at the pace position, **pinned at the hub** — 1.79 dp at 85%
    with a 4.48 dp cleared halo (the Mac's J2 weights). This replaced the Mac's radial band
    tick, which at **7.6 px** was the weakest mark on the glyph. Their needle runs from the
    *centre*, but our hollow holds the weekly dot, firing their own degrade rule; pinning it
    at the dot keeps both features and makes the dot read as the pin a hand turns on.
  - **The 12 o'clock post only at a truncated 100**, as the sole "spent" cue. Chosen over an
    erased notch: a post is a cleared halo *with* an ink line in it, so a gap **and** a mark
    survive the QS tile's tinting. Drawn last, so when the needle lands near 12 the spent cue
    wins the overlap.
  - **Marks are neutral ink** — "time has no severity". This reverts CCRM-50's themed cool
    pace line here: the fill already carries the colour, so a coloured mark spends the glyph's
    one colour budget twice. `Palette.paceColor` and the partner table are retained,
    undrawn, as the shared contract the Mac still consumes.
  - **Red slice over the severity fill** — deliberately the opposite of the Mac's "never two
    alarms on one gauge": severity and pace answer different questions. **This meant no
    renderer change at all**: `RingRenderer`/`BarRenderer` already drew the pace-red
    regardless of severity, so what was filed as a defect became the specification.
- **7-day dot: four rungs, and a tightened contract.** **empty → grey → yellow → red**, one
  shape step then three colour steps, so the ladder survives the tile.
  - `EMPTY` (no usage) and `SPENT` (truncated ≥ 100) key on level alone and need no clock;
    `ABOVE`/`WITHIN` split on the shared `PACE_DEAD_ZONE`, so the dot flips at the exact poll
    the pace sentence does.
  - **`WITHIN` is wider than CCRM-50's `ON_PACE`** — it covers below pace too, where CCRM-50
    drew nothing. That made "no dot" mean either *healthy* or *no reading*; **"no dot" now
    means exactly one thing: no weekly reading.**
  - Which fixed a real flaw: a week with usage but **no reset clock** used to draw nothing,
    which under this ladder would have falsely claimed "no reading". It rests on `WITHIN` —
    there is usage so not empty, pace unjudgeable so never `ABOVE`.
  - **Why `EMPTY` is an outline, not a black dot** (recorded because it will be re-proposed):
    on a dark bar black ink reads as a hole and vanishes on true-black AMOLED — but the
    disqualifier is the **QS tile**, which tints every non-transparent pixel one colour, so a
    filled dot arrives fully inked and **becomes the `SPENT` rung**. "You have used nothing"
    would render as "your week is gone". An outline survives because it is a *shape*.
- **The honesty contract, now testable:** no reading → the extent alone, never 0%; **no usage
  → no needle even with a known clock** (a mark on an unused gauge measures nothing and reads
  as "just opened"); no clock → no needle and no red. The first two render **byte-identically**
  — the wireframe rasterises both and compares all 37 × 37 pixels in-page.
- **Alphas lifted for the 37 px canvas:** hairline 50% (Mac 35%), spent post 70% (Mac 55%),
  needle 85% (the Mac's own). Not a disagreement about the design — a smaller canvas. The
  hairline's weight matters most in the one state where it is the entire glyph.
- **Where:** `ui/UsageIcon.kt` (drawing), `ui/RingGeometry.kt` (`WeeklyFlag`, pure and
  unit-tested in `RingGeometryTest`), `ui/Palette.kt` (retained-contract note),
  `notify/PinnedNotification.kt` and `tile/UsageTileService.kt` (call sites; the notification's
  red toggle now reaches the glyph), `SettingsScreen.kt` (caption). Wireframe
  `design/rails-gauge-wireframe.html` (rev J, as built) — mocks at the true 14.1 dp and
  **rasterises to the real 37 px**, the discipline CCRM-49 established after CCRM-48 shipped
  unreadable. Findings flowed back to the Mac in `MAC-GAUGE-HANDOVER.md` ("Round two").

### CCRM-44 · One Surface — every alert folds into the pinned notification
- **Status:** **Done — built 2026-08-18, verified on the Fold 7 (pass completed
  2026-08-19)** (release-signed build, Huge number style — the user's own): with the
  panel switched to Teams (live re-auth state), the collapsed row showed "● Sign-in
  stopped working" in place of the reset line and the expanded panel stacked all three
  condition strips (re-auth red, stale red, expiry accent) above the 7-day bar, with
  **zero** standalone notifications from the app. The states still unobserved on
  2026-08-18 — event strips, the "+ n more" overflow line, and the update strip — were
  confirmed in the 2026-08-19 pass. Ships in the next release.
- **Revised same day, on device feedback:** the approved "other profile posts
  standalone" rule survived first contact for about an hour — the Teams re-auth
  posting beside a Pro panel was exactly the clutter the feature exists to remove.
  Now `Conditions.foldedInto` is simply "pinned on", and the panel carries **both**
  profiles: the unshown profile's condition strips are prefixed with its name (event
  titles already carry it), merged newest-first with the rest. Only the shown
  profile's staleness dims the headline number. Wireframe state 4 updated to match.
- **How it landed:** `UsageCache.FoldedEvent` store (per-profile JSON pref, replace by
  kind, pruned on read); `Conditions.panelFor` orders faults · events newest-first ·
  warnings · update and caps at `MAX_STRIPS = 3` with an overflow count; new `reauth`
  and `update` conditions (update reads the new `latestKnownVersion`, persists until
  the installed version catches up — resolving CCBG-12 (Status Icon Swap)'s
  once-per-version timeout tension — and respects "skip this version", though skip
  itself is only reachable on the pinned-off standalone notice); every `Alerts` post
  site branches on `Conditions.foldedInto` (dedup state advances identically either
  way, so unfolding never replays); `UpdateNotification.maybePost` early-outs when
  pinned is on without setting `lastNotifiedVersion`, so unfolding still gets the one
  post; reset strips live a fixed 30 min, other events follow `alertLifetime`; the
  always-on toggle's subtitle states the silence contract. Wireframe:
  `design/fold-all-alerts-wireframe.html` (approved 2026-08-18), kept until the
  event-strip states are seen on device.
- While the always-on notification is on and showing a profile, that profile posts **no
  other notification** — every alert becomes a strip in the pinned panel, on demand and
  silent by the user's explicit choice (decided 2026-08-18: the silence is the point, not
  a cost). Extends CCBG-12 (Status Icon Swap)'s `notify/Conditions.kt` from two conditions
  to the full set: re-auth and update-available become conditions; usage warnings, pace
  alerts and reset pings become timed event strips reusing `alertLifetime`. Pinned off, or
  the panel not showing that profile → alerts post standalone exactly as today
  (`Conditions.foldedInto` rule). Stack order red conditions · events newest-first · amber
  conditions · update; 3 strips max plus a "+ n more" line. The update strip persists
  until the installed version catches up, resolving CCBG-12's "once per version, ever"
  timeout tension for pinned-on users. The CCBG-12 group summary stays as the
  pinned-off safety net.

### CCRM-45 · Tracker Icon — replace the ring identity (formerly Mascot Icon)
- **Status:** **Done — hourglass (option B) built and verified on the Fold 7,
  2026-08-18** (release-signed install: app-drawer tile and the Settings → About
  rendition both match the wireframe). Ships in the next release; still unobserved:
  the themed/monochrome icon under a Material You launcher theme, and the status-bar
  16 px case (needs the pinned-off two-alert state). Wireframe history:
  round 3 approved — `design/tracker-icon-m3-wireframe.html` (Material 3 tonal
  treatment: palette seeded from Claude Orange, radial-gradient background,
  circle container, multi-tone sand). Round 2 (flat marks,
  `design/tracker-icon-wireframe.html`) judged too plain by the user. Round 1
  (mascot direction:
  `design/mascot-icon-wireframe.html`) was dropped by the user on review; the request
  for Anthropic's actual mascot artwork was declined — a publicly distributed repo
  can't carry their trademarked mark while the About screen says "not affiliated".
- New direction: the icon says **usage tracker**, the colour says Claude — a darker
  burnt orange (#A34A22) squircle so it stands apart from the Claude app's tile. Not a
  plain line bar, and still never readable as a percentage at 16 px (the constraint
  that killed the CCRM-42 (App Icon) arc). Options: signal steps (three ascending
  rounded bars), hourglass, dial with ticks, four-window ring (four equal segments —
  the four 5-hour windows a day holds). Foreground/monochrome layers plus the
  background colour resource change; the pinned notification's live meter icon is
  untouched. Third revision of CCRM-42.

### CCRM-47 · Crab Easter Egg — the mascot hiding in the app and the pinned panel
- **Status:** Planned, for fun — filed 2026-08-18, deliberately not scheduled.
- **What:** a crab mascot as an easter egg — somewhere in the app (candidate: the
  About screen after the same 7-tap ritual that unlocks debug, or riding the empty
  "Starts when a message is sent" state) and as a rare cameo on the always-on
  notification's expanded panel (e.g. peeking over the 7-day bar when every window
  sits at 0%). Needs a wireframe per working agreement 2 before building, including
  where it appears, how rarely, and how it stays out of the way of real data.
- **Constraint, settled during CCRM-45 (Tracker Icon):** not Anthropic's actual
  mascot artwork — this repo is publicly distributed and the About screen says "not
  affiliated", so shipping their trademarked asset is out. Same species, our own
  drawing: the original **plush crab** already designed and kept in
  `design/mascot-icon-wireframe.html` (option A2 — round body, mitten claws up,
  happy face) is the ready-made candidate.
- An easter egg is the right home for the mascot energy that lost the icon decision:
  the launcher identity stayed a tracker (hourglass), the personality goes here.

### CCRM-17 · Window Pings — start a 5-hour window on a schedule
- **Status:** **Disabled in-app** (2026-08-18) — ToS posture (see the Posture paragraph
  below): an automated inference call from a third-party client risks the *user's*
  account, so `UsageCache.pingEnabled` is hard-wired to `false` and the Settings
  section is hidden. Code retained dormant; a stored per-profile pref survives so a
  user's choice is restored if Anthropic ever sanctions third-party clients. The
  outstanding Doze test below is moot while disabled.
  - Previously: **Built and the premise is confirmed** (2026-07-31) · opt-in, off by
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
     **Overridden 2026-08-13 by explicit user decision:** the Mac-parity faces ship as
     dedicated providers — CCRM-39 (Ring Widget), CCRM-40 (Mini-Rings Widget), CCRM-41
     (Pace Widget). The ring drawing extraction this phase wanted now exists as
     `ui/RingRenderer.kt`; what remains of phase 3 is only migrating
     `PinnedNotification.drawGauge` / `UsageIcon` onto it, and the layout-*option* idea
     stays open for the existing bar providers only.
- **Verification:** every layout wants looking at on real hardware before it is called done
  (CCRM-15 exists because a state shipped unobserved), then a figure in `release/docs`.
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
- **Interacts with CCRM-6 (Multi-Account) — resolved 2026-08-21:** the registry exists, so
  "build it on the two-slot model and migrate later" is moot. "Per profile" now means per
  registered account, and any ordering/visibility token has to be stored per `profile.key`
  and read through `registry.all()` from the start. It also raises the stakes on the pinned
  notification and the tile: `MAX_PINS_PER_PROVIDER` was a two-account question, and four
  accounts' worth of readings competing for the same space-starved surfaces is a harder one.

### CCRM-31 · Combined Total — one aggregate reading across every account
- **Status:** Needs design · medium · **wireframe rev B on review
  (`design/combined-total-wireframe.html`, 2026-08-27) — Q2–Q5 open**
- **Decided 2026-08-27 — spent-only, and the CCBG-6 gate is designed out rather than argued
  past.** Rev A carried a combined denominator (`53% of $190.00` in the ring centre, a
  `$90.22 left` footer), both leaning on the monthly cap standing in for a credit balance
  the API does not give us. **Both lines are gone.** `spend.used` is present and real and is
  the question this item was filed to answer; `spend.balance` is not, so nothing is claimed
  from it. The **share ring is unaffected** — it never needed a denominator, which is why it
  is a share rather than a gauge — and two states stop existing, since nothing is measured
  against a cap. Per-account caps stay on each profile's credits card, where they are scoped
  to the account that set them. This supersedes the CCBG-6 (Credits Denominator) gate below.
- **Note (CCRM-6 (Multi-Account), 2026-08-21):** "both accounts" below is now "every
  account". The ring's slice count comes from `configuredProfiles()`, not from two, and
  `MINIMUM_SPEND_SLICE_SHARE` earns its keep at four slices rather than being nearly
  redundant at two.
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
  - ~~**Gated on [CCBG-6](BUGS.md).**~~ **Resolved 2026-08-27** by the spent-only decision
    above: with no combined denominator there is no aggregate of denominators to be wrong.
- ~~**Shares the ring drawing** with CCRM-3 phase 3 and CCRM-13.~~ **Stale.** CCRM-39 (Ring
  Widget) already landed the extraction — `ui/RingRenderer.kt` is the shared bitmap ring. A
  multi-slice donut is *different geometry*, not a parameter of a single-value ring, and this
  one is drawn in Compose on a live screen rather than rasterised for Glance. CCRM-31 reuses
  RingRenderer's stroke and gap conventions and adds `ui/ShareRingGeometry.kt`; it neither
  inherits nor owes the Canvas-to-bitmap extraction.

### CCRM-52 · Spend Meter — spend per day, week and month, from the cumulative counter
- **Status:** Needs design · medium · filed 2026-08-27
- **Why:** Asked for directly — a day/week/month usage dashboard "like a network usage or
  power usage app gives me". The framing is the right one: `spend.used` is a **cumulative
  counter**, so differencing it across polls gives spend-per-bucket exactly the way a power
  app turns a meter reading into a daily bar. Nothing in the app shows spend *over time*;
  CCRM-1 (Credits Display) shows the running total and CCRM-31 (Combined Total) shows how
  it splits between accounts, but both are a single instant.
- **Token counts are not this item, and never will be.** The same request asked about
  *tokens*. The payload has **no token field anywhere** — `limits[]` carries `percent`,
  `resets_at`, `severity` and a model display name; `spend` carries currency. OpenQuota's
  token dashboard reads Claude Code's own JSONL logs out of `~/.claude` and prices them
  against a bundled LiteLLM snapshot; a phone has neither the logs nor the install. The
  appendix already rules this out — see *Everything downstream of local Claude Code JSONL
  logs*. **This item is money over time, which the appendix does not rule out:** it rules
  out token counts and *priced* estimates, not differencing the API's own money figure.
- **What ports from the existing surfaces:** the History screen already owns day/week bars,
  the profile tab strip and a 5-hour/7-day toggle, so spend wants to be a **third mode
  there**, not a fourth screen. Needs no `spend.balance`, so CCBG-6 (Credits Denominator)
  does not gate it.
- **The five constraints that shape the design** — every one of them is a state to draw
  before any code, per working agreement 2:
  1. **Forward-only, no backfill.** `HistoryStore` records percent and prunes at 8 days
     (`MAX_AGE_MS`), so there is nothing to reconstruct. Day view is useful in a week,
     week view in a month, month view in a quarter.
  2. **The counter resets monthly.** A *decrease* in `usedMinor` means a new billing month.
     The delta across that boundary must be attributed to **nothing** rather than guessed,
     or the reset renders as a spike.
  3. **Bucket edges are only as sharp as the poll cadence.** Phone off or dozing overnight
     puts a 14-hour gap in one bucket. Range **sums stay exact**; day attribution is fuzzy
     at the edges, and the surface has to say so — the same disclosure duty as CCRM-30
     (Estimate Honesty).
  4. **Most days read $0.00 for most people.** Credits only tick once a plan window has
     actually run out. A dashboard that is usually empty is the real product risk, and the
     empty state is the first thing to wireframe, not the last.
  5. **The current month is already on the card.** `spend.used` *is* this month. The new
     information is past months and the day/week shape — so the month view has to justify
     itself against a figure the user can already see.
- **Do this part first, whatever happens to the dashboard:** extend `HistoryStore.record`
  to persist `credits.usedMinor` + `currency` + `exponent` alongside the percentages, and
  give spend its **own retention** — the 8-day prune is right for a 7-day window curve and
  fatally wrong for a month view. Cheap now, and the series cannot be created retroactively
  later, which is the whole reason to start. Interacts with CCRM-14 (Clear History), whose
  clear must reach the new store, and with CCBG-1 (History Retention) on ownership.
- **Open questions:** whether month buckets are calendar months or billing months (the
  counter resets on the billing boundary, which we only observe indirectly, by the decrease);
  whether the combined-across-accounts series is in scope or strictly per-account like the
  rest of History; and whether a bar chart or a stepped line reads better for a quantity that
  is mostly zero.

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
- **Overlaps CCRM-6 (Multi-Account), 2026-08-21 — but does not close.** Account removal gave
  `HistoryStore.clear()` and `SessionLog.clear()` their **first callers**, and the
  "switching the account behind a profile slot" case above is now served by remove-then-add:
  the new account gets a fresh key and slot, so it cannot inherit the old one's trend line.
  What is still missing is clearing history *without* dropping the account — the same
  confirmed action, on a card that stays. That is what this entry is now for.

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
  `release/screenshots/chart-above-pace-work-fold-inner.png`.
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
- **The bar and ring half is now observed too** (2026-08-13, CCRM-43 (Bar Pace Marks)).
  Work's 5-hour window crossed during that day's device pass and every over-pace element
  rendered on real hardware: the in-app bar's red segment, the same on the pinned
  notification, and the red arc on both ring faces — the last of which exposed and then
  confirmed the fix for a cap defect nobody could see while no window was over pace.
  Like the chart sighting above, this one came up **clean**, and like it, it was luck
  rather than a repeatable test.
- **The harness that would make it repeatable** (same change):
  `DebugFacesActivity` renders every above-pace bar and ring state — the dead zone, both
  sides of the strict boundary, each red-off variant — through the real renderers, with
  no obliging window required. **But it is a debug-build activity, and the debug build
  can't be installed beside a release-signed one** (same `applicationId`, different
  signature), so on the phone that actually has the app it stays out of reach. Uninstall,
  a second device, or an emulator is the price today; an `applicationIdSuffix` on the
  debug build type would remove it, and was declined on 2026-08-13 in favour of waiting
  for a natural crossing. The *chart* half is still uncovered either way: its warning
  half needs the synthetic series, not a fixture.

### CCRM-13 · Chart Widget — standalone chart widget
- **Status:** Done (2026-08-13) — **delivered as CCRM-41 (Pace Widget)**, which is a
  superset (on-face 5h/7d toggle, pace sentence, state table). The text below stays as
  the historical sketch; the extraction question it raises is answered in CCRM-41's
  entry (semantic layer shared via `SparkGeometry`; pixel-level unification declined).
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
- **Rewritten by CCRM-6 (Multi-Account), 2026-08-21.** "Only Personal can be pinned" is now
  "only one account at a time can be pinned" — the picker lists every registered account.
  The ID namespacing this leaned on is no longer `+100` for Work but `kind + slot * 100` for
  any slot, so a per-account pinned notification has a collision-free ID scheme waiting for
  it. The per-profile toggles become a per-account list rather than a second checkbox, and
  the feature now has to answer a question two accounts hid: **how many pinned
  notifications is too many**. Note also that CCRM-44 (One Surface) already carries every
  account's condition strips inside the single panel, prefixed with their labels, which is
  most of what "both windows live at a glance" was asking for — so the case for this needs
  re-making before it is built.

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

### Addendum 2026-09-06 — the multi-provider re-read

The appendix above was written to explain why *nothing* from OpenQuota's other providers
ported. The multi-provider arc (CCRM-53 (Provider Model) to CCRM-57 (Provider Plumbing))
reverses the *scope* decision, not the analysis: it was re-checked against a fresh crawl of
OpenQuota's source (commit `0b21b35`, MIT-licensed, v0.5.0) and of the official `openai/codex`
and `google-gemini/gemini-cli` repos, by four sub-agents whose reports the roadmap items
summarise. What follows is what still does not port, and the handful of things that do.

**Still structurally unavailable on a phone, for the same reason as before — OpenQuota reads
the machine it runs on:**
- Every **credential** OpenQuota holds is *read from disk or the OS keychain*, never minted:
  `~/.codex/auth.json` (and a macOS Keychain item "Codex Auth") for ChatGPT; the Keychain
  item `service=gemini, account=antigravity` for Google. It never runs an authorization flow
  for either — it only *refreshes* tokens the real CLI/IDE created. We do the opposite (the
  phone mints its own family), which is why our ChatGPT path is the Codex **device-code
  flow** the CLI ships and OpenQuota never needed.
- The **Codex token history** (Today / Yesterday / 30 days / model breakdown / estimated
  cost) walks `~/.codex/sessions/**/*.jsonl` — the CLI's own transcripts — exactly like the
  Claude JSONL case above. No server equivalent exists.
- **Antigravity's richest source** is the IDE's local language server: process discovery
  via `ps` / `lsof` / `/proc`, a CSRF token read out of the process's own argv, TLS
  verification off, Connect-RPC to `127.0.0.1`. Phone-impossible by construction. Only the
  *cloud* fallback (`cloudcode-pa.googleapis.com` `v1internal:*`) is reachable from a phone,
  and it is the degraded one — see CCRM-55 (Antigravity Account).
- **Auto-detecting installed tools**, drag-reorderable provider cards, a fixed 320 px popup,
  the tray composite — desktop shapes.
- The **nine other providers** (Cursor, Copilot, Devin, Grok, OpenCode, OpenRouter, Z.ai,
  Kimi, MiniMax). Out of scope permanently, not deferred: three named services, by decision.
- **`rate-limit-reset-credits/consume`** — OpenQuota can *spend* a ChatGPT reset credit. A
  write, in a read-only app. Never.

**What ports, as pattern rather than code:**
- **Classify windows by duration, not by slot** (`codex/mapper.rs`): 18000 s → session,
  604800 s → weekly, positional only as a last resort. Adopted in CCRM-53 (Provider Model).
- The four Antigravity bucket ids (`gemini-5h`, `gemini-weekly`, `3p-5h`, `3p-weekly`), the
  `remainingFraction` inversion, and **worst-of-pool** when per-model rows have to collapse
  into a pool. Recorded in CCRM-55 (Antigravity Account).
- Per-provider brand colours — Claude `#DE7356`, OpenAI `#10A37F`, Google `#4285F4` — as the
  starting point for CCRM-56 (Provider Identity)'s tokens (ours keep `#D97757` for Claude,
  the theme value that already ships).
- **Hide what is not signed in** on the dashboard (grey it only in the configuration
  screen) — the rule CCRM-6 (Multi-Account) already applies to tabs; CCRM-56 (Provider Identity) applies it to
  the Add-account sheet in the *other* direction (greyed, with the reason) for the one
  provider we cannot sign into yet.
- Their `QuotaWindow` carries `estimated` and `source_note` flags — the idea CCRM-30
  (Estimate Honesty) already implements. Reassuring, not new.

**Explicitly rejected, having looked:** their magic plan renames (`prolite → "Pro 5x"`,
`pro → "Pro 20x"`, no source given) — we show `plan_type` as is until a multiplier is
verified, per CCRM-38 (Plan Tier); a **cross-provider combined percentage** — different
pools, different plans, meaningless as one number; a per-provider *layer* in the UI —
accounts stay the unit.

**Licence note:** OpenQuota is MIT. We read it as a reference and copy *patterns*; if a line
of its code is ever copied, its copyright notice comes with it.
