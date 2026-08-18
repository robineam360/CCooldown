<p align="center">
  <img src="release/screenshots/hero.png" alt="CCooldown — your Claude usage limits, right on your Android home screen" width="100%">
</p>

# CCooldown

**Claude Cooldown** — an Android home-screen widget and app that shows how much of your
Claude Pro / Max / Team usage limits you've burned through, and when your 5-hour and 7-day
windows reset. Built for people who live in Claude and keep hitting the wall mid-thought —
especially in **Claude chat and Cowork**, where (unlike Claude Code) there's no built-in way
to see how close you are.

**📱 Android only (for now)** — an iOS version is on the roadmap. iPhone folks, watch this space.

📄 **Docs:** [User Guide (PDF)](release/docs/CCooldown-User-Guide-v1.2.pdf) — the full
install → sign-in → widgets walkthrough · [Brochure (PDF)](release/docs/CCooldown-Brochure.pdf) —
a 2-page overview.

> ⚠️ **Unofficial.** This is a personal community tool. It is not affiliated with, endorsed
> by, or supported by Anthropic. "Claude" is a trademark of Anthropic, PBC.

> 🚩 **Please read before you install — this is unofficial and carries some account risk.**
> To read your usage, CCooldown signs in with the **same OAuth client as the Claude Code
> CLI** (identical client ID and scopes) and, when it fetches usage, identifies itself as
> `claude-code/<version>` against an **undocumented** endpoint
> (`api.anthropic.com/api/oauth/usage`) — deliberately, because without the CLI's identity
> the request is routed to an aggressively rate-limited bucket. In other words, it
> **impersonates the official CLI**. This is **not sanctioned by Anthropic**, and their
> consumer terms restrict using consumer OAuth tokens in third-party tools. Installing means
> accepting that Anthropic could **revoke your token or flag your account** for an unusual
> traffic pattern. What lowers the practical risk: the app is **read-only** (it never sends
> prompts or spends quota), signs in **once per install** (no repeated logins, no CLI
> subprocess), and is shared privately, not on any store. Use it at your own discretion.

## What it does

- **Home-screen widgets** (small / medium / large, plus a compact single-bar widget) showing
  the 5-hour session window, the 7-day all-models window, and per-model 7-day caps —
  with countdown and exact local reset time
- **Pinned notification** 🆕 — an optional always-on, silent notification with a status-bar
  gauge icon that fills as you use your 5-hour window; expands to show the 7-day and
  per-model bars, with a one-tap Refresh
- **Usage history** 🆕 — a scrollable bar per 5-hour session, week by week (and a per-week
  view across weeks), so you can see how many sessions you ran and which ones hit 100%
- **Two profiles with editable names** — track a Personal and a Work account side by side in
  swipeable tabs, and rename them to whatever you like
- **Quick Settings tiles** — glance at your 5h/7d percentages from the notification shade
- **Granular alerts** 🆕 — pick exactly which thresholds warn you per window (5-hour, 7-day,
  per-model), turn reset pings off / smart / always, and mute a whole profile — no more
  all-or-nothing
