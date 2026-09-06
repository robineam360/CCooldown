# How OpenQuota (deviffyy/OpenQuota) tracks Google Antigravity / Gemini usage

Repo crawled at commit `0b21b354e1a0a3f65d78900ec244c43f581542e2` (HEAD of `main`, fetched via
`https://api.github.com/repos/deviffyy/OpenQuota/git/trees/main?recursive=1` and
`https://raw.githubusercontent.com/deviffyy/OpenQuota/main/<path>`). All claims below are from
reading the actual source unless explicitly marked "inferred."

## 0. Where the code lives (no separate "Gemini CLI" provider)

There is **one** provider for all things Google/Gemini: `antigravity`. There is no
`gemini`, `gemini-cli`, or `gemini-code-assist` provider anywhere in the repo — I grepped
`files.txt` (full recursive tree) for `antigrav|gemini|google` and it returned only:

```
docs/providers/antigravity.md
src-tauri/src/providers/antigravity/auth.rs
src-tauri/src/providers/antigravity/client.rs
src-tauri/src/providers/antigravity/discovery.rs
src-tauri/src/providers/antigravity/fixtures/quota_summary.json
src-tauri/src/providers/antigravity/mapper.rs
src-tauri/src/providers/antigravity/mod.rs
src/assets/provider-icons/antigravity.svg
```

README.md's provider table lists Antigravity as **"shared Gemini and Claude quota pools"** — i.e.
this single provider tracks Gemini usage *through* the Antigravity IDE/CLI product, not a
standalone Gemini CLI / Gemini Code Assist integration. So there's nothing to keep "clearly
separated" — it's already one thing.

The provider is registered once, as a singleton, in `src-tauri/src/lib.rs:374-376`:

```rust
Arc::new(AntigravityProvider::new(
    app_data_dir.join("antigravity").join("auth.json"),
)?) as Arc<dyn UsageProvider>,
```

(Compare to Claude, which is registered via `claude::runtimes(...)` — plural, one runtime per
detected account. Antigravity gets no such treatment. See §6.)

---

## 1. CREDENTIALS / ACCESS — all three of your hypothesized paths are implemented, tried in a fixed priority order

File: `src-tauri/src/providers/antigravity/mod.rs`, function `refresh_inner()` (lines 111-212).
The order, exactly as coded:

1. **(a) Local Antigravity language-server process, if discovered** — tried first, and if it
   answers usefully, remote/OAuth is never touched.
2. **(b) Local OS-keychain-stored Google OAuth credentials owned by Antigravity itself** — used to
   get an access token, then call Google's cloud endpoint directly.
3. **(c) Google Cloud Code Assist HTTPS endpoints** (`cloudcode-pa.googleapis.com`) — this is
   *always* the actual data source in the end; (a) is just a local shortcut to the same data, and
   (b) supplies the bearer token used to call (c) when (a) isn't available.

### 1a. Local language-server discovery

File: `src-tauri/src/providers/antigravity/discovery.rs`.

- Public entry point `discover() -> Option<LanguageServer>`, platform-dispatches to
  `discover_windows()` or `discover_unix()`.
- **Process enumeration:**
  - macOS/Linux: runs `ps -ax -o pid=,command=` (macOS uses `/bin/ps` explicitly) via
    `background_command`/`output_with_timeout` (5s timeout, `child_process.rs`).
  - Windows: runs a PowerShell one-liner that uses `Get-CimInstance Win32_Process` filtered on
    `Name -match 'language_server|^agy(\.exe)?$'`, cross-referenced with
    `Get-NetTCPConnection -OwningProcess ... -State Listen`, falling back to parsing
    `netstat -ano -p TCP` output if `Get-NetTCPConnection` yields nothing.
- **Two process "shapes" are searched, in this order (`DISCOVERY_OPTIONS`):**
  1. `process_name: "language_server"`, `markers: ["antigravity", "antigravity-ide"]`,
     `csrf_flag: "--csrf_token"`, `port_flag: "--extension_server_port"` — this is the IDE's
     embedded language-server binary (same architecture family as Codeium/Windsurf's
     `language_server` — see §7).
  2. `process_name: "agy"`, no markers, no csrf/port flags — this is the standalone `agy` CLI
     mentioned throughout the docs/errors ("open Antigravity or run `agy`").
- **Matching logic (`marker_rank`)**: a candidate process must have its executable basename equal
  (or `_`-suffixed / substring-contain, depending on process-name length) to the target process
  name. For the `language_server` shape, it further requires one of `--ide_name`,
  `--override_ide_name`, or `--app_data_dir` command-line flags to case-insensitively equal
  `"antigravity"` or `"antigravity-ide"` (rank 0, exact marker match) — or, if none of those flags
  are present at all, falls back to checking whether the command path contains `/antigravity/` as
  a path segment (rank 1, weaker match). Candidates are ranked so exact-marker matches are tried
  before path-based guesses.
