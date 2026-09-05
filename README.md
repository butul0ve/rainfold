# Rainfold

**Endless soundscapes for sleep and focus.**

Rainfold is an offline Android noise player. The stage 0 implementation generates white, pink and brown noise, with Play/Stop, volume control and English/Russian resources. It establishes the architecture for the next iterations in [ROADMAP.md](ROADMAP.md).

## Stack

- Kotlin, Jetpack Compose, Material 3, Coroutines and StateFlow
- AndroidX Media3 service base and Android AudioTrack
- Android-independent domain and DSP code

## Current architecture

```text
MainActivity → RainfoldScreen → PlaybackViewModel
    → local service binding → PlaybackService (MediaSessionService)
    → NoisePlayer → noise generator → gain → limiter → AudioTrack
```

`MainActivity` only hosts Compose. `RainfoldScreen` renders immutable `PlaybackScreenState` and sends user actions to `PlaybackViewModel`. The ViewModel observes the service's StateFlow and owns an application-context binding, never an audio resource. Its binding survives Activity configuration changes, so rotating the screen does not start a second renderer. Clearing the ViewModel removes the binding; destroying the bound service stops the renderer and releases its AudioTrack. Stop also releases the track, retaining the selected mix for the next Play.

The service owns the authoritative playback snapshot and the engine. The render thread handles partial PCM writes and releases its track in `finally`; stop pauses the track to unblock writes and joins the renderer before another one can start. Audio failures return the screen to idle with a localized retry message.

`PlaybackSession` defines play, pause, resume, stop and update-mix transitions independently of Android. Repeated commands do not reset a running session. `SessionSettings` describes endless or finite sessions and nonnegative finite fade-in/fade-out durations. A supplied monotonic clock computes remaining duration from elapsed active time; pauses freeze the remaining duration. Stop and natural expiry retain the mix, and a subsequent Play starts a fresh duration. Android supplies `SystemClock.elapsedRealtime`.

`RainfoldTheme` centralizes the light/dark palettes; `RainfoldTokens` holds dimensions, shapes and noise marker colors. User-facing text is stored in `values/strings.xml` and `values-ru/strings.xml`.

## Stage 0 boundary

The UI retains Play/Stop and the existing layout. Pause/resume are domain/service commands, with UI controls planned for stage 1. Session durations and fades are tested domain contracts; the service currently runs endless sessions only and does not apply timed stops or audio envelopes.

This is a bound service foundation, not yet a foreground player: there is no MediaSession, MediaController, notification, lock-screen control, audio-focus handling or guarantee of screen-off playback. The local binding uses the custom-action extension described in the [MediaSessionService API](https://developer.android.com/reference/androidx/media3/session/MediaSessionService#onBind(android.content.Intent)); stage 1 will connect the UI through MediaController. Rain, saved settings and smooth transitions are also still planned. No network permission or network dependency has been added.

## Build and verification

Open in Android Studio with JDK 17 or later. Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Unit tests cover DSP behavior, playback transitions, session expiry, pause/resume time accounting, repeated commands and settings validation. The repository has no configured formatter task; follow `.editorconfig`, including no final newline in text files.

Device smoke test for this iteration: start noise, change its volume/type, rotate repeatedly, verify continuous playback and matching controls, then Stop and close the Activity. Repeat in Russian. Device playback and lifecycle checks still require an attached device or emulator.