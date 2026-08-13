# Pull request

## Tracker item

<!-- Every change serves a tracked item. Name it with its epic, e.g.
     "CCRM-17 (Window Pings)" or "CCBG-4 (Alert Dedup)" — see ROADMAP.md / BUGS.md.
     No matching item? Say so and why; new IDs are allocated from the highest
     existing number in the relevant file. -->

Serves:

## Wireframe

<!-- Anything that changes what the user sees — screens, widgets, the pinned
     notification, the Quick Settings tile, chart elements, settings sections —
     needs a wireframe approved BEFORE implementation (CLAUDE.md §2). -->

- [ ] This change alters visible UI, and the wireframe was approved before building
      (link or reference: )
- [ ] No visible UI change (pure logic, parsing, scheduling, tests, docs, or a fix
      restoring an already-approved design)

## What changed

<!-- A few sentences. First use of any tracker ID in this body carries its epic
     name in brackets. -->

## Tests

- [ ] `./gradlew testDebugUnitTest` passes locally
- [ ] New/changed pure logic has unit tests in the existing style

## Credentials check

- [ ] Nothing in this diff or description contains a real token, authorization
      header, or `code_verifier`