- **CSRF token**: extracted straight from the process's own command-line argument
  `--csrf_token <value>` (or `--csrf_token=value`) via `extract_flag()`. This is literally reading
  a secret out of `ps`/CIM process-listing output — anyone with local process-list visibility (not
  even root) can read it the same way.
- **Port discovery**:
  - macOS/other-unix: `lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>` (tries `/usr/sbin/lsof`,
    `/usr/bin/lsof`, then bare `lsof` on PATH).
  - Linux: reads `/proc/<pid>/fd/*` symlinks to find `socket:[inode]` fds, cross-references
    against `/proc/<pid>/net/tcp` and `/proc/<pid>/net/tcp6` (state `0A` = LISTEN) to map inode →
    port; falls back to `lsof` if `/proc` yields nothing.
  - Windows: `Get-NetTCPConnection`/`netstat` as above.
  - Additionally, an `--extension_server_port=<port>` flag value is captured separately as
    `extension_port` and tried as an HTTP (not HTTPS) fallback endpoint.
- Result type: `LanguageServer { csrf: String, ports: Vec<u16>, extension_port: Option<u16> }`.

### 1b. Local credential file / OS keychain (Antigravity's own stored OAuth tokens)

File: `src-tauri/src/providers/antigravity/auth.rs`, function `load_token()`:

```rust
pub fn load_token() -> Result<Option<AntigravityToken>, AntigravityError> {
    let Some(raw) = read_generic_password("gemini", "antigravity")
        .map_err(|_| AntigravityError::CredentialStoreUnreadable)?
    else {
        return Ok(None);
    };
    extract_token(&raw).map(Some).ok_or(AntigravityError::InvalidCredentialData)
}
```

`read_generic_password(service, account)` is from `src-tauri/src/providers/credential_store.rs`
and is a thin per-OS wrapper (**not a flat file** like `~/.gemini/oauth_creds.json` —
this is the actual **OS credential store**, the same store Antigravity/the `agy` CLI itself
writes to):

- **macOS**: `security_framework::passwords::generic_password()` — i.e. the macOS Keychain,
  generic-password item with **service = `"gemini"`, account = `"antigravity"`**.
- **Windows**: raw Win32 `CredReadW` against Windows Credential Manager, target name
  `"gemini:antigravity"`.
- **Linux**: `secret_service` crate (D-Bus Secret Service, e.g. GNOME Keyring/KWallet), searching
  for an item with attributes `service="gemini", username="antigravity"`.

The raw secret bytes are then parsed by `extract_token()`:
- If prefixed `go-keyring-base64:`, base64-decode it first (`decode_go_keyring_value` in
  `credential_store.rs`) — this prefix is the format the **Go `zalando/go-keyring`-style
  library** uses when it stores structured (non-string) secrets, which strongly implies
  Antigravity's own backing implementation (a Go-based CLI/daemon, `agy`) writes its OAuth
  credential blob into the OS keychain in that wrapped form.
- The unwrapped text is then tried as JSON. Recognized shapes (via `token_from_value`):
  - Direct object with any of `access_token`/`accessToken`/`token`/`id_token`/`idToken`/
    `bearerToken`/`auth_token`/`authToken` for the access token, and
    `refresh_token`/`refreshToken` for the refresh token, plus `expiry`/`expires_at`/`expiresAt`
    (RFC3339) for expiry.
  - Or nested one level under a `"token"` object.
  - Or nested inside `tokens`/`oauth`/`oauth2`/`credentials`/`auth` keys (recursively probed).
  - Falls back to: a bare JSON string, or a raw/`Bearer `-prefixed plain-text token (used only as
    an access token with no refresh token/expiry).
- Result: `AntigravityToken { access_token: Option<String>, refresh_token: Option<String>,
  expiry: Option<DateTime<Utc>> }`.

**This is the actual multi-purpose "read Google OAuth tokens from local storage" path you asked
about** — but note it's the OS keychain, not a JSON file on disk the way `~/.gemini/oauth_creds.json`
would be (that path/file is not referenced anywhere in this repo).

