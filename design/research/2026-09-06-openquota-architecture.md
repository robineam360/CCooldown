# OpenQuota (deviffyy/OpenQuota) — Multi-Provider Architecture & Branding

Researched by crawling the GitHub tree at `main` via `api.github.com/repos/deviffyy/OpenQuota/git/trees/main?recursive=1`
and reading raw files from `raw.githubusercontent.com/deviffyy/OpenQuota/main/<path>` (via `curl`,
which returns exact file bytes — WebFetch's own output was AI-summarized and was only used for the
very first orientation pass, then discarded in favor of raw reads). All code quoted below is
**verbatim, verified by direct read of the raw file** unless explicitly marked "inferred."

Stack: Tauri 2 (Rust backend, `src-tauri/`) + Svelte 5 (frontend, `src/`), pnpm workspace, version
0.5.0 at time of research.

---

## 1. Provider list

Source: `src-tauri/src/providers/mod.rs` (module declarations + `UsageProvider` trait test asserting
each provider's declared links), `README.md` "Supported providers" section, and
`docs/providers/*.md` (one file per provider, each with a "Sign-in and local data" section that is
the ground truth for the data-source classification below).

| Provider ID | Display name | Data source | One-line description (from README/docs) |
|---|---|---|---|
| `claude` | Claude Code | **Local file** — reuses CLI's own OAuth credentials (respects `CLAUDE_CONFIG_DIR`); usage/spend computed locally from Claude Code's own usage logs (JSONL) | Multiple accounts, session/weekly limits, model-specific usage, token history, estimated spend |
| `codex` | Codex | **Local file** — reuses ChatGPT/Codex CLI auth (respects `CODEX_HOME`); spend computed locally from `sessions`/`archived_sessions` logs | Session/weekly limits, credits, token history, model breakdown, estimated spend |
| `cursor` | Cursor | **Local file/app state + remote** — reads Cursor's local app state and OS credential store for the account, then calls Cursor's own usage-export API for history | Total, Auto and API usage, credits, token history, estimated spend |
| `antigravity` | Antigravity | **Local process/file** — reuses Antigravity's locally stored credentials, can discover a *running* local Antigravity language-server process | Shared Gemini and Claude quota pools (rolling 5h + weekly) |
| `copilot` | GitHub Copilot | **Local file + remote** — looks for credentials left by Copilot editor integrations, falls back to GitHub CLI (`gh auth login`) auth, then calls GitHub's billing/usage API | Premium requests, extra usage, chat/completion quotas, org billing |
| `devin` | Devin | **Local file** — reads credentials created by the Devin CLI (`devin auth login`) or the desktop app's signed-in state | Daily/weekly limits, reset times, extra-usage balance |
| `grok` | Grok | **Local file** — reads auth/usage stored by the Grok CLI (`grok login`, respects `GROK_HOME`) | Weekly allowance, extra-usage status, token history, estimated spend |
| `opencode` | OpenCode | **Local file (SQLite/data dir) + remote** — reads OpenCode's local auth file and databases (`OPENCODE_DATA_DIR`/`XDG_DATA_HOME`), and calls OpenCode Go's account-usage endpoint for the Go quota rows | OpenCode Go session/weekly/monthly caps + local hosted usage history |
| `openrouter` | OpenRouter | **Remote HTTPS with API key** — user-supplied key (Customize UI, `OPENROUTER_API_KEY`/`OPENROUTER_KEY` env, or `~/.config/openrouter/key.json`) | Credit balance, daily/weekly/monthly spend |
| `zai` | Z.ai | **Remote HTTPS with API key** — GLM Coding Plan key (Customize UI, `ZAI_API_KEY`/`GLM_API_KEY` env, or config files) | GLM Coding Plan session/weekly/web-search quotas |
| `kimi` | Kimi | **Remote HTTPS with API key** — Kimi Code key against `https://api.kimi.com/coding/v1` | Kimi Code session/weekly quotas |
| `minimax` | MiniMax | **Remote HTTPS with API key** — Token Plan key against `https://www.minimax.io/v1/token_plan/remains` | Token Plan session/weekly quotas |

Notes:
- `claude` and `codex` additionally support **multiple accounts**: an account gets a synthetic
  provider id of the form `claude@<8-hex-chars>` (see `is_claude_account_provider_id` in
  `providers/mod.rs`), one dashboard card per detected login, combining logins that resolve to the
  same underlying account.
- Only `claude`, `codex`, `cursor` are `fallback_enabled: true` in their `ProviderDefinition` (the
  three that ship as defaults if nothing else can be detected — see §3).
- `README.md` states plainly: "Most providers use credentials already available on your computer.
  OpenRouter, Z.ai, Kimi, and MiniMax require API keys... OpenQuota stores them securely in your
  operating system's credential store."
- The app is explicitly local-only: "OpenQuota runs locally and has no account, cloud backend,
  analytics, or usage telemetry of its own."

---

## 2. Generic usage model

Source: `src-tauri/src/models.rs` (Rust, canonical) and `src/lib/types.ts` (TypeScript mirror, kept
in lockstep — camelCase via `#[serde(rename_all = "camelCase")]`). I did **not** dig into any
provider's `mapper.rs` (per the task's instruction to leave fetch-logic depth to other agents); this
section is purely the shared contract every provider's mapper must produce.

