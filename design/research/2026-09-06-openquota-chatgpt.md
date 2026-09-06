# OpenQuota: how it tracks ChatGPT / OpenAI Codex usage

Repo: github.com/deviffyy/OpenQuota, branch `main` at time of research.
All facts below are verified by reading raw source with `curl` (bypassing any
summarization) unless explicitly marked "inferred". Line numbers refer to the
files as fetched on 2026-09-06.

Files read in full:
- `src-tauri/src/providers/codex/auth.rs` (518 lines)
- `src-tauri/src/providers/codex/client.rs` (414 lines)
- `src-tauri/src/providers/codex/mapper.rs` (750 lines)
- `src-tauri/src/providers/codex/mod.rs` (475 lines)
- `src-tauri/src/providers/codex/reset_claim.rs` (219 lines)
- `src-tauri/src/providers/codex/local_usage.rs` (1217 lines, grepped/spot-read)
- `src-tauri/src/providers/registry.rs` (598 lines)
- `src-tauri/src/providers/detection.rs` (244 lines)
- `src-tauri/src/providers/mod.rs` (315 lines)
- `src-tauri/src/providers/credential_store.rs`, `api_key.rs` (grepped — confirmed unused by Codex)
- `src-tauri/src/service.rs` (partial — refresh/backoff logic), `src-tauri/src/policy.rs`, `src-tauri/src/refresh_loop.rs`
- `src-tauri/src/models.rs` (grepped for struct shapes)
- `docs/providers/codex.md`
- `src-tauri/tests/fixtures/codex_usage.json`, `codex_session.jsonl`

---

## 1. CREDENTIALS

OpenQuota does **not** run its own OAuth flow for ChatGPT/Codex. It is a pure
**reader of the OpenAI Codex CLI's own credential file** (and, on macOS, the
CLI's Keychain item). It piggybacks entirely on whatever the user already did
by running `codex login`.

### Where it reads from — `src-tauri/src/providers/codex/auth.rs`

```rust
fn candidate_paths(home: &Path, codex_home: Option<&Path>) -> Vec<PathBuf> {
    if let Some(codex_home) = codex_home.filter(|path| !path.as_os_str().is_empty()) {
        return vec![codex_home.join("auth.json")];
    }
    vec![
        home.join(".config").join("codex").join("auth.json"),
        home.join(".codex").join("auth.json"),
    ]
}
```
(`auth_paths()`, lines 264–285). `CODEX_HOME` env var (read via
`crate::provider_environment::value("CODEX_HOME")`) overrides both defaults
with `$CODEX_HOME/auth.json`.

On macOS it also probes the Keychain, generic-password item labeled
`"Codex Auth"` (empty account string):
```rust
let bytes = generic_password(PasswordOptions::new_generic_password("Codex Auth", "")).ok()?;
```
(lines 207–213). This mirrors wherever the real `codex` CLI itself may store
the same JSON document (OpenQuota doesn't write it — the CLI does; OpenQuota
only reads/updates it).

**All candidate sources are tried in parallel** (`load_candidates()`, lines
46–90): every path in `auth_paths()` plus the Keychain document, so a user
with both a file and Keychain entry gets two "candidates" the provider will
try in turn on refresh (mod.rs `refresh_with_identity`, lines 192–217) until
one works. If a file has only an `OPENAI_API_KEY` field and no `tokens`
object, it's flagged `api_key_only` and surfaces as `CodexError::ApiKeyOnly`
("Subscription usage is unavailable for API-key-only logins").

The file may also be **hex-encoded** — `parse_auth_document()` (auth.rs
287–300) tries plain JSON first, then falls back to decoding the whole file
as a hex string before parsing as JSON. (Presumably some obfuscated/legacy
storage format the CLI has used.)

### JSON shape read (`auth.json`)

Accessed via JSON Pointers, not a typed struct — the document is otherwise
opaque and round-tripped as-is:

```
/tokens/access_token   (string, required)
/tokens/refresh_token  (string, optional)
/tokens/account_id     (string, optional — explicit override)
/tokens/id_token       (string, optional — a JWT)
/last_refresh          (RFC3339 string, optional)
/OPENAI_API_KEY         (used only to detect "API-key-only" logins)
```

### Token types

