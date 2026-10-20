# VOX AI

Personal Android AI agent that can see, understand, speak and interact with your phone — only during sessions you start manually.

## Capabilities
- **Screen awareness** — `VoxAccessibilityService` reads the visible UI tree.
- **Voice conversations** — `VoiceIO` (SpeechRecognizer + TextToSpeech).
- **Smart notification replies** — `VoxNotificationListener` captures messages; replies are sent only after you approve the text in a dialog.
- **Phone automation** — click, type, back, home via accessibility actions.
- **Privacy-first** — `SessionState` gates every read/action; nothing runs unless you tap *Start session*. Accessibility and notification access must also be enabled by you in system Settings.
- **Brain** — Claude Messages API with tool use (`agent/`).

## Layout
- `app/.../agent` — `ClaudeClient`, `AgentTools`, `AgentLoop`
- `app/.../service` — accessibility + notification services
- `app/.../voice` — voice I/O
- `app/.../session` — session gate
- `MainActivity.kt` — Compose UI

## Build
Open in Android Studio (AGP 8.5, Kotlin 2.0), run on a device, enable *VOX AI* under Accessibility and Notification access, paste your Anthropic API key, then Start session → Talk to VOX.

## Not yet implemented
Video understanding (uploaded video / live camera frames), screenshot-based vision, wake word, secure API-key storage (currently plain SharedPreferences), a foreground service for long sessions.