### Core snapshot returned per provider (`ProviderSnapshot`)

```rust
pub struct ProviderSnapshot {
    pub provider_id: String,
    pub plan: Option<String>,
    pub quotas: Vec<QuotaWindow>,
    pub value_metrics: Vec<ValueMetric>,
    pub status_metrics: Vec<StatusMetric>,
    pub notices: Vec<ProviderNotice>,
    pub usage: UsageHistory,
    pub warnings: Vec<String>,
    pub refreshed_at: DateTime<Utc>,
}
```

### How "different window shapes" (5h vs weekly vs daily vs per-model) are handled

There is **no fixed field per window type** (no `.session_window`, `.weekly_window` fields on the
struct). Instead, `quotas: Vec<QuotaWindow>` is an **open list of arbitrarily many named windows**,
each self-describing its own period:

```rust
pub struct QuotaWindow {
    pub id: String,               // e.g. "session", "weekly", "claudeWeekly"
    pub label: String,            // display label, e.g. "Session", "Weekly"
    pub used_percent: f64,
    pub resets_at: Option<DateTime<Utc>>,
    pub period_seconds: u64,      // the window's actual length — this is what encodes
                                   // "5h" vs "weekly" vs "daily", not an enum
    pub format: QuotaFormat,      // Percent | Dollars | Count
    pub used_value: Option<f64>,
    pub limit_value: Option<f64>,
    pub unit: Option<String>,     // e.g. "requests", "searches"
    pub estimated: bool,
    pub source_note: Option<String>,
}
```

So Claude's rolling 5-hour session window, Codex's weekly window, Antigravity's shared Gemini vs
Claude pools, and Z.ai's monthly web-search allowance are all just different `QuotaWindow` entries
with different `id`/`label`/`period_seconds`/`format` — the model imposes no fixed shape, only a
convention. Per-model limits (Claude's "Sonnet/Fable", Codex's "Spark/Spark Weekly") are likewise
just additional `QuotaWindow` (or `ValueMetric`) rows with their own ids — there's no separate
per-model quota type.

Two other loosely-typed containers cover non-quota numbers and text:

```rust
pub struct ValueMetric {          // arbitrary numeric metrics: credits, balances, extra spend
    pub id: String,
    pub label: String,
    pub values: Vec<MetricValue>, // each tagged Count or Dollars, optionally sub-labeled
    pub expiries_at: Vec<DateTime<Utc>>,
}

pub struct StatusMetric {         // arbitrary short text/status rows (e.g. "2500 cap")
    pub id: String,
    pub label: String,
    pub text: String,
    pub tone: StatusTone,         // Neutral | Positive | Warning | Danger
    pub subtitle: Option<String>,
}
```

### Token/spend history (separate from quotas)

```rust
pub struct UsageHistory {
    pub today: Option<UsagePeriod>,
    pub yesterday: Option<UsagePeriod>,
    pub last_30_days: Option<UsagePeriod>,
    pub daily: Vec<DailyUsage>,
    pub unknown_models: Vec<String>,
}

pub struct UsagePeriod {
    pub tokens: u64,
    pub estimated_cost_usd: Option<f64>,
    pub cost_estimated: bool,             // true unless a real billed cost was returned
    pub estimate_complete: bool,
    pub model_breakdown: Option<ModelUsageBreakdown>,
    pub unknown_models: Vec<String>,
}

pub struct ModelUsageBreakdown {
    pub models: Vec<ModelUsageEntry>,     // id="model", total_tokens, cost_usd, optional variants
    pub source_note: String,
}
```

### What is declared per provider, independent of any live snapshot (`ProviderDefinition`/`MetricDefinition`)

Rather than the UI hard-coding "Claude has a session bar and a weekly bar," each provider declares
its own metric catalog at startup, and the frontend renders generically off that catalog:

```rust
pub struct ProviderDefinition {
    pub id: String,
    pub display_name: String,
    pub short_name: String,               // tray-facing short label
    pub fallback_enabled: bool,           // enabled-by-default safety net (see §3)
    pub local_usage_source_note: Option<String>,
    pub links: Vec<ProviderLink>,          // "Status"/"Dashboard" quick links
    pub metrics: Vec<MetricDefinition>,
}

#[serde(tag = "kind", rename_all = "camelCase")]
pub enum MetricSource {
    Quota { source_id: String, session_window: bool },
    QuotaOrValue { source_id: String, session_window: bool },  // falls back to a value metric
                                                                 // if no matching quota exists
    Value { source_id: String },
    Status { source_id: String },
    Usage { period: UsagePeriodSelection },  // Today | Yesterday | Last30Days
    Trend,
}
```

