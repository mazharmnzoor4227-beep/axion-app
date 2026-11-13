---
name: skill-authoring
display: Write a skill
description: When the user wants to create, import or change a skill (a fixed way of doing one kind of task): how to write it, how to check for duplicates, how to save it
aliases: ["create a skill", "new skill", "write a skill", "save a skill", "import a skill", "change a skill", "skill"]
terms: ["skill", "way of doing", "process", "steps", "template", "do it like this from now on", "save as a skill", "import", "rewrite"]
---
## When to write one

- Write one when the user clearly asks to "make a skill", "save this way of doing it", "import this as a skill", or "change a skill". When they are only talking about a way of doing something, or asking whether it can be done, answer first — do not save anything on your own.
- A skill describes how one kind of task is done from now on, not a one-off fact or preference. "I don't eat coriander" is a memory, not a skill — save that kind of thing with `memory_add` instead.
- The only tool that can save a skill is `skill_create`. If that tool is not available in this surface, tell the user plainly that it cannot be saved this time — never say it is saved.

## Check whether one already exists

1. Look at the skills list in your context and check the names and descriptions for this kind of task. If a likely match exists, read the full text with `load_skill` (or `skill_view`).
2. If one of your own learned skills is for this kind of task, improve that one instead: use `skill_update` with the complete new content, keeping the same name. Do not start a second one for the same kind of task.
3. If only a built-in skill matches: built-in skills cannot be changed. When the user wants a different way of doing it, write a new one under a different name as your own skill, and tell the user which one will be used from now on.

## Agree it before you write it

When the user only gave one vague sentence ("help me make a skill for ordering coffee"), ask them in your reply — there is no ask-user tool, so ask in the chat and wait for the answer — about the one or two points that most affect the way of doing it: in which app or website, where it stops, whether there are fixed requirements. List the points to be saved in a few lines for the user to confirm, then save. When the user has already explained the way of doing it clearly, or pasted the complete content, there is no need to ask.

## How to write it

A saved skill is one document: a header plus five sections, with the headings written as shown, and the whole text no more than 4000 characters. `skill_create` takes the same pieces as fields (`name`, `description`, `when_to_use`, `procedure`, `pitfalls`, `verification`), and the saved file looks like this:

```
---
name: order-coffee
display: Order coffee
description: When the user wants a coffee for delivery or pickup
aliases: ["order coffee", "buy a coffee"]
---
## Entry
## Steps
## Easy mistakes
## What counts as done
## When to stop and hand it to the user
```

- `name` uses lowercase English, numbers and hyphens, and says which kind of task it is; `display` is a short name.
- `description` is one sentence saying when it is used; put the ways the user might ask for it in `aliases` — whether it ever gets used depends entirely on these two.
- Entry says where to start (which app, website or search); Steps are numbered short sentences, one action each, naming buttons and pages, never coordinates.
- Things that change every time (a person's name, an amount, an address, the text to send) are written as `<parameter>` or "the contact the user named", never hard-coded.
- Easy mistakes lists the points where it can go wrong and how to get around them; What counts as done says where the result can be seen when it is finished; When to stop says the moments that need the user to log in, pay, confirm, or choose.
- Backticks are only for tool names; do not put app names or button text in backticks. Never write passwords, verification codes, card numbers, or other people's private information.
- Keep it short rather than long: do not write what the model already knows, only the way of doing it and the traps that are specific to this kind of task.

## Importing content the user pasted

When the user pastes a skill, notes or process written somewhere else, rewrite it into the format above: keep the way of doing it itself, and drop anything that does not belong here (commands for other software, tools that do not exist in Axion, scripts). If a section is missing content, add a sentence or two in the spirit of the original. Do not bring over account names, passwords or similar things from the original text.

## Save it and tell the user

1. Save with `skill_create`: the fields are the pieces of the document above. To change one of your own learned skills, use `skill_update` with the complete new content instead.
2. Only a success result counts as saved. Tell the user what happened, following the result: what the skill is called, whether it is new or a change to an existing one, and that it can be seen in the Skills screen, where it will be tried first next time this kind of task comes up.
3. If the result fails, change what the reason points to and save once more; if it still fails, say honestly that it was not saved and why — never say it was created.
4. Renaming is not supported: save it under the new name and ask the user to delete the old one in the Skills screen (learned skills can be read in full and deleted there). You can also delete one of your own learned skills yourself with `skill_delete`; built-in and user-installed skills can never be deleted by you.

## What counts as done

`skill_create` (or `skill_update`) returns success, and the name in the result is the skill that was meant to be saved; the name you tell the user, and whether it is new or a change, match the result.
