# CCooldown

**Claude Cooldown** — an Android home-screen widget and app that shows how much of your
Claude Pro / Max / Team usage limits you've burned through, and when your 5-hour and 7-day
windows reset. Built for people who live in Claude Code and keep hitting the wall mid-thought.

> ⚠️ **Unofficial.** This is a personal community tool. It is not affiliated with, endorsed
> by, or supported by Anthropic. "Claude" is a trademark of Anthropic, PBC.

## What it does

- **Home-screen widgets** (small / medium / large, plus a compact single-bar widget) showing
  the 5-hour session window, the 7-day all-models window, and per-model 7-day caps —
  with countdown and exact local reset time
- **Two profiles** — track a Personal and a Work account side by side, in swipeable tabs
- **Quick Settings tiles** — glance at your 5h/7d percentages from the notification shade
- **Alerts** — notifications at 80% / 95% of the 5-hour window and 90% of the 7-day and
  per-model windows, plus an optional "window has reset, Claude is fresh again" notification
- **Burn-rate projection** — a sparkline of each window's usage curve and a plain-words
  forecast ("At this pace: 100% at 2:40 PM — 1h 20m before the reset"), built from a local
  history of your own polls
- **A "days elapsed" pacing bar** — see whether your weekly usage is running ahead of or
  behind the week itself
- **13 theme colors** including Material You dynamic color, full light/dark support

Screenshots live in [`Release/screenshots/`](Release/screenshots/).

## How it works

The app calls Anthropic's own usage endpoint (`api.anthropic.com/api/oauth/usage`) — the
same one Claude Code uses — with the OAuth token from your Claude Code sign-in. It polls
on a battery-friendly interval (15 minutes by default, configurable).

**Privacy:** your token stays on your device, encrypted with the Android Keystore
(EncryptedSharedPreferences, AES-256-GCM). It is sent to Anthropic's API and nowhere else.
There are no servers, no analytics, no third-party network calls.

## Install

1. Grab `CCooldown.apk` from [`Release/`](Release/) (or build from source, below)
2. Copy it to your phone and open it (allow "install unknown apps" if prompted)
3. Open the app → Settings → add your token (see next section)
4. Long-press your home screen → Widgets → add a Claude Cooldown widget

## Getting your token

You need Claude Code installed and signed in on a computer.

**macOS** — credentials are in the Keychain. In Terminal:

```bash
security find-generic-password -s "Claude Code-credentials" -w
```

Copy the JSON output.

**Windows** — open this file and copy its contents:

```
%USERPROFILE%\.claude\.credentials.json
```

**Linux:**

```
~/.claude/.credentials.json
```

Get the JSON onto your phone (Samsung Link to Windows clipboard sync, Quick Share,
KDE Connect — anything works), then paste it into the app's Settings. The app accepts
either the whole file or just the inner `claudeAiOauth` object, and refreshes the token
automatically when it expires.

**Or skip the copying with a QR code** — if the computer has Node.js, render the token
as a QR code in the terminal and tap "Scan QR" on the account card in Settings:

```bash
# macOS
npx -y qrcode-terminal "$(security find-generic-password -s 'Claude Code-credentials' -w)"
# Linux (needs jq)
jq -c .claudeAiOauth ~/.claude/.credentials.json | npx -y qrcode-terminal
# Windows (PowerShell)
(Get-Content "$env:USERPROFILE\.claude\.credentials.json" -Raw | ConvertFrom-Json).claudeAiOauth | ConvertTo-Json -Compress | npx -y qrcode-terminal
```

The Windows/Linux commands extract just the `claudeAiOauth` object first — the full
credentials file can hold other logins (e.g. MCP servers) and outgrow what a QR code
can carry (`code length overflow`). If the macOS command hits that error, filter it
the same way: `security ... -w | jq -c .claudeAiOauth | npx -y qrcode-terminal`.
If the QR square doesn't fit on screen, shrink the terminal font until it does.

> Treat this token like a password — it grants access to your Claude account. Don't
> send it through channels you don't trust.

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
- Anthropic's consumer terms restrict the use of consumer OAuth tokens in third-party
  tools. This project exists for personal/educational use — use it at your own discretion.
- Polling is rate-limit-friendly (default 15 min, hard floor of 5 min).

## Feedback

Found a bug or want a feature? Open an issue here, use "Share feedback" in the app's
About section, or [message me on WhatsApp](mailto:robin@eam360.com).

## Credits

Made by **Robin Richard Rajan** · Built with [Claude Code](https://claude.com/claude-code) 🧡

Licensed under the [MIT License](LICENSE). See [RELEASING.md](RELEASING.md) for the
update workflow.