Each `MetricDefinition` declares its `pinnable`/`default_enabled`/`default_section`
(`AlwaysVisible`|`OnDemand`)/`default_pinned` and an optional `TrayMetricDefinition { short_label,
suffix }`. `ProviderRegistry::new` (see §3) enforces structural invariants at construction time —
e.g. every metric id must be prefixed `"{provider_id}."`, a Trend metric can never be pinnable, at
most 2 metrics may be `default_pinned` per provider (`MAX_DEFAULT_PINS = 2`), and every provider
must have at least one default always-visible metric. This is effectively a compile-time-adjacent
contract test (`registry_rejects_invalid_defaults_and_sources`, `builtin_provider_catalog_keeps_the_product_defaults`) guaranteeing new providers can't ship a config the renderer can't display.

---

## 3. Provider abstraction: trait, registry, detection, scheduler

### The trait (`src-tauri/src/providers/mod.rs`)

```rust
pub trait UsageProvider: Send + Sync {
    fn definition(&self) -> ProviderDefinition;
    fn has_local_credentials(&self) -> bool;
    fn refresh(&self) -> Result<ProviderSnapshot, ProviderError>;

    fn refresh_for_service(&self) -> Result<ProviderRefresh, ProviderError> { ... } // default impl
    fn cache_identity(&self) -> CacheIdentity<'_> { CacheIdentity::Unscoped }
    fn supports_account_names(&self) -> bool { false }
    fn supports_api_key_configuration(&self) -> bool { false }
    fn account_identity(&self) -> Option<&str> { None }
    fn api_key_status(&self) -> Option<Result<ApiKeyStatus, ProviderError>> { None }
    fn save_api_key(&self, _value: &str) -> Result<(), ProviderError> { Err(...) }
    fn delete_api_key(&self) -> Result<(), ProviderError> { Err(...) }
}
```

Every concrete provider module (`providers::claude`, `::codex`, `::cursor`, `::antigravity`,
`::copilot`, `::devin`, `::grok`, `::kimi`, `::minimax`, `::opencode`, `::openrouter`, `::zai`) is a
sibling top-level module implementing this trait once per provider "family," plus a free function
`definition() -> ProviderDefinition` used by tests and the registry.

`ProviderError` is a single struct (not a per-provider error enum): `{ kind: ProviderErrorKind,
message: String }`, where `ProviderErrorKind` is one of `Authentication | Permission | RateLimited |
Network | InvalidResponse | CredentialStorage | LocalData | Storage | Internal`. The frontend
(`ProviderErrorKind` in `types.ts`) uses this kind to decide whether to show a "Configure" button
(only for `authentication`/`permission`/`credentialStorage`) alongside the always-present "Retry"
button — see `Dashboard.svelte`'s `provider-error-row`.

### Registry (`src-tauri/src/providers/registry.rs`)

`ProviderRegistry::new(providers: Vec<Arc<dyn UsageProvider>>)` builds:
- `runtimes: HashMap<String, Arc<dyn UsageProvider>>` — id → live provider instance
- `catalog: ProviderCatalog` (an ordered `Vec<ProviderDefinition>` — **order is preserved from
  construction order**, i.e. registration order in the app's bootstrap defines default provider
  order) plus `api_key_provider_ids: Vec<String>`
- indices for O(1) lookup by provider id and by fully-qualified metric id

