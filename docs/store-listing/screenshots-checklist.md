# Axion — screenshots checklist

> CHECKLIST ONLY. No screenshots have been taken and none are included
> here. Each shot is taken later, on the user's real phone, from the real
> app with real data. Take the shots in the order listed — the order is
> the story: chat first, then build, then the tools that prove the phone
> is the computer.

## Capture rules (apply to every shot)

- Real phone, real app, real data. No mock, demo, sample, or placeholder
  content — the app ships no demo content, and the screenshots must not
  invent any.
- English UI throughout. If any screen shows non-English text, fix the
  app first; do not hide it with cropping.
- No API keys, tokens, passwords, phone numbers, email addresses, or
  secrets visible in any shot. Blur or re-frame before keeping a shot
  that catches one.
- No other person's private data (chats, repos, or files that are not the
  user's own) visible.
- Full screen, no cropping of the header or the bottom navigation. The
  header must sit clearly below the status bar with a gap.
- Portrait, unless the screen itself is genuinely better in landscape —
  note it if so.
- One task per shot: the screen must show the thing the caption claims,
  actually happening, not an empty state.
- After each shot, check it on the phone at full size before moving on.

## The shots, in order

### 1. Ask chat — a real answer in progress

- Screen: Home / Ask chat.
- Must show: a real user question and Axion's answer streaming or
  complete, with the composer and tool chips visible.
- Capture note: use a question the user actually asked. Do not stage a
  question just for the screenshot; pick a real recent chat that reads
  well. The answer must be real output, not typed-in text.

### 2. Build project — prompt to working app

- Screen: Build pane, then the built project's preview.
- Must show: the build prompt and the resulting app/website running in
  the on-phone preview.
- Capture note: this is two shots if one frame cannot hold both — (a)
  the prompt and build progress, (b) the live preview. The preview must
  be the real built project, not a stock template screenshot.

### 3. Browser — a real page, logged in

- Screen: in-app Browser.
- Must show: a real website loaded with JavaScript working (a page that
  proves it is a real browser, e.g. a logged-in page or an interactive
  page), with the address bar visible.
- Capture note: use a page the user actually uses. Do not use a page
  that shows private messages or account details.

### 4. Terminal — real Linux, real output

- Screen: Terminal.
- Must show: a real command and its real output in the PRoot Ubuntu
  shell (e.g. `uname -a` / `ls` / a Python or Node command the user
  actually ran), with the Ubuntu prompt visible.
- Capture note: run a command that produces readable, non-trivial
  output. The prompt must show it is the Linux shell, not a fake
  terminal mock.

### 5. Voice call — a live call in progress

- Screen: voice call screen (`ui/call/CallScreen.kt`).
- Must show: an active voice call with Axion — the call UI, live
  indicator, and controls visible.
- Capture note: take this during a real call, not from a preview state.
  Keep the shot free of any transcript that contains private content.

### 6. Tools library — the tool groups

- Screen: Settings → Tools Library (`ui/settings/ToolsLibraryScreen.kt`).
- Must show: the tool groups list — Web search & pages, Deep research,
  Browser, Files & workspace, Run commands, Skills, Memory & learning,
  Scheduled tasks, GitHub, Connectors (MCP).
- Capture note: scroll position must show real group names and
  descriptions. If the list is longer than one screen, take the top of
  the list; do not stitch screenshots together.

### 7. Schedules — a real scheduled task

- Screen: Settings → Schedules (`ui/settings/SchedulesScreen.kt`).
- Must show: at least one real scheduled task the user actually set,
  with its time/frequency visible.
- Capture note: if the user has no scheduled task yet, create a real
  one first (something the user actually wants) and then take the shot.
  Do not screenshot the empty state as a store screenshot.

### 8. Providers / keys — connected, keys hidden

- Screen: Settings → Connectors / Providers
  (`ui/settings/ConnectorsScreen.kt`).
- Must show: connected providers with their status, and the universal
  key box.
- Capture note: keys must be masked — never show a full key. If a key
  is visible on screen, mask it in the app UI before shooting, or
  re-frame to the provider list only. A shot showing a real key must be
  deleted, not blurred after the fact.

## Optional extra shots (only if they come out well — do not force them)

### 9. Deep Research — a cited report

- Screen: a finished Deep Research answer in chat.
- Must show: the report with its Sources list and citations visible.
- Capture note: use a real research run the user actually asked for.

### 10. GitHub — a real repo, a real push

- Screen: a chat turn where Axion cloned / committed / pushed, or
  Settings → GitHub.
- Must show: a real repo (owner/repo) and a real commit or PR result.
- Capture note: only the user's own repos. Never show another person's
  private repo.

### 11. Local model — offline chat

- Screen: a chat running on the local model (LFM3 1.7B).
- Must show: the local model answering, with the model name visible.
- Capture note: prove it is offline if the shot claims it — e.g. take it
  with mobile data/Wi-Fi off. If that cannot be shown honestly in one
  frame, do not claim "offline" in the caption.

## Before submitting to the store

- [ ] All 8 required shots taken, in order, on the real phone.
- [ ] Every shot re-checked at full size on the phone.
- [ ] No key, token, password, phone number, or email visible in any shot.
- [ ] No non-English UI text in any shot.
- [ ] No mock/demo/placeholder content in any shot.
- [ ] Header clear of the status bar in every shot.
- [ ] Each shot's caption matches what the shot actually shows.
- [ ] Any shot that fails a check above is retaken, not edited to hide it.
