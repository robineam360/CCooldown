# Runbook — the multi-provider arc (Cooldown: Claude + ChatGPT + Gemini)

Ordered, checkable steps to take the app from v1.4 (Claude only) to v1.5 (Claude + ChatGPT,
Gemini greyed as coming). Every design decision is already taken; nothing here waits on a
wireframe. **Each step is one fresh Claude Code session**, and each session ends by handing you
the prompt for the next one — so the runbook drives itself. Work the steps top to bottom.

**Where the detail lives.** This file says *what order, which model, and what to paste*. The
specs are the roadmap items themselves — [ROADMAP.md](ROADMAP.md), section *Multi-provider*
(CCRM-53 (Provider Model) to CCRM-57 (Provider Plumbing)) — and the approved wireframe
`design/provider-identity-wireframe.html`. Research behind the specs:
`design/research/2026-09-06-*.md`. Don't duplicate the specs here; if a spec turns out wrong,
fix it in ROADMAP.md and note it in the step's *Log* line.

## Conventions — every session reads this block first

1. **Start from the paste.** Each step has a fenced *Paste into a fresh session* block. Paste it
   verbatim into a new session started with the **Model** and **Effort** the step names
   (`/model` and `/effort` in Claude Code, or the picker in the app). The prompt tells the
   session what to read; it never needs an earlier conversation.
2. **Tests:** `./gradlew testDebugUnitTest`. Green before a step closes.
3. **Commits:** straight to `main`, subject `feat(CCRM-NN): …` / `fix(CCRM-NN): …` /
   `docs(CCRM-NN): …`, body naming any unrelated work riding along. Never stage
   `ccooldown-release.jks`, `keystore.properties`, `local.properties`.
4. **Close-out, in this order:** (a) satisfy every item in the step's *Done when* list;
   (b) set the roadmap item's **Status** line; (c) in this file, tick the step's box in the
   Progress table (☐ → ☑) and write one line after its **Log:**; (d) commit — the tick rides in
   the same commit as the work.
5. **Handover — the last thing every session does.** After the commit, the session's **final
   message** must contain, in this order: what changed in one paragraph; anything Robin has to
   do himself before the next step (a sign-in, a screenshot to approve, the Mac); then the
   **next step's complete paste block copied from this file**, headed by its **Model** and
   **Effort** lines, so it can be pasted straight into a fresh session. If this step's outcome
   changes the next step (for example the device endpoint returned 404 in Step 2), **edit the
   next step's prompt in this file first, commit that too, then print the edited prompt.**
   If the next step is one Robin does alone (a screenshot, a sign-in), say so and print the
   prompt for the step after it. When every step is ticked, say the arc is complete and print
   nothing further.
6. **Wireframe gate.** Working agreement 2 is already satisfied for this arc by the approved
   rev B. If a session wants to draw something the wireframe doesn't show, it stops and asks
   Robin first, in one AskUserQuestion, naming the file to open.
7. **Effort scale used here:** *low* = mechanical, follow the text; *medium* = ordinary
   implementation with tests; *high* = protocol or research judgment where being wrong is
   expensive. Robin can raise a level any time; don't lower one.

## Progress

| Step | Item | Model | Effort | Needs Robin | Status |
|---|---|---|---|---|---|
| 0 | Commit the current tree | Haiku (or Sonnet) | low | 1 min | ☑ |
| 1 | CCRM-53 (Provider Model) | Sonnet | medium | no | ☑ |
| 2 | CCRM-54 (ChatGPT Account) part 1 — source, device flow, payload capture | Opus | high | one sign-in on the phone | ☑ |
| 3 | CCRM-56 (Provider Identity) — rename, icon, marks, accents, Add-account sheet, hidden windows | Sonnet | medium | approve the icon at 48 dp | ☑ |
| 4 | CCRM-54 (ChatGPT Account) part 2 + CCRM-57 (Provider Plumbing) — the ChatGPT account on every surface | Sonnet | medium | no | ☐ |
| 5 | Device pass on the Fold 7 | Sonnet | medium | phone in hand | ☐ |
| 6 | Release v1.5 — README, guide, brochure, tag | Sonnet | medium | keystore, upload | ☐ |
| A | *Any time:* CCRM-55 (Antigravity Account) spike — terminal on the Mac | Sonnet | low | Antigravity signed in on the Mac | ☐ |
| B | *After A, if real data:* CCRM-55 (Antigravity Account) design + wireframe | Opus | high | wireframe review | ☐ |