Construction fails fast (`ProviderRegistryError::Invalid(String)`) on: duplicate provider/metric ids,
missing display/short name, no metrics, metric id not prefixed by its provider id, more than
`MAX_DEFAULT_PINS` (2) default pins, no default always-visible metric, and inconsistent tray metadata
— and on **no provider at all being `fallback_enabled`** ("registry has no fallback-enabled
provider"). A confirmed test enumerates the actual built-in order:

```
["claude", "codex", "cursor", "antigravity", "copilot", "devin", "grok", "opencode", "openrouter", "zai"]
```
(Kimi and MiniMax are present in `providers/mod.rs`'s module list and in the icon/tray-rendering
switch statements, but are not part of that particular registry-order test fixture — they are
newer additions confirmed elsewhere, e.g. `docs/providers/kimi.md`, `docs/providers/minimax.md`,
and `menu_bar.rs`'s bundled icon constants.)

Of these, only `claude`, `codex`, `cursor` have `fallback_enabled: true` (verified by test
`assert!(registry.definition("codex").unwrap().fallback_enabled)` and the loop asserting
`antigravity`/`copilot`/`devin`/`grok`/`opencode`/`openrouter`/`zai` are all *not*
fallback-enabled).

### Enable/disable and auto-detection (`src-tauri/src/settings.rs`, `providers/detection.rs`)

- **Detection** (`detect_local_credentials` in `detection.rs`): for a list of provider ids, spawns
  one `tauri::async_runtime::spawn_blocking(|| runtime.has_local_credentials())` worker **per
  provider concurrently** (confirmed by test `credential_probes_run_concurrently_and_return_only_hits`
  using a `Barrier`), each wrapped in a **10-second timeout**
  (`CREDENTIAL_PROBE_TIMEOUT: Duration::from_secs(10)`). Results are `Detected | Absent | Unknown`
  (a stalled or panicking probe resolves to `Unknown` rather than blocking the batch — verified by
  `stalled_credential_probe_does_not_hold_up_detection` and
  `failed_credential_probe_is_unknown_instead_of_absent`).
- **First-run defaults** (`settings::default_settings`): every provider's `enabled` flag defaults to
  whatever `detected` returned for it. **If detection finds nothing at all** (no provider ends up
  enabled), the code falls back to enabling exactly the `fallback_enabled` set (claude/codex/cursor):
  ```rust
  if !settings.providers.iter().any(|provider| provider.enabled) {
      for provider in &mut settings.providers {
          provider.enabled = registry.definition(&provider.id)
              .is_some_and(|definition| definition.fallback_enabled);
      }
  }
  ```
- **Subsequent runs** (`normalize_with_persisted_accounts`): a provider id not previously present in
  `known_provider_ids` (i.e. newly discovered, e.g. the user installed a new CLI) gets
  auto-`enabled = detected.contains(id)` — this is the mechanism behind the onscreen "Welcome to
  OpenQuota — We set you up with the AI tools found on your computer" detection-notice card in
  `Dashboard.svelte` (dismissible via `settings.detectionNoticeDismissed`).
- **Ordering**: a `ProviderLayout` is a persisted per-provider record `{ id, enabled, detected,
  expanded, metrics: Vec<MetricLayout> }`; the frontend reorders providers purely by mutating the
  order of `settings.providers` (`CustomizeProviderList.svelte`'s `reorder()` splices the enabled
  subset and re-appends disabled ones) — there's no separate "priority" field, order **is** the
  persisted array order, and it's drag-reorderable both on the dashboard and the Customize screen.
- **Per-provider settings**: `ProviderLayout.metrics: Vec<MetricLayout>` (`{id, enabled, section,
  pinned}`) — per-metric visibility/pin state, not just per-provider; API keys are stored via OS
  credential store, exposed to the frontend only as a status enum (`ApiKeyStatus`), never the secret
  itself (test `api_key_state_exposes_status_without_a_secret_field` confirms serialization never
  includes a secret field).
- **Per-provider errors**: carried on `ProviderViewState.error: Option<String>` +
  `error_kind: Option<ProviderErrorKind>` per provider id in a `HashMap`/`Record` — there's no global
  error state, each card in `UsageViewState.providers` owns its own.

### Poll scheduler (`src-tauri/src/refresh_loop.rs`, `policy.rs`, `service.rs`)

```rust
pub const REFRESH_INTERVAL: Duration = Duration::from_secs(5 * 60);      // 5 minutes
pub const FAILURE_RETRY_BACKOFF: Duration = Duration::from_secs(60);     // 1 minute
pub const STALE_AFTER: chrono::Duration = chrono::Duration::minutes(10);
```

`refresh_loop::spawn` runs one `tauri::async_runtime::spawn`'d infinite loop: every
`REFRESH_INTERVAL` (5 min) it takes `settings.enabled_provider_ids()` and calls
`service.refresh_all_with_progress(...)`, which (in `service.rs`) delegates to
`refresh_enabled_with_progress`:

```rust
for provider_id in provider_ids {
    ...
    tauri::async_runtime::spawn(async move {
        let state = service.refresh(&provider_id, force).await;
        let _ = completed_tx.send(state);
    });
}
```

**Every enabled provider refreshes fully in parallel** (one Tokio task per provider, not staggered,
not sequential — confirmed by both the code shape and a test literally named
`enabled_providers_refresh_in_parallel`), and results stream back to the frontend incrementally
via an `on_progress` callback that emits a Tauri `"usage-state"` event after each individual
provider completes (so the dashboard fills in card-by-card rather than waiting for the slowest
provider).

Each individual provider refresh (`Service::refresh`) additionally has its own
**in-flight de-duplication / coalescing** ("flight" generations): concurrent refresh requests for the
same provider don't launch duplicate network calls — a second caller just awaits the in-flight
attempt's completion (or bumps a "run again after this one" generation counter if `force` is
requested mid-flight). Each provider's actual fetch runs on `spawn_blocking` (providers are
synchronous/blocking under the hood) under a configurable `self.refresh_timeout`; a timed-out result
is discarded when it eventually arrives ("late_worker"). A provider that just failed is skipped for
`FAILURE_RETRY_BACKOFF` (60s) unless forced. A cached snapshot older than `STALE_AFTER` (10 min) is
flagged `stale: true` to the UI (rendered as an "Outdated" badge — see §4) rather than blocked from
display.

