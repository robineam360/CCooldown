# Security policy

CCooldown is an unofficial, sideload-only community tool. It signs in with a Claude
account's OAuth credentials and holds a **working OAuth token** on the device — so a
security report about this app is, more often than not, a report about someone's live
Claude sign-in. Please handle it that way.

## How to report

Use **GitHub's private vulnerability reporting**: on this repo, go to the **Security**
tab → **Report a vulnerability**. That opens a private advisory only the maintainer can
see. Please do **not** open a public issue for anything security-related — issues here
are public the moment you press submit.

If you can't use the Security tab, email <robin@eam360.com> (the same address as the
app's "Share feedback" button) with "SECURITY" in the subject.

## Do not include real credentials in a report — ever

**Never paste real credentials, OAuth tokens, refresh tokens, or `Authorization`
headers into a report** — not in a private advisory, not in an email, and absolutely
not in a public issue. Redact them (`sk-ant-…REDACTED`) or reproduce with a revoked
token. The v0.14 history scrub exists precisely because token material once had to be
purged from this repo's history; the failure mode we guard against is a report that
hands out a working Claude sign-in. If you believe a token has been exposed, revoke it
first (sign out and back in on claude.ai), then report.

The same goes for logs: the app is designed never to log tokens, authorization headers,
or the OAuth `code_verifier`, but check anything you attach before you attach it.

## What's in scope

- Token handling on the device (storage is `EncryptedSharedPreferences`, AES-256-GCM
  via the Android Keystore) and anywhere token material could leak — logs, intents,
  widgets, notifications, the share/export paths.
- The OAuth sign-in flow (browser PKCE, the paste/QR token import paths).
- Network behaviour: the app should talk to Anthropic's API and nowhere else — no
  servers, no analytics, no third-party calls.

Reports about Anthropic's own endpoints or the Claude Code CLI belong with Anthropic,
not here.

## Supported versions

Only the [latest release](../../../releases/latest) is supported — the app's
*Check for updates* button and the README both point there, and there is no mechanism
for patching older builds.