OpenQuota additionally keeps its **own** short-lived access-token cache (not the refresh token) on
disk at `<app_data_dir>/antigravity/auth.json`, managed by `AccessTokenCache` (same `auth.rs`
file). It stores `{version, access_token, expires_at_millis,
credential_fingerprint: SHA256(refresh_token)}`, written atomically via a temp file + rename, with
Unix file mode forced to `0o600` and parent dir `0o700`. The fingerprint (not the refresh token
itself) binds the cached access token to "this same underlying refresh credential," so if the
user re-signs-in with a different account, the stale cached access token is silently discarded
(see `AccessTokenCache::load`, `credential_fingerprint`).

### 1c. Direct Google Cloud Code Assist HTTPS endpoints

File: `src-tauri/src/providers/antigravity/client.rs`. Constants:

```rust
const CLOUD_BASES: [&str; 2] = [
    "https://daily-cloudcode-pa.googleapis.com",
    "https://cloudcode-pa.googleapis.com",
];
const GOOGLE_TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const GOOGLE_CLIENT_ID: &str =
    "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
const GOOGLE_CLIENT_SECRET_PARTS: [&str; 2] = ["GOCSPX-", "(remainder: read from OpenQuota's src-tauri/src/providers/antigravity/client.rs, GOOGLE_CLIENT_SECRET_PARTS, or from your own Antigravity keychain entry)"];
```

