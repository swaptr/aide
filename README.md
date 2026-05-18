# Aide

**Aide** (French for _assistant_) is a hybrid, private-first Android app that puts a frontier-class AI model on the surfaces you already use: the system keyboard and the assistant button. Local-first; cloud is opt-in.

Runs **Gemma 4** on-device via **LiteRT-LM**. For heavier weights (Gemma 4 26B / 31B), an optional **Ollama** endpoint takes over. Chat, keyboard, and assistant share the same loaded model.

## Download

Get the latest APK from [Releases](https://github.com/swaptr/aide/releases/latest).

## Features

- **Chat** — multi-chat workspace with markdown, image input, and a thinking trace.
- **Keyboard** — Android IME with a transform bar (rephrase, simplify, summarize, tone shifts, translate, etc.) and a magic button for one-shot Custom Instructions.
- **Translation** — 37 languages, paired with on-device dictation.
- **Assistant** — long-press the assistant button for a turn-based voice loop (STT → VAD → LLM → TTS), on-device by default.
- **Tool calling** — Gemma 4 function calling for alarms, calendar, contacts, phone, clipboard, filesystem, web search, calculator, and time.
- **Custom tasks** — every transform is a row in a local task table. Edit the prompt, change the icon, reorder, delete, or add your own with a `{{text}}` template. New languages or tone shifts wire in without an app update.
- **Permissions you control** — per-category tool toggles in settings, and destructive actions (dial, send SMS, delete file, etc.) route through a confirmation gate tagged INFO / WARN / DANGER. Model never acts without a nod.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Gemma 4 via LiteRT-LM (on-device); Ollama (optional cloud)
- Sherpa-ONNX for STT / TTS / VAD
- Room, Hilt, Coroutines
