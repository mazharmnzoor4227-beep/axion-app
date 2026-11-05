---
name: video
description: Edit videos on the phone (no cloud) — trim, captions, reel 9:16 / square / 16:9, loudness, compress to a size, extract audio, Instagram / TikTok / YouTube / WhatsApp presets.
---
# Video editing (local ffmpeg)

You have two tools: `video_probe` (length, size, sound) and `video_edit` (operation + options). Everything runs on this phone inside Axion's Linux. Nothing is uploaded.

## Workflow
1. The user's video is in `uploads/`. Call `video_probe` first so trims and captions fit the real length.
2. Pick ONE operation:
   - "reel / reels size / 9:16 / vertical" → `reel` (or `preset` with `instagram_reel`). Add `fit: "blur"` only if the user wants the whole picture kept (default crops to fill the frame).
   - "captions / subtitles lagao" → `captions` (or `reel` when they also want vertical). Pass the words in `captions`. If the user gave no text, ask ONE short question for the words they want shown, or use the exact text they typed. There is no speech-to-text: never invent what is said in the video.
   - "kaato / trim / cut" → `trim` with `start` and `end` (seconds or mm:ss).
   - "size kam karo / compress to 8 MB / WhatsApp" → `compress` with `target_mb`, or `preset: whatsapp_status`.
   - "sound / awaaz barabar" → `normalize`. "audio nikalo" → `extract_audio` (`format`: mp3, m4a, wav).
   - platform requests → `preset`: instagram_reel, tiktok, youtube_shorts, youtube, instagram_post, whatsapp_status.
3. Several operations in a row are fine (e.g. trim, then reel on the trimmed file `outputs/…_trim.mp4`).
4. The result appears as a card and is saved to the Gallery (Movies/Axion). Reply with one or two lines: what you made, its length/size, and where it is. Do not paste commands.

## Rules
- First use installs ffmpeg once (needs internet, a few minutes). Tell the user that if the step takes long.
- If Linux is not installed, say: Settings → Developer tools → Linux, then ask again.
- Caption text is Roman Urdu / English / Urdu script as the user wrote it; keep lines short (the tool splits them).
- Never claim a video was made unless the tool returned "Done".
