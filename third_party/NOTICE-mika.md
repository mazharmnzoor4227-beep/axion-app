# Mika — credit (Apache-2.0)

Source: https://github.com/wangzan101/Mika — Apache License 2.0.

Axion v0.6 Phase 3.2 (phone/ package: AxionAccessibilityService, PhoneActions, PhoneTools, PhoneOverlay, PhoneController) is inspired by the **idea** shown on Mika's
public README page: an assistant that works other apps from one sentence by looking at the screen, acting one step at a time, showing each step, letting the user stop it
at any moment, and reporting where it got to after a stop. Only the README was read; **no Mika source code was copied, translated or depended on**.
Axion's approach differs on purpose: it uses only Android's official Accessibility APIs (no wireless debugging, no hidden display, no system-level access), works on the
visible screen only while the user watches, and carries its own safety rules (banking/payment/wallet apps and password screens are never operated; run token; STOP overlay).