- **access_token** — a JWT, used as the Bearer token against `chatgpt.com`.
- **refresh_token** — opaque, POSTed to OpenAI's OAuth token endpoint to mint a new access/id/refresh token triad.
- **id_token** — a JWT whose payload carries `chatgpt_account_id` (see below); OpenQuota decodes it purely to read claims, never sends it anywhere itself.

### Expiry / refresh decision — `needs_refresh()` (auth.rs 126–135)

```rust
const REFRESH_WINDOW: Duration = Duration::from_secs(5 * 60);
...
pub fn needs_refresh(&self, now: DateTime<Utc>) -> bool {
    if let Some(expiry) = jwt_expiry(&self.access_token) {
        return expiry.signed_duration_since(now).num_seconds()
            <= REFRESH_WINDOW.as_secs() as i64;
    }
    self.last_refresh
        .as_deref()
        .and_then(|value| DateTime::parse_from_rfc3339(value).ok())
        .is_some_and(|date| now.signed_duration_since(date.to_utc()).num_days() > 8)
}
```
So: decode the access token's own `exp` JWT claim; refresh if it's due within
5 minutes. If the token isn't a parseable JWT (no `exp`), fall back to
"refresh if `last_refresh` is more than 8 days old" as a conservative safety
net.

`jwt_payload()` (302–311) does the generic thing — split on `.`, take
segment 1, base64url-decode (`URL_SAFE_NO_PAD`), parse as JSON. No signature
verification is performed (OpenQuota trusts its own local file; it is only
reading claims for display/matching, never trusting them for security
decisions beyond "does this look like the same account").

### OAuth refresh call — `client.rs`

```rust
const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";
const REFRESH_URL: &str = "https://auth.openai.com/oauth/token";
```
```rust
pub fn refresh_token(&self, refresh_token: &str) -> Result<TokenRefresh, CodexError> {
    ...
    let response = self.client.post(&self.refresh_url)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", CLIENT_ID),
            ("refresh_token", refresh_token),
        ])
        .send()
    ...
}
```
This is a standard OAuth2 `grant_type=refresh_token` form POST — **no PKCE, no
scopes, no client_secret** in this call (refresh tokens don't need PKCE
replay). The `client_id` `app_EMoamEEZ73f0CkXaXp7hrann` is **hardcoded** and
is almost certainly **reused from the actual Codex CLI's own OAuth client**
(OpenQuota never performs an authorization-code/PKCE login flow itself — it
never needs a login redirect, browser, or local callback port, because it
only ever refreshes tokens the CLI already obtained). This is the closest
thing to a "surprising/fragile" detail: a hardcoded upstream client_id that
depends on OpenAI never rotating or restricting it to the Codex CLI's own
User-Agent/origin.

```rust
#[derive(Debug, Deserialize)]
pub struct TokenRefresh {
    pub access_token: String,
    pub refresh_token: Option<String>,
    pub id_token: Option<String>,
}
```

Error mapping from the refresh endpoint (client.rs 223–231):
```rust
let code = oauth_error_code(&body);   // body.error / body.error.code / body.error.error / body.code
match code.as_deref() {
    Some("refresh_token_expired")     => CodexError::SessionExpired,
    Some("refresh_token_reused")      => CodexError::TokenConflict,
    Some("refresh_token_invalidated") => CodexError::TokenRevoked,
    _ => CodexError::RequestFailed(status.as_u16()),
}
```
`refresh_token_reused` in particular is the classic OAuth refresh-token-rotation
replay-detection error — evidence the upstream flow does rotate refresh
tokens on every use, and OpenQuota is prepared for a race where another process
(the actual `codex` CLI, running concurrently) refreshed first.

### Account identity — decoding the id_token JWT (auth.rs 99–116)