- **Pace chart + burn-rate projection** — every reading plotted with threshold guides and a
  forecast tail, plus a plain-words verdict ("At this pace: 100% at 2:40 PM — 1h 20m before
  the reset"), built from a local history of your own polls
- **Pay-as-you-go usage credits** — spent, total and what's left, on the main screen and as
  a widget; hidden for plans without a credit budget
- **An "even pace" line on every chart** — the diagonal from 0% at the window's start to
  100% at its reset. Stay below it and you'll finish inside your limit; cross it and the
  overshoot shades amber
- **13 theme colors** including Material You dynamic color, full light/dark support; usage
  bars shade amber then a clear warning-red as you approach 100%

## Screenshots

<table>
  <tr>
    <td align="center"><img src="release/screenshots/history-5h-dark.png" width="240" alt="Usage history, per-session bars"><br><sub><b>Usage history</b> 🆕 — a bar per 5-hour session (red = hit 100%)</sub></td>
    <td align="center"><img src="release/screenshots/pinned-collapsed.png" width="240" alt="Pinned notification, collapsed"><br><sub><b>Pinned notification</b> 🆕 — always-on gauge in your shade</sub></td>
    <td align="center"><img src="release/screenshots/settings-alerts.png" width="240" alt="Granular alert settings"><br><sub><b>Granular alerts</b> 🆕 — pick your thresholds per window</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="release/screenshots/pinned-expanded.png" width="240" alt="Pinned notification, expanded"><br><sub><b>Expanded</b> — 7-day &amp; per-model bars, one-tap Refresh</sub></td>
    <td align="center"><img src="release/screenshots/profile-names.png" width="240" alt="Editable profile names"><br><sub><b>Rename your profiles</b> 🆕 — not just Personal/Work</sub></td>
    <td align="center"><img src="release/screenshots/history-7d-dark.png" width="240" alt="Per-week history"><br><sub><b>Per-week view</b> 🆕 — one bar per 7-day window</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="release/screenshots/widget-large-dark.png" width="240" alt="Home-screen widget, dark theme"><br><sub><b>Home-screen widget</b> — 5-hour &amp; 7-day windows at a glance</sub></td>
    <td align="center"><img src="release/screenshots/widget-theme-blue.png" width="240" alt="Home-screen widgets in a blue theme"><br><sub><b>Themeable</b> — 13 accent colors (here in blue)</sub></td>
    <td align="center"><img src="release/screenshots/app-tabs-personal.png" width="240" alt="App main screen, Personal profile"><br><sub><b>The app</b> — full breakdown with exact reset times</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="release/screenshots/quick-settings-tiles.png" width="240" alt="Quick Settings tiles"><br><sub><b>Quick Settings tiles</b> — swipe down, see your %</sub></td>
    <td align="center"><img src="release/screenshots/notifications.png" width="240" alt="Usage alerts"><br><sub><b>Alerts</b> — near-limit warnings and "window has reset"</sub></td>
    <td align="center"><img src="release/screenshots/app-tabs-work.png" width="240" alt="Work profile tab"><br><sub><b>Two profiles</b> — Personal and Work, side by side</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="release/screenshots/signin-settings.png" width="240" alt="Sign in on this phone"><br><sub><b>Sign in on this phone</b> 🆕 — no computer needed</sub></td>
    <td align="center"><img src="release/screenshots/signin-consent.png" width="240" alt="Authorize in the browser"><br><sub><b>Authorize in your browser</b> — Pro, Max &amp; Team accounts</sub></td>
    <td align="center"><img src="release/screenshots/signin-finish.png" width="240" alt="Paste the code to finish"><br><sub><b>Paste the code, done</b> — self-renews for ~a month</sub></td>
  </tr>
</table>

More screenshots (settings, themes, widget setup, token guide) in
[`release/screenshots/`](release/screenshots/). All screenshots are from a real device in dark
mode; **Usage history** fills in over time as your windows close.

## Get started — about 2 minutes, no computer needed

All you need is your Android phone.

**1. Install the app.** Grab `CCooldown.apk` from the
[latest release](../../releases/latest) onto your phone and open it (allow "install
unknown apps" if your phone asks).

**2. Sign in on the phone.** In the app: **Settings → tap "Sign in on this phone"** on
the account card. Your browser opens Claude's sign-in — log in (Pro, Max, and Team
accounts all work), tap **Authorize**, copy the code the page shows, hop back to the app,
and tap **Paste → Finish sign-in**. Usage loads immediately.

Tracking two accounts? Do the same on the second card — if you keep each account logged
in to claude.ai in a different browser, the built-in browser picker lets you route each
sign-in accordingly (e.g. Work in Chrome, Personal in Brave).

**3. Add a widget.** Long-press your home screen → **Widgets** → pick a **Claude
Cooldown** widget.

The phone's sign-in renews itself for about a month, then the app reminds you before it
lapses — re-signing in is the same one-minute flow.

<details>
<summary><b>Backup method: import a token from a computer (QR / paste)</b></summary>

<br>

If the phone can't complete the browser sign-in, you can copy the sign-in that the
Claude Code CLI holds on your computer. Each account card keeps these options under
**"Use a computer token instead"**.

**Put your Claude sign-in on the screen as a QR code.** On a Mac, paste this one line
into Terminal and press Enter:

```bash
npx -y qrcode-terminal "$(security find-generic-password -s 'Claude Code-credentials' -w)"
```

A big QR square appears right in the terminal. Then in the app: **Settings → "Use a
computer token instead" → Scan QR**, and point your phone at the screen.

> 🔑 That QR code carries your Claude sign-in — it's a password in picture form. Show it
> to your own phone, not to a screenshot, a screen-share, or a colleague.

</details>

<details>
<summary><b>Windows / Linux QR one-liners</b></summary>

<br>

Both need Node.js installed (and `jq` on Linux).

**Windows (PowerShell):**

```powershell
(Get-Content "$env:USERPROFILE\.claude\.credentials.json" -Raw | ConvertFrom-Json).claudeAiOauth | ConvertTo-Json -Compress | npx -y qrcode-terminal
```

**Linux:**

```bash
jq -c .claudeAiOauth ~/.claude/.credentials.json | npx -y qrcode-terminal
```

**Troubleshooting:** if you see `code length overflow`, the credentials file is carrying
extra logins (e.g. MCP servers) — filter it down to just the Claude part. On macOS:

```bash
security find-generic-password -s 'Claude Code-credentials' -w | jq -c .claudeAiOauth | npx -y qrcode-terminal
```

If the QR square doesn't fit on screen, shrink the terminal font until it does.

</details>

<details>
<summary><b>No Node.js? Copy-paste method</b></summary>

<br>

You can also move the sign-in as plain text. First, get the JSON on your computer:

- **macOS** — in Terminal: `security find-generic-password -s "Claude Code-credentials" -w`
- **Windows** — open the file `%USERPROFILE%\.claude\.credentials.json` and copy its contents
- **Linux** — open the file `~/.claude/.credentials.json` and copy its contents

Then get that text onto your phone any way you trust — Samsung Link to Windows clipboard
sync, Quick Share, KDE Connect — and paste it into the app's **Settings**. The app accepts
either the whole file or just the inner `claudeAiOauth` object, and refreshes the token
automatically when it expires.

Treat this token like a password — it grants access to your Claude account. Don't send it
through channels you don't trust (email, group chats, cloud notes).

</details>

## How it works

The app signs in with the **same OAuth client as the Claude Code CLI** — the identical
client ID and scopes (`org:create_api_key user:profile user:inference
user:sessions:claude_code user:mcp_servers user:file_upload`) — via browser-based PKCE
against `claude.com` / `platform.claude.com`. To read usage it calls an **undocumented**
endpoint (`api.anthropic.com/api/oauth/usage`) and sends `User-Agent: claude-code/<version>`
on that call, because without the CLI's identity the request is routed to an aggressively
rate-limited bucket. It polls on a battery-friendly interval (15 minutes by default,
configurable). In short, it presents itself to Anthropic as the official CLI — see the
⚠️ install warning near the top for what that means for your account.

**Privacy:** your token stays on your device, encrypted with the Android Keystore
(EncryptedSharedPreferences, AES-256-GCM). It is sent to Anthropic's API and nowhere else.
There are no servers, no analytics, no third-party network calls.

## Build from source

Requirements: JDK 17, Android SDK (compileSdk 36).

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Stack: Kotlin, Jetpack Compose (Material 3), Glance for widgets, WorkManager for polling,
OkHttp. No other dependencies.

## Fair-use notes

- The usage endpoint is undocumented; the parser is deliberately lenient and this app may
  break without notice if Anthropic changes it.
- The app only **reads** usage data — it never sends prompts or consumes your quota
  (checking your usage does not count as usage).
- The app presents the Claude Code CLI's OAuth client identity and User-Agent to reach the
  usage endpoint (see [How it works](#how-it-works)); this is not sanctioned by Anthropic.
- Anthropic's consumer terms restrict the use of consumer OAuth tokens in third-party
  tools. Installing accepts some risk of token revocation or account flagging. This project
  exists for personal/educational use — use it at your own discretion.
- Polling is rate-limit-friendly (default 15 min, hard floor of 5 min).

## Feedback

Found a bug or want a feature? Open an issue here, or use "Share feedback" in the app's
About section (it emails <robin@eam360.com>).

## Version history

A quick, plain-English tour of what each update added (newest first). The full technical
changelog lives in the [User Guide](release/docs/CCooldown-User-Guide-v1.2.pdf).

- **1.0** — First official release — everything below, polished into one build and shared with the team.
- **0.14** — Feedback now opens an email (was WhatsApp); added a **Check for updates** button; the app downloads from GitHub Releases.
- **0.13** — The big one: **usage history**, an always-on **pinned notification**, and **finer-grained alerts**. Profiles are renameable, and your history & settings now survive a reinstall.
- **0.12** — **Sign in right on your phone** — no computer needed. Also fixed a recurring sign-in error.
- **0.11** — Internal build (never released).
- **0.10** — Clearer sign-in error messages and easier token setup.
- **0.9** — **Burn-rate forecast** (when you'll hit the limit at your current pace) + sign in by scanning a QR code.
- **0.8** — Smarter heads-ups when your sign-in is about to expire or the data goes stale.
- **0.7** — Renamed to **Claude Cooldown**, new icon, tidier Settings, in-app help.
- **0.6** — Added the **Work** profile alongside Personal, plus **Quick Settings tiles**.
- **0.5** — Big visual refresh — cleaner bars, the pacing indicator, and 13 theme colors.
- **0.4** — First usage alerts and a Quick Settings tile.
- **0.3** — Material You theming and dark-mode polish.
- **0.2** — Bigger widget with exact reset times.
- **0.1** — First working app + widget.

## Credits

Made by **Robin Richard Rajan**, built with [Claude Code](https://claude.com/claude-code) 🧡 — the
app was prototyped and **built in a weekend by Claude Fable 5**, with the native sign-in and the
docs finished by **Claude Opus 4.8** (Fable declined the OAuth handshake on cybersecurity grounds).

Licensed under the [MIT License](LICENSE). See [RELEASING.md](RELEASING.md) for the
update workflow.
