# Working agreements — Claude Cooldown

Rules for anyone (human or agent) working in this repo. These override default behaviour.

## 1. Always name a tracker ID, never leave it bare

Every reference to a tracker ID carries its epic name in brackets on **first use in any
message, commit body, or document paragraph**:

- ✅ `CCRM-17 (Window Pings)`, `CCBG-4 (Alert Dedup)`
- ❌ `CCRM-17`, `see CCBG-4`

The epic name is the short title already in the tracker heading — the part between the ID and
the em-dash. In `### CCRM-20 · Wide Chart — one profile at full width`, the epic name is
**Wide Chart**.

**Why:** an ID alone is unreadable to a human who doesn't have the file open, which includes
future-you scanning `git log`. Every ID has a name; use it.

Exceptions, kept narrow: the tracker headings themselves (the name is right there), commit
message *subjects* where the convention is `feat(CCRM-17): …` and the subject line explains
itself, and repeat references later in the same paragraph.

New IDs are allocated from the highest existing number in
[ROADMAP.md](ROADMAP.md) (`CCRM-N`) or [BUGS.md](BUGS.md) (`CCBG-N`). **IDs are never reused
and never renumbered**, including for dropped or retracted items.

## 2. Show a wireframe and get approval before building

Anything that changes what the user sees — a new screen, card, widget layout, notification
style, chart element, settings section, or a visible change to an existing one — needs a
**wireframe reviewed and approved before implementation starts**. Not after. Not alongside.

- A wireframe is enough: ASCII/markdown sketch, an SVG, or a described layout with real
  numbers (dp sizes, text sizes, what's above what, what happens at each width class).
  Fidelity is not the point; agreeing on the layout before code exists is.
- Show the **states**, not just the happy path — empty, error, at 100%, above pace, and the
  narrow width where something has to drop out. Half the defects in
  [BUGS.md](BUGS.md) are states nobody looked at; CCRM-15 (Above-Pace Verification) exists
  purely because a visual state shipped unobserved.
- **Wait for an explicit approval.** Silence is not approval. If the answer is "change X",
  show the revised wireframe before building.

**Applies to:** the in-app screens, both widget providers, the pinned notification, the Quick
Settings tile, and the share card (CCRM-24 (Share Card)). **Does not apply to:** pure logic,
parsing, scheduling, tests, docs, or a bug fix that restores an already-approved design.

## 3. Where things live

- [ROADMAP.md](ROADMAP.md) — feature work, `CCRM-N`, ordered by when we intend to build it.
  Its appendix records what deliberately does *not* port from other apps; read it before
  proposing something that was already ruled out.
- [BUGS.md](BUGS.md) — defects, `CCBG-N`, with a status and a severity.
- [RELEASING.md](RELEASING.md) — the release process.
- `Release/docs/` — the user-facing guide and brochure (HTML → headless Chrome; sources in
  `docs/src/`).

This is an **Android-only** repo. The Mac client is a separate repo by deliberate decision —
see CCRM-8 (Mac Menu-Bar) for the reasoning, which centres on the GitHub `releases/latest`
stream being repo-wide. Do not add another platform's code here, and do not fork this repo to
start another client — start clean and copy the shared contract in.

Claude Cooldown tracks **Claude usage only**. Adding other AI providers is not a roadmap gap;
it's out of scope.
