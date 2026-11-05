# ffmpeg-skill — credit (MIT)

Source: https://github.com/kajisho5/ffmpeg-skill — Copyright (c) 2026 kajisho5 — MIT License.

Axion's video skill (app/src/main/java/com/mazharansari/axion/agent/FfmpegCommands.kt, VideoTools.kt, assets/skills/video/SKILL.md) takes its
**design ideas** from this project: a small machine-readable tool contract for an agent (probe → edit → check the result), and the list of
operations (trim/cut, captions/subtitles, reframe to vertical/square/landscape, loudness normalisation, compress to a target size, audio
extraction, platform export presets). I read only the project's public README; **no source code was copied or translated** — the command
builders are Axion's own Kotlin and run ffmpeg locally (no cloud, no API keys).

ffmpeg itself is not bundled in the APK: it is installed on the phone, inside Axion's Linux, from the Ubuntu package archive (`apt-get install ffmpeg`)
the first time the user asks for a video edit.