Comment in the source (verbatim): *"Installed-app OAuth clients cannot keep this value
confidential. Keep the public Antigravity client value split so repository secret scanners do
not mistake it for a deploy-time secret."* — i.e. this is a **hardcoded, public, installed-app
OAuth client id + secret pair lifted from Antigravity itself** (an "installed application" OAuth
client, per Google's own classification, has no real confidentiality requirement on its secret).
This is the fixed client id **all Antigravity installs share**, not something per-user or
per-OpenQuota-install.

`refresh_google_token(refresh_token)` does a plain `POST` to
`https://oauth2.googleapis.com/token` with form body
`client_id=<GOOGLE_CLIENT_ID>&client_secret=<concatenated secret>&refresh_token=<refresh_token>&grant_type=refresh_token`
— textbook OAuth2 refresh-token grant, no PKCE/device-flow specifics visible because it's reusing
an already-issued refresh token, not doing a fresh authorization. Response parsed as
`{access_token, expires_in}`; on success cached via `AccessTokenCache::store`; a client-error
status (other than 408/429) is treated as `AuthExpired` (bad/revoked refresh token), anything else
transient is `Unavailable`.

`cloud_code(path, token, body, user_agent)` does `POST {base}{path}` for each of the two cloud
bases in order, with:
- `Authorization: Bearer <token>` (via `.bearer_auth(token)`)
- `Accept: application/json`
- `User-Agent: antigravity` or `User-Agent: agy` depending on call site (`CloudUserAgent` enum)
- JSON body

401/403 responses short-circuit to `CloudOutcome::AuthFailed` (triggers the refresh-token flow);
any other non-2xx or transport error falls through to try the next base, then finally
`CloudOutcome::Unavailable`.

---

## 2. USAGE ENDPOINTS / RPCs — exact names, methods, bodies, and response shapes parsed

### Local RPC (language-server), Connect-style JSON-over-HTTP

Called via `AntigravityClient::call_language_server(server, method)`
(`client.rs` lines 95-138). URL pattern:

```
{scheme}://127.0.0.1:{port}/exa.language_server_pb.LanguageServerService/{method}
```

tried for every discovered port, `https` first then `http` on the same port, plus the
`extension_server_port` over plain `http` as a last resort. This is a **Connect protocol**
(gRPC-over-HTTP/JSON) call: headers are
`Content-Type: application/json`, `Connect-Protocol-Version: 1`, and
`x-codeium-csrf-token: <csrf>` (the CSRF value pulled from the process argv — see §1a). Request
body is always:

```json
{"metadata": {"ideName": "antigravity", "extensionName": "antigravity", "ideVersion": "unknown", "locale": "en"}}
```

TLS verification is disabled for this local client (`danger_accept_invalid_certs(true)`) because
it's talking to 127.0.0.1 over a self-signed/loopback cert.

**Methods called, in this priority order (`refresh_inner`, `mod.rs`):**

1. **`RetrieveUserQuotaSummary`** — parsed by `parse_quota_summary` (mapper.rs). Expected shape
   (from the fixture `antigravity/fixtures/quota_summary.json`):
   ```json
   {
     "response": {
       "groups": [
         { "buckets": [
             { "bucketId": "gemini-5h", "remainingFraction": 0.8, "resetTime": "2026-07-12T10:00:00Z" },
             { "bucketId": "gemini-weekly", "remainingFraction": ..., "resetTime": "..." },
             { "bucketId": "3p-5h", "remainingFraction": ... },
             { "bucketId": "3p-weekly", "remainingFraction": 0.5 }
         ] }
       ]
     }
   }
   ```
   (`groups` is also accepted top-level, not just under `response`, via `.pointer("/response/groups")
   .or_else(|| value.get("groups"))`.) Only four `bucketId`s are recognized (others ignored):
   `gemini-5h`, `gemini-weekly`, `3p-5h`, `3p-weekly` — see §3 for what these map to. Each bucket
   contributes `remainingFraction` (0.0-1.0 float, also accepted as a numeric string) and an
   optional `resetTime` (RFC3339).

2. If that fails/is absent: **`GetUserStatus`** — parsed by `parse_user_status` +
   `parse_plan`. Response shape parsed:
   ```
   userStatus.userTier.name                                  → plan
   userStatus.planStatus.planInfo.planName                   → plan (fallback)
   userStatus.cascadeModelConfigData.clientModelConfigs[]     → per-model quota configs
     each: { label, modelOrAlias.model, quotaInfo: { remainingFraction, resetTime } }
   ```

3. If that yields no usable model configs: **`GetCommandModelConfigs`** — parsed by
   `parse_command_model_configs`, expects `{ clientModelConfigs: [ {label, quotaInfo:{...}}, ... ] }`
   (same per-model shape as above, one level shallower — no `userStatus` wrapper).

`GetUserStatus` is also called a second time in parallel (line 120) purely to extract the plan
name (`parse_plan`) when `RetrieveUserQuotaSummary` succeeded, since the summary RPC doesn't
carry a plan/tier field itself.

### Remote HTTPS RPCs (Google Cloud Code Assist API), used when no local server is reachable

All via `cloud_code(path, token, body, user_agent)` against
`{daily-cloudcode-pa.googleapis.com, cloudcode-pa.googleapis.com}` — same-shaped JSON bodies as the
local RPCs but as literal path suffixes:

```rust
const QUOTA_SUMMARY_PATH:  &str = "/v1internal:retrieveUserQuotaSummary";
const FETCH_MODELS_PATH:   &str = "/v1internal:fetchAvailableModels";
const LOAD_CODE_ASSIST_PATH: &str = "/v1internal:loadCodeAssist";
const RETRIEVE_QUOTA_PATH: &str = "/v1internal:retrieveUserQuota";
```

Fetch order in `fetch_remote()` (mod.rs lines 214-287), each a `POST` with empty JSON body `{}`
(and `User-Agent: antigravity` for the first two, `User-Agent: agy` for the rest):

1. **`POST /v1internal:retrieveUserQuotaSummary`** — same response shape/parser as the local
   `RetrieveUserQuotaSummary` RPC (`parse_quota_summary`). If it yields quotas, the plan is
   separately fetched via `load_remote_plan()` which calls `loadCodeAssist` (see step 3) just for
   its tier field.
2. **`POST /v1internal:fetchAvailableModels`** — parsed by `parse_cloud_models`. Expected shape:
   ```json
   { "models": {
       "<key>": {
         "model": "MODEL_...",            // optional, falls back to the map key
         "displayName": "Gemini 3 Pro",   // or "label"
         "isInternal": false,             // true → model is dropped entirely
         "quotaInfo": { "remainingFraction": 0.4, "resetTime": "..." }
       }, ...
   } }
   ```
   Models whose resolved `model` id is in a 9-entry blacklist (old/duplicate ids —
   `MODEL_CHAT_20706`, `MODEL_CHAT_23310`, `MODEL_GOOGLE_GEMINI_2_5_FLASH[...]`,
   `MODEL_GOOGLE_GEMINI_2_5_PRO`, `MODEL_PLACEHOLDER_M9/M12/M19`) are excluded from pooling in
   `build_legacy_quotas`.
3. **`POST /v1internal:loadCodeAssist`** — used both (a) to resolve the **plan/tier name** via
   `remote_plan()`, which reads `paidTier.name` (or `currentTier.name` fallback) and canonicalizes
   any string containing "ultra"/"pro"/"free" (case-insensitively) down to `"Ultra"`/`"Pro"`/`"Free"`,
   else passes the raw tier name through; and (b) to resolve
   **`cloudaicompanionProject`** — a project id string used as the request body for the next call.
4. **`POST /v1internal:retrieveUserQuota`** — body is `{"project": "<cloudaicompanionProject>"}` if
   step 3 returned one, else `{}`; if that variant returns "Unavailable" and a project id *was*
   available, it retries once more with an empty body. Parsed by `parse_quota_buckets`:
   ```json
   { "buckets": [ { "modelId": "gemini-3-pro", "remainingFraction": 0.5, "resetTime": "..." }, ... ] }
   ```

### Common per-model struct parsed off any of the above (`ModelConfig`, mapper.rs)

```rust
struct ModelConfig {
    label: String,
    model_id: Option<String>,
    remaining_fraction: f64,       // 0.0..=1.0 (or numeric string), else defaults to 0.0
    resets_at: Option<DateTime<Utc>>,  // parsed as RFC3339
}
```

### Final generic struct OpenQuota actually stores/renders (`QuotaWindow`, `src-tauri/src/models.rs`)

```rust
pub struct QuotaWindow {
    pub id: String,
    pub label: String,
    pub used_percent: f64,               // = round((1 - remainingFraction) * 100)
    pub resets_at: Option<DateTime<Utc>>,
    pub period_seconds: u64,             // fixed constant per bucket, NOT from the API
    pub format: QuotaFormat,             // always QuotaFormat::Percent for Antigravity
    pub used_value: Option<f64>,         // always None for Antigravity
    pub limit_value: Option<f64>,        // always None for Antigravity
    pub unit: Option<String>,            // always None for Antigravity
    pub estimated: bool,                 // always false for Antigravity
    pub source_note: Option<String>,     // always None for Antigravity
}
```

Notably: **there is no absolute "quota"/limit number anywhere in the Google response that
OpenQuota parses** — Google only ever returns a `remainingFraction` (0.0-1.0). OpenQuota converts
that directly to a percent-used display; it never learns (or shows) a "you have used 340 of
1000 requests"-style absolute count/credits for Gemini/Antigravity, only percentages, unlike some
other providers in this same repo (e.g. Cursor/OpenRouter which do carry credit balances).

---

## 3. WINDOWS & SEMANTICS

From `mod.rs::definition()` (the four metrics shown to the user) and `mapper.rs::BUCKETS`:

| Metric id                | Display label   | Section        | Default pinned | Window            | Source bucket / pooling rule |
|---------------------------|-----------------|----------------|-----------------|--------------------|-------------------------------|
| `antigravity.geminiPro`   | "Session"       | AlwaysVisible  | yes (tray "S")   | rolling 5 hours   | `gemini-5h` bucket, or (legacy path) the **worst** (lowest remaining-fraction) among all models whose label contains "gemini" |
| `antigravity.geminiWeekly`| "Weekly"        | AlwaysVisible  | yes (tray "W")   | 7-day / weekly    | `gemini-weekly` bucket (no legacy-path equivalent — the fallback model-config paths only ever produce Session + Claude, never a weekly figure) |
| `antigravity.claude`      | "Claude"        | OnDemand       | no (tray "C")    | rolling 5 hours   | `3p-5h` ("third-party") bucket, or the **worst** among all models NOT labeled "gemini" (i.e. the Claude models Antigravity also offers) |
| `antigravity.claudeWeekly`| "Claude Weekly" | OnDemand       | no (tray "CW")   | 7-day / weekly    | `3p-weekly` bucket |

`period_seconds` is a **hardcoded constant per bucket** (`5*60*60` = 18,000s for the two
"5h" buckets, `7*24*60*60` = 604,800s for the two weekly buckets) — it is not derived from the
Google response at all; only `resets_at` (an absolute RFC3339 timestamp) comes from Google, when
present (`resetTime` field). If Google doesn't supply a `resetTime`, `resets_at` is simply `None`
and the UI presumably just doesn't show a countdown (not something this agent traced further into
the frontend, but the `Option` type and mapper tests confirm the field can be absent).