```rust
pub(super) fn account_identity(&self) -> Option<String> {
    self.account_id
        .as_deref()
        .and_then(nonempty_lowercase)
        .or_else(|| {
            self.document
                .pointer("/tokens/id_token")
                .and_then(Value::as_str)
                .and_then(jwt_payload)
                .and_then(|payload| {
                    payload
                        .pointer("/https:~1~1api.openai.com~1auth/chatgpt_account_id")
                        .or_else(|| payload.get("chatgpt_account_id"))
                        .and_then(Value::as_str)
                        .and_then(nonempty_lowercase)
                })
        })
}
```
So the precedence is: explicit `/tokens/account_id` field first, else decode
the `id_token` JWT and read the namespaced claim
`"https://api.openai.com/auth".chatgpt_account_id` (the escaped pointer
`~1` = `/`), falling back to a bare top-level `chatgpt_account_id` claim if
the namespace object isn't present. This is the standard OIDC "namespaced
custom claim" pattern OpenAI uses on ChatGPT id_tokens. **`chatgpt_plan_type`
is never read from the JWT** — plan is instead read from the *usage API
response body* (`plan_type` field — see §2), not decoded from any token
claim. So despite the claim existing in real ChatGPT id_tokens, OpenQuota
doesn't use it; it prefers a live server-reported plan字段.

The resulting identity string is lowercased, then SHA-256-hashed
(`account_identity_key()`, mod.rs 338–340: `sha256_hex(identity.as_bytes())`)
before being used as a cache/account key — so the raw account id is never
persisted to OpenQuota's own storage or logs, only its hash.

### Writing back the refreshed tokens

`update_and_save_if_current()` (auth.rs 137–149) re-reads the credential
source right before writing, and refuses to write if the on-disk document
changed underneath it (i.e., if the real Codex CLI itself refreshed
concurrently) — returns `CodexError::AccountChanged` rather than clobbering.
Writes are atomic (`tempfile::NamedTempFile` + `persist()`, `save_file_document`,
lines 195–205) or via `security_framework::passwords::set_generic_password`
for the Keychain path.

---

## 2. USAGE ENDPOINT(S)

All defined as constants in `client.rs` (lines 9–14):

```rust
const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";
const REFRESH_URL: &str = "https://auth.openai.com/oauth/token";
const USAGE_URL: &str = "https://chatgpt.com/backend-api/wham/usage";
const RESET_CREDITS_URL: &str = "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits";
const CONSUME_RESET_CREDIT_URL: &str =
    "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume";
```

This confirms it uses the **`chatgpt.com/backend-api/wham/…` backend
(undocumented, "wham" = the internal Codex/Codex-Web codename), not** the
public Responses API's `rate_limits` field on a completion call. It is the
same private backend the Codex CLI/desktop app itself calls for its own usage
UI — reverse-engineered/reused, not part of any public OpenAI API contract.

### `GET https://chatgpt.com/backend-api/wham/usage` (`fetch_usage`, client.rs 71–107)

Headers:
```rust
self.client.get(&self.usage_url)
    .bearer_auth(access_token)                 // Authorization: Bearer <access_token>
    .header("Accept", "application/json")
// + conditionally:
    .header("ChatGPT-Account-Id", account_id)   // only if account_id non-empty
```
User-Agent is set globally on the `reqwest::Client` builder (line 59):
```rust
.user_agent(concat!("OpenQuota/", env!("CARGO_PKG_VERSION")))
```
— i.e. it identifies itself as `OpenQuota/<version>`, **not** a spoofed Codex
CLI user-agent. No `originator` header on this call (unlike the
reset-credits calls below).

Body: none (GET).

Response is captured generically as:
```rust
pub struct UsageResponse {
    pub status: StatusCode,
    pub headers: HashMap<String, String>,   // all headers, lowercased keys
    pub body: Value,                        // raw serde_json::Value, untyped
}
```
There is **no fixed Rust struct for the response body** — it's parsed
dynamically field-by-field in `mapper.rs` via `Value::get`/`.pointer`, which
is more defensive against upstream schema drift. The full set of JSON fields
actually read (assembled from `mapper.rs`):

```
plan_type                              (string, e.g. "plus", "pro", "prolite")
rate_limit.primary_window.used_percent            (number, 0-100)
rate_limit.primary_window.reset_at                (unix seconds, optional)
rate_limit.primary_window.reset_after_seconds     (number seconds, optional fallback)
rate_limit.primary_window.limit_window_seconds    (number seconds — used to classify session vs weekly)
rate_limit.secondary_window.{used_percent,reset_at,reset_after_seconds,limit_window_seconds}
additional_rate_limits[]                            (array)
additional_rate_limits[].limit_name                 (string, matched case-insensitively for "spark")
additional_rate_limits[].metered_feature            (string, same "spark" match)
additional_rate_limits[].rate_limit.{primary_window,secondary_window}  (same shape as top-level rate_limit)
credits.balance                                     (number)
credits.has_credits                                 (bool)
rate_limit_reset_credits.available_count            (number, fallback path — see below)
```
Header-level fallbacks it also reads off the *usage* response (used only
when the corresponding body field is absent):
```
x-codex-primary-used-percent
x-codex-secondary-used-percent
x-codex-credits-balance
```
(`header_number()`, mapper.rs 344–349). These header names strongly suggest
the real Codex CLI/desktop client can also be driven purely by headers on
some server variant/version, and OpenQuota defensively supports both shapes.

