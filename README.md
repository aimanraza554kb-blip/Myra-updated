# MYRA — Android AI Voice Assistant

MYRA is a Kotlin + Jetpack Compose (Material 3, MVVM) Android application that talks
to the **Google Gemini Live API** over the **BidiGenerateContent WebSocket** for
real-time, streaming, human-like voice conversation.

## Highlights

- **Gemini Live WebSocket** client (OkHttp) — not REST, not WebRTC.
  - Automatic reconnect with backoff, heartbeat/keepalive, session renewal.
  - Streaming PCM audio in (16 kHz) and out (24 kHz), audio queue, barge-in / interrupt.
  - Input + output live transcription.
- **Audio pipeline** — `AudioRecord` PCM microphone, `AudioTrack` PCM playback,
  energy-based Voice Activity Detection, hardware AEC / noise suppression,
  mic mute and playback mute.
- **Models** — switch between `gemini-2.5-flash-native-audio-preview` and
  `gemini-2.0-flash-live-001` in Settings.
- **Personalities** — GF (natural Hinglish), Assistant, Professional. System prompts
  are tuned to sound natural, never robotic.
- **Premium UI** — black background, red + purple glow, animated orb, live audio
  waveform, particle background, glassmorphism cards, live transcript, chat history.
- **Phone control** — open/close apps, call, WhatsApp, SMS, email, torch, WiFi,
  Bluetooth, volume, brightness, alarm, timer, calendar, camera, gallery, maps,
  music, Spotify, YouTube, Instagram, Facebook, Chrome, Play Store, settings,
  calculator, clipboard, share, contacts search, plus Accessibility automation.
- **AI memory** — Room database for conversation history + memories, pinned
  memories, summarization, user profile, learning mode.
- **Services** — foreground always-on service, accessibility service, floating
  bubble overlay, notification listener, boot receiver, screen/power receiver.
- **Security** — EncryptedSharedPreferences, SQLCipher-style passphrase-protected
  storage helper, no API key in source.

## Setup

1. Open the project in **Android Studio Narwhal or newer**.
2. Let Gradle sync (JDK 17, AGP 8.5.2, Kotlin 2.0.20).
3. Run on a device/emulator with **minSdk 26** (Android 8.0) or higher.
4. Open **Settings → Gemini API** and paste your Gemini API key
   (https://aistudio.google.com/apikey). The key is stored encrypted on-device.
5. Grant microphone, overlay, accessibility, and notification-listener permissions
   from the in-app Permissions screen.

## Notes

- The Gemini Live protocol is a **preview** API; model IDs and message fields can
  change on Google's side. The client is written against the documented
  BidiGenerateContent schema.
- Wake-word ("Hey MYRA") uses an on-device keyword spotter hook; a lightweight
  energy+ASR fallback is included. For best accuracy plug in a dedicated wake-word
  model.
- Some phone actions (WiFi/Bluetooth toggling on Android 10+, global actions)
  require the Accessibility service or user confirmation by OS design.