Steps 1 and 3 do not depend on each other and can run in parallel sessions. 2 needs 1. 4 needs
1, 2 and 3. Do A whenever the Mac is free; it does not block 1–6. The handover after Step 1
prints Step 2; the handover after Step 2 prints Step 3; and so on down the table.

---

## Step 0 · Commit the current tree

**Model:** Haiku (Sonnet is fine) · **Effort:** low

The working tree holds the roadmap rewrite, the CLAUDE.md scope change, this runbook, the
approved wireframe and the research reports — plus Robin's earlier uncommitted CCRM-31 (Combined
Total) / CCRM-52 (Spend Meter) roadmap edits and `design/combined-total-wireframe.html`.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 0. Do Step 0: confirm with
`git check-ignore` that ccooldown-release.jks, keystore.properties and local.properties are
ignored; run git status and list what will be committed; tick Step 0 in the RUNBOOK.md Progress
table and write its Log line; then `git add -A` and commit with this message exactly:

docs: multi-provider arc — CCRM-53…57, Cooldown identity, runbook

Roadmap section, approved provider-identity wireframe (rev B), research reports,
CLAUDE.md scope change, RUNBOOK.md. Rides along: CCRM-31 (Combined Total) rev B notes
and CCRM-52 (Spend Meter) filing, plus design/combined-total-wireframe.html.

Push to main. Then follow the runbook's Handover rule: print Step 1's Model and Effort lines
and its complete paste block from RUNBOOK.md.
```

**Done when:** `git status` is clean and `git log -1` shows the commit. **Log:**

2026-09-06 — confirmed `ccooldown-release.jks`, `keystore.properties`, `local.properties` all
gitignored (`.gitignore` lines 7, 21, 22). Committed CLAUDE.md, ROADMAP.md, RUNBOOK.md, the two
design wireframes and design/research/. GitHub push protection caught two real Google OAuth
client secrets quoted verbatim in the research (Antigravity's and Gemini CLI's own public
installed-app credentials) — redacted the secret portions in
design/research/2026-09-06-openquota-antigravity.md and
design/research/2026-09-06-phone-feasibility.md, each pointing to where to read the real value
(OpenQuota's src-tauri/src/providers/antigravity/client.rs GOOGLE_CLIENT_SECRET_PARTS, or
google-gemini/gemini-cli's packages/core/src/code_assist/oauth2.ts, or your own Antigravity
keychain entry). Pushed to main.

---

## Step 1 · CCRM-53 (Provider Model)

**Model:** Sonnet · **Effort:** medium

Pure logic. No UI, no wireframe. Adds `Provider` to `Profile`, the `UsageSource` seam,
`Credentials.accountId`, the duration-based window classifier, the credits widening, tests.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 1 — then ROADMAP.md section
"Multi-provider", item CCRM-53 (Provider Model), in full. Build exactly what that item specifies
and nothing visible: the Provider enum, the provider field on Profile with registry persistence
("v", absent → CLAUDE), the data/source/ UsageSource seam with ClaudeSource wrapping the existing
ApiClient/UsageParser code untouched, Credentials.accountId, classifyWindow, the SpendCredits
`unlimited` widening, the [poll][provider:key] log prefix rule, and every test the item names.
Sources.of(CHATGPT/ANTIGRAVITY) throw NotImplementedError for now. A Claude account must behave
byte-identically: do not touch OAuthSignIn, the authorize URL, or the User-Agent rules. Run
./gradlew testDebugUnitTest until green. Close the step per the runbook's Close-out rule
(Status line "Done (date)" with a two-line summary, tick, Log, commit as feat(CCRM-53): …), then
follow the Handover rule and print Step 2.
```

**Done when:**
- ☐ All existing tests pass unchanged; the new tests named in the item exist and pass.
- ☐ `profiles` prefs JSON carries `"v":"claude"` after any write (check via a unit test on
  `encode`).
