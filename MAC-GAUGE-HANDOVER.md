# Handover: the Android gauge work, for CCooldownMac

**From:** CCooldown (Android), 2026-08-21, after CCRM-48 (Status-Bar Gauge), CCRM-49
(Glyph Legibility) and CCRM-50 (Weekly Flag) — all shipped in Android v1.3 and verified
on a Fold 7 with live data.
**To:** the CCooldownMac agent. Android ported your menu-bar gauge, hit constraints you
don't have, and in solving them designed three things worth flowing back: the **weekly
flag dot**, the **theme-coloured ring**, and the **themed pace line**. This document is
the shared contract for all three — copy the values, adapt the drawing to AppKit.

Android sources of truth, if you need to read the implementation:
`ui/UsageIcon.kt` (drawing), `ui/RingGeometry.kt` (`weeklyFlag`, pure + unit-tested),
`ui/Palette.kt` (`ThemeOption.paceLight/paceDark`, `paceColor`), and the approved
wireframes `design/status-bar-glyph-legibility-wireframe.html` and
`design/weekly-flag-dot-wireframe.html` (both mock at true rendered size and rasterise
to real pixels — do the same when you wireframe; vector zooms lie).

---

## 1 · The weekly flag dot (the headline)

Android's icon slot fits **one** readable ring, so the 5-hour window gets the ring and
the 7-day window became a **state, not a level**: a dot dead-centre in the ring's
hollow, keyed on **pace, not usage**.

**The rungs are the pace verdicts, drawn.** Both apps already compute
"above / on / below even pace" with a shared dead zone; the dot binds to those
verdicts exactly, plus one absolute rung:

| Dot | Predicate (`delta = usedPct − elapsedPct`) | Meaning |
|---|---|---|
| **Red** | `trunc(usedPct) ≥ 100` — level, not pace; **needs no reset clock** | the week is spent |
| **Yellow** | `delta > PACE_DEAD_ZONE` | above even pace |
| **Grey** | `−PACE_DEAD_ZONE ≤ delta ≤ PACE_DEAD_ZONE` | on even pace |
| **nothing** | `delta < −PACE_DEAD_ZONE` | below pace — good news is silence |

- `PACE_DEAD_ZONE = 3.0` percentage points — **the same constant your pace sentence
  uses**, never a copy that can drift. The dot must flip at the exact poll the sentence
  flips.
- **Truncate before the 100 comparison** (99.7 → 99, not spent) — the ladder convention
  both apps already share.
- **Honesty:** no weekly reading → no dot (an alert never claims silence proves
  health); no reset clock → no pace verdict, never a guessed one — only the red rung
  can fire then, because it keys on level alone.
- **Why pace, not level** (this was a review correction to Android's first design, and
  it was right): a weekly that crosses a usage threshold on day 6 is beyond
  correcting; "above pace on day 2" is actionable. Validated live the same day: the
  weekly sat at **16% used** but **6 points above pace**, and the dot flagged it while
  any level threshold would have been silent.
- **Why no orange rung:** indistinguishable from yellow at rendered size, and it
  collides with both the Claude-orange theme and the Amber theme's accent. Three rungs
  with three meanings beat four hues, two of which are a coin flip.

**Geometry (as ratios of the icon box, Android's box = 24 dp):**
dot Ø = **7.5/24** of the box (~31%), dead centre; Android's ring is 22.4/24 Ø at a
4/24 stroke, leaving ~3.45/24 clearance so the dot floats rather than crowds.

**Rung colours** (dark surface / light surface):
grey `#BDBDBD` / `#757575` (deliberately a step brighter than the ring track, so it
reads as a mark, not an artifact) · yellow `#FDD663` / `#F9A825` · red `#FF5252` /
`#C62828` — the yellow/red are the ladder's own values.

**Template-image / monochrome fallback** (your menu bar's template mode; Android's QS
tile): collapse rungs to alpha — grey → **45% alpha** disc, yellow/red → full alpha.
"Which rung" is lost; "the week needs a look" survives.

**Mac placement suggestion, yours to decide:** your menu bar keeps two full-size rings,
so you may not need the dot at all — but if you ever add a compact one-ring style, this
is its design; and even with twin rings, the dot in the 7-day ring's hollow is a
cheaper at-a-glance state than reading the arc.

## 2 · The coloured ring (theme fill + the ladder)

Below 80 the ring fill is the **user's theme accent**; above, the fixed warm ladder
takes over (the `BarColor` contract you already share): >80 `#FDD663`/`#F9A825` ·
>90 `#FFA726`/`#F57C00` · ≥100 `#FF5252`/`#C62828`, truncated before comparing.

- **Track (remaining):** a desaturated mid grey (Android uses `argb(150,150,150,150)`)
  — deliberately drained so the fill pops. Bare track = the no-data state, so it must
  stay visible on its own.
- **Minimum fill floor 9%** of the circle, so 1–3% still reads as "started"; a true 0
  draws only track.
- **≥100 notch:** a small gap opens at 12 o'clock when the ring closes, so "spent"
  reads as *closed* rather than merely long — the only severity cue that survives a
  surface that flattens colour.

## 3 · The themed pace line ("partner" colours)

Your gauge draws the pace tick in the neutral foreground. Android now draws it in a
**per-theme partner colour** — and learned one rule the hard way:

**Never a true complement.** Past 80 the fill is the fixed warm ladder *whatever the
theme*, so a warm pace line (e.g. the literal complement of a blue theme) vanishes
exactly in the states where the gauge matters most. **The partner must always be cool
or neutral.** Demonstrated pixel-by-pixel in
`design/status-bar-glyph-legibility-wireframe.html` §6c.

Partner table (dark / light):

| Themes | Partner |
|---|---|
| Claude Orange, Amber, Deep Orange, Red, Pink, Brown, Green, Purple | blue `#5BC8FF` / `#0288D1` |
| Blue, Indigo | spring green `#69F0AE` / `#00A05C` |
| Cyan, Teal | violet `#B388FF` / `#651FFF` |

**Drawing the mark — the part that generalises:** the mark is a slot **erased through
the band** (real transparency), deliberately **wider than the line inside it**
(slot ≈ line + 0.5 × stroke; line ≈ 0.40 × stroke), with a small overhang past each
band edge (**0.12 × stroke** — longer reads as a spike into the hollow at small
sizes). The erased margins are what keep the mark legible over *any* fill colour, and
what leave a visible gap even if a surface flattens the bitmap to one tint. The tick
stays "where am I in the window", never severity — that contract is unchanged.

## 4 · Android-only findings — context, do not port

- Android's status-bar slot is ~15 dp and fits bitmaps **by width**, so one ring beat
  two; your menu bar is a wide strip and keeps its twin rings.
- Android's status bar preserves bitmap colour (on Samsung) while its Quick Settings
  tile flattens to one tint — hence the dual colour/mono rendering path. Your
  equivalent split is coloured `NSImage` vs template image.

## 5 · Tracker references (Android side)

CCRM-48 (Status-Bar Gauge) → CCRM-49 (Glyph Legibility) → CCRM-50 (Weekly Flag) in
`ROADMAP.md`, plus CCBG-14 (Stale Notification Theme) and CCBG-15 (Amber Ladder
Blindness — the Amber accent *is* the >80 yellow rung; you likely share this quirk if
you have an amber theme; check before adopting the ladder colours under theming).