---

## 4. UI composition

Source: `src/lib/Dashboard.svelte` (1295 lines), `src/lib/TotalSpend.svelte`,
`src/lib/CustomizeProviderList.svelte`, `src-tauri/src/tray_presentation.rs`,
`src-tauri/src/menu_bar.rs`.

### Dashboard layout: a single vertical stack of provider cards, not tabs

`Dashboard.svelte` renders, top to bottom, in this order:
1. An update-available banner (`hint-card update-banner`), dismissible.
2. A one-time "Welcome to OpenQuota" **detection notice** card (see §3), dismissible, with an "Open
   Customize" CTA button.
3. `<TotalSpend>` — the **combined/aggregate cross-provider view** (see below) — shown only if
   `settings.showTotalSpend` and at least one enabled provider has usage data.
4. `{#each dashboardProviders as ...}` — **one `<section class="provider-section">` card per enabled
   provider**, drag-reorderable (`use:pointerReorder`, `animate:flip`), each with:
   - a `provider-header`: drag grip, provider display name (`<h1>`), an optional plan badge
     (`snapshot.plan`), an "Outdated" badge if `state.stale`, a status slot showing a spinning
     refresh icon / warning icon (tooltip = error or warning text), and the provider's brand
     `<ProviderIcon>` mark on the right.
   - a `provider-card` body: any `ProviderNotice` rows, an inline error row (message + "Configure"
     button when the error kind allows credential fixing + a "Retry"/"Retrying…" button) when
     `state.error` is set, then the provider's `alwaysMetrics` (always rendered) and `demandMetrics`
     (behind an expand toggle, per `ProviderLayout.expanded`), each rendered by a generic
     `<MetricRenderer>` dispatching on the metric's declared `MetricSource` kind (Quota → a bar/gauge
     component, Value → numeric row, Status → text row, Usage → token/spend row, Trend → sparkline).
5. If zero providers are enabled: a single `empty-dashboard` section — "Turn on Customize to choose
   what to show."

There are **no tabs** — every enabled provider's card is simultaneously visible in one scrollable
column (the whole window is a fixed-width 320px popup/floating panel, see §5), reordered by drag.

### Not signed in / not installed: hidden on the dashboard, greyed on the Customize screen

- **Dashboard**: a disabled provider does not appear at all — `dashboardProviders` is filtered to
  `enabledProviders` before iteration; there is no "greyed out card" state on the main screen.
- **Customize screen** (`CustomizeProviderList.svelte`): shows **every known provider**, including
  disabled ones, as a row with `class:inactive={!provider.enabled}` → `opacity: 0.55`, a name, a
  metric count ("N metrics"), and a toggle switch — this is where a not-installed/not-signed-in
  provider becomes visible-but-greyed, with an explicit enable switch, rather than hidden.
- A provider that *is* enabled but fails to authenticate/refresh still shows its card (with the
  inline error row described above) — it isn't hidden, since "enabled" and "currently erroring" are
  orthogonal states (`ProviderViewState.error` vs `ProviderLayout.enabled`).

### Combined/aggregate view: `TotalSpend.svelte`

This is the one genuinely cross-provider visual: a donut/ring chart (`spend-ring`, SVG arc
sectors) plus a legend list, aggregating spend or tokens **across every enabled provider with usage
data** for a selectable period (Today / Yesterday / Last 30 Days) and metric (Cost / Cost-per-Mtok /
Tokens). Each ring segment and legend dot is colored by
`var(--provider-${providerFamily(id)}, var(--provider))` — i.e. the provider's own brand color token
if one is defined in `tokens.css`, else falling back to OpenQuota's own generic app-accent color
(`--provider`, teal). A tooltip on the info icon reads "Only includes {list of provider names}." —
i.e., it silently excludes any provider lacking usage data rather than showing a zero segment. It
also has its own "Share Screenshot" button (produces a shareable image of the ring — implementation
in `src/lib/shareCard.ts`, not read in depth here).

### Tray / menu bar: pinned metrics only, no single "primary" provider by default

Two very different code paths, `src-tauri/src/tray_presentation.rs`:

- **Non-macOS (Windows/Linux tray icon)**: `primary_gauge()` picks the **first pinned quota metric
  across all enabled providers, in provider layout order**, and renders *only that one* as a
  circular gauge PNG (`tray_icon::render_gauge`) — the tray icon shows a single ring for whichever
  metric happens to be first. If no provider has a pinned quota metric, it falls back to the static
  OpenQuota mark icon. The full tooltip text (hover), however, lists **every** pinned metric across
  **every** enabled provider, joined by " · ".
