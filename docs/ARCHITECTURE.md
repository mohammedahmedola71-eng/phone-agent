# Architecture

## Layers
1. **UI (Compose)** reads `StateFlow`s from the `AppContainer` and calls its non-suspending commands. Screens never touch Android services directly.
2. **Controllers** (`ChatController`, `ScreenController`, `LiveVideoEngine`, `VideoLabViewModel`, `NotificationsViewModel`) orchestrate a feature.
3. **`SessionManager` + `CapabilityController`s** decide what Vox may access. Each Android permission/service is wrapped by one controller in `session/Capabilities.kt`.
4. **`Assistant`** (core) is one conversation thread: history, image-context pruning, cancel, regenerate, error turns. There are four: chat, screen, live, video.
5. **`AiProvider`** is the only AI dependency. `GatewayAiProvider` speaks the JSON protocol below.

## Gateway protocol
`POST /v1/complete` (Bearer app token)
```json
{ "task": "chat|screen|live|video|reply|notifications",
  "responseLength": "short|medium|detailed", "spoken": true,
  "messages": [ { "role": "user", "parts": [ {"type":"text","text":"…"}, {"type":"image","mediaType":"image/jpeg","data":"<base64>"} ] } ] }
```
→ `{ "text": "…", "model": "…", "usage": {…} }` or `{ "error": { "code", "message", "retryAfterSec"? } }`
(`unauthorized` 401, `invalid_request` 400, `payload_too_large` 413, `rate_limited` 429, `model_unavailable`/`provider_unavailable` 502/503).
`GET /v1/status` returns provider, model and today's usage. System prompts live on the server; the client cannot supply one.
Content from screens/notifications/videos is declared untrusted data in the prompt (prompt-injection hardening), and reply drafting never auto-sends regardless.

## Frame economy
* Screen: one capture per user request. Live: a timer (default 4 s) + luma-thumbnail difference (`FrameSamplerPolicy`) + black-frame detection + per-session budget.
* Video: ≤ 24–32 evenly spaced frames at ≤ 768 px, JPEG q70, held in memory for follow-ups only.
* Model context keeps only the newest 2 images (video: up to 32).

## Where each Android API is used
`MediaProjection` (`capture/ScreenGrabber`, foreground service type `mediaProjection`), `NotificationListenerService` + `RemoteInput`
(`notifications/`), `SpeechRecognizer`/`TextToSpeech` (`voice/`), `ActivityResultContracts.OpenDocument` + `ACTION_SEND video/*` (Video Lab / `MainActivity`),
CameraX `ImageAnalysis` (`capture/CameraFrameSource`), Keystore AES-GCM (`data/SecretStore`), DataStore (`data/SettingsRepository`).