"Credits" in the sense used by other providers in this repo (a numeric balance, e.g. Cursor/
OpenRouter) **does not exist for Antigravity** — everything is percent-of-window-remaining.

**Pooling/"legacy" fallback semantics** (`build_legacy_quotas`, used only when
`RetrieveUserQuotaSummary`/`retrieveUserQuotaSummary` isn't available and the code falls back to
per-model configs from `GetUserStatus`/`GetCommandModelConfigs`/`fetchAvailableModels`/
`retrieveUserQuota`): every individual model (e.g. "Gemini 3 Pro", "Gemini Flash", "Claude
Sonnet", "Claude Opus") is bucketed into either the Gemini pool or the "Claude"/third-party pool
purely by a **case-insensitive substring match for `"gemini"` in the model's display label**
(anything not matching is treated as "Claude"), and each pool's displayed value is the **minimum
remaining fraction across all its models** (i.e. worst-case model within the pool determines the
shown percentage) — confirmed directly by the unit test `pools_language_server_models_by_the_worst_remaining_fraction`.

**Plan/tier resolution** (`parse_plan` in mapper.rs, used for the local-server path, and
`remote_plan` in mod.rs, used for the cloud path — two separate, slightly different
implementations):
- Local: reads `userStatus.userTier.name` or `userStatus.planStatus.planInfo.planName`; strips a
  literal `"Google AI "` prefix if present and title-cases the remainder; else matches
  case-insensitive substrings `"ultra"/"pro"/"free"` down to `Ultra/Pro/Free`; else title-cases
  the raw string.
- Cloud: reads `paidTier.name` or `currentTier.name` from the `loadCodeAssist` response; same
  `ultra/pro/free` substring canonicalization, else passes the raw string through unchanged (no
  title-casing fallback here).

---

## 4. LOCAL-MACHINE DEPENDENCIES — could a phone with only a Google OAuth token do this?

**Short answer: yes, partially, but only for the "Claude Cooldown"-style question of "call the
same HTTPS endpoint with a valid access token" — a phone could NOT reproduce path (a) (the local
language-server) or path (b) (reading Antigravity's own OS-keychain-stored refresh token) because
both require running on the same machine as a live/previously-installed Antigravity/`agy`
process. But the cloud RPCs in path (c) are plain HTTPS to Google, so a phone that already
possessed a valid OAuth access token (or the refresh token + the same public client id/secret)
for the right Google account/scope could call them directly, with no Antigravity IDE running
anywhere.**

Breaking this down explicitly, everything that is local-machine-only:

- **Process discovery** (`ps`/CIM/`Get-NetTCPConnection`/`/proc`/`lsof`) — inherently requires
  being a process on the same machine as the running Antigravity IDE or `agy` CLI. A phone has no
  access to another machine's process table.
- **The CSRF token** — extracted from that same local process's command-line args. Not
  obtainable remotely.
- **The local port** — a loopback (127.0.0.1) TCP port; not reachable from a phone even on the
  same LAN (it's bound to loopback, and the code always targets `127.0.0.1` explicitly).
- **Reading the OS keychain item `service="gemini", account="antigravity"`** — this requires
  running as the same OS user on the same machine (macOS Keychain / Windows Credential Manager /
  Linux Secret Service are all local, per-machine, per-user stores; none of them are network
  services). A phone cannot read a laptop's Keychain.
- **OpenQuota's own on-disk access-token cache** (`<app_data_dir>/antigravity/auth.json`) — same
  local-file constraint (and it's derived from the keychain token anyway).

Everything that is **not** inherently local-machine — i.e. what a phone with the right credential
could do:

- **The three cloud RPCs** (`retrieveUserQuotaSummary`, `fetchAvailableModels`, `loadCodeAssist`,
  `retrieveUserQuota`) are plain HTTPS POSTs to `cloudcode-pa.googleapis.com` (or the
  `daily-cloudcode-pa.` variant) with an `Authorization: Bearer <access_token>` header and a JSON
  body. Nothing about the HTTP call itself is local-machine-specific — no mTLS, no IP allowlist
  logic visible in this client code, no device attestation. A phone (or any HTTP client anywhere)
  presenting a valid bearer token for the right Google account could call these and get the same
  JSON.
- **The token refresh call** (`oauth2.googleapis.com/token`, standard refresh-token grant) is
  likewise just HTTPS with `client_id`, `client_secret`, `refresh_token`, `grant_type=refresh_token`
  — a phone holding the same refresh token, plus the same hardcoded public client id/secret pair
  (`1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com` /
  `GOCSPX-` (remainder: read from OpenQuota's src-tauri/src/providers/antigravity/client.rs,
  GOOGLE_CLIENT_SECRET_PARTS, or from your own Antigravity keychain entry)), could mint fresh
  access tokens indefinitely on its own, with zero dependency on Antigravity being installed or
  running anywhere.

**What a phone would need, concretely:** a **refresh token** (or a live access token, though
those expire in ~1 hour per `DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600.0`) issued to the exact same
OAuth client id above, with whatever scope(s) that client id's original consent grant covered.
This code never sends an explicit `scope` parameter on refresh (scopes are fixed to whatever the
original authorization included), and this repo's client code never performs the initial
authorization-code exchange — it *only* ever refreshes an already-existing refresh token that
Antigravity/`agy` itself obtained. So the only way to originally get such a token is to sign in
through the real Antigravity/`agy` product (or reverse-engineer its initial OAuth flow, which is
out of scope of this repo and not present here). Once you have that refresh token, though, this
code proves the follow-on calls are ordinary bearer-token HTTPS with no other local/device-bound
requirement — i.e. **the dependency on "the Antigravity IDE process running locally" is real for
the convenience path, but not structurally required by the API itself**, only for initially
acquiring/holding the credential.

---

## 5. POLLING & ERRORS

Global (not Antigravity-specific — this is the app-wide refresh policy in
`src-tauri/src/policy.rs`):

```rust
pub const REFRESH_INTERVAL: Duration = Duration::from_secs(5 * 60);      // 5 minutes
pub const FAILURE_RETRY_BACKOFF: Duration = Duration::from_secs(60);     // 1 minute
pub const STALE_AFTER: chrono::Duration = chrono::Duration::minutes(10);
```

`src-tauri/src/refresh_loop.rs` runs an infinite async loop per app session: gather all
enabled provider ids, call `service.refresh_all_with_progress(...)`, emit a `usage-state` Tauri
event, run notification pacing, then `tokio::time::sleep(REFRESH_INTERVAL)` (5 min) and repeat.
I did not trace exactly where `FAILURE_RETRY_BACKOFF` (60s) is consumed (likely in
`service.rs`/`ProviderService`, which this pass did not fetch) — noting this as **inferred, not
directly verified**, that a failed provider gets retried sooner (60s) than the normal 5-minute
cadence rather than being retried on the very next 5-minute tick; the constant's existence and
name strongly imply this but I have not read the consuming code.

**Antigravity-specific behavior when nothing is running / signed in** (all in `mod.rs`):
- If `discover()` finds nothing (Antigravity/`agy` not running) **and** there's no usable
  keychain token at all → `AntigravityError::NotSignedIn` ("Start Antigravity or run `agy` and
  try again.").
- If a keychain refresh token exists but every access-token attempt plus a refresh attempt fails
  with an auth error → `AntigravityError::AuthExpired` ("Antigravity sign-in expired...").
- If the keychain read itself fails (I/O error) → `AntigravityError::CredentialStoreUnreadable`.
- If the stored credential blob doesn't parse into any recognized token shape →
  `AntigravityError::InvalidCredentialData`.
- If everything is structurally fine but every network call transiently fails/times out →
  `AntigravityError::Unavailable` ("Usage temporarily unavailable. Try again shortly.").
- These five variants map to three `ProviderErrorKind`s for the rest of the app: `Authentication`
  (NotSignedIn, AuthExpired, InvalidCredentialData), `CredentialStorage`
  (CredentialStoreUnreadable), `Network` (Unavailable).
- HTTP client timeouts: the local language-server client uses a **5-second** timeout per request
  attempt (tries up to 3 endpoint/scheme combos per discovered server: https+port, http+port,
  http+extension_port); the remote/cloud client uses a **15-second** timeout per request, tried
  against up to 2 cloud base URLs.
- Discovery itself (the `ps`/PowerShell/CIM subprocess) has its own 5-second timeout
  (`DISCOVERY_TIMEOUT`), independent of the HTTP timeouts.

---

## 6. MULTI-ACCOUNT support for Google — there is none

Unlike Claude (`claude::runtimes(storage, pricing)` returns **one runtime per detected Claude
account**, and there's a dedicated `providers/claude/accounts.rs` file for that), Antigravity is
instantiated **exactly once** in `lib.rs`:

```rust
Arc::new(AntigravityProvider::new(
    app_data_dir.join("antigravity").join("auth.json"),
)?) as Arc<dyn UsageProvider>,
```

There is no `providers/antigravity/accounts.rs`, no loop, no per-account credential path — a
single `AccessTokenCache` at a single fixed path, keyed only by a fingerprint of whichever
refresh token happens to be in the OS keychain under `service="gemini", account="antigravity"`
at call time. If a user switches which Google account is signed into Antigravity, OpenQuota just
picks up whatever's currently in that one keychain slot (and the fingerprint mismatch causes the
old cached access token to be discarded automatically — see `AccessTokenCache::load`). There is
no way to show two Google accounts' quotas side by side. This is a hard, structural limitation,
not a config toggle.

---

## 7. Surprising / fragile things

1. **The local RPC protocol is an internal, undocumented Connect/gRPC-JSON service**:
   `exa.language_server_pb.LanguageServerService`, with method names `RetrieveUserQuotaSummary`,
   `GetUserStatus`, `GetCommandModelConfigs`. The `exa.language_server_pb` package name and the
   `x-codeium-csrf-token` header name are legacy artifacts of **Codeium/Windsurf's** internal
   protocol (Antigravity's team has Codeium/Windsurf lineage) — this is a reverse-engineered,
   versionless, undocumented private RPC surface that could change or disappear in any Antigravity
   update with zero warning, and OpenQuota has no version pinning or capability negotiation against
   it beyond "try 3 methods in a fallback chain and give up."
2. **The CSRF token is read out of a live process's argv, and TLS verification is explicitly
   disabled** (`danger_accept_invalid_certs(true)`) for local calls — a deliberate, if narrowly
   scoped (loopback-only), weakening of transport security to talk to Antigravity's self-signed
   local endpoint.
3. **Hardcoded public Google OAuth client id + secret** exercised directly by OpenQuota
   (`1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com` /
   `GOCSPX-` (remainder: read from OpenQuota's src-tauri/src/providers/antigravity/client.rs,
   GOOGLE_CLIENT_SECRET_PARTS, or from your own Antigravity keychain entry), split across two
   string literals specifically to dodge secret-scanner false-positives, per the code comment) —
   this is *Antigravity's own* installed-
   app client id, not something Google issued to OpenQuota; OpenQuota is impersonating the
   Antigravity client to Google's OAuth token endpoint whenever it refreshes a token itself.
4. **Two different undocumented Google Cloud Code Assist hosts** are tried in order —
   `daily-cloudcode-pa.googleapis.com` first, then `cloudcode-pa.googleapis.com` — suggesting an
   internal canary/staging host takes priority over the production one; not documented anywhere
   Google-side that this crawl found.
5. **`v1internal:` RPC path prefix** on all four cloud endpoints is itself a strong signal these
   are explicitly **internal, unstable Google APIs** not meant for third-party consumption — this
   whole integration is built entirely on reverse-engineered internal endpoints, not a published
   Gemini/Cloud Code Assist API.
6. **The "legacy" per-model fallback path pools by literally checking whether the word "gemini"
   is a substring of the model's human-readable display label** (`build_legacy_quotas`) — if
   Google ever renames a Gemini model to not contain that substring (or names a non-Gemini model
   that happens to contain "gemini"), the pooling breaks silently (no error, just wrong bucketing).
7. **No absolute quota numbers are ever available for Antigravity** — everything is a
   remaining-fraction percentage; `used_value`/`limit_value`/`unit` are hardcoded `None` in the
   Antigravity mapper, unlike richer providers in the same codebase.
8. **Model exclusion blacklist is a hardcoded list of specific internal Google model-ID
   strings** (`MODEL_CHAT_20706`, `MODEL_CHAT_23310`, `MODEL_GOOGLE_GEMINI_2_5_FLASH*`,
   `MODEL_GOOGLE_GEMINI_2_5_PRO`, `MODEL_PLACEHOLDER_M9/M12/M19`) that exist purely to dedupe/hide
   stale entries Google's own `fetchAvailableModels` response apparently still returns — a
   maintenance burden that requires updating this list by hand whenever Google adds new
   superseded/placeholder model ids.
9. **`period_seconds` (5h / 7d) is a hardcoded constant in OpenQuota, not derived from the API at
   all** — if Google ever changes the actual window length (e.g. to 4 hours), OpenQuota's UI would
   keep showing "resets in X" math based on the wrong assumed period unless someone updates this
   constant; only the absolute `resetTime` timestamp is authoritative, when Google supplies one.

---

## Verified vs. inferred summary

**Directly verified by reading the source** (essentially everything above except item 5's backoff
consumption site): the fallback chain order in `refresh_inner`/`fetch_remote`; all RPC/method
names and URL paths; the CSRF/port/process discovery logic end-to-end including OS-specific
branches; the OS-keychain read functions per platform; the `go-keyring-base64:` unwrap and JSON
token-shape parsing; the OAuth client id/secret and token-refresh request/response shape; the
`QuotaWindow`/`ModelConfig`/`LanguageServer`/`AntigravityToken`/`CachedAccessToken` struct
definitions; the bucket-id-to-metric mapping and the "worst remaining fraction" pooling rule
(confirmed by an explicit unit test); the single-instance (no multi-account) registration in
`lib.rs`; the global `REFRESH_INTERVAL`/`FAILURE_RETRY_BACKOFF`/`STALE_AFTER` constants.

**Inferred, not directly traced in this pass**: exactly how/where `FAILURE_RETRY_BACKOFF` is
applied (would require reading `service.rs`, `ProviderService`, and possibly
`notifications.rs`/`pacing.rs`, none of which were fetched in this pass); the exact frontend
(Svelte) rendering of "resets in" when `resets_at` is `None`; whether `agy` (the CLI) uses a
materially different on-disk credential path than the IDE (the code treats them identically via
the same keychain service/account pair, so this is likely moot); the precise OAuth scopes
originally granted to the Antigravity client id (never sent explicitly in any refresh call in this
codebase, so not directly observable from client-side code alone).
