# Phone feasibility: ChatGPT / Antigravity / Gemini-CLI quota in Claude Cooldown

Independent web/source research, 2026-09-06. Scope: what an **Android phone with only a token**
can do — no desktop, no local process, no local files.

**Evidence grades used throughout**
- **[V] Verified** — read in official source code or official vendor docs.
- **[R] Reported** — third-party project docs, issues, blog posts. Credible, not first-party.
- **[I] Inferred** — my engineering conclusion from the above; flagged where load-bearing.

**Headline:** ChatGPT/Codex is *fully* feasible on a phone today, including the sign-in — Codex
ships a **device-code flow** with no localhost anywhere. Antigravity is feasible for the *data*
(a real cloud HTTPS endpoint) but its sign-in is a localhost redirect with no official paste
fallback, and the remote payload is known to be degraded versus the local one. Gemini CLI /
Code Assist is dead for consumer accounts as of 2026-06-18 and should not be built.

---

## 1. ChatGPT / OpenAI Codex

### A. Is there an HTTPS usage endpoint a phone can call with only a bearer token?

**Yes. Unambiguously yes.** This is the single cleanest answer in the whole report.

**[V]** `GET https://chatgpt.com/backend-api/wham/usage`

Verified in `codex-rs/backend-client/src/client/rate_limit_resets.rs`, which builds the URL in
`rate_limit_status_url()`:

```rust
fn rate_limit_status_url(&self) -> String {
    match self.path_style {
        PathStyle::CodexApi  => format!("{}/api/codex/usage", self.base_url),
        PathStyle::ChatGptApi => format!("{}/wham/usage", self.base_url),
    }
}
```
<https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/rate_limit_resets.rs>

`base_url` is normalised in `client.rs` — any `https://chatgpt.com` base has `/backend-api`
appended automatically, so the effective URL is `https://chatgpt.com/backend-api/wham/usage`.
<https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client.rs>

**Headers** — verified in `Client::headers()` in `client.rs`:

```
Authorization: Bearer <access_token>       (via auth_provider.add_auth_headers)
ChatGPT-Account-Id: <account_id>           (header name literally "ChatGPT-Account-Id")
User-Agent: codex-cli                      (default when none supplied)
X-OpenAI-Fedramp: true                     (only for FedRAMP accounts)
```

Optional: `x-openai-codex-luna-reserve: 1` — **do not send this.** The source comments it
"Opt in only for clients that can apply Reserve, **not for passive account usage readers**."
A read-only tracker is exactly a passive account usage reader.

**Response shape** — the type is `RateLimitStatusPayload`, verified at
<https://github.com/openai/codex/blob/main/codex-rs/codex-backend-openapi-models/src/models/rate_limit_status_payload.rs>:
`plan_type`, `rate_limit`, `credits`, `spend_control`, `additional_rate_limits`,
`rate_limit_reached_type`.

**[R]** CodexBar (a shipping Mac menu-bar tracker doing precisely this) documents the concrete
JSON, which matches the Rust types field-for-field:

```json
{
  "plan_type": "pro",
  "rate_limit": {
    "primary_window":   { "used_percent": 15, "reset_at": 1735401600, "limit_window_seconds": 18000  },
    "secondary_window": { "used_percent": 5,  "reset_at": 1735920000, "limit_window_seconds": 604800 }
  },
  "credits": { "has_credits": true, "unlimited": false, "balance": 150.0 }
}
```
<https://github.com/steipete/CodexBar/blob/main/docs/codex-oauth.md>

`18000` seconds = 5 hours. `604800` = 7 days. **This is the identical two-window shape Claude
Cooldown already renders.** `used_percent` + `reset_at` + `limit_window_seconds` maps 1:1 onto
your existing 5-hour / 7-day model with no new UI semantics required.

Supporting endpoints, same auth, same headers, all **[V]** from `client.rs`:

| Purpose | URL |
|---|---|
| Usage / rate limits | `GET https://chatgpt.com/backend-api/wham/usage` |
| Account check | `GET https://chatgpt.com/backend-api/wham/accounts/check` |
| Token usage profile | `GET https://chatgpt.com/backend-api/wham/profiles/me` |
| Reset credits inventory | `GET https://chatgpt.com/backend-api/wham/rate-limit-reset-credits` |

