# Rainfold

**Endless soundscapes for sleep and focus.**

Rainfold is an offline Android noise player for white, pink and brown noise. Current app version: `0.1.0` (version code 1), with Android 8.0/API 26 as the minimum platform and API 36 as the target. The stage 1 player is implemented: background playback, system media controls, Play/Pause/Resume/Stop, a sleep timer and smooth transitions. Device acceptance remains open; see [ROADMAP.md](ROADMAP.md) for the verified smoke check and remaining coverage.

## Playback

- Play starts the selected noise; Pause freezes the remaining session time; Resume continues it.
- `Session actions → Stop session` ends playback while retaining the selected noise, volume and timer settings. The next Play starts a fresh session.
- Choose no timer or 15, 30, 60 or 90 minutes. Timer and fade settings can be changed while stopped.
- Fade-in options: 0, 1, 5, 10 or 30 seconds. Fade-out options: 0, 5, 10, 30 or 60 seconds. Defaults are an endless session, 1-second fade-in and 10-second fade-out.
- Even a zero fade setting retains a short protection ramp. Pause and manual Stop use a short 50 ms ramp; the configured fade-out applies to the timer deadline.
- The app saves noise, volume and timer/fade preferences locally. Process restart restores preferences in the stopped state and never starts audio automatically.
- The interface follows the system light/dark theme and provides English/Russian resources, a horizontal volume slider and scrollable controls.

## Stack and architecture

Kotlin, Jetpack Compose, Material 3, Coroutines/StateFlow, AndroidX Media3 and Android AudioTrack. No network permission, analytics or cloud dependencies.

```text
MainActivity → RainfoldScreen → PlaybackViewModel
    → MediaController → MediaSession → PlaybackService (MediaSessionService)
    → NoisePlayer → noise generators → crossfade → volume/envelope → limiter → AudioTrack
```

The service owns the authoritative Android-independent `PlaybackSession`, the Media3 `SimpleBasePlayer` adapter and the audio engine. UI and system media controls send commands to the same session. The ViewModel only owns the controller connection; Activity destruction does not stop active playback. Media3 publishes the foreground media notification and lock-screen metadata.

Before requesting audio focus, the service enters foreground mode, including on Android 15+. Transient focus loss pauses playback and focus gain resumes it. Repeated transient losses and changes to noise or volume during that pause preserve the intent to resume. Permanent focus loss, an explicit user Pause/Stop, disconnected headphones or an audio failure cancel automatic resumption. Playback holds a partial wake lock, released with audio resources on pause, stop or failure. Paused playback keeps its media session and notification available for Resume. Stop removes foreground state; service destruction releases the media session. A controller still bound to an idle service can start another session.

`PlaybackSession` calculates remaining time from `SystemClock.elapsedRealtime`, excluding paused time. Each command evaluates expiry and its transition with one clock reading. A 250 ms observer publishes snapshots and detects expiry; it does not decrement a counter. The renderer independently applies the session's absolute monotonic deadline, so command setup time and a delayed observer do not prolong audible playback. The renderer uses sample-counted gain ramps, a 50 ms noise crossfade, output headroom and the final limiter. Switching noise does not restart AudioTrack.

A single audio-priority thread handles partial blocking PCM writes and releases AudioTrack in `finally`. Pause/stop first render silence, then release the track after a drain delay derived from the buffer size. Rapid Resume cancels the pending release while preserving renderer error handling. A fresh Play after timer expiry applies fade-in even if the output has not yet been released. Failures return to stopped state with a localized message; renderer errors are reported after output cleanup. Debug builds log underruns, buffer frames and maximum block rendering time every ten seconds; release builds do not log these metrics.

## Build and verification

Open in Android Studio with JDK 17 or later. Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Deterministic unit tests cover DSP generators/limiting, gain ramps and retargeting, crossfade headroom, deadline envelopes, playback transitions, repeated commands, pause/resume accounting and settings validation. Regression tests cover focus interruption sequences, commands at timer expiry, partial PCM writes, rapid renderer resume and output cleanup on failure. Renderer tests use a fake audio output; Android focus callbacks, actual AudioTrack behavior, wake locks and foreground transitions still require device acceptance. The repository has no configured formatter task; follow `.editorconfig`, including no final newline in text files.

Device verification must be explicitly authorized before installing or controlling a build. The initial emulator smoke check confirmed Play, background system Pause/Resume and an active foreground media notification with the screen off. This was a short functional check, not listening or endurance acceptance.

Still required before stage 1 acceptance:

- Two hours on a physical device with the screen off, checking underruns, battery use and audio continuity.
- Listening checks for start/pause/resume/stop, noise changes, volume changes and timer fade-out.
- Timer expiry on device, rotation and Activity destruction, process restart and saved preferences.
- Audio focus interruptions, wired/Bluetooth disconnection, notification and lock-screen controls.
- Android 8 and Android 15+ device coverage; Russian, TalkBack, large font and landscape checks.

Rain layers, saved scenes and Sleep/Focus modes remain later roadmap stages.