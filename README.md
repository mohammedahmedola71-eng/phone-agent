# Vox AI — a personal Android assistant you control manually

Vox is a voice-first Android assistant (Kotlin + Jetpack Compose) with a futuristic orb UI. It can talk with you, look at your
screen, watch a live view or camera, analyze videos you pick or share, and help you triage and answer notifications — **but only
after you tap ACTIVATE VOX**, only for the capabilities you switch on, and everything stops when you tap END SESSION.

```
┌────────────── Android app (android/) ───────────────┐        ┌──── Gateway (backend/) ────┐      ┌─ AI provider ─┐
│ Compose UI · SessionManager · voice · MediaProjection│  HTTPS │ auth · validation · limits  │ HTTPS│ Anthropic /   │
│ NotificationListener · Video Lab · live sampling     │ ─────► │ system prompts · no storage │ ───► │ OpenAI-compat │
│ holds only a per-install APP TOKEN (Keystore-encrypted)│       │ holds the PROVIDER API KEY  │      └───────────────┘
└──────────────────────────────────────────────────────┘        └─────────────────────────────┘
```

## Honest status

| Piece | State | How it was verified |
|---|---|---|
| Gateway (`backend/`) | Complete | 10 automated tests pass (`npm test`): auth, validation, size/rate/daily limits, provider error mapping, request shaping for Anthropic and OpenAI-style APIs |
| App core logic (`android/.../core`, no Android imports) | Complete | 29 JVM unit tests pass: session shutdown, reply-approval state machine, frame-change detection, video sampling/limits, history trimming, gateway client (MockWebServer), TTS text cleaning |
| Android app (UI, services, voice, capture, notifications, Video Lab) | **Written, type-checked, not yet built or run on a device** | The whole source tree compiled cleanly against the Android 15 framework jar, Compose signatures and stubs for the few AndroidX APIs used. The sandbox it was written in could not reach Google's Maven repository, so the real Android Gradle build (`./gradlew assembleDebug`), resource linking, R8 and **every on-device behavior** are still unverified. |

So: nothing here is pretended to work. Treat the first `./gradlew assembleDebug` and the checklist in
[`docs/TESTING.md`](docs/TESTING.md) as the real acceptance test, and expect to fix a few build/runtime issues.
CI (`.github/workflows/ci.yml`) runs the backend tests, Android unit tests and `assembleDebug` and uploads the debug APK.

## What you need (external services and keys)

| Need | Why | Cost |
|---|---|---|
| An AI provider API key — **Anthropic** (`ANTHROPIC_API_KEY`) or an **OpenAI-compatible** endpoint (`OPENAI_API_KEY`) with image input | Answers, vision, video-frame analysis | Pay per use. Vision requests (screen/video frames) cost more. |
| A host for the gateway with **HTTPS** (any Node 20+ host: a VPS behind Caddy/nginx, Fly, Render, Railway, Cloud Run…) | Keeps the provider key off the phone | Free tiers often suffice |
| An **app token** you generate (`openssl rand -hex 24`) | Authenticates your phone to your gateway | Free |
| Android phone, Android 8.0+ (API 26+) | Target device. Designed for Android 14/15 rules (foreground-service types, per-session screen-capture consent) but untested on them | — |
| Android Studio (Koala+) / JDK 17, Android SDK 35 | Building the APK | Free |
| A speech engine on the phone (Google speech services is typical) | Speech-to-text and text-to-speech | Free. Recognition may use the network depending on device. |

No AI key, Firebase project or other account is baked into the app.

## Setup

### 1. Run the gateway
```bash
cd backend
npm install
cp .env.example .env        # edit: VOX_APP_TOKENS, VOX_PROVIDER=anthropic|openai, the matching API key
set -a; . ./.env; set +a
npm start                   # listens on :8787
```
Try it first with `VOX_PROVIDER=mock` (no key needed; it echoes what it received) to check the phone ⇄ gateway path.
Put it behind HTTPS before using it for real (the app refuses non-HTTPS gateway addresses except `10.0.2.2`/`localhost`, and release builds block cleartext at the network layer anyway).
Check: `curl -H "Authorization: Bearer $TOKEN" https://your-host/v1/status`.

### 2. Build and install the app
```bash
cd android
./gradlew testDebugUnitTest assembleDebug     # needs the Android SDK (ANDROID_HOME) and JDK 17
# APK: app/build/outputs/apk/debug/app-debug.apk   (or open `android/` in Android Studio and press Run)
```
For the emulator talking to a gateway on your computer use `http://10.0.2.2:8787` (debug build only).

### 3. Connect the app
Open Vox → ⚙ Settings → **AI gateway**: enter the gateway address and the app token → Save → **Test connection**.
Then go back and tap **ACTIVATE VOX**.