- **macOS menu bar** (`menu_bar.rs`, user-selectable `MenuBarStyle`):
  - `Text` style: renders **all** providers with pinned metrics side by side as one composited
    template-image "strip" — each provider's brand glyph (parsed from its bundled SVG path) next to
    its pinned value(s), stacked two-high if a provider has 2 pinned metrics, separated by a gap
    between provider groups. So on macOS multiple providers *can* coexist simultaneously in the menu
    bar, each keeping its own icon — there's no single "primary" chosen, they're concatenated.
  - `Bars` style: takes up to `MAX_BARS = 4` pinned quota fractions (in provider layout order, across
    all providers) and renders them as up to 4 stacked mini progress bars in one compact glyph — no
    provider icons in this mode, just proportional fill bars.
  - If nothing is pinned/enabled, both styles fall back to the static app mark
    (`MacMenuBarIcon::Mark`).
- Per-metric pin cap: `MAX_PINS_PER_PROVIDER = 2` (`settings.rs`) and registry-enforced
  `MAX_DEFAULT_PINS = 2` — so a provider can surface at most 2 metrics into tray/menu-bar space.

So: **no explicit "primary provider" concept** exists in the model; the tray/menu-bar behavior is
entirely a function of (a) which providers are enabled, (b) which of their metrics are pinned, and
(c) provider layout order — "primary" on non-mac platforms is simply "first pinned quota metric in
list order," while macOS Text/Bars modes are additive/combining rather than picking one.

---

## 5. Branding

### App identity
- **Name**: OpenQuota (`package.json` `"name": "openquota"`, Tauri `"productName": "OpenQuota"`,
  bundle identifier `io.github.deviffyy.openquota`).
- **Tagline** (README, centered under the name): *"Track usage and limits across your AI coding
  tools."* Body copy: *"OpenQuota brings usage data from Claude Code, Codex, Cursor, Copilot, and
  other AI coding providers into one compact panel. See session and weekly limits, reset times,
  token usage, and estimated spend at a glance."*
