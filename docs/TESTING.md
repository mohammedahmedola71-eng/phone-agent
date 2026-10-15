# Testing

## Automated (run these)
```bash
cd backend && npm test                       # 10 tests
cd android && ./gradlew testDebugUnitTest    # 29 JVM tests in app/src/test (core logic)
cd android && ./gradlew lintDebug assembleDebug
```
Status when this was written: backend and core tests were executed and pass; the Android Gradle build was **not** executable in the authoring sandbox.

## Manual device checklist (NOT yet executed — please run and record results)
Devices: at least one Android 13, one Android 14/15 (partial-screen-share prompt, stricter foreground-service rules), one older (8–10) if you can.

**Session & shutdown**
- [ ] Cold start: no mic/screen/camera indicators, no foreground notification, notification listener ignores everything.
- [ ] ACTIVATE → bar shows SESSION ACTIVE; enable each capability; the bar chips match reality.
- [ ] END SESSION from home, from the bar, and from the capture notification: mic indicator gone, screen-share chip gone, camera off, notification gone, bar says VOX IS OFF, no further gateway traffic (watch gateway logs).
- [ ] Background the app while listening/camera on: both stop. Screen capture keeps its visible notification.
- [ ] Swipe the app away mid-session; relaunch: inactive, nothing running.
- [ ] Auto-end fires after the configured minutes.

**Permissions**: deny each (mic, camera, notification posting, screen-capture dialog, notification access) → clear message, no crash, rest of the app works; revoke in system settings mid-session; Android 14+ "single app" sharing; user taps the status-bar “stop sharing” chip → Vox flips Screen to off.

**Voice**: partial transcript shows; reply spoken; stop button/orb tap interrupts immediately; settings voice/speed/pitch/length apply; continuous listening; no speech engine installed → text still works; airplane mode → friendly error, retry works.

**Screen**: describe/read text/summarize; follow-up reuses screenshot; “Look in 5s” + switch app; notification “Look at screen” opens Vox with the answer; banking/DRM app → black-frame message; rotation.

**Live**: change detection (static screen sends ≈ nothing); budget limit message; pause/resume from notification; protected video → “blocked” status; camera source and leaving the screen turns the camera off.

**Video Lab**: pick (MP4, MKV, 4K, long >1 h → rejected, corrupt file → friendly error); Share → Vox from Gallery/Files; confirmation dialog appears before any network call (verify with gateway logs); cancel during extraction and during upload; follow-ups; remove video clears results; transcribe task is disabled with explanation.

**Notifications**: not granted → guidance; sideload “restricted settings” flow; list populates only when ON, empties on OFF/END; secret/ongoing skipped; draft → edit → read aloud → Send shows exact-text dialog → reply delivered in WhatsApp/Messages/Slack; Cancel/Back never sends; non-replyable shows explanation + Open app; expired notification error.

**Privacy**: Settings → delete conversations/everything works; with “save conversations” off nothing is written to `files/conversations`; backups disabled; release build rejects `http://` gateway.

**Robustness**: memory (Android Studio profiler) during 10 min live screen and a 24-frame video; gateway 401/429/5xx/timeouts; oversized request (413) message; rapid activate/end cycling; process death while capturing.

## Known gaps to watch
Speech recognizer behavior varies by OEM; some OEMs kill foreground services aggressively; MediaMetadataRetriever frame extraction is slow for long 4K files; `SpeechRecognizer` may play system beeps.
