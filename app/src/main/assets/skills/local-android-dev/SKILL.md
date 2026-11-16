---
name: local-android-dev
description: "Production-grade Android app development and XML journey testing: native and cross-platform architecture, UI flows, journey execution, and CI-built releases."
version: 1.0.0
author: User custom skill (ported from Muse)
license: MIT
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [android, kotlin, java, mobile, testing, ci, apk]
    related_skills: [local-app-store-design, local-pre-delivery-verification-gate, reverse-engineering-android]
---

# Android Development and UI Journey Testing

## Choose the stack and architecture

- Read the detailed guide in references/detailed-guide.md before end to end work and load only the section that fits a focused task.

- Choose native Kotlin or Java for a fully native app and choose Flutter, React Native, or Kotlin Multiplatform only when the shared code benefit outweighs the platform cost.

- Keep one clear architecture for state, navigation, data, and background work and keep package names, permissions, and privacy disclosures matched to the actual project.

- Verify release critical library versions and store policy thresholds against current Android and library documentation before shipping.

## Build screens and flows

- Design each screen from the existing design system and keep touch targets, spacing, and text sizes consistent across screens.

- Handle errors with a visible state and a recovery action and never leave a user on a blank screen after a failure.

- Apply image orientation correctly in avatar and sent photo pipelines and keep keyboard behaviour correct at every text field.

- Optimise startup, memory, and rendering for low end devices and measure the result instead of claiming it from code inspection.

## Run XML journey tests

- Parse the journey XML in order and treat the actions block as the source of truth for the flow under test.

- Run interactive actions in sequence with ADB and tap the centre of each target element bounds after computing the midpoint from its bounds.

- Wait one to two seconds after an interactive action so layouts and transitions finish before the next assertion.

- Verify each state assertion by inspecting the current screen without interacting or scrolling and fail the assertion when any sub assertion is missing.

- Stop the journey at the first failure and mark later steps as skipped with the exact reason recorded.

- Write the outcome as a JSON report naming each action, its status, and the commands used and redact passwords, OTP codes, tokens, and payment data from that report.

## Ship a CI built release

- Ship a release only from continuous integration and never present a hand built local APK as an official release.

- Download the served release artifact and verify its package name, version code, signer, assets, and native libraries before delivery.

- Compare the release artifact against an artifact that runs on the same device when startup or packaging is in doubt.

- Treat a green build with no launch or smoke test as no evidence that the app opens.

## Alternatives for better results

- Use the pre delivery verification gate skill for measured geometry, fixed overlay ancestry, and control wiring checks before claiming a build is done.

- Use the evidence first crash diagnosis skill when an app still crashes on launch after a fix instead of building another fix on a guess.