- **Positioning**: explicitly local-first/no-telemetry ("OpenQuota runs locally and has no account,
  cloud backend, analytics, or usage telemetry of its own") and explicitly credits a prior-art
  inspiration: *"OpenQuota was inspired by [OpenUsage](https://github.com/robinebers/openusage) and
  developed as a cross-platform alternative for Windows, Linux, and macOS."* — positions itself as
  the cross-platform (Win/Linux/macOS) successor/alternative to a narrower tool, for developers who
  use several AI coding CLIs/IDEs at once and want one panel instead of checking each provider's own
  dashboard.
- **Window form factor**: a small fixed-width **320×800 max, 320×240 min, non-resizable** popup
  panel (`tauri.conf.json`: `decorations: false`, `transparent: false`, `shadow: true`,
  `alwaysOnTop: true`, `skipTaskbar: true`, hidden at launch) — i.e., designed as a tray-triggered
  popup or an optionally-pinned floating panel (`WindowMode::Popup | Floating`), not a normal
  resizable app window.

### Icon assets
Listed at `assets/` and `src-tauri/icons/`:
- `assets/openquota-icon.png` / `assets/openquota-icon.svg` — the main 1024×1024 app icon.
- `assets/openquota-tray.svg` — a 24×24 monochrome/two-tone tray variant.
- `assets/openquota-demo.gif`, `assets/screenshot.png` — README demo assets (dashboard in light &
  dark).
- `src-tauri/icons/{32x32,128x128,128x128@2x}.png`, `icon.icns`, `icon.ico` — platform bundle icons.
- `src/assets/openquota-mark.png` and `src/lib/OpenQuotaMark.svelte` — an in-app rendering of the
  brand mark (used as the dashboard/empty-state mark).

**Icon description** (from reading the raw SVG paths in `assets/openquota-icon.svg` and
`assets/openquota-tray.svg` directly): the mark is a stylized **"Q" built from a broken circular dial
plus a diagonal tail** — six short curved segments arranged like tick marks around a ring (echoing a
speedometer/gauge face, consistent with the product's "quota gauge" metaphor used again in the
`TotalSpend` donut ring and the tray's circular gauge icon), with a single bold diagonal stroke
crossing the ring to complete the "Q" shape. In the full-color app icon the ring segments are filled
with an angular gradient from white to `#1689EF` (the same blue as `--meter-fill`), and the diagonal
tail is a linear gradient from white to `#1689EF`. In the tray variant (`openquota-tray.svg`), the
ring is rendered as dark/black "track" segments (`#161616`) with blue (`#1689EF`) "fill" segments
layered on top (mirroring how a quota bar's track vs. fill are drawn elsewhere in the app), and the
diagonal tail is solid white — i.e. the icon literally encodes the app's own quota-meter visual
language into its logo.

### Color palette (`src/styles/tokens.css`, `@layer tokens`)

Generic UI tokens (light — root `:root`; equivalent dark values under
`@media (prefers-color-scheme: dark)` and mirrored again under explicit `:root[data-theme='dark']`
for a manual toggle):

| Token | Light | Dark |
|---|---|---|
| `--tray` (surface) | `#ffffff` | `#1d1d1f` |
| `--text` | `#171719` | `#f4f4f5` |
| `--secondary` | `#727278` | `#a7a7ad` |
| `--tertiary` | `#a5a5aa` | `#707077` |
| `--meter-fill` (generic progress) | `#1689ef` | `#2997ff` |
| `--meter-warning` | `#e5a400` | `#ffd54a` |
| `--meter-critical` | `#e3483f` | `#ff6961` |
| `--provider` (generic app accent, used when a provider has no dedicated brand var) | `#10a37f` | `#19c39a` |
| `--warning` / `--warning-bg` | `#a85d00` / `#fff4df` | `#ffb95c` / `#3b2e1e` |
| `--error` / `--error-bg` | `#b42318` / `#fff0ee` | `#ff8f86` / `#3d2525` |

Per-provider brand color CSS custom properties (defined in `tokens.css`, used by the `TotalSpend`
ring/legend via `var(--provider-{family}, var(--provider))`):

| Provider | Light hex | Dark hex (only where overridden) |
|---|---|---|
| `--provider-codex` | `#10a37f` | (same, not overridden in dark block) |
| `--provider-claude` | `#de7356` | (same) |
| `--provider-antigravity` | `#4285f4` | (same) |
| `--provider-copilot` | `#a855f7` | (same) |
| `--provider-cursor` | `#13120a` (near-black) | `#f5f5f7` (near-white — inverts for contrast) |
| `--provider-grok` | `#8e8e93` | `#98989d` |
| `--provider-opencode` | `#6e6e73` | `#aeaeb2` |
| `--provider-openrouter` | `#6467f2` | (same) |
| `--provider-zai` | `#2d2d2d` | `#d1d1d6` (inverts for contrast) |

Separately, `src/lib/providerIconPaths.ts` defines **icon fill colors** (used only by the small
per-card `<ProviderIcon>` glyph, distinct from the `tokens.css` vars above which color the
TotalSpend ring):

```ts
const visuals: Record<string, { source: string; color: string | null }> = {
  antigravity: { source: antigravity, color: '#4285F4' },
  claude:      { source: claude,      color: '#DE7356' },
  codex:       { source: codex,       color: null },   // renders as currentColor (monochrome)
  copilot:     { source: copilot,     color: null },
  cursor:      { source: cursor,      color: null },
  devin:       { source: devin,       color: null },
  grok:        { source: grok,        color: null },
  kimi:        { source: kimi,        color: '#1783FF' },
  minimax:     { source: minimax,     color: '#E2167E' },
  opencode:    { source: opencode,    color: null },
  openrouter:  { source: openrouter,  color: null },
  zai:         { source: zai,         color: null },
};
```
So only **Antigravity, Claude, Kimi, and MiniMax** get a full-color per-card icon glyph; every other
provider's small icon renders monochrome (`currentColor`, i.e. it just follows the surrounding text
color) even though several of them (codex, cursor, etc.) *do* have a distinct brand hex defined in
`tokens.css` for use elsewhere (the TotalSpend ring). This looks like a deliberate but understated
choice — full color is reserved for a handful of providers, everything else stays visually quiet in
the small icon context.

### Answering the specific asks:
- **OpenAI/ChatGPT (Codex)**: no distinct "OpenAI" brand color is used — `--provider-codex:
  #10a37f`, i.e. it reuses the same green as OpenQuota's own generic `--provider` accent
  (`#10a37f`/`#19c39a`), and its small icon glyph is monochrome (`color: null`). There is no
  separate ChatGPT-purple anywhere in the codebase I read.
- **Google/Gemini/Antigravity**: `--provider-antigravity: #4285f4` (Google's canonical blue), and its
  icon glyph is one of the few given a hardcoded color (`#4285F4`, same value). This is the closest
  thing to a "Google blue" in the app.
- **Claude/Anthropic**: `--provider-claude: #de7356` (Anthropic's terracotta/rust orange), and its
  icon glyph is colored the same (`#DE7356`) — Claude is one of the few full-color icons.

### Typography
- UI font stack (`tokens.css` `:root`): `system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI',
  sans-serif` — no custom UI webfont, relies on the OS system font.
- One bundled font exists but is used **only for tray/menu-bar glyph rendering**, not the Svelte UI:
  `src-tauri/assets/fonts/Poppins-SemiBold.ttf` (with its `OFL.txt` license), rasterized at draw time
  by `menu_bar.rs` via `fontdue` to paint the macOS text-strip menu bar values (single-value size
  23pt-equivalent, stacked-value size 17pt-equivalent, at a 36px-tall retina-density strip).

### Light/dark handling
Three-way: `ThemePreference::System | Light | Dark` (`models.rs`), implemented in CSS as:
1. A `prefers-color-scheme: dark` media query (system default), guarded so it doesn't fire under
   `:root:not([data-theme='light'])`-style logic is not literally present but the effect is achieved
   via specificity — actual dark values are declared twice: once under the media query (system
   default) and once again under an explicit `:root[data-theme='dark']` selector (so a manual
   in-app toggle can force dark regardless of OS setting), and light is likewise re-declared under
   `:root[data-theme='light']` to force-light regardless of OS dark mode.
2. `color-scheme: light`/`dark` is set explicitly in each block (native form control / scrollbar
   theming follows suit).

---

## 6. License

**MIT License**, copyright `(c) 2026 deviffyy` (verified verbatim from `LICENSE`):

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND...

**Attribution requirement**: only the standard MIT requirement — the copyright notice and license
text must be included in copies/substantial portions of the software. No additional
attribution/notice/trademark clause beyond stock MIT. (The bundled font,
`src-tauri/assets/fonts/Poppins-SemiBold.ttf`, carries its own separate `OFL.txt` — SIL Open Font
License — not read in full here, but standard OFL also just requires the font's own license file to
travel with the font, and forbids selling the font itself standalone.)

---

## 7. Naming, positioning, "who it's for" (synthesis, lightly inferred from the above)

- The product name itself ("Open" + "Quota") signals: (a) open-source, and (b) its single subject is
  *quota* — not "usage dashboard" or "AI assistant," specifically the consumable-limit framing
  (session/weekly windows, credits, resets) that developers using metered AI coding tools run into.
- It explicitly frames itself as filling a **cross-platform gap** left by a predecessor
  (OpenUsage, credited by name, which the README implies was not cross-platform) — the positioning
  is "the same idea, but for Windows, Linux and macOS."
- The provider list (Claude Code, Codex, Cursor, Antigravity, Copilot, Devin, Grok, OpenCode,
  OpenRouter, Z.ai, Kimi, MiniMax) makes clear the intended audience is developers who juggle
  **multiple coding-agent CLIs/IDEs at once** (not end-users of a single chat app) — the products
  covered are all developer-facing coding agents/CLIs or their underlying model marketplaces
  (OpenRouter), not general consumer chat products.
- Every provider doc's troubleshooting section is written in the imperative, CLI-first voice ("run
  `claude`", "run `codex`", "run `grok login`") — reinforcing that the target user is comfortable at
  a terminal and already has these tools installed; OpenQuota's job is explicitly to *observe*
  existing local credentials/usage rather than to be another account you sign into.
- No provider is described as more or less "premium" — the UI and docs treat all twelve as peers in
  one flat list, reordable by the user, with the only structural distinction being which three
  (Claude, Codex, Cursor) are trusted enough to be the safety-net defaults if local detection finds
  nothing at all.

---

## Files read (for reference — all paths relative to repo root, `main` branch)

`README.md`, `LICENSE`, `package.json`, `src-tauri/Cargo.toml`, `src-tauri/tauri.conf.json`,
`src-tauri/src/models.rs`, `src-tauri/src/settings.rs`, `src-tauri/src/providers/mod.rs`,
`src-tauri/src/providers/registry.rs`, `src-tauri/src/providers/detection.rs`,
`src-tauri/src/provider_environment.rs`, `src-tauri/src/refresh_loop.rs`, `src-tauri/src/policy.rs`,
`src-tauri/src/service.rs` (partial — the refresh/scheduling functions only, not per-provider
mappers), `src-tauri/src/tray_icon.rs`, `src-tauri/src/tray_presentation.rs`,
`src-tauri/src/menu_bar.rs`, `src/App.svelte` (partial), `src/lib/Dashboard.svelte`,
`src/lib/TotalSpend.svelte`, `src/lib/CustomizeProviderList.svelte`, `src/lib/ProviderIcon.svelte`,
`src/lib/providerIconPaths.ts`, `src/lib/providerNames.ts`, `src/lib/types.ts`,
`src/styles/tokens.css`, `assets/openquota-icon.svg`, `assets/openquota-tray.svg`,
`docs/providers/{claude,codex,cursor,antigravity,copilot,devin,grok,opencode,openrouter,zai,kimi,minimax}.md`.

Deliberately **not** read in depth (per task scope): any provider's `client.rs`/`mapper.rs`/`auth.rs`
fetch/parsing implementation — those are other agents' territory.