There is also a **header-based** path: `codex-api/src/rate_limits.rs` parses
`x-codex-primary-used-percent`, `x-codex-primary-window-minutes`, `x-codex-primary-reset-at`
(and `-secondary-`, and `x-<limit-id>-` families) off *any* Codex API response.
<https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/rate_limits.rs>
Not useful to you — it requires making a *billable model call* to observe the headers. The
`/wham/usage` GET is free and is the right call.

### B. How does the phone get the token?

**Two viable flows. One of them needs no localhost at all.**

#### B1. Device-code flow — **the recommended path** [V]

Verified in `codex-rs/login/src/device_code_auth.rs`.
<https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs>

```
1. POST https://auth.openai.com/api/accounts/deviceauth/usercode
   Content-Type: application/json
   { "client_id": "app_EMoamEEZ73f0CkXaXp7hrann" }
   → { "device_auth_id": "...", "user_code": "...", "interval": "5" }

2. Show the user:  https://auth.openai.com/codex/device   +  the user_code
   (source: verification_url = format!("{base_url}/codex/device"); code expires in 15 min)

3. Poll POST https://auth.openai.com/api/accounts/deviceauth/token
   { "device_auth_id": "...", "user_code": "..." }
   → { "authorization_code": "...", "code_challenge": "...", "code_verifier": "..." }
   (max_wait is 15 minutes in source)

4. POST https://auth.openai.com/oauth/token
   Content-Type: application/x-www-form-urlencoded
   grant_type=authorization_code
   &code=<authorization_code>
   &redirect_uri=https://auth.openai.com/deviceauth/callback
   &client_id=app_EMoamEEZ73f0CkXaXp7hrann
   &code_verifier=<code_verifier>
   → { id_token, access_token, refresh_token }
```

Note step 3: **the server hands you the PKCE verifier**. The device flow is self-contained.
Step 4 reuses `exchange_code_for_tokens` from `server.rs` — same token endpoint as the browser
flow, with `redirect_uri = https://auth.openai.com/deviceauth/callback` (an HTTPS URL, never
touched by the client).

**Rating: fully feasible in an Android app. No Custom Tab required, no app link required, no
localhost socket, no paste of a secret.** The user taps a link, sees a short code, types it on
`auth.openai.com` in whatever browser they like — even on a *different device*. This is strictly
better than what Claude Cooldown does for Anthropic today. It is also robust: it does not depend
on redirect-URI allowlists, Digital Asset Links, or intent filters.

Caveat **[V]**: the source treats HTTP 404 from `/deviceauth/usercode` as "device code login is
not enabled for this Codex server" — so OpenAI can feature-flag it off. Keep B2 as a fallback.

#### B2. Browser PKCE flow with a loopback redirect [V]

Verified in `codex-rs/login/src/server.rs`:
<https://github.com/openai/codex/blob/main/codex-rs/login/src/server.rs>

```
https://auth.openai.com/oauth/authorize
  ?response_type=code
  &client_id=app_EMoamEEZ73f0CkXaXp7hrann
  &redirect_uri=http://localhost:1455/auth/callback
  &scope=openid%20profile%20email%20offline_access%20api.connectors.read%20api.connectors.invoke
  &code_challenge=<S256>
  &code_challenge_method=S256
  &id_token_add_organizations=true
  &codex_cli_simplified_flow=true
  &state=<32 random bytes, base64url>
  &originator=<client originator>
```

- `DEFAULT_ISSUER = "https://auth.openai.com"`, `DEFAULT_PORT = 1455`, `FALLBACK_PORT = 1457`.
- Source comment on the fallback port: *"Keep in sync with the Codex CLI Hydra redirect URI
  allow-list."* → **only 1455 and 1457 are allowlisted.** You cannot use an ephemeral port.
- Token exchange: `POST https://auth.openai.com/oauth/token`,
  `grant_type=authorization_code&code=…&redirect_uri=…&client_id=…&code_verifier=…`.