- ☐ A release build over the live install still polls all Claude accounts with identical log
  lines (Robin can defer this check to Step 5 if the phone isn't to hand — note it in the Log).
- ☐ Status line updated, ticked, committed.

**Log:**

2026-09-06 — built the `Provider` enum, the `provider` field on `Profile` (registry-persisted
as `"v"`, absent → `CLAUDE`), the `data/source/` `UsageSource` seam with `ClaudeSource`
wrapping `ApiClient`/`UsageParser` untouched, `Credentials.accountId`, `classifyWindow`, the
`SpendCredits.unlimited` widening, and the `[poll][provider:key]` log prefix (computed in
`AppLog.log`, so `AppLog.formatLine`'s pinned shape and its test are untouched). All named
tests added and green alongside the full existing suite. The release-build device check is
deferred to Step 5 — no phone to hand this session.

---

## Step 2 · CCRM-54 (ChatGPT Account) part 1

**Model:** Opus · **Effort:** high · **needs Robin's phone once**

The source, the parser, the device-code flow, and the **payload capture**. Minimal UI: a
debug-section button is enough to sign in and capture; the real sheet comes in Step 4.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 2 — then ROADMAP.md section
"Multi-provider": CCRM-53 (Provider Model) for the seam that already exists and CCRM-54
(ChatGPT Account) in full, plus design/research/2026-09-06-phone-feasibility.md sections 1.A
and 1.B. Build part 1 only: ChatGptSource (constants, headers, honest User-Agent, refresh,
parseTokenResponse with id_token claim decoding, isAuthFailure 401||403), ChatGptUsageParser
with the tests the item lists (synthetic fixture from the documented shape), CodexDeviceSignIn
(start/pending/poll/exchange, persisted pending state, Unavailable on 404),
UsageRepository.completeDeviceSignIn, and the ProbeHost.CHATGPT allowlist entry. Two things to
verify against github.com/openai/codex before writing them, recording in code comments which
file you read: (1) whether the refresh grant in codex-rs/login/src/auth/manager.rs is
form-encoded or JSON; (2) which poll statuses in codex-rs/login/src/device_code_auth.rs mean
pending vs denied. Add a debug-only path: on a ChatGPT-provider account card, "Sign in with a
code" (plain sheet, no polish) and "Capture ChatGPT payload", which logs the raw usage body at
DEBUG. Never log tokens, headers or the id_token. Run the unit tests green, build a debug APK,
then stop and tell me exactly how to do the capture on the phone. When I paste the captured
body back, make it the fixture app/src/test/resources/chatgpt-usage-2026-09.json, adjust the
parser and tests to the real shape, set CCRM-54's Status to "In progress — part 1 done (date)",
and close per the Close-out rule (commit as feat(CCRM-54): part 1). If /usercode returned 404,
edit Step 4's prompt in RUNBOOK.md to build the loopback fallback first, note it in the Log,
and say so in the handover. Then follow the Handover rule and print Step 3.
```

**Robin's part, mid-session:** install the debug APK, add a ChatGPT account via the debug path,
sign in at `auth.openai.com/codex/device` with the code, tap *Capture ChatGPT payload*, export
the log from Settings → Diagnostics, paste the body into the session.

**Done when:**
- ☑ Device-code sign-in completed on the phone with Robin's own account.
- ☑ Real payload captured and committed as the test fixture; parser tests run against it.
- ☑ The account polls on the normal schedule; `[poll][chatgpt:pN]` lines appear; no token
  material in the log.
- ☑ Status line updated, ticked, committed.

**Log:**

2026-09-06 — built `ChatGptSource` + `ChatGptUsageParser`, `CodexDeviceSignIn`,
`UsageRepository.completeDeviceSignIn` / `captureUsagePayload`, `ProbeHost.CHATGPT` and a
`UsageSource.planFrom` hook (null by default, so Claude is untouched). Two facts verified
against `openai/codex` before writing, recorded in code comments: the refresh grant is
**JSON**, not form-encoded as `design/research/2026-09-06-phone-feasibility.md` recorded —
`request_chatgpt_token_refresh` in `login/src/auth/manager.rs`; the code *exchange* at the
same URL really is form-encoded, so the two differ — and in `poll_for_token`
(`login/src/device_code_auth.rs`) **403 and 404 both mean pending**, with no distinct
denied status, so `Poll.Denied` is our name for the single terminal bucket. Note 404 means
the opposite thing on `/usercode`, where it means the flow is switched off; only `start`
raises `Unavailable`.

**`/usercode` did not 404** — device sign-in is live for the Codex client id, so Step 4
stands unedited and the loopback fallback is not needed. Signed in on the Fold 7 over
wireless adb on a release-signed build (the debug unlock is a runtime 7-tap, so no
debug-signed APK and no uninstall — the three Claude accounts and their history were
untouched), captured a real Plus payload, and it is now
`app/src/test/resources/chatgpt-usage-2026-09.json`. **The body carries `user_id`,
`account_id` and `email`** — redacted in the fixture, with a test guarding it, since this
is a public repo. Real-shape surprises: both windows present on Plus (the July 5-hour
suspension is not universal); `credits.balance` is a JSON *string*; `additional_rate_limits`
null; five undocumented sibling keys, none of them readings.

The device check found a real defect the unit tests could not: `Snapshot.data` re-parses
the cached body on read and was hardcoded to Anthropic's `UsageParser`, so the account
fetched HTTP 200, wrote "Last success", and showed "No data yet" everywhere at once. Fixed
by giving `Snapshot` a `provider` (default `CLAUDE`) and routing through `Sources.of`;
`SnapshotTest` is the regression, and CCRM-53 (Provider Model)'s Status carries the
amendment. Reinstalled and confirmed on the phone: 9% / 79% with correct resets, and
`[poll][chatgpt:p5] auto → OK` with no token material. 321 tests green.

**Follow-up, same day:** the fixture was redacted but the *log* wasn't — the capture
button had written the real body, email and all, to `app-log.txt`, whose Share button
exists to send it to someone else. Added `AppLog.redactPayload`, a recursive scrub of
identifying keys (plus a free-text email regex for bodies that aren't JSON), applied to
the capture line, to the `HttpResult` the debug card renders for copying, and to "Show
last raw response". Shape survives, identity doesn't. The log already on the phone was
rewritten in place — all 354 lines kept, only the one payload redacted — and a fresh
capture confirmed `"email":"[redacted]"` on device. `AppLog`'s hard-rule comment now says
personal data as well as tokens: "carries no token material" was the test that let this
through, and it was the wrong test.

Two things left on the phone deliberately: the debug-made ChatGPT account (labelled
"Account 4", key `p5`) which Steps 4 and 5 need, and the app log level on **Debug**.
Scaffolding added beyond the brief, all debug-gated: a `+ Add ChatGPT account` button —
there is no other way to make one before CCRM-56 (Provider Identity) builds the real
Add-account sheet — and the endpoint probe's host button now cycles all three hosts
instead of flip-flopping two. For part 2: the sheet's "code expires in 14m" is computed
once and does not tick down; the real sheet needs a live countdown.

---

## Step 3 · CCRM-56 (Provider Identity)

**Model:** Sonnet · **Effort:** medium · **Robin approves the icon at 48 dp once**

Everything visible that isn't ChatGPT-specific: the rename to **Cooldown**, the three-sand
hourglass, the provider marks, the three-level accent with the per-account override, the
Add-account sheet (Gemini greyed), hidden windows. The wireframe is approved; build to it.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 3 — then ROADMAP.md section
"Multi-provider", item CCRM-56 (Provider Identity) in full, then open
design/provider-identity-wireframe.html and read its Decisions and "Rev B review" tables — that
wireframe is approved; build to it and do not redesign. Work the item's six "Build" steps in
order: 1 the name (the copy sweep with the line anchors listed); 2 the icon (the three
vector-drawable edits — render the launcher icon at 48 dp light, dark and monochrome, show me
the screenshot in one AskUserQuestion, and wait for my approval before continuing); 3 the marks
(ui/ProviderMark.kt and three hand-traced single-colour vector drawables of the public Claude,
OpenAI and Gemini marks, with the brand page linked in each file header; sizes 20 / 14 / 28 dp);
4 the accents (three-level resolution, the "Per provider" swatch, ⋮ → Accent colour…, the eight
call sites, ink pace ticks on multi-account faces); 5 the Add-account sheet; 6 hidden windows.
Copy that only Claude's sign-in needs stays as it is. Run ./gradlew testDebugUnitTest green,
close per the Close-out rule (commit as feat(CCRM-56): …), then follow the Handover rule and
print Step 4.
```

**Done when:**
- ☑ Robin approved the 48 dp icon screenshot (light, dark, monochrome).
- ☑ App label reads *Cooldown* on the launcher and top bar; About names four trademarks.
- ☑ Settings theme grid leads with *Per provider*; ⋮ on an account shows *Accent colour…*.
- ☑ *+ Add account* opens the three-row sheet with Gemini greyed.
- ☑ A Claude account still looks pixel-identical with the picker untouched (absent
  `themeColor` key still resolves through `Palette.accentName` to `Provider.CLAUDE.themeName`
  = "Claude Orange", byte-identical to the old hardcoded default).
- ☑ Tests green (333 cases), Status updated, ticked, committed.

**Log:**

2026-09-06 — built all six steps. **1 name:** the copy sweep, plus the tap-target pair
generalised from "app"/"claude" to "app"/"provider" (`PinnedNotification.providerLaunchIntent`,
keyed off the pinned account's own provider — `Provider.appPackage` is new, `CLAUDE_PACKAGE`
now derives from it). **2 icon:** built exactly to spec, then Robin's live review asked for
"much bigger, less padding" — the mockup preview also had a rendering bug (an opaque HTML
backdrop bleeding through the rounded corners on the light tile, an artifact of the preview
harness, not the real asset). Fixed by moving the backdrop inside the SVG and wrapping the
real vector's content in a `<group android:scaleX="1.3" android:scaleY="1.3" pivotX/Y="54">` —
approved on the second render; recorded in ROADMAP.md as "rev C". **3 marks:** could not
safely recall exact brand path data from memory (one attempt at transcribing the OpenAI knot
from memory produced garbled, wrong-looking output when caught in review) — switched to
fetching real reference artwork: Simple Icons' CC0 "Claude" and "Google Gemini" traces
(v16.30.0, verified against jsdelivr), and Wikimedia Commons'
`File:OpenAI logo 2025 (symbol).svg` for the OpenAI blossom mark (re-centred into the 24dp
viewport via a translate group — Android vector drawables have no viewBox offset — path data
itself untouched). **4 accents:** `Palette.accentName` added as a thin wrapper over a new
pure `Palette.resolveAccent(override, global, providerTheme)` — this repo has no Robolectric,
so the pure split is what let `AccentResolutionTest` exist at all. `ChatGPT Green`/`Gemini
Blue` live in `Palette.options` (so `Provider.themeName` resolves) but are excluded from both
colour grids via a new `Palette.selectableOptions` — picking a provider's own colour manually
isn't a choice either grid was meant to offer. Found in passing: CCRM-51 (Rails Gauge) already
made every ring/bar pace tick neutral ink app-wide, so decision 7 needed no code change on
Android — `RingRenderer` and the pinned panel's bar ticks were already there. **5 Add-account
sheet:** wired `repo.addProfile(provider=...)` to a real `ModalBottomSheet`; picking a row now
auto-starts that provider's sign-in on the freshly minted card via a `LaunchedEffect(Unit)`
gated on a parent-tracked `autoStartProfileKey`. Un-gated ChatGPT's "Sign in with a code"
button from `debugUnlocked` and removed the debug-only "+ Add ChatGPT account" button — both
were explicitly scaffolding "until CCRM-56 builds the real Add-account sheet" per CCRM-54 part
1's own log; the debug-only *payload capture* button stays, per RUNBOOK Step 4. **6 hidden
windows:** `ProfileScreen`, `HistoryScreen`'s 5h/7d toggle, and the Pace widget's on-face
toggle all gained the same `hasSession`/`hasWeekly` gate.

**Deferred, noted in ROADMAP.md rather than guessed:** the provider mark inside the four
bar-face widgets' own baked-in-string labels, and on the pinned panel's folded condition
strips (would need `Conditions.Condition` to carry a `Provider` through several construction
sites) — both are decision-7/build-note details, not numbered Build-step deliverables, and
neither blocks Step 4.

---

## Step 4 · CCRM-54 (ChatGPT Account) part 2 + CCRM-57 (Provider Plumbing)

**Model:** Sonnet · **Effort:** medium

The ChatGPT account on every surface, plus the long tail of Claude-only assumptions.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 4 — then ROADMAP.md section
"Multi-provider": CCRM-54 (ChatGPT Account) "Build — part 2, the surfaces" and CCRM-57
(Provider Plumbing) in full, and open design/provider-identity-wireframe.html sections 4–6
(approved). Part 1 of CCRM-54 is built (ChatGptSource, CodexDeviceSignIn, the real fixture) and
CCRM-56 (Provider Identity) has landed the marks, accents, Add-account sheet and hidden-window
rule. Build: the real device-code sheet with its five states, replacing the debug path; the
ChatGPT account card (mark, plan chip with no multiplier, no "expires around", Sign in with a
code / Refresh / Clear, no backup/paste/QR); the main-screen tab (Spark rows as model caps,
"$X balance" credits copy); Ring/Bar "No 5-hour window on this account"; the Pace widget toggle
hiding the absent side; pinned headline and tile fallback to the 7-day window; and every
CCRM-57 item (provider-aware ErrorKind copy, the Quick Links table, PlanChip tier=null for
non-Claude, Window Pings gated to Claude, expiry lines gated on refreshExpiresAt > 0,
contract-test copy). Remove the debug capture button. Tests green; set CCRM-54 and CCRM-57 to
Done; close per the Close-out rule (commit as feat(CCRM-54): …), then follow the Handover rule
and print Step 5.
```

**Done when:**
- ☐ Device-code sheet shows waiting / expired / denied / unavailable / done correctly.
- ☐ ChatGPT tab shows real numbers; credits card shows a balance or is hidden.
- ☐ Errors on a ChatGPT account say "OpenAI", never "Anthropic".
- ☐ Tests green, both Status lines updated, ticked, committed.

**Log:**

---

## Step 5 · Device pass on the Fold 7

**Model:** Sonnet · **Effort:** medium · **phone in hand**

Run the device-pass lists from CCRM-54 (ChatGPT Account) and CCRM-56 (Provider Identity) on a
release-signed build over the live install, cover screen, **Huge number** pinned style first.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step 5 — then the "Device pass"
bullets of ROADMAP.md items CCRM-54 (ChatGPT Account) and CCRM-56 (Provider Identity). Build a
release-signed APK, install it over the live install on the Fold 7 via wireless adb, and walk
both lists with me one state at a time, capturing a screenshot for each (screencap needs -d;
the inner screen captures black while folded). Record each outcome as a table row at the foot
of design/provider-identity-wireframe.html the way design/multi-account-wireframe.html does;
file any defect as a new CCBG in BUGS.md with the next free number; update both items' Status
lines. Close per the Close-out rule (commit as docs(CCRM-56): device pass), then follow the
Handover rule and print Step 6.
```

**Done when:**
- ☐ Every state in both lists is marked seen or explicitly "not seen, because …".
- ☐ Defects filed as CCBG items; nothing severe left open.
- ☐ Ticked, committed.

**Log:**

---

## Step 6 · Release v1.5

**Model:** Sonnet · **Effort:** medium · **Robin signs and uploads**

Follow [RELEASING.md](RELEASING.md). The docs change is the big one: the README risk box grows
an OpenAI paragraph, the hero and disclaimer say Cooldown, and the guide and brochure regenerate.

**Paste into a fresh session:**

```
Read CLAUDE.md, RELEASING.md, then RUNBOOK.md — its Conventions block and Step 6 — then
ROADMAP.md section "Multi-provider" for what shipped (CCRM-53 (Provider Model), CCRM-54
(ChatGPT Account), CCRM-56 (Provider Identity) and CCRM-57 (Provider Plumbing) Done; CCRM-55
(Antigravity Account) blocked and greyed in the app). Prepare v1.5: bump versionName and
versionCode; update README.md (name Cooldown, hero copy, the unofficial notice naming Anthropic,
OpenAI and Google, a second risk paragraph for the ChatGPT path mirroring the Anthropic one —
own token, the Codex CLI's client id, an undocumented endpoint, read-only, honest User-Agent);
regenerate the user guide and brochure from docs/src per RELEASING.md with a ChatGPT sign-in
section; write the release notes. Stop before signing and uploading — I do those — and tell me
the exact commands. After I confirm the release is published, close per the Close-out rule
(commit as "v1.5 — CCRM-53/54/56/57 ship: Cooldown, ChatGPT accounts", tag), then follow the
Handover rule: if Step A is still unticked print it, otherwise say the arc is complete.
```

**Done when:**
- ☐ README, guide PDF, brochure PDF regenerated and reviewed.
- ☐ Signed APK built by Robin, GitHub release published, update check on a phone sees v1.5.
- ☐ Ticked, committed and tagged.

**Log:**

---

## Step A · CCRM-55 (Antigravity Account) spike — any time, on the Mac

**Model:** Sonnet · **Effort:** low · **Antigravity signed in on the Mac**

No app code. Answers the one question that blocks Gemini: does a token minted outside the
Antigravity IDE return real quota fractions from Google's cloud endpoint? The exact commands
are in the item's "The spike" bullet.

**Paste into a fresh session (on the Mac):**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step A — then ROADMAP.md item
CCRM-55 (Antigravity Account), the bullet "The spike", and
design/research/2026-09-06-openquota-antigravity.md section 1b. Antigravity is signed in on
this Mac. Walk the four spike steps with me in the terminal: read the refresh token from the
Keychain item (service gemini, account antigravity), refresh it with curl against
oauth2.googleapis.com using the client id and secret from the research file, quit Antigravity
and agy, POST retrieveUserQuotaSummary with no IDE running, and tell me whether the four buckets
carry real fractions or all 1.0. Redact tokens and save the response bodies to
design/research/2026-MM-DD-antigravity-spike.md with a one-paragraph verdict. Never paste a
token into a file or into chat. Update CCRM-55's Status line with the verdict; close per the
Close-out rule (commit as docs(CCRM-55): spike); then follow the Handover rule — print Step B
if the fractions were real, otherwise say Step B does not apply and CCRM-55 stays blocked.
```

**Done when:**
- ☐ Verdict recorded: real fractions (→ Step B) or placeholders (→ CCRM-55 (Antigravity
  Account) stays blocked; the Mac-relay route becomes the only option and gets its own roadmap
  item).
- ☐ Ticked, committed.

**Log:**

---

## Step B · CCRM-55 (Antigravity Account) design — only if Step A found real data

**Model:** Opus · **Effort:** high · **wireframe review by Robin**

Pick the auth route (refresh-token paste is the best phone-only option per the item), design
the four-lane Gemini surface and the new states (not started, unknown, stepped signal, untouched
pool hidden), extend `UsageData` with `lanes`, wireframe it, and get approval before building —
working agreement 2 applies in full here.

**Paste into a fresh session:**

```
Read CLAUDE.md, then RUNBOOK.md — its Conventions block and Step B — then ROADMAP.md item
CCRM-55 (Antigravity Account) and the spike verdict in design/research/. Step A found real
fractions. Propose the auth route with reasoning, then write design/antigravity-wireframe.html
mocking the Gemini tab, the Add-account row going live, the refresh-token paste (or chosen)
sign-in, and every state the item lists — Huge number pinned style first. Do not write app
code. Ask me the open questions one at a time via AskUserQuestion, each naming the file to open.
When approved, move CCRM-55 to Planned with the route recorded, append the build steps for it
to RUNBOOK.md as Steps C onwards in the same format (Model, Effort, paste block, Done when,
Log), close per the Close-out rule (commit as docs(CCRM-55): design), then follow the Handover
rule and print Step C.
```

**Done when:** ☐ Wireframe approved, CCRM-55 (Antigravity Account) Status moved to Planned with
the route recorded, Steps C+ added below, ticked, committed.

**Log:**
