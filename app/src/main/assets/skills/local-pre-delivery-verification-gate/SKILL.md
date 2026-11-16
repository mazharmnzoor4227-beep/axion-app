---
name: local-pre-delivery-verification-gate
description: "Measured pre-delivery gate on any web or Android build before claiming it is done: geometry, header gap below the status bar, fixed-overlay ancestry, keyboard and media paths, banned assets, and control wiring."
version: 1.0.0
author: User custom skill (ported from Muse)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [verification, testing, qa, geometry, android, web, gate]
    related_skills: [local-frontend-design, local-android-dev, local-game-dev, local-app-store-design]
---

# Pre-Delivery Verification Gate

## Purpose
Catch failures that screenshots, DOM stubs, and code reading keep missing: clipped content, dead controls, a header colliding with the status bar, and a banned asset reused from a rejected build, before a build is delivered as done.

## Workflow
1. Restate the user request in their own words at the top of the work notes. If the delivery contradicts it, it fails the gate regardless of test results.
2. Measure geometry at real mobile widths (360, 390, 412 px) with getBoundingClientRect on web or layout dumps on Android. Record rect versus rect numbers for every card, rail, and composer element: nothing clipped, nothing overlapping, no element at natural size from a percentage-height chain with an ancestor lacking a definite height.
3. Check fixed overlays in the built output: every position fixed overlay must be a direct child of the body element on web with no backdrop-filter, filter, or transform ancestor. Verify by parsing the built HTML ancestor chain, not by visual inspection.
4. Check device-only paths: keyboard behaviour with imePadding and WindowInsets.ime at every text-field site, EXIF orientation in avatar and sent-photo pipelines, and media send and play on a real file, not a fixture.
5. Trace every interactive control end to end to a real handler and a real effect. A control with no handler, an unwired picker, a placeholder, or a start route ignoring an existing-state check fails the gate. Remove the control or wire it, never ship it as working.
6. Compare the delivery item by item against any explicit spec the user gave, including theme colours and what must NOT appear. A spec item changed without the user word fails the gate.
7. Check the visual medium and missing assets. List every real photo, model, or asset the delivery was supposed to show. Do not replace a missing asset with a procedural, SVG-drawn, or 3D-rendered stand-in and do not fold the substitution into a pass. A stand-in fails unless the user approved that medium for that asset. Name each missing or substituted asset as unverified in the gate report.
8. Check the header gap and the rejected-asset list. On every screen, measure the header top against the status bar bottom and confirm window insets or fitsSystemWindows top padding leaves a clear gap, verified by a screenshot at the target device size as well as by measured numbers. List every model, character, image set, and UI direction used and compare it against banned or rejected items in AGENTS.md and MEMORY.md. A header touching the status bar fails the gate, and a banned model, character, or rejected UI reused in a new version fails the gate even if it renders cleanly.

## Output Contract
Deliver only with a short gate report: the user request restated, measured numbers per width, header top versus status bar bottom numbers per screen, overlay-ancestry result, keyboard and media checks, banned-asset check with each asset marked allowed or banned, and a control-by-control wiring list with any removed or deferred items named. Anything not checked is listed as unverified, never folded into a pass count.

## Operating Rules
- A passing screenshot, DOM-stub harness, or node check is never sufficient evidence for a layout or wiring claim.
- A functional test pass never overrides a visual-medium failure. A build that works in tests but shows drawn or procedurally rendered stand-ins where real photographs were required fails the gate.
- A header that overlaps or touches the status bar fails the gate, even when every other geometry check passes.
- A banned or rejected asset reused from an earlier rejected build fails the gate, even when it renders cleanly.
- Coverage is stated as plain numbers from completed checks only; partial results are named partial.
- If a check cannot run in this environment, say so in the report and leave the item unverified instead of inferring a pass.
- Do not widen scope while gating: fix only what the gate failed, within the named task.