**Can a phone serve this?** **[I] Yes** — and this is worth stating plainly because the premise
in the brief ("a localhost redirect which a phone cannot serve") is not quite right. An Android
app can open a `ServerSocket` bound to `127.0.0.1:1455`; a Chrome Custom Tab on the *same device*
navigating to `http://localhost:1455/auth/callback` hits that socket, because localhost on the
phone is the phone. This is standard RFC 8252 native-app practice
(<https://datatracker.ietf.org/doc/html/rfc8252>, and
<https://www.oauth.com/oauth2-servers/oauth-native-apps/redirect-urls-for-native-apps/>).
It is fiddlier than the device flow (port contention, backgrounding, Android's cleartext-traffic
policy needing a `network-security-config` exception for `localhost`) — but it works.

**Is there an OpenAI equivalent of Anthropic's code-paste page?** **[V] Not a documented one.**
There is no OOB/`urn:ietf:wg:oauth:2.0:oob` redirect in the Codex source, and no "copy this code"
success page. The success page constant is
`CODEX_OPEN_APP_URL = "https://chatgpt.com/codex/open-app"` and the local success redirect is
`http://localhost:{port}/success?...` — both assume a working loopback.
<https://github.com/openai/codex/blob/main/codex-rs/login/src/success_page.rs>
**The device flow is OpenAI's answer to that problem, and it is a better answer.**

#### Token lifetimes and refresh [V]

- `REFRESH_TOKEN_URL = "https://auth.openai.com/oauth/token"`,
  `REVOKE_TOKEN_URL = "https://auth.openai.com/oauth/revoke"`
- `CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"`
- Refresh body: `grant_type=refresh_token&refresh_token=…&client_id=…`
- `TOKEN_REFRESH_INTERVAL: i64 = 8` (days) — the CLI proactively refreshes if `last_refresh` is
  older than 8 days.
- `CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES: i64 = 5` — refresh 5 minutes before the access
  token's JWT `exp`.
- Refresh failures are classified as `refresh_token_expired` / `refresh_token_reused` /
  `refresh_token_invalidated`. **`refresh_token_reused` is a rotation trap**: refresh tokens
  rotate, so if the phone and a desktop CLI ever share one `auth.json` lineage, redeeming the
  same refresh token twice invalidates the family. Your app must own its own token from its own
  login — never import someone's `auth.json`.

All from <https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/manager.rs>.

The `id_token` carries the plan for free — `token_data.rs` parses claims under
`https://api.openai.com/auth` into `chatgpt_plan_type`, `chatgpt_account_id`, `chatgpt_user_id`,
`chatgpt_account_is_fedramp`, plus `email` from `https://api.openai.com/profile`.
<https://github.com/openai/codex/blob/main/codex-rs/login/src/token_data.rs>
So you get plan and account id without any extra request — same trick Claude Cooldown can use.

### C. What would we show, plan tiers, fragility

**Windows.** Exactly the two Claude Cooldown already draws:
- **Session / 5-hour** ← `rate_limit.primary_window` (`limit_window_seconds: 18000`)
- **Weekly / 7-day** ← `rate_limit.secondary_window` (`limit_window_seconds: 604800`)

Both give `used_percent` (0–100) and `reset_at` (epoch seconds). **[V]** Official docs confirm
the semantics: "five-hour rolling window" plus "Weekly limits may also apply"
(<https://learn.chatgpt.com/docs/pricing>). **[R]** Limits are shared across every Codex surface
— CLI, IDE, web, iOS — on one plan allowance
(<https://standardcompute.com/codex-usage-limits>), which is the same "one pool, many clients"
story your app already tells.

Extras worth a second lane, not a first one:
- `credits` → `{ has_credits, unlimited, balance }`. Post-limit continuation budget.
- `additional_rate_limits[]` → per-model-family windows. CodexBar renders these as
  "Spark" (`codex-spark` / `codex-spark-weekly`) rows. Maps naturally onto your existing
  multi-lane rails gauge; you'd read `normal_model_slug` and `limit_name` to label them.
- `spend_control`, `rate_limit_reached_type` → state flags for an "above pace / limit hit" state.

**Plan tiers [V]** — the full `PlanType` enum from
<https://github.com/openai/codex/blob/main/codex-rs/protocol/src/account.rs>:
`free, go, plus, pro, prolite, team, self_serve_business_prolite,
self_serve_business_usage_based, business, ent26, enterprise_cbp_automation,
enterprise_cbp_usage_based, enterprise, edu, edu_plus, edu_pro, unknown`.
Note `#[serde(other)] Unknown` — **OpenAI adds tiers and the enum is explicitly built to tolerate
it.** Detect via `plan_type` in the response, or offline from the `id_token` claims.

**Fragility.** Moderate-low, and lower than it looks:
- `/wham/usage` is an internal ChatGPT backend endpoint, not a published API. It can change
  without notice. But it is load-bearing for the Codex CLI's own `/status`, so it does not
  churn casually, and the response is code-generated from an OpenAPI spec
  (`codex-backend-openapi-models`), which means changes are additive and versioned in a repo you
  can watch. A CI job diffing that one generated file would give you early warning.
- **[R]** The limits *themselves* churn a lot: OpenAI removed the 5-hour restriction for Plus /
  Business / Pro on 2026-07-12 with no published end date, while leaving weekly limits in place
  (<https://simplemetrics.xyz/chatgpt-codex-limits-2026/>). Your UI must survive
  `primary_window` being absent or permanently 0% — that is a real empty state to wireframe,
  not a hypothetical.
- **ToS risk.** You are reading *your own* account's usage with *your own* OAuth token via the
  same client id the official CLI uses. That is the same posture Claude Cooldown already takes
  with Anthropic, and the same posture CodexBar has shipped publicly for a long time. The
  material risks are (a) impersonating `codex-cli` in the User-Agent, and (b) OpenAI deciding
  third-party use of `app_EMoamEEZ73f0CkXaXp7hrann` is off-limits. Mitigation: send an honest
  `User-Agent` identifying Claude Cooldown, poll conservatively (your existing cadence is far
  gentler than a coding session), never send `x-openai-codex-luna-reserve`, and never touch a
  token you did not mint.

---

## 2. Google Antigravity

### A. Is there an HTTPS usage endpoint a phone can call with only a bearer token?

**Yes — but with a real asterisk. The remote payload is a degraded version of the local one.**

This is *not* a localhost-only story. The brief's worry — that the only route is the IDE's
language server — is wrong, but the local route is genuinely richer.

**[R] Remote (cloud, phone-reachable), all `POST`, base `https://cloudcode-pa.googleapis.com`:**

| Endpoint | Gives you |
|---|---|
| `/v1internal:retrieveUserQuotaSummary` | The good one: merged pools **and weekly windows** |
| `/v1internal:retrieveUserQuota` | Per-model buckets, 5-hour windows only |
| `/v1internal:fetchAvailableModels` | Non-Gemini pool fractions via `quotaInfo.remainingFraction` |
| `/v1internal:loadCodeAssist` | Project id, plan/tier, account identity |
| `/v1internal:onboardUser` | First-run onboarding |

Sources: <https://github.com/steipete/CodexBar/blob/main/docs/antigravity.md> ("Remote OAuth data
sources" section) and <https://github.com/robinebers/openusage/blob/main/docs/providers/antigravity.md>.

**Headers [R]:**
```
Authorization: Bearer <access_token>
Content-Type: application/json
User-Agent: antigravity
X-Goog-Api-Client: google-cloud-sdk vscode_cloudshelleditor/0.1     (per PicoClaw)
```
<https://gist.github.com/taoalpha/22773d2132519e55a4c7427fd3e96d8e>,
<https://docs.picoclaw.io/docs/providers/antigravity/>

**Request body [R]** — minimal client metadata: `{ ideName: "antigravity", extensionName:
"antigravity", locale: "en", ideVersion: "unknown" }`. `retrieveUserQuota` additionally takes
`{ "project": "<projectId>" }` (or `{}`), where `projectId` comes from
`loadCodeAssist` → `cloudaicompanionProject`.

**Response [R]** — `retrieveUserQuotaSummary` returns:
```
response.groups[].displayName
response.groups[].buckets[].bucketId
response.groups[].buckets[].displayName
response.groups[].buckets[].remaining.remainingFraction     // 0..1
response.groups[].buckets[].description                     // reset prose
```
The legacy endpoints return `quotaInfo.remainingFraction` (0–1) and `resetTime` (ISO-8601) per
model. Example from `fetchAvailableModels`:
```json
{ "models": { "gemini-3-pro-high": { "displayName": "Gemini 3 Pro (High)",
  "quotaInfo": { "remainingFraction": 1, "resetTime": "2026-01-14T09:20:41Z" } } } }
```

**Proof it works with no IDE and no local process [R]:** `tingyi365/agy-quota` is described as a
"Headless Antigravity (agy CLI) / Gemini Code Assist usage-quota checker — **No IDE required**",
and its documented architecture calls `cloudcode-pa` directly with credentials read from OS
storage, explicitly stating "No localhost API used" and "No localhost/language-server component
required." <https://github.com/tingyi365/agy-quota>

**The asterisk — this is the part to take seriously [R].** CodexBar, which implements *both* the
local and remote paths and compares them in production, reports:

> "OAuth payloads can be less complete and may only prove model availability."
> "An all-100% `fetchAvailableModels` payload is only accepted after `retrieveUserQuota` echoes
> bucket fractions."
> "`retrieveUserQuotaSummary` (available, but current observed OAuth responses are model-bucket
> shaped rather than Antigravity 2.0's two quota groups)."
> "Without a signed-in `agy`, the OAuth fallback can only prove model availability, so the menu
> shows an all-100% placeholder instead of real quota numbers."

**[I] Read together:** the remote endpoints answer, and they can return real fractions — but a
token minted *outside* an Antigravity session may get an availability-shaped response (everything
100%) rather than live quota, and weekly grouping is not reliably present remotely. A phone-only
implementation would need a live spike against a real account to find out which side of that line
it lands on. **Rate: probably possible, not yet proven.** Do not commit to a wireframe until a
spike returns non-100% fractions from a phone-minted token.

### B. How does the phone get the token?

**This is where Antigravity is materially worse than Codex.**

**[R] Antigravity OAuth client** (found identically in ~20 independent projects, e.g.
<https://github.com/wusimpl/AntigravityQuotaWatcher> `src/auth/constants.ts` and
<https://github.com/liuw1535/antigravity2api-nodejs> `src/constants/oauth.js`):

```
client_id     = 1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com
client_secret = GOCSPX-(remainder: read from OpenQuota's src-tauri/src/providers/antigravity/client.rs,
                GOOGLE_CLIENT_SECRET_PARTS, or from your own Antigravity keychain entry)   (installed-app; public by design)
auth  = https://accounts.google.com/o/oauth2/v2/auth
token = https://oauth2.googleapis.com/token
scopes = cloud-platform, userinfo.email, userinfo.profile, cclog, experimentsandconfigs
PKCE   = S256
redirect_uri = http://localhost:51121/oauth-callback
```
<https://docs.picoclaw.io/docs/providers/antigravity/>

- **Localhost redirect, fixed port 51121, no official code-paste page.** There is no Google
  equivalent of Anthropic's paste page for this client. Google killed the OOB
  (`urn:ietf:wg:oauth:2.0:oob`) flow —
  <https://developers.google.com/identity/protocols/oauth2/resources/oob-migration>.
- **[R] There is a manual mode in third-party clients**: PicoClaw supports "manual mode allows
  pasting the full redirect URL", and the openclaw issue notes the Custom Tab / browser address
  bar still contains `?code=...&scope=...` after the failed navigation, which the user can copy.
  <https://github.com/openclaw/openclaw/issues/2463>
  That is a *user-hostile* fallback: "let this page fail to load, then copy the URL out of the
  address bar." It is not a page designed to be copied from, the way Anthropic's is.
- **[!] Live breakage [R]:** as of January 2026 Google began rejecting this redirect outright —
  *"You can't sign in to this app because it doesn't comply with Google's OAuth 2.0 policy for
  keeping apps secure"* — because the registered URI uses the `localhost` **hostname** over plain
  HTTP rather than the `127.0.0.1` IP literal. Same issue thread. Google's own guidance prefers
  the IP literal (<https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration>).
  **You cannot fix this from your side** — the redirect string is whatever Google has registered
  against that client id.

**Rating.** **[I]** A Custom Tab + loopback socket on `127.0.0.1:51121` is *technically* the same
Android trick that works for Codex (see §1.B2), and Google's loopback deprecation targets
**client types** registered as Android/iOS/Chrome — this is a *Desktop* client, so loopback
remains supported for it regardless of the device running it. But you are betting on a redirect
URI Google is actively flagging, that you do not control, for a client id you do not own. **Rate:
possible but fragile, and one Google policy sweep from breaking permanently.** Custom-scheme or
app-link redirects are flatly unavailable — those require an Android-type OAuth client bound to
*your* package name and signing certificate, and this client is not yours.

Token refresh **[R]**: standard Google —
`POST https://oauth2.googleapis.com/token`, form-encoded
`client_id, client_secret, refresh_token, grant_type=refresh_token`. Google refresh tokens for
published apps do not expire on a fixed schedule (they die on revocation, password change, or
6 months of disuse). Access tokens are ~1 hour; implementations use a 5-minute refresh buffer.
This half is genuinely easy — **once you have a refresh token, keeping it alive on a phone is a
solved problem.** The problem is minting the first one.

### C. What would we show, plan tiers, fragility

**Windows [R].** Two shared pools × two windows each — a clean 4-lane model:

| Lane | Meaning |
|---|---|
| Gemini Session | Shared Gemini pool (Pro and Flash draw from the *same* quota), rolling 5-hour |
| Gemini Weekly | Same pool, weekly window |
| Claude + GPT Session | Shared non-Gemini pool (Claude, GPT-OSS, …), rolling 5-hour |
| Claude + GPT Weekly | Same pool, weekly window |

<https://github.com/robinebers/openusage/blob/main/docs/providers/antigravity.md>

Important modelling notes, all **[R]** from the same source, and all of them are states somebody
has to look at before code exists:
- **Fractions, not percentages used.** `remainingFraction` is 0–1 *remaining*. Claude Cooldown
  shows *used*. `usedPercent = (1 - remainingFraction) * 100`. Easy to invert by accident.
- **Per-model rows collapse into two pools.** Merge by keeping each pool's **worst** remaining
  fraction across its models.
- **"Not started" is a real state.** While a 5-hour window has no usage yet, there is no reset
  countdown at all — the session begins at the first message. Your rails gauge has no such state
  today.
- **Untouched families pin at 0%.** Antigravity reports every family the plan covers, so a
  Gemini-only user still gets a Claude/GPT pair reading 0% forever. OpenUsage hides a family once
  every lane reports known zero.
- **Reset metadata without a fraction.** Some rows carry `resetTime` but no `remainingFraction`;
  they must render as "unknown", never as 0% or 100%.
- **[R]** Milestone quantisation: the local API is reported to update only at ~0/20/40/60/80%,
  so the number is coarse and steppy, not smooth. Your pace line and "above pace" logic assume
  a continuous signal — this would look broken.

**Plan tiers [V]** — official: **Google AI Ultra**, **Google AI Pro**, and standard/free users.
Ultra: "the highest, most generous quota, refreshed every five hours." Pro: "high, generous
quota, refreshed every five hours **until weekly limit reached**." Standard: "meaningful quota,
refreshed weekly." <https://antigravity.google/docs/plans/>
Detection **[R]**: `loadCodeAssist` → prefer Antigravity's own `userTier`, else `paidTier.name`,
else map `standard-tier` → Paid, `free-tier` + `hd` claim → Workspace, `free-tier` → Free,
`legacy-tier` → Legacy.

**[R]** The Pro semantics are the interesting product story and the thing users are actually
confused about: burn the weekly baseline on day 1 and the 5-hour refresh does nothing, because
the weekly pool is empty — users report "multi-day lockouts instead of 5-hour reset"
(<https://discuss.ai.google.dev/t/google-ai-pro-antigravity-quota-shows-multi-day-lockouts-instead-of-5-hour-reset/130202>).
An app that shows both windows *side by side and explains the interaction* is genuinely more
useful here than for Claude. That is the strongest product argument for Antigravity support.

**Fragility: high.**
- `v1internal` is, by name, an internal Google API. No public contract, no deprecation policy.
- **[R]** OpenUsage's own footnote: *"Reverse-engineered from the app and language-server binary;
  endpoints and storage may change without notice."*
- Endpoint availability is *build-dependent*: `retrieveUserQuotaSummary` 404s on older builds and
  from the IDE local server, and OpenUsage ships a documented "weekly meters show No data"
  troubleshooting entry for exactly this.
- **ToS risk: higher than OpenAI's.** You would be using a client id + client secret extracted
  from Google's binary, sending `User-Agent: antigravity` to impersonate the IDE, against an
  endpoint marked internal. Google enforces OAuth client policy aggressively and has already
  broken this exact client's redirect once in 2026.

---

## 3. Gemini CLI / Code Assist

### A. Endpoint

**[V]** Yes, and it is the best-documented of the three, because it is in the CLI's own
open-source repo rather than reverse-engineered:

`POST https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota`

```ts
export const CODE_ASSIST_ENDPOINT = 'https://cloudcode-pa.googleapis.com';
export const CODE_ASSIST_API_VERSION = 'v1internal';
getMethodUrl(method: string): string { return `${this.getBaseUrl()}:${method}`; }
```
<https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/server.ts>

Request/response types **[V]** from
<https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/types.ts>:

```ts
export interface RetrieveUserQuotaRequest  { project: string; userAgent?: string; }
export interface BucketInfo {
  remainingAmount?: string;      // int64-as-string
  remainingFraction?: number;    // 0..1
  resetTime?: string;            // ISO-8601
  tokenType?: string;
  modelId?: string;
}
export interface RetrieveUserQuotaResponse { buckets?: BucketInfo[]; }
```

Headers **[R]** (CodexBar's Gemini provider): `Authorization: Bearer <access_token>`,
`Content-Type: application/json`; body `{ "project": "<projectId>" }` or `{}`.
Project discovery: `loadCodeAssist` → `cloudaicompanionProject`, falling back to
`GET https://cloudresourcemanager.googleapis.com/v1/projects` picking `gen-lang-client*`.
Tier detection: `POST .../v1internal:loadCodeAssist` with
`{ metadata: { ideType: "GEMINI_CLI", pluginType: "GEMINI" } }`.
<https://github.com/steipete/CodexBar/blob/main/docs/gemini.md>

### B. Token — **and here is the one genuine paste-page precedent**

**[V]** `client_id = 681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com`,
`client_secret = GOCSPX-` (remainder: read from google-gemini/gemini-cli's
packages/core/src/code_assist/oauth2.ts, linked below) — with an in-source comment explaining
*"It's ok to save this in git because this is an installed application… the client secret is
obviously not treated as a secret."*
Scopes: `cloud-platform`, `userinfo.email`, `userinfo.profile`.
<https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/oauth2.ts>

Gemini CLI has **two** flows, and the second is exactly the Anthropic-style fallback the brief
asked about:

1. Loopback: `http://127.0.0.1:${port}/oauth2callback` — note the CLI uses the **IP literal**,
   with an in-source comment that Google's policy requires it. (Antigravity's client, by
   contrast, registered the `localhost` hostname — which is why Antigravity's is the one that
   broke.)
2. **`authWithUserCode()` — `redirect_uri = 'https://codeassist.google.com/authcode'`**, with
   PKCE S256, `access_type: 'offline'`, and a 5-minute timeout, then
   `rl.question('Enter the authorization code: ', …)`.

**[I]** Flow 2 is a genuine phone-friendly path: Custom Tab → Google → an **HTTPS page that
displays the code** → user copies → pastes into the app → app exchanges it with the verifier it
generated. No socket, no app link, no address-bar scraping. It is the direct structural analogue
of Anthropic's paste fallback.

**So the auth story here is the *second*-best of the three. It does not matter, because:**

### C. **This path is dead for the accounts you care about**

**[V] Official Google deprecation notice:**

> "Starting **June 18, 2026**, Gemini Code Assist IDE extensions stopped serving requests for the
> Gemini Code Assist for individuals, Google AI Pro, and Google AI Ultra tiers."

Affected: individuals, **Google AI Pro**, **Google AI Ultra** — i.e. essentially every consumer
subscriber. Unaffected: Code Assist **Standard** and **Enterprise** (paid Google Cloud
subscriptions). Replacement: *"migrate to the Antigravity family of products."*
<https://developers.google.com/gemini-code-assist/docs/deprecations/code-assist-individuals>

**[R]** CodexBar has already implemented the fallout: it watches for `UNSUPPORTED_CLIENT`,
`IneligibleTierError`, or Antigravity-migration copy in quota / `loadCodeAssist` / refresh
responses and offers a handoff to its Antigravity provider.
<https://github.com/steipete/CodexBar/blob/main/docs/gemini.md>

Tiers **[V]**: `UserTierId = { FREE: 'free-tier', LEGACY: 'legacy-tier', STANDARD: 'standard-tier' }`,
typed as `| string` because "the source list is frequently updated."

**Verdict for §3: do not build this.** The endpoint is real and the auth is the friendliest of
the three, but the addressable user is "someone with a paid Google Cloud Code Assist Standard or
Enterprise subscription" — not a Claude Cooldown user. Its only value to you is as *reference
implementation*: it is the open-source, first-party spec for the `cloudcode-pa` protocol that
Antigravity speaks a dialect of. Read it to understand Antigravity; don't ship it.

---

## Ranked verdict

### 1st — ChatGPT / Codex: **build it. Feasible on a phone today, end to end.**

Nothing is missing. The usage endpoint is a plain authenticated `GET` returning a
`{used_percent, reset_at, limit_window_seconds}` pair for a 5-hour and a 7-day window — the exact
shape Claude Cooldown already renders. And the **device-code flow removes the login problem
entirely**: no localhost socket, no app link, no paste of a secret, and the user can even
complete it on a different device. It is *strictly easier* than the Anthropic flow you already
ship.

Recommended shape, in order:
1. Device code (`/api/accounts/deviceauth/usercode` → `/codex/device` → `/deviceauth/token` →
   `/oauth/token`). No workaround needed.
2. Loopback `127.0.0.1:1455` + Custom Tab as fallback if OpenAI 404s the device endpoint.
3. Poll `GET https://chatgpt.com/backend-api/wham/usage`. Refresh 5 min before JWT `exp`; treat
   `refresh_token_reused` as fatal and force re-login.
4. Read plan + account id from the `id_token` claims — no extra request.

Real work is UI, not plumbing: the extra `credits` lane, the `additional_rate_limits[]`
per-model-family lanes, and an empty state for a `primary_window` that may be absent while
OpenAI's 5-hour suspension holds.

### 2nd — Antigravity: **needs a workaround. Spike before you wireframe.**

Two independent blockers, of different kinds:
- **Data (soft):** the cloud endpoints are real and phone-reachable — this is *not* a
  localhost-only provider — but a token minted outside an Antigravity session may get an
  availability-shaped all-100% response rather than live fractions, and weekly grouping is
  unreliable remotely. Unproven either way from a phone.
- **Auth (hard):** `http://localhost:51121/oauth-callback`, a client id you don't own, no paste
  page, and Google actively rejecting that redirect since January 2026.

**Cleanest workaround, and it is genuinely clean for *your* setup:** you already have a Mac
menu-bar client (CCRM-8 (Mac Menu-Bar)). The Mac is where Antigravity and `agy` actually live, it
is where the richest source — the local `RetrieveUserQuotaSummary` on the language server — is
available, and it is where a loopback OAuth redirect is uncontroversial. **Let the Mac companion
own Antigravity and relay a small normalised snapshot to the phone.** That gets you the *better*
data (real weekly groups, plan name, correct fractions) rather than the degraded remote payload,
and it sidesteps the auth blocker completely. The phone renders; the Mac fetches.

Ranked alternatives if a Mac relay is off the table:
- **Refresh-token paste.** User signs into Antigravity/`agy` on a computer, pastes the refresh
  token into the phone once; the phone refreshes against `oauth2.googleapis.com` forever after.
  Ugly onboarding, but the *steady state* is fine and it needs no desktop process running. This
  is the best phone-only option.
- **Custom Tab + `127.0.0.1:51121` loopback socket.** Technically sound, but bets on a redirect
  Google is already flagging, for a client you don't own. Would likely work; could stop working
  any week.
- **Address-bar scrape** ("let the page fail, copy the URL"). Works, but is the kind of
  onboarding that generates support mail forever. Avoid.

### 3rd — Gemini CLI / Code Assist: **do not build.**

Google stopped serving individuals, AI Pro and AI Ultra on 2026-06-18 and told them to move to
Antigravity. Only Code Assist Standard/Enterprise remain — not your audience. Its real value is
that it is the **open-source, first-party reference** for the `cloudcode-pa` protocol: read
`packages/core/src/code_assist/{server,types,oauth2}.ts` to understand Antigravity's endpoints
and its `authWithUserCode` paste-page pattern, then close the tab.

---

### Two things worth flagging back to the roadmap

1. **The `CLAUDE.md` scope line.** "Claude Cooldown tracks **Claude usage only**. Adding other AI
   providers is not a roadmap gap; it's out of scope." Everything above is technically feasible
   and none of it is a roadmap item yet — this is a scope decision, not an engineering one, and
   it should be made explicitly (and the appendix updated) before any wireframe.
2. **Rule 2 applies in full.** Antigravity in particular introduces states the current rails
   gauge has never had to draw: *"Not started"* (a 5-hour window with no usage and therefore no
   reset countdown), *unknown* (reset metadata present, fraction absent — must not render as 0%
   or 100%), *untouched family pinned at 0% forever*, and a **remaining**-fraction signal that is
   quantised to ~20% steps and will make a pace line look broken. ChatGPT adds *credits* and a
   possibly-absent 5-hour window. Those are exactly the unobserved visual states that
   CCRM-15 (Above-Pace Verification) exists because of. Wireframe them, get approval, then build.