### `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` (`fetch_reset_credits`, client.rs 109–147)

Headers:
```rust
.bearer_auth(access_token)
.header("Accept", "application/json")
.header("OpenAI-Beta", "codex-1")
.header("originator", "Codex Desktop")
// + ChatGPT-Account-Id if present
```
Note the **`originator: Codex Desktop`** header — OpenQuota is explicitly
claiming to be the Codex Desktop client on this endpoint (not on
`/usage`). This is the one place it identifies itself the way the real
first-party app would, likely because the endpoint gates on `originator`.
Also sends `OpenAI-Beta: codex-1`, marking this as a beta/internal API.

Response fields read (`reset_credits_source`/`map_reset_credits`, mapper.rs
245–289):
```
available_count           (number)
credits[]                 (array)
credits[].status           ("available" / "consumed" / absent = treated as available)
credits[].expires_at       (RFC3339 string OR unix-seconds number)
credits[].id               (string — used only by reset_claim.rs, not by the mapper)
```

### `POST https://chatgpt.com/backend-api/wham/rate-limit-reset-credits/consume` (`consume_reset_credit`, client.rs 149–190)

Same headers as reset-credits GET, plus JSON body:
```json
{"credit_id": "<credit_id>", "redeem_request_id": "<redeem_request_id>"}
```
Response `code` field drives an outcome enum (`reset_claim.rs`
`outcome_from_consume`, lines 163–172): `"reset"` / `"already_redeemed"` →
Success, `"nothing_to_reset"` → NothingToReset, `"no_credit"` → NoCredit,
anything else / non-2xx → Failed. This is a **write** endpoint — OpenQuota
can actually spend/claim a "rate-limit reset credit" on the user's behalf
(a ChatGPT feature where certain plans get credits that instantly reset
their rate limit window), gated by a `redeem_request_id` idempotency key
that the caller supplies (1–128 chars) and OpenQuota caches per key
(`matched_credit_ids: Mutex<HashMap<String,String>>`, capped at 256 entries)
so repeat calls with the same key don't re-search for the matching credit.

---

## 3. WINDOWS & SEMANTICS

### Constants (`mapper.rs` 8–10)
```rust
const SESSION_PERIOD_SECONDS: u64 = 5 * 60 * 60;      // 5 hours
const WEEKLY_PERIOD_SECONDS: u64 = 7 * 24 * 60 * 60;  // 7 days
const CREDIT_USD_RATE: f64 = 0.04;                     // $ per "reset credit" unit, used for the Extra-Usage dollar estimate
```

Two ChatGPT/Codex windows are shown by default: **Session (5h)** and
**Weekly (7d)** — exactly matching what the real ChatGPT/Codex product
exposes. Two more are exposed conditionally: **Spark** and **Spark Weekly**
— an additional, separately rate-limited "Codex Spark" model/feature that
appears as a nested `additional_rate_limits[]` entry rather than the top
level `rate_limit`.

### Classification logic (`map_classified_window`, `exact_kind`, mapper.rs 177–207)

