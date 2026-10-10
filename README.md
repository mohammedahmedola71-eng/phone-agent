# Phone Agent

An Android app that lets Claude operate your phone. You give it a task in plain English; it reads
the screen through Android's Accessibility Service, asks Claude what to do next, and taps, types,
scrolls and opens apps until the task is done.

## How it works

1. `AgentAccessibilityService` turns the visible UI into a numbered text list (`[5] Button "Wi-Fi" (clickable)`).
2. `AgentRunner` sends the task + current screen to Claude (`ClaudeClient`, Messages API with tool use).
3. Claude replies with one tool call (`click`, `type_text`, `scroll`, `open_app`, `back`, `home`, `wait`, `finish`).
4. The service performs it, the new screen is read, and the loop repeats (max 40 steps).

Only the latest screen is kept in the conversation to keep requests small.

## Build & run

1. Open this folder in Android Studio (it will generate the Gradle wrapper) and run on a device/emulator (Android 8.0+).
2. Open the app, paste your Anthropic API key, and tap **Enable accessibility service** → turn on *Phone Agent*.
3. Type a task and tap **Run task**. The app goes home and the agent takes over; reopen the app to watch the log or **Stop**.

## Safety notes

- The agent is told not to enter passwords/payment details or send, buy or delete without explicit instruction, and to
  `finish` and ask when unsure. This is a prompt-level guard, not a hard one — supervise it.
- The API key is stored in app-private SharedPreferences (unencrypted). Don't use this build on a shared device.
- Screen contents are sent to the Anthropic API. Some apps (banking, password managers) block accessibility reads.
- Android 13+ may require "Allow restricted settings" in App info before the service can be enabled for a sideloaded APK.

## Ideas for next steps

- Hard confirmation dialog for sensitive actions, voice input, a foreground-service notification with a Stop button,
  screenshot input for apps with poor accessibility trees, scheduled/triggered tasks.
