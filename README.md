# Rainfold

**Endless soundscapes for sleep and focus.**

Store name: **Rainfold: Sleep Sounds & Noise**.

Rainfold is an offline Android soundscape player. It produces white, pink, and brown noise in real time, then mixes optional seamless natural ambience loops such as rain into one PCM stream. Playback remains available with the screen off through a foreground media service and system media controls.

## Stack

- Kotlin, Jetpack Compose, Material 3, Coroutines and Flow
- Media3 `MediaSessionService` for foreground/background playback and media controls
- A small, testable DSP core that is independent of Android APIs
- `AudioTrack` in `MODE_STREAM` for the final PCM output

## Architecture

```text
Compose UI → ViewModel / ScreenState → MediaController → MediaSessionService
    → NoisePlayer / AudioEngine → sources + mixer + limiter → AudioTrack
```

The player uses monotonic time to calculate remaining duration. The DSP engine owns the audio track; UI state never owns audio resources.

## Project setup

Open the project in the current stable Android Studio and use JDK 17. The Gradle wrapper is included, so build with:

```bash
./gradlew assembleDebug
```

## MVP scope

- White, pink and brown noise
- Timed and endless sessions; play, pause, resume and stop
- Independent noise/rain volume, fade-in and fade-out
- Rain loop, audio focus handling, notification controls, offline background playback