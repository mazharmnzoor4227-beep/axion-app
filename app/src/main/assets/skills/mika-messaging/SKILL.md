---
name: messaging
display: Messages and email
description: Read chat history and email, draft replies, and hand the user a message that is ready to send
aliases: ["send a message", "reply to a message", "send an email", "check unread"]
apps: ["wechat", "feishu"]
sites: ["instagram.com"]
terms: ["send a message", "reply", "email", "chat", "group", "unread", "pass on", "draft"]
---
## Entry

First work out whether the user wants to read, to draft, or to send — and which app and which account. **Axion cannot open or send WhatsApp, WeChat, or other chat messages: there is no built-in tool that sends messages, and no tool that opens another app's chats.** What it can do is read messages the user pastes or forwards into the chat (or uploads as a file in `uploads/`, read with `read_file`), draft a reply for the user to send, and — only if the user has connected Gmail in Settings → Connectors — send email through that connector's tools (named `mcp_<server>_<tool>`). Never promise to send a chat message, and never say a message was sent when it was only drafted.

## Steps

1. **Read**: work from the conversation the user pasted or forwarded, or from a file in `uploads/` read with `read_file`. Note the sender, the time, the group members and any attachments. Unread items are the ones the user says are unread — Axion cannot see another app's unread badges.
2. **Find the recipient**: match by saved name, nickname, group name or email address. When several contacts, groups or accounts share a name, check the details before drafting. Use the real email address from the user's contacts or from earlier emails with that person.
3. **Draft**: write it the way the user means it. When the user only wants a draft, give them the draft and stop here. When the user wants it sent, draft it and hand it to them to send in the app themselves — do not send it without them.
4. **Send (email only, connector only)**: if the user has connected Gmail in Settings → Connectors, use that connector's send tool with the exact recipient and the full draft, and check the tool's result. If there is no connector, say so plainly and hand the draft to the user instead.
5. **Check**: for a draft, the check is the draft itself, shown to the user with the right recipient and text. For an email sent through a connector, the check is the connector tool's success result — and if it failed, say so and do not claim it went out.

## Easy mistakes

- Several people can share a name, and their pictures and saved names differ — drafting for the wrong one sends the wrong message to the wrong person.
- A draft is not a sent message. Never tell the user a message "has been sent" unless a connector tool returned success for it.
- A chat preview only shows the first part, never the full text — do not summarise a conversation from previews alone.
- If the user pastes only part of a conversation, say what is missing instead of guessing the rest.

## What counts as done (evidence to check)

Read: the points and times you give come from the conversation or email text the user provided. Draft: the user has the full draft with the right recipient. Sent (email via a connector only): the connector tool returned success, with the recipient and content correct.

## When to stop and hand it to the user

When a login or verification code is needed, or when the recipient or the content has more than one possible reading and the user has to choose, hand it to the user. When the user asks Axion to send a WhatsApp, WeChat, or other chat message, say plainly that Axion cannot send it and hand over a ready-to-send draft instead.
