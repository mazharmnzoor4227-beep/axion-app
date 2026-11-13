---
name: content-transcribe
display: Turn content into text
description: Turn a video, podcast or article into text: a summary, chapters, quotes, or a rewritten article
aliases: ["summarise video", "organise podcast", "article summary", "extract subtitles"]
apps: ["bilibili", "xiaohongshu"]
sites: ["youtube.com", "xiaoyuzhoufm.com"]
terms: ["summary", "summarise", "transcribe", "subtitles", "podcast", "chapters", "key points", "quotes", "transcript"]
---
## Entry

First find out which result the user wants: a full transcript, a summary, chapters, quotes, or a rewritten article. If the user did not say, make a summary of five to ten sentences.

Take the text from where the content lives: for an article, read the page body; for a video, take the subtitles or the transcript in the description; for a podcast, check whether the episode page has a transcript or show notes. This device has no speech-to-text, so if there is only audio, ask the user for a transcript or subtitle file.

## Steps

1. **Get the full text**: read the page body with `browse_url`; for a subtitle or transcript file the user sent, it is in `uploads/` — read it with `read_file`.
2. **Read in sections**: when the content is long, read it in a few overlapping sections, remove the duplicates when you are done, and check that no section is missing from start to finish.
3. **Write it in the form asked for**: the shape of a summary is: one or two sentences of overview; three to eight topics in the order they appear, each with its approximate start time and two or three sentences of content; finally, the points, numbers, and books or works mentioned that are worth noting. A full transcript keeps the original wording and order; quotes keep the original wording and say where they come from; a rewritten article keeps the facts and order of the original and says nothing the original did not say.
4. **Write in the language of the original content**, unless the user asked for another language.
5. **Read it back**: after writing, check it against the original: is the order right, is any topic missing, and can every time and quote be found in the subtitles or text you read?

## Easy mistakes

- Subtitles are text only: charts, demos and words on screen in the picture cannot be read from them, so never write as if you saw the picture.
- Auto-generated subtitles often have wrong characters and broken sentences; check proper nouns, people's names and numbers against the context or the description.
- Times are estimated from where the subtitle sits, so write "around minute 23", never an exact second.
- Mark passages that are inaudible or missing from the subtitles; do not fill them in yourself.

## What counts as done (evidence to check)

The result covers every section of the original content; every quote and time can be found in the subtitles or text you read.

## When to stop and hand it to the user

When the content needs a login or payment to view, or when there is only audio and no transcript at all, tell the user what is missing and ask them to provide it.