### 4. Optional: notification access
Notifications → *Grant notification access* opens Android's system list; enable Vox there. On Android 13+ a sideloaded
build may first need *App info → ⋮ → Allow restricted settings*. Vox still ignores every notification until you switch
notification assistance on inside an active session.

## How the manual-control rules are enforced

* `SessionManager` (core, unit-tested) is the single gate. Capabilities (mic, screen, camera, live analysis, notifications) can only be
  enabled while a session is active; `end()` flips the indicator to “inactive” first and then stops **every** controller, even if one throws.
* A permanent bar shows `SESSION ACTIVE` plus exactly what is on (`Mic on`, `Screen awareness active`, `Camera on`, …) and an END SESSION button.
* Microphone and camera also stop when the app leaves the foreground. Screen capture is a visible foreground-service notification with
  *Look at screen / Pause live / End session* buttons.
* The notification listener service is bound by Android once you grant access, but `NotificationStore.isOpen` is false outside a session
  with notification assistance on: it drops everything immediately and stores nothing.
* Replies go `Drafted → Confirming → Sending` only; `confirmSend()` returns the exact text shown in the approval dialog, once.
* Optional auto-end (default 30 min) and per-session budgets for screen analyses, live frames and video frames.

## Features and their real limits

| Feature | How | Limits you should know |
|---|---|---|
| Voice chat | `SpeechRecognizer` + `TextToSpeech`, sentence-chunked, Markdown stripped, voice/speed/pitch/length settings, stop button, optional continuous listening | Quality depends on the phone's installed voices; you can interrupt by tapping the orb/stop button, but Vox does **not** detect you talking over it. No wake word (by design). Architecture isolates speech behind two small classes so a realtime voice model can replace them later. |
| Screen awareness | `MediaProjection` snapshot on demand (downscaled JPEG) → vision model; follow-ups reuse the same screenshot | Android asks consent each session (and on 14+ may offer sharing one app). Inside Vox a capture shows Vox itself; to ask about another app use **Look in 5s** or the notification's **Look at screen**. Secure/DRM content renders black — Vox detects that and says so. |
| Live video | Samples the screen or camera every N s, sends only frames whose thumbnail changed, narrates what's new, speaks it | Only permitted capture sources; cannot read another app's internal/protected video stream. Camera runs only while Live Video is open and the app is on screen. Frame budget per session. |
| Video Lab | System file picker or Share → Vox; metadata + thumbnail; 6 analysis tasks + free questions; progress, cancel, follow-ups | Sends **~24 sampled frames, not the file** (after a confirmation dialog). Audio is not analyzed, so **speech transcription is intentionally disabled** and labelled as unavailable. Max 1 h. Needs a format Android can decode (MP4 is safest). |
| Notifications | `NotificationListenerService`; summarize/prioritize; draft with your instruction; edit; read aloud; explicit send | Reply works only when the source app exposes a free-text `RemoteInput` action (messaging apps usually do). Otherwise Vox says so and offers to open the app. “Sent” means the app accepted the reply intent. Secret-visibility, ongoing, call, media and navigation notifications are skipped. |
| Accessibility Service | **Not used.** No feature required it. | — |

## Privacy and data flow

* Nothing is captured, heard or read before ACTIVATE; a consent dialog explains the external AI processing once.
* Sent to your gateway (and from there to the AI provider): typed/spoken text, and only the screenshot / live frames / video frames / notification text you chose to use.
* The gateway keeps no content: no database, no request-body logging, `Cache-Control: no-store`; it keeps only per-token counters in memory.
  **The AI provider's own retention policy still applies** to what it receives.
* On the phone: conversation text is saved only if you switch it on (never images). Frames/screenshots/video results live in memory and are dropped on END SESSION. Settings → Data deletes saved conversations and cached files, or everything including the token.
* The provider key never reaches the phone. The app token is stored encrypted with a non-exportable Android Keystore key. Backups are disabled.
* Cleartext traffic is blocked in release builds.

## Repository layout

```
backend/                      Node gateway (express + zod). src/providers/* is the swap point for AI vendors.
android/app/src/main/java/ai/vox/app/
  core/        pure Kotlin: session, assistant, gateway client, reply state machine, frame diff, video plan  (unit-tested)
  voice/       AndroidSpeechInput / AndroidSpeechOutput
  capture/     MediaProjection grabber + foreground service, camera source
  live/        LiveVideoEngine
  video/       Video Lab engine (metadata, frame sampling), share inbox
  notifications/ listener service, store, replier
  controllers/ ChatController, ScreenController
  session/     capability controllers (what “enable/disable” means for each permission)
  data/        settings (DataStore), Keystore secret store, conversation store
  ui/          Compose theme, orb, components, screens, view models
docs/          ARCHITECTURE.md · TESTING.md
```
Swapping the AI vendor: change `VOX_PROVIDER` on the gateway, or add a provider file in `backend/src/providers/`. Swapping the
transport entirely (e.g. realtime audio): implement `AiProvider` in the app.
