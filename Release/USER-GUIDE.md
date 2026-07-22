# Claude Cooldown (CCooldown) — Android Widget

**A personal home-screen widget for your Claude plan limits — for two accounts.**
Shows the 5-hour and 7-day rolling windows for your **Personal** and **Work** profiles (including per-model caps like Fable), when each resets, how far into the week you are, forecasts *when* you'll hit a limit at your current pace, and warns you *before* you hit it — and *when* a window resets.

> Version **1.0** · released 22 Jul 2026 · sideloaded personal app, not on any store
> Download the APK from the [latest GitHub release](https://github.com/robineam360/CCooldown/releases/latest)

---

## What it looks like

**New in 0.13:**

| Usage history *(illustration)* | Pinned notification | Granular alerts |
|:---:|:---:|:---:|
| ![Usage history bars](screenshots/history-5h-dark.png) | ![Pinned notification collapsed](screenshots/pinned-collapsed.png) | ![Granular alert settings](screenshots/settings-alerts.png) |

| Pinned (expanded) | Editable profile names | Per-week history *(illustration)* |
|:---:|:---:|:---:|
| ![Pinned notification expanded](screenshots/pinned-expanded.png) | ![Profile name fields](screenshots/profile-names.png) | ![Per-week history](screenshots/history-7d-dark.png) |

> The two **Usage history** images are **illustrations** showing a full week of data. History
> is recorded as each window closes, so a fresh install starts nearly empty and fills in over
> the following days — a real screenshot today would show only a bar or two.

| Sign in on this phone 🆕 | Authorize in the browser | Paste the code, done |
|:---:|:---:|:---:|
| ![Account cards with Sign in on this phone](screenshots/signin-settings.png) | ![Claude's consent page](screenshots/signin-consent.png) | ![Finish signing in step](screenshots/signin-finish.png) |

| Personal tab | Work tab | Widget (dark) |
|:---:|:---:|:---:|
| ![Personal tab](screenshots/app-tabs-personal.png) | ![Work tab](screenshots/app-tabs-work.png) | ![Widget, dark mode](screenshots/widget-large-dark.png) |

| Widget setup (on placement) | Notifications | Two Quick Settings tiles |
|:---:|:---:|:---:|
| ![Widget setup screen](screenshots/widget-setup.png) | ![Threshold and reset notifications](screenshots/notifications.png) | ![Personal and Work tiles](screenshots/quick-settings-tiles.png) |

| Settings — accounts | Settings — About (v1.0) | Themeable (13 colors) |
|:---:|:---:|:---:|
| ![Accounts and profile names](screenshots/settings-top-dark.png) | ![About, version 1.0](screenshots/settings-bottom-dark.png) | ![Blue-theme widgets](screenshots/widget-theme-blue.png) |

| Granular alerts | In-app token guide |
|:---:|:---:|
| ![Alert controls](screenshots/settings-alerts.png) | ![Get your token screen](screenshots/token-guide.png) |

---

## 1 · Install (Galaxy Fold 7)

1. Download `CCooldown.apk` from the [latest GitHub release](https://github.com/robineam360/CCooldown/releases/latest) onto the phone. (In-app, **Settings → About → Check for updates** links you straight there.)
2. Tap the APK. When Android warns about unknown apps, **allow installs from the app you opened it with**, then tap **Install**.
   *A Play Protect "scan app?" prompt may appear — scan or install anyway; it's your own app.*
3. Open **CCooldown** and **allow notifications** when asked (needed for usage alerts).

**Updating from v0.13 onward:** just install the new APK over it — token, settings, history, and widgets all survive (releases are now signed with a permanent key).

**One-time note for the v0.12 → v0.13 update:** v0.13 moves to a permanent signing key, so this single upgrade needs an **uninstall + reinstall** (Android blocks an in-place update when the signature changes). You'll lose the old on-device history and sign-ins once; re-sign in after installing. Every update *after* v0.13 is a normal in-place install.

---

## 2 · Connect your Claude accounts (two profiles)

The app has two independent slots — **Personal** and **Work**. Since v0.12 each one signs in **right on the phone — no computer needed**:

### Sign in on this phone (the normal way) 🆕

1. Open **⚙ Settings** and tap **"Sign in on this phone"** on the profile's card.
2. If you have more than one browser, a picker appears — **choose the browser where that account is already logged in to claude.ai** (e.g. Work in Chrome, Personal in Brave). That's how you control *which* account gets connected.
3. The browser opens Claude's sign-in. Log in if needed, then tap **Authorize**.
4. The page shows a **code** — tap **Copy**, switch back to the app, tap **Paste**, then **Finish sign-in**.
5. The card turns **Active** and usage loads: *"signed in — usage fetched, polling started."*

Works for **Personal (Pro / Max)** and **Work (Team)** accounts alike. The sign-in is minted on the phone and is yours alone — no computer shares it, so nothing can rotate it away. It self-renews for about **a month** (the card shows *"Sign-in expires around …"*); when it runs out you'll get 7/3/1-day warnings, and re-signing in is the same one-minute flow via **"Re-sign in"**.

### Backup: use a computer token instead

If the phone can't open the sign-in page, each card also keeps the old method under **"Use a computer token instead"** — copying the token that the **Claude Code CLI** holds on the machine where that account is signed in. Getting the token depends on the machine's OS, not on which profile it's for:

### Getting the token on Windows

1. Press the Windows key, type `notepad C:\Users\<you>\.claude\.credentials.json` — or just open that file in Notepad (it's a hidden-ish folder; paste the path into Notepad's File ▸ Open box).
2. Select **all** the text (Ctrl+A), copy (Ctrl+C). That whole file is what you paste into the app.

### Getting the token on a Mac

The Mac CLI keeps it in the Keychain, not a file. Open **Terminal** (Cmd+Space → "Terminal") and run:

```bash
security find-generic-password -s "Claude Code-credentials" -w
```

It prints one line of JSON — that's what you paste into the app. (macOS may ask for your Mac login password to allow Keychain access — that prompt is from macOS itself, not the app.)

### If either command finds nothing

Then the Claude Code **CLI** isn't installed or isn't logged in on that machine (the Claude **desktop app** stores its login separately in an unusable encrypted form — only the CLI's token works). Fix:

1. Install the CLI — Mac/Linux: `curl -fsSL https://claude.ai/install.sh | bash` · Windows (PowerShell): `irm https://claude.ai/install.ps1 | iex`
2. Open a new terminal, run `claude`, choose **"Claude account with subscription"**, and sign in in the browser **with the right account for that profile**.
3. Retry the step above — the file/Keychain entry now exists.

### Putting it into the phone

**Easiest way — scan a QR code 🆕:** if the computer has Node.js, you can skip the clipboard entirely. Show the token as a QR code in the terminal, then tap **"Scan QR"** on that profile's card and point the camera at the screen:

```bash
# Mac
npx -y qrcode-terminal "$(security find-generic-password -s 'Claude Code-credentials' -w)"
# Windows (PowerShell)
(Get-Content "$env:USERPROFILE\.claude\.credentials.json" -Raw | ConvertFrom-Json).claudeAiOauth | ConvertTo-Json -Compress | npx -y qrcode-terminal
```

The Windows command extracts just the sign-in part first — the full credentials file can hold other logins too and outgrow what a QR code can physically carry (you'd see `Error: code length overflow`). If the Mac command ever hits that error, filter it the same way: `security … -w | jq -c .claudeAiOauth | npx -y qrcode-terminal` (needs `brew install jq`).

If the QR square doesn't fit on screen, shrink the terminal font (Cmd/Ctrl+minus) until it does. The token goes straight from the screen to the phone — it never touches a clipboard, chat app, or synced note.

**Or the clipboard way:** get the copied JSON onto your phone's **clipboard** privately (Link to Windows clipboard sync, Quick Share, a synced note — **delete it afterwards**), open **⚙ Settings**, and tap **"Paste from clipboard"** on that profile's card.

Either way, the app validates the token with a live call and starts polling — you should see *"token added — usage fetched, polling started."* You can set up one profile or both — polling, alerts, widgets, and tiles all work per profile.

All of these steps are also inside the app: tap **"How do I get my token?"** on any account card for a step-by-step guide (macOS / Windows / Linux), including what to do when a token expires.

Tokens are stored **encrypted on the phone only** (Android Keystore) and are never sent anywhere except Anthropic's own API.

> **If a machine's Claude Code ever re-logs-in**, that profile's token may stop working (refresh tokens rotate — the phone and that machine share the token). The app notifies you with a "re-auth needed" alert — just re-copy from that machine and re-paste that one profile. **Or simply switch to "Sign in on this phone" (above), which avoids the problem entirely.**

### Legacy: give the phone its own sign-in *via a computer* (superseded by v0.12)

*You no longer need this — "Sign in on this phone" gives the phone its own independent sign-in with zero computer steps.* It's kept for the rare case where the phone can't complete the browser flow. The idea: **park the computer's sign-in → sign in fresh (that sign-in becomes the phone's) → scan it → give the computer its original back.** The phone then renews independently. Don't run `claude` between the scan and the restore.

**Windows (PowerShell)** — close every Claude Code terminal first:

```powershell
# 1. Park the computer's sign-in
Rename-Item "$env:USERPROFILE\.claude\.credentials.json" ".credentials.backup.json"
# 2. Sign in fresh: run claude → "Claude account with subscription" → browser sign-in → exit
claude
# 3. Show the phone's new token as a QR → app → that profile's card → Scan QR
(Get-Content "$env:USERPROFILE\.claude\.credentials.json" -Raw | ConvertFrom-Json).claudeAiOauth | ConvertTo-Json -Compress | npx -y qrcode-terminal
# 4. Hand the computer its own sign-in back
Remove-Item "$env:USERPROFILE\.claude\.credentials.json"
Rename-Item "$env:USERPROFILE\.claude\.credentials.backup.json" ".credentials.json"
```

**Mac (Terminal)** — the Mac stores credentials in the Keychain, so "parking" is backup-and-delete (macOS will ask to allow Keychain access — that prompt is macOS itself). Quit every Claude Code session first:

```bash
# 1. Park the Mac's sign-in
security find-generic-password -s "Claude Code-credentials" -w > ~/cc-backup.json
security delete-generic-password -s "Claude Code-credentials"
# 2. Sign in fresh: run claude → "Claude account with subscription" → browser sign-in → exit
claude
# 3. Show the phone's new token as a QR → app → that profile's card → Scan QR
npx -y qrcode-terminal "$(security find-generic-password -s 'Claude Code-credentials' -w)"
# 4. Hand the Mac its own sign-in back, and delete the temp backup
security delete-generic-password -s "Claude Code-credentials"
security add-generic-password -a "$USER" -s "Claude Code-credentials" -w "$(cat ~/cc-backup.json)"
rm ~/cc-backup.json
```

Afterwards the account card should stay "Active" through refreshes indefinitely. The phone's own sign-in still has a hard expiry — the app's 7/3/1-day "sign-in expiring soon" warnings will tell you when to renew (and the easiest renewal is just **"Sign in on this phone"**).

---

## 3 · Reading the screens

The main screen has **two tabs — Personal | Work** — swipe horizontally (or tap the tab) to switch. Both the app and the widgets share the same layout per profile:

- **5-hour window card** — "X% used", the bar, and a split reset line:
  *left* `Resets in 4h 47m` · *right* `Resets at Thu 11:45 PM`
- **7-day window card** — three bars sharing one reset footer (they all reset together):
  - **All models** — your total weekly usage
  - **Fable** (and any other per-model cap the API reports)
  - **Days elapsed** — how far through the 7-day window you are (time, not usage)
- **Pacing trick:** compare *Days elapsed* against the usage bars. Usage **behind** Days elapsed → you have headroom. Usage **ahead** of it → you may run out before the weekly reset.
- **Burn-rate forecast:** once a window has ~20 minutes of history, each card grows a **sparkline** of that window's usage curve (solid = what actually happened, dashed = where it's heading) and a plain-words projection: *"At this pace: 100% at Thu 2:40 PM — 1h 20m before the reset"* in red when you're on course to hit the wall, or *"At this pace: ~62% when the window resets"* in grey when you're safe. The history is collected from the app's own polls and stays on the phone.

**Bar colors:** your chosen theme color normally → **yellow** above 80% → **orange** above 90% → a clear **warning-red** at 100%. The warning hues are deliberately vivid so they never blend into the muted Claude Orange theme. Only the bars shift color; text stays neutral.

### Usage history 🆕

Tap the **calendar icon** in the top bar to open **Usage history** — a scrollable list of bars, one per window, per profile:

- **5-hour mode** — one bar for every 5-hour session that had usage, newest first, each labelled with its day and start time (e.g. *Mon 09:15*). The bar length is the session's peak, coloured by the warning ladder, and **red when the session hit 100%**. A summary line shows *"N sessions · M maxed out"*, and the **‹ ›** arrows page back through earlier weeks.
- **7-day mode** — one bar per weekly window, so you can see how each week compared.

History is written as each window **closes**, so it fills in going forward — a fresh install starts nearly empty (just the current "now" session) and builds up over the following days.

### Pinned notification 🆕

Optionally keep an **always-on, silent notification** in your shade with a **status-bar gauge icon that fills as you burn your 5-hour window**. Collapsed, it shows the profile, reset countdown, and 7-day %; expanded, it adds the 7-day and per-model bars with a one-tap **Refresh**. It follows your theme and turns orange, then red, near the limit, and refreshes on every poll. Turn it on in **Settings → Pinned notification**, choose which profile it tracks, and pick the status-bar icon style (Ring / Pie / Battery / Number).

**Two widget types** (the moment you drop either one, a **Widget setup** screen appears asking which **profile** it should show):

- **Full widget (4×3 default)** — everything: 5-hour, 7-day bars, days elapsed, reset times
- **Single-bar widget (2×1)** — one bar of your choice: 5-hour, 7-day all models, per-model (Fable), or Days elapsed. Place as many as you like, mixing profiles freely.

**Widget specifics:**

- **↻ icon (top right)** = refresh now (rate-limited to once per 3 minutes — the API forbids faster polling)
- **Tapping anywhere else** opens the app
- **Footer** shows the weekly reset (left) and "updated Xm ago" (right/below). If it turns **amber**, the last fetch failed or data is >45 min old — the numbers shown are the last known good ones, never blank
- **Resize it** (long-press → drag handles): small = 5-hour only · medium = 5-hour + 7-day total · large (default) = everything

Data refreshes automatically every **15 minutes** (configurable, minimum 5).

---

## 4 · Usage alerts 🔔

All alerts are prefixed with the profile ("Personal: …" / "Work: …"):

| Alert | When |
|---|---|
| **5-hour window at 80%** | Time to pace yourself |
| **5-hour window at 95%** | You're about to be cut off |
| **7-day window at 90%** | Weekly budget nearly spent |
| **Per-model cap at 90%** 🆕 | A model's own weekly cap (e.g. Fable) is nearly spent — previously these bars could fill up silently |
| **Window reset** | The 5-hour or 7-day window just reset — Claude is fresh again (the app schedules a check right at the known reset moment, so this lands within ~2 minutes) |
| **Re-auth needed** | That profile's token stopped working — paste a fresh one. Also fires when renewals have been failing for 6+ hours straight (a dead token can masquerade as a temporary error). |
| **Sign-in expiring soon** 🆕 | 7 / 3 / 1 days before the known sign-in expiry — re-paste at your convenience instead of getting cut off |
| **Usage data is stale** 🆕 | Polls have been failing for 6+ hours — the widget numbers are old and nothing else would tell you |

Each alert fires **once per window/episode** and re-arms afterwards. Tapping a notification opens the app **on that alert's profile tab** (a Work alert lands on the Work tab); re-auth and stale alerts dismiss themselves when the problem is fixed.

**Granular controls (Settings → Notifications) 🆕** — no more all-or-nothing:

- **Usage warnings** — tap the percentage **chips** to choose exactly which thresholds warn you, per window: 5-hour (80 / 90 / 95), 7-day (75 / 90), and per-model caps (75 / 90). Deselect them all to silence a window.
- **Reset pings** — set each window's reset notification to **Off / If busy / Always**. *"If busy"* only pings when that window had actually reached 80% before it reset — which kills most of the reset-notification noise.
- **Per-profile** — mute all of a profile's usage warnings and reset pings with one switch.
- **Sign-in alerts** and **Stale data alerts** are separate toggles.
- Plus a shortcut to Android's per-channel notification settings.

Existing settings carry over: if you'd previously turned the old toggles off, the matching new controls start off too.

---

## 5 · Quick Settings tiles (one per profile)

See usage from inside any app: pull down the shade — **"Personal 94% / 7d 14%"** and **"Work 37% / 7d 62%"** are separate tiles.

**Add them once:** pull the shade fully down → **✏ edit** → drag **Claude Personal** and/or **Claude Work** into your active tiles. Tapping a tile opens the app on that profile's tab; opening the shade also nudges a background refresh (skipped when the data is under 3 minutes old, so shade-flicking doesn't spam the API).

---

## 6 · Settings reference

| Setting | What it does |
|---|---|
| **Account cards (Personal / Work)** | **"Sign in on this phone"** 🆕 runs the browser sign-in (with a browser picker when you have several — sign each account in via the browser where it's logged in). The old paste/QR path lives under "Use a computer token instead". Once signed in: status chip (Active / Needs re-auth), plan badge (Pro / Max / Team), last-checked time with a ↻ check-now button, auto-renew countdown, last auto-renewed time, "Sign-in expires around <date>" (≈30-day estimate for phone sign-ins; exact date for pasted tokens), added date, token tail, rate-limit backoff status, Re-sign in / Clear. "How do I get my token?" opens the in-app backup-method guide. |
| **Profile names** 🆕 | Rename the two profiles (default *Personal* / *Work*) to anything you like — the names flow through tabs, widgets, tiles, and notifications. Clear a field to restore the default. |
| **Check usage every** | Poll cadence presets: 5 / 15 / 30 / 60 min (default 15) — saves on tap |
| **Usage warnings** 🆕 | Per-window threshold chips (5-hour 80/90/95, 7-day 75/90, per-model 75/90) — pick exactly what warns you |
| **Reset pings** 🆕 | Per window: Off / If busy (only if it reached 80%) / Always |
| **Profile alerts** 🆕 | One switch to mute a whole profile's warnings and reset pings |
| **Sign-in alerts** / **Stale data alerts** | Separate toggles for token expiry/re-auth and long-stale data |
| **Pinned notification** 🆕 | Turn on the always-on usage notification, choose its profile, and pick the status-bar icon (Ring / Pie / Battery / Number) |
| **System notification settings** | Opens Android's per-channel controls for the app |
| **24-hour time** | Off = "Thu 11:45 PM" (default) · On = "Thu 23:45" |
| **Theme color** | 13 choices: Material You (dynamic, first dot), **Claude Orange** (default), Blue, Indigo, Cyan, Teal, Green, Amber, Deep Orange, Red, Pink, Purple, Brown. Applies to the app and the widget bars. |
| **Widgets** | "Add widget to home screen" shortcuts for both widget types |
| **About** | Version, credits, **Share feedback** (emails robin@eam360.com), **Check for updates** (asks GitHub for the latest release and links you to it). Tap the version 7 times to unlock the debug raw-response viewer until the app is closed. |

Below the Refresh button the app shows **Last success** and **Last attempt** as "Thu 7:46 PM (12m ago)". A red status line appears only when something's wrong.

---

## 7 · Troubleshooting

| Symptom | Meaning / fix |
|---|---|
| Widget footer is **amber** | Data is stale (failed fetch or >45 min old). Usually temporary; self-heals on the next poll. |
| **"Re-auth needed"** | The token and its refresh both failed. Tap **"Re-sign in"** on that card (§2) — or re-paste from a computer if you use the backup method. |
| **"Rate limited (429)"** status | The API asked us to back off; automatic retries with increasing delays (5 min → 1 h max). |
| **"Token refresh failed (HTTP 429)"** status | Usually a *dead* refresh token, not real rate-limiting — the source machine's Claude Code rotated it (Anthropic answers 429 for dead tokens). Fix: **"Sign in on this phone"** (§2), which gives the phone its own sign-in so this can't recur. *(Historical note: through v0.11 the app itself could trigger a deterministic 429 on every renewal — fixed in v0.12, see §9.)* |
| Widget says **"No data yet"** | No successful fetch so far — tap ↻, or open the app and check the status line. |
| Alerts never appear | Check notification permission and the **Usage alerts** toggle. |
| Anything else | Settings → **Show last raw response** + the red status line tell the whole story. |

---

## 8 · Privacy & good-to-know

- **No servers, no telemetry, no analytics.** The app talks only to `api.anthropic.com` (usage), `platform.claude.com` (sign-in code exchange + token refresh), and `claude.com` (the sign-in page in your browser) — the same endpoints Claude Code uses.
- Token lives in **Android-Keystore-encrypted storage**; cached usage numbers contain nothing sensitive.
- **Backup (since v0.13):** Android Auto Backup now preserves your usage history and settings across a reinstall or a new phone. The **encrypted sign-in token is deliberately excluded** from backup (it's sealed to this device's Keystore and can't be restored anyway) — after a restore you simply sign in again.
- Polling is deliberately gentle (15-min default, 3-min manual floor, exponential backoff on 429s).
- Heads-up: reading the usage API with a Pro token outside Claude Code/claude.ai is not covered by Anthropic's consumer ToS. Personal-use risk was accepted when this was built.

---

## 9 · Version history

- **1.0** — **First official release.** The v0.1–v0.14 beta, now official and shared with the team — everything below in one polished build: live 5-hour & 7-day windows for Personal + Work, usage history, pinned notification, granular alerts, burn-rate forecast, home-screen widgets & Quick Settings tiles, on-phone sign-in — private, no servers, backed up. Prototyped and built over a weekend by **Claude Fable 5**; the native sign-in Fable declined (on cybersecurity grounds) and all the docs finished by **Claude Opus 4.8**.
- **0.14** — **Feedback, updates & distribution.** Share feedback now opens an email to robin@eam360.com (was WhatsApp); new **Check for updates** in About queries the public GitHub Releases API and links to the latest release; the APK is no longer committed to the repo — it ships only as a GitHub Release asset.
- **0.13** — **History, pinned notification & finer alerts.** New **Usage history** screen: scrollable bars, one per 5-hour session (day + start time, red when it hit 100%) with a week pager, plus a per-week view — backed by a new on-device session log kept for a year. New optional **pinned notification**: an always-on, silent status readout with a status-bar gauge icon that fills with your 5-hour usage (Ring/Pie/Battery/Number styles), expanding to the 7-day and per-model bars with a Refresh action. **Alerts are now granular** — per-window threshold chips, reset pings set to Off/If-busy/Always, and a per-profile mute — instead of three blunt toggles. **Profile names are editable.** The 100% bar colour is now a true warning-red (previously an orange that clashed with the Claude Orange theme). And **Android Auto Backup** now preserves history + settings across reinstall/new-device (the encrypted token is excluded). Releases are now signed with a permanent key, so from here on updates install in place and keep your data — the one-time cost was this upgrade needing a reinstall.
- **0.12** — **Native sign-in release.** Each account card now has **"Sign in on this phone"**: a browser-based sign-in (the same PKCE flow Claude Code uses) that needs **no computer at all** — tap, authorize in the browser of your choice (picker included, so Work and Personal can live in different browsers), paste the code, done. Works for Pro/Max and Team accounts. The sign-in is minted on the phone, so no computer can rotate it away; it self-renews for ~30 days and the card shows *"Sign-in expires around …"* with the usual 7/3/1-day warnings before it lapses. This also fixed the chronic **HTTP 429** on token exchange/renewal: Anthropic's token endpoint sits behind a firewall that rejects requests identifying themselves as `claude-code` — the app no longer sends that identity on token calls (usage calls still require it). The computer-token paste/QR method remains as a backup under "Use a computer token instead", and the desktop-paste renewal may well be steadier now too (same 429 fix applies to renewals). *(0.11 was an internal build, never released.)*
- **0.10** — QR fixes & diagnostics. The in-app token guide's Windows QR command now extracts just the sign-in object and pipes it (the full credentials file can hold other logins and overflow a QR code's ~2.9 KB capacity; piping also avoids PowerShell 5.1 mangling quotes); Linux switched to a `jq` pipe for the same reason. Refresh failures now say *why* in the status line — e.g. "Token refresh failed (HTTP 429)" (a dead/rotated refresh token) vs. a network error — instead of a blind "token refresh failed". The in-app guide's "Re-pasting often?" section now covers the Mac Keychain variant of the dedicated-sign-in ritual (full commands in this guide, §2).
- **0.9** — Forecast & QR release. The app now keeps a local history of its own polls (8 days, on-phone only) and each window card shows a **sparkline + burn-rate forecast**: when you'll hit 100% at the current pace and how long before the reset that is, or the projected percent at reset when you're safe. **Per-model weekly caps** (e.g. Fable) now fire their own 90% alert. **"Scan QR"** on the account cards imports a token straight from a QR code rendered in the computer's terminal — no clipboard needed (in-app guide has the commands). Notifications now open the app on the alert's own profile tab. Quieter on the API: the Quick Settings tiles skip their refresh when data is under 3 minutes old, and manual refreshes now also evaluate alerts immediately instead of waiting for the next background poll.
- **0.8** — Token health release. Account cards now show a plan badge (Pro/Max/Team), an auto-renew countdown, the last auto-renewed time, and "sign-in valid until <date> · Xd Xh Xm to go" (read from the pasted JSON). New notifications: sign-in expiry early warning (7/3/1 days out) and a stale-data alert after 6+ hours of failed polls; the re-auth alert now also fires when renewals have failed continuously for 6+ hours (previously a dead token could look like a permanent "will retry"), and notifications self-dismiss when fixed. Polling section shows the next automatic check; cards show rate-limit backoff. Guide explains why re-pasting happens (token rotation by the source computer) and the dedicated-sign-in workaround.
- **0.7** — Renamed to **Claude Cooldown (CCooldown)** with a new cooldown-sweep launcher icon (adaptive + themed-icon support). Settings redesigned into grouped sections: account cards with one-tap "Paste from clipboard" (no more text box), token status chip, last-checked with check-now, added date and token tail; in-app "Get your token" guide (macOS/Windows/Linux + expiry explainer); poll-interval presets; token-expiry alert toggle; add-widget shortcuts; About with credits and WhatsApp feedback; hidden debug (7 taps on the version). "Starts when a message is sent" shown for windows that haven't begun. System back now navigates instead of exiting.
- **0.6** — Two profiles (Personal + Work) with swipeable tabs, per-profile tokens/cache/backoff/alerts; widget setup screen on placement (choose profile); new single-bar widget (choose any one bar); window-reset notifications scheduled at the exact reset moment; two Quick Settings tiles; alerts prefixed with the profile name.
- **0.5** — Redesigned per feedback: grouped 7-day card (All models / Fable / **Days elapsed** pacing bar), Claude-style bars (tint track + solid fill), bar-only color shift (yellow 80 / orange 90 / red 100), split reset rows with day + 12/24-hour time setting, 13 theme colors (Claude Orange default), widget refresh icon + bigger text, widget body tap opens app, "Last success/attempt (Xm ago)" lines. Fixed a widget truncation bug (Android's 10-child RemoteViews limit).
- **0.4** — Usage alerts (80/95% session, 90% weekly, re-auth), Quick Settings tile, auto-refresh on app open.
- **0.3** — Material You polish: system corner radius, dynamic colors, dark-mode-aware statuses.
- **0.2** — Large widget layout with exact reset date-times.
- **0.1** — First working app + widget.

## 10 · For future rebuilds (dev notes)

- Source: this OneDrive folder (`ClaudeUsage/`), single-module Android project
- Stack: Kotlin · Jetpack Compose · Glance 1.1.1 · WorkManager · OkHttp · AGP 9.2.1 (built-in Kotlin 2.3.10) · min SDK 31, target 36
- Build on the Mac (signed release): `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. Signing reads the gitignored `keystore.properties` + `ccooldown-release.jks` at the repo root — **back those up; losing them means no more updates.** See `RELEASING.md`.
- Glance gotcha: RemoteViews containers max out at 10 children — keep widget blocks wrapped in nested Columns.
- If Anthropic changes the undocumented response schema, the parser ignores unknown fields; if bars go blank, check the raw JSON in the debug view first.

*Built and verified with Claude Code; v0.13 on 21 Jul 2026.*