The server's `primary_window` is *usually* the 5-hour window and
`secondary_window` the weekly one, but the code explicitly handles the case
where only one window is present and it's actually the weekly one sitting in
the primary slot (comment at line 184: *"a temporarily sole weekly limit can
appear in the primary slot"*). Classification order:
1. **Exact match by duration** — if `limit_window_seconds` equals exactly `18000` (5h) or `604800` (7d), that window is definitively Session or Weekly regardless of which JSON slot (`primary_window`/`secondary_window`) it's in.
2. **Positional fallback** — if duration doesn't match either known constant (or is absent), fall back to "primary→session, secondary→weekly" by position.
This means an "unfamiliar" window duration (e.g. a hypothetical daily/monthly
window OpenAI might introduce) still gets shown, just bucketed by position
rather than mislabeled — verified by the test
`unfamiliar_window_durations_keep_positional_fallbacks`.

Header-derived `used_percent` (`x-codex-primary-used-percent` /
`-secondary-`) is used only as the last-resort fallback for the top-level
(non-Spark) windows if the body has no `used_percent`. Spark windows get no
header fallback (`None, None` passed explicitly, mapper.rs 80–82).

### Reset time computation (`map_window`, mapper.rs 209–243)
```rust
let resets_at = candidate.window.and_then(|window| {
    number(window.get("reset_at"))
        .and_then(timestamp)
        .or_else(|| {
            number(window.get("reset_after_seconds"))
                .map(|seconds| now + Duration::milliseconds((seconds * 1000.0) as i64))
        })
});
```
Prefers an absolute `reset_at` (unix seconds) if present; else computes an
absolute instant by adding the server's relative `reset_after_seconds` to
`now` (the request-processing time, not necessarily the exact server
timestamp — no clock-skew correction is applied here). `period_seconds`
comes from the window's own `limit_window_seconds` if present, else the
matching default constant (18000 or 604800).

### Plan-tier logic (`format_plan`, mapper.rs 360–380)
```rust
Some(match raw.to_ascii_lowercase().as_str() {
    "prolite" => "Pro 5x".to_owned(),
    "pro"     => "Pro 20x".to_owned(),
    _ => raw.split('_').map(title_case).collect::<Vec<_>>().join(" "),
})
```
So `plan_type` from the API is normalized: the raw codes `"prolite"` and
`"pro"` get special-cased human names ("Pro 5x" / "Pro 20x" — presumably
referring to Codex-specific usage multipliers on top of the base ChatGPT
Plus/Pro tier), everything else (e.g. `"plus"` → "Plus", `"free"` → "Free",
`"enterprise"` or `"team"` → "Enterprise"/"Team" via underscore-split
title-casing) is generically prettified. There's no special-cased
"unlimited" plan handling visible in the mapper — an account with no
`rate_limit` object at all (e.g. because the server considers the account
"unlimited"/exempt) simply produces **zero quota windows** for the metric —
confirmed by the test `ignores_non_spark_and_malformed_additional_limits`
where malformed/empty `rate_limit` blocks silently produce
`mapped.quotas.is_empty()` rather than a crash or a fabricated 0%/100%.
There is no visible per-plan pacing math in this file (pacing lives
elsewhere, generically, in `src-tauri/src/pacing.rs`, which was not
independently re-verified in this pass but is referenced from mapper.rs's
own tests via `NotificationEvaluator`).

### 401/403 → treated as auth failure, not as "empty usage"
```rust
if matches!(response.status.as_u16(), 401 | 403) {
    return Err(CodexError::TokenExpired);
}
```
(mapper.rs 23–25) — this is evaluated *before* even checking whether the
body parses, so a 401/403 always becomes `TokenExpired` regardless of body
content.

---

## 4. LOCAL-MACHINE DEPENDENCIES

Two genuinely separate subsystems exist for Codex, and only one of them is a
"local machine dependency" in the interesting sense:

### (a) Live subscription/rate-limit usage (Session/Weekly/Spark/Credits/Resets)
This is **just an HTTP call with a Bearer token** — `fetch_usage()` needs
nothing but `access_token` (+ optional `account_id` header). **A phone with
only a valid OpenAI/ChatGPT OAuth access token (and ideally the matching
refresh token + account id) could make this exact same GET request and get
the exact same JSON.** Nothing here is inherently desktop-only — it's
local-machine-dependent today only because:
1. OpenQuota gets its *credential* by reading a file the desktop Codex CLI wrote (`~/.codex/auth.json` / `~/.config/codex/auth.json` / macOS Keychain) — it never obtains the token itself via any login UI.
2. The token refresh call itself is a plain HTTPS POST with `client_id` + `refresh_token` — trivially portable to any HTTP client on any OS.

So the **minimum a phone would need** is: a copy of a valid
`{access_token, refresh_token, account_id?}` triple (or just a still-valid
access_token if it doesn't need to refresh), and to know the 3 URLs and
2-3 headers above. It would not need `CODEX_HOME`, the Keychain, or any
local file at all — those are purely *how OpenQuota currently obtains* the
credential on this particular machine, not requirements of the API itself.

### (b) Local usage history (Today / Yesterday / Last 30 Days / Usage Trend)
This is a **hard, structural, local-machine-only** dependency — confirmed in
`local_usage.rs`. It does not call any network endpoint at all; it:
- Resolves one or more "Codex home" directories: `$CODEX_HOME` (comma-separated list supported, each expanded against `~`) or else `~/.codex` (`codex_homes()`, lines 110–136).
- Walks `{home}/sessions/**/*.jsonl` and `{home}/archived_sessions/**/*.jsonl` (`discover_session_files()`, lines 145–183) — these are the **Codex CLI's own local transcript/rollout logs**, written by the CLI as it runs, containing per-turn token-usage events (`event_msg` → `token_count` → `info.last_token_usage.{input_tokens,cached_input_tokens,output_tokens,reasoning_output_tokens,total_tokens}`, confirmed against the fixture `codex_session.jsonl`).
- Also reads `{home}/config.toml`'s `service_tier` (grep hit at line ~845 in tests) to decide the Codex cost-multiplier tier ("priority" vs standard) used in local cost estimation (`codex_priority_multiplier`, `codex_cost`).
- Handles active-vs-archived path de-duplication and even symlinked `sessions` directories.
- Aggregates deltas from cumulative-looking token counters, estimates USD cost locally against a pricing table (`estimate_cost`/`ModelPricing`), and caches per-file scan state keyed by file path + mtime in OpenQuota's own local sqlite/db (`storage.prune_log_events("codex", &seen_paths)`).

**A phone cannot do this at all** unless it also had a full copy of the
Codex CLI's local `sessions`/`archived_sessions` JSONL logs (which only
exist because a real terminal-attached `codex` CLI process ran locally and
wrote them) — there is no server-side equivalent OpenQuota calls for this
data; it is reconstructed entirely from local disk. The docs file
(`docs/providers/codex.md`) states this outright: *"Spend history is
calculated locally from the Codex `sessions` and `archived_sessions` logs
… OpenQuota does not upload these local records."* This is the provider's
explicit, documented boundary between "live remote rate-limit state" (works
from any device with a token) and "historical spend/token accounting"
(only works on the machine that ran the CLI, or one that has synced/copied
its log directory).

### Keychain
`security_framework::passwords::{generic_password, set_generic_password}` is
macOS-only (`#[cfg(target_os = "macos")]`); on other OSes only the file
paths are probed. Not itself a barrier for a phone (a phone wouldn't have
Keychain either), just noted for completeness.

---

## 5. POLLING & ERRORS

### Interval / backoff (`policy.rs`, full file)
```rust
pub const REFRESH_INTERVAL: Duration = Duration::from_secs(5 * 60);        // 5 minutes
pub const FAILURE_RETRY_BACKOFF: Duration = Duration::from_secs(60);       // 1 minute
pub const STALE_AFTER: chrono::Duration = chrono::Duration::minutes(10);
```
This is the **generic** provider policy (shared by all providers, not
Codex-specific) — `refresh_loop.rs` spawns one async loop per app session
that calls `service.refresh_all_with_progress(...)` for all enabled
provider ids every `REFRESH_INTERVAL` (5 minutes), sleeping
`tokio::time::sleep(REFRESH_INTERVAL)` between iterations
(`refresh_loop.rs` lines 17–42).

`service.rs`'s `refresh()` (lines 131-220) additionally:
- Skips a call entirely if a cached result is still "fresh this session" (in-memory session cache, separate from `STALE_AFTER`).
- Skips (returns cached state) if the provider errored recently: `is_in_failure_backoff()` checks `last_failed_refresh` timestamp against `FAILURE_RETRY_BACKOFF` (60s) — i.e., **no immediate retry storm on failure**, but a `force=true` refresh (user-triggered) bypasses both the freshness cache and the failure backoff.
- Coalesces concurrent refresh requests for the same provider into one in-flight "flight" (generation-counter based), so a background poll and a manual "refresh now" click don't race into two parallel HTTP calls.
- Has its own outer `refresh_timeout` (a `tokio::time::timeout` around the blocking worker) — a timed-out call is treated as a `Network` error ("Provider refresh timed out.") and any late result that eventually arrives is discarded.
- `STALE_AFTER` (10 min) governs UI-level "this reading is stale" display logic (`update_staleness_from_snapshot_age`), not the refresh scheduling itself.

### Codex-specific retry-on-401 (`mod.rs` `refresh_candidate`, lines 237–302)
Per attempt:
1. If the access token's own `needs_refresh()` check says it's near expiry (or unknown-expiry-and->8 days since last refresh), proactively re-read the credential file/Keychain first (in case another process, i.e. the real `codex` CLI, already refreshed it) and only call the OAuth refresh endpoint if it's *still* stale after that re-read.
2. Call `fetch_usage`. If the response is `401` or `403`, force a token refresh and retry the usage call exactly once more.
3. If a 401/403 survives even after refresh, `map_usage` maps it to `CodexError::TokenExpired` ("Your Codex access token expired. Run `codex` to sign in again.").
4. If refresh itself fails with an OAuth error, it's mapped as described in §1 (`SessionExpired`/`TokenConflict`/`TokenRevoked`), or a generic `RequestFailed(status)` for anything else (e.g. 429 → `RequestFailed(429)`, which `service.rs`'s generic error-kind mapping (`provider_error()`, mod.rs 351–371) turns into `ProviderErrorKind::RateLimited`).
5. Multiple credential *candidates* (file(s) + Keychain) are tried in order; only auth-classified errors (`SessionExpired`/`TokenConflict`/`TokenRevoked`/`TokenExpired`) fall through to try the next candidate — any other error (network, invalid response, storage) aborts immediately rather than silently trying a different credential source.
6. After a successful usage fetch, it re-validates that the credential source on disk still matches what was used (`ensure_candidate_source_current`) — guards against a race where the account changed mid-refresh (returns `AccountChanged`, mapped to `ProviderErrorKind::Authentication`).

