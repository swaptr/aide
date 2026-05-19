# Aide

**Aide** (French for _assistant_) is a hybrid, private-first Android app that puts a frontier-class AI model on the surfaces you already use: the system keyboard and the assistant button. Local-first; cloud is opt-in.

Runs **Gemma 4** on-device via **LiteRT-LM**. For heavier weights (Gemma 4 26B / 31B), an optional **Ollama** endpoint takes over. Chat, keyboard, and assistant share the same loaded model.

## Download

Get the latest APK from [Releases](https://github.com/swaptr/aide/releases/latest).

## Features

### Home

A single workspace that fronts every surface: chat, keyboard, assistant, models, and tool permissions.

<p>
  <img src="assets/aide-home-page.png" width="200" alt="Home" />
</p>

### Chat

Multi-chat workspace with markdown replies, image input that Gemma 4 reads as a multimodal turn, and a tappable thinking chip that opens the model's reasoning trace.

<p>
  <img src="assets/aide-local-chat.png" width="200" alt="Local chat" />
  <img src="assets/aide-thinking.png" width="200" alt="Thinking" />
  <img src="assets/aide-thinking-complete.png" width="200" alt="Thinking complete" />
</p>

### Models

Pick a local Gemma 4 weight (E2B / E4B) or point at an Ollama endpoint for heavier weights. Selection is per-chat, swappable mid-session.

<p>
  <img src="assets/aide-model-selector.png" width="200" alt="Model selector" />
  <img src="assets/aide-language-models.png" width="200" alt="Language models" />
  <img src="assets/aide-ollama-connected.png" width="200" alt="Ollama connected" />
</p>

### Keyboard

A real Android `InputMethodService` with a transform bar above the keys (rephrase, simplify, fix grammar, summarize, tone shifts, key points, bullets, table view, three-reply suggestions) and a magic button for one-shot Custom Instructions. Every transform is a row in a local task table — edit the prompt, reorder, delete, or add your own with a `{{text}}` template.

<p>
  <img src="assets/aide_ime.png" width="200" alt="IME" />
  <img src="assets/aide-ime-comprehension.png" width="200" alt="IME comprehension" />
  <img src="assets/aide-ime-tasks.png" width="200" alt="IME tasks" />
  <img src="assets/aide-ime-task.png" width="200" alt="IME task editor" />
  <img src="assets/aide-ime-new-task.png" width="200" alt="IME new task" />
</p>

### Translation

37 languages on the transform bar, paired with 20+ on-device STT models. The keyboard can listen in one language and write back in another without phoning home.

<p>
  <img src="assets/aide-ime-translate.png" width="200" alt="IME translate" />
</p>

### Assistant

Long-press the assistant button for a turn-based voice loop: STT → Silero VAD → Gemma 4 → TTS, on-device by default.

<p>
  <img src="assets/assistant-input.png" width="200" alt="Assistant input" />
  <img src="assets/assistant-thinking.png" width="200" alt="Assistant thinking" />
  <img src="assets/assistant-output.png" width="200" alt="Assistant output" />
</p>

### Tool calling

Gemma 4's native function calling drives a single dispatcher for alarms, calendar, contacts, phone, clipboard, filesystem, web search (Tavily, Brave, DuckDuckGo, Ollama), calculator, and time. Per-category toggles in settings; destructive actions route through an INFO / WARN / DANGER confirmation gate.

<p>
  <img src="assets/aide-tools.png" width="200" alt="Tools" />
  <img src="assets/aide-web-search.png" width="200" alt="Web search" />
</p>

### Speech

Roughly 23 Piper, Kokoro, MeloTTS, and Matcha voices across 10+ languages for output; Zipformer, Whisper, Moonshine, Parakeet, SenseVoice, and GigaAM for input. Auto / Sherpa / System is a per-surface choice.

<p>
  <img src="assets/aide-stt.png" width="200" alt="STT" />
  <img src="assets/aide-tts.png" width="200" alt="TTS" />
</p>

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Gemma 4 via LiteRT-LM (on-device); Ollama (optional cloud)
- Sherpa-ONNX for STT / TTS / VAD
- Room, Hilt, Coroutines