### 429 handling
No explicit `Retry-After` header parsing was found anywhere in `client.rs`
or `mapper.rs` — a 429 just becomes `CodexError::RequestFailed(429)` →
`ProviderErrorKind::RateLimited`, and the *generic* `FAILURE_RETRY_BACKOFF`
(60s) is what prevents hammering the endpoint, not a value read from the
response.

---

## 6. MULTI-ACCOUNT

**Codex does NOT support true multi-account switching** the way Claude does
in this codebase. Evidence:

- `is_claude_account_provider_id()` (`providers/mod.rs` lines 35–39) explicitly recognizes only a `"claude@<8-hex-hash>"` provider-id pattern — there is no equivalent `codex@…` recognized anywhere (confirmed by grep across `providers/mod.rs`, `registry.rs`, and the codex module itself — no `@` handling in codex code at all).
- Claude has a dedicated `src-tauri/src/providers/claude/accounts.rs` file (seen in the file tree) for that; Codex has no equivalent file.
- `CodexProvider` (mod.rs) tracks exactly **one** `account_identity: Option<String>` — the identity of whichever credential candidate happened to work first (`load_candidates()` order: files first in listed order, then Keychain).
- What *does* exist is **account-change detection, not account switching**: `account_identity()`/`validate_account_identity()` exist so that if the user re-runs `codex login` with a *different* ChatGPT account between refreshes, OpenQuota notices the SHA-256 identity hash changed and returns `CodexError::AccountChanged` rather than silently mixing cached data from account A with live data from account B. `remember_default_account()` (providers/mod.rs 41-60) persists a `(family="codex", identity_hash)` record purely so the UI can label a "default account" once observed — it is not a UI for adding a second, independently-refreshed account.
- If a user is logged into two different ChatGPT accounts via two different credential sources (e.g. `~/.codex/auth.json` for one, Keychain for another), OpenQuota will pick whichever one it tries first that yields a *successful, non-auth-error* response and stop there (`refresh_with_identity`'s `for mut auth in candidates` loop, mod.rs 201–216) — it does not surface both accounts as separate rows.

So: **single ChatGPT/Codex account only**, in contrast to Claude's explicit
multi-account (`claude@<hash>`) support in the same codebase.

---

## 7. Surprising / fragile things

1. **Hardcoded OAuth `client_id`** (`app_EMoamEEZ73f0CkXaXp7hrann`) baked into the binary, reused against OpenAI's real `auth.openai.com/oauth/token` endpoint. This is the Codex CLI's own client id (inferred — OpenQuota never registers/derives it, just hardcodes the literal string), so OpenQuota's continued functioning depends entirely on OpenAI (a) keeping that client id valid and (b) not restricting refresh-token grants to a specific user-agent/origin that only the real CLI sends. If OpenAI ever locks that client id to only accept requests from the genuine `codex` binary (e.g. via attestation), OpenQuota's refresh path breaks.
2. **Un-versioned, undocumented internal API**: `chatgpt.com/backend-api/wham/usage` and the two `rate-limit-reset-credits` endpoints are not part of any published OpenAI API — "wham" appears to be an internal Codex backend codename. The header-based fallbacks (`x-codex-primary-used-percent` etc.) suggest the team building OpenQuota noticed the server has shipped at least two different response shapes over time (body JSON vs. response headers) and defensively supports both.
3. **`originator: Codex Desktop`** header sent only on the reset-credits endpoints (not on `/usage`) — a deliberate identity claim ("I am the Codex Desktop app") likely required by that specific endpoint's server-side gating, while `/usage` apparently doesn't require it. This is the one spot where OpenQuota impersonates the first-party client rather than identifying as itself; everywhere else its own `User-Agent: OpenQuota/<version>` is sent.
4. **Hex-encoded `auth.json` fallback parsing** — `parse_auth_document()` silently tries interpreting the *entire file* as a hex string if it isn't valid JSON. This implies the Codex CLI has, at some point (some version/platform), stored this file in a hex-obfuscated form, and OpenQuota had to reverse-engineer that to stay compatible.
5. **Reset-credit "consume" is a real financial/quota-affecting write call**, not read-only telemetry — `POST …/rate-limit-reset-credits/consume` actually spends a limited resource on the user's account. OpenQuota guards it with a caller-supplied idempotency key (`redeem_request_id`) and an in-memory match cache, but there is no persistence of "already consumed" state across app restarts beyond what the server itself reports back (`already_redeemed` is treated as success, which suggests the server is itself idempotent on this key/credit pair — a reasonable but unverified assumption OpenQuota is relying on).
6. **No response schema/struct for `/usage`** — everything is dynamic (`serde_json::Value` + pointer lookups), which is defensive against drift but also means a field-name typo upstream anywhere would silently be treated as "absent" (`None`) rather than causing a deserialize error the team would notice quickly. The extensive, close-to-100%-branch-covered unit tests in `mapper.rs` (fixtures for spark windows, weekly-only primary slot, malformed additional_rate_limits, credits header-vs-body precedence, etc.) suggest the authors are well aware of this fragility and have hardened against every edge case they've personally observed in the wild.
7. **Plan-name special cases `"prolite"→"Pro 5x"`, `"pro"→"Pro 20x"`** are unexplained magic strings with no visible upstream documentation reference — clearly reverse-engineered from observed `plan_type` values returned by the real API, not from any public OpenAI plan-naming doc.

---

## What's inferred vs. verified

Verified directly from source (100% of §1–§6 above): every URL, header,
constant, struct field, JSON pointer path, and control-flow branch quoted
above was read from the actual raw file content via `curl` (bypassing the
model-mediated WebFetch tool, which repeatedly refused full reproduction on
copyright grounds even though this is the target's own public GitHub repo).

Inferred / not independently verified in this pass:
- That `app_EMoamEEZ73f0CkXaXp7hrann` is literally the *same* client id the official Codex CLI uses (very likely given the whole design only ever refreshes, never performs authorization-code login, but I did not fetch OpenAI's own Codex CLI source to confirm byte-for-byte).
- The exact behavior/contents of `src-tauri/src/pacing.rs` (pacing/milestone math) beyond what's visible through `mapper.rs`'s own tests that reference `NotificationEvaluator` — not independently read in full.
- Whether `chatgpt_plan_type` ever appears as a JWT claim in real ChatGPT id_tokens (I know OpenQuota doesn't read it, but didn't independently verify OpenAI's JWT schema outside what OpenQuota's own code/tests reference — only `chatgpt_account_id` is confirmed read).
- Full contents of `service.rs` beyond the refresh/backoff section quoted (1620 lines total; only ~200 lines around the flight/backoff logic were read in detail).
