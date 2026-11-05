# Rainfold

**Endless soundscapes for sleep and focus.**

Rainfold is an offline Android soundscape player for white, pink and brown noise with an optional bundled rain recording. Current app version: `0.1.0` (version code 1), with Android 8.0/API 26 as the minimum platform and API 36 as the target. The stage 1 player is implemented: background playback, system media controls, Play/Pause/Resume/Stop, a sleep timer and smooth transitions. The first stage 2 slice adds noise + rain in one PCM stream. By explicit user decision, stage 2 development proceeds while stage 1 device acceptance remains open; neither stage is accepted on devices. See [ROADMAP.md](ROADMAP.md) for remaining coverage.

## Playback

- Play starts the configured mix; Pause freezes the remaining session time; Resume continues it.
- `Session actions → Stop session` ends playback while retaining the selected noise, volume and timer settings. The next Play starts a fresh session.
- Choose no timer or 15, 30, 60 or 90 minutes. Timer and fade settings can be changed while stopped.
- Fade-in options: 0, 1, 5, 10 or 30 seconds. Fade-out options: 0, 5, 10, 30 or 60 seconds. Defaults are an endless session, 1-second fade-in and 10-second fade-out.
- Even a zero fade setting retains a short protection ramp. Pause and manual Stop use a short 50 ms ramp; the configured fade-out applies to the timer deadline.
- Layer cards provide separate noise/rain volume and mute. Add rain through **Add sound → Environment → Add rain**, and remove it from its card. The noise remains the base layer; mute it to hear rain alone. Removing rain retains its volume/mute settings for the next addition.
- The Play/Pause/Resume button stays below the scrollable controls and is also available in the bottom sheet. Opening the sheet does not preview audio. Adding a layer while stopped/paused only updates settings; explicit Play/Resume starts listening. During playback, an explicit layer edit changes the live mix.
- The app saves mix composition, both volumes, mute states and timer/fade preferences locally. Existing noise-only preferences migrate with rain absent. Process restart restores preferences in the stopped state and never starts audio automatically.
- The interface follows the system light/dark theme and provides English/Russian resources, a horizontal volume slider and scrollable controls.

## Stack and architecture

Kotlin, Jetpack Compose, Material 3, Coroutines/StateFlow, AndroidX Media3 and Android AudioTrack. No INTERNET permission, analytics or cloud dependencies. Media3 contributes the existing ACCESS_NETWORK_STATE permission during manifest merge; this slice adds no permissions.

```text
MainActivity → RainfoldScreen → PlaybackViewModel
    → MediaController → MediaSession → PlaybackService (MediaSessionService)
    → NoisePlayer → noise crossfade + rain loop → layer gain ramps/headroom
    → session/deadline envelope → final limiter → one AudioTrack
```

The service owns the authoritative Android-independent `PlaybackSession`, the Media3 `SimpleBasePlayer` adapter and the audio engine. UI and system media controls send commands to the same session. The ViewModel only owns the controller connection; Activity destruction does not stop active playback. Media3 publishes the foreground media notification and lock-screen metadata.

Before requesting audio focus, the service enters foreground mode, including on Android 15+. Transient focus loss pauses playback and focus gain resumes it. Repeated transient losses and changes to noise or volume during that pause preserve the intent to resume. Permanent focus loss, an explicit user Pause/Stop, disconnected headphones or an audio failure cancel automatic resumption. Playback holds a partial wake lock, released with audio resources on pause, stop or failure. Paused playback keeps its media session and notification available for Resume. Stop removes foreground state; service destruction releases the media session. A controller still bound to an idle service can start another session.

`PlaybackSession` calculates remaining time from `SystemClock.elapsedRealtime`, excluding paused time. Each command evaluates expiry and its transition with one clock reading. A 250 ms observer publishes snapshots and detects expiry; it does not decrement a counter. The renderer independently applies the session's absolute monotonic deadline, so command setup time and a delayed observer do not prolong audible playback. The Android-independent `SoundLayerMixer` uses 50 ms sample-counted ramps for noise changes, layer addition/removal, volume and mute. A 500 ms linear tail/head overlap repeats rain without a hard wrap. Fixed per-layer gain of 0.4 reserves headroom: two full-scale sources sum to at most 0.8 before the final limiter. This reduces the noise-only maximum compared with stage 1; adding/removing rain never renormalizes the noise. Layer edits retain the session, absolute deadline and AudioTrack. Source phase advances while muted or absent within a live renderer.

A single audio-priority thread handles partial blocking PCM writes and releases AudioTrack in `finally`. Pause/stop first render silence, then release the track after a drain delay derived from the buffer size. Rapid Resume cancels the pending release while preserving renderer error handling. A fresh Play after timer expiry applies fade-in even if the output has not yet been released. Failures return to stopped state with a localized message; renderer errors are reported after output cleanup. Debug builds log underruns, buffer frames and maximum block rendering time every ten seconds; release builds do not log these metrics.

## Bundled rain

`assets/rain.wav` is a 30-second mono PCM16 field recording at 48 kHz: **Gentle Rain.wav by shelbyshark**, [Freesound #501243](https://freesound.org/people/shelbyshark/sounds/501243/), **CC0 1.0 Universal**. The file comes from a pinned, checksum-verified Ronumi mirror with explicit CC0 attribution; its original Freesound page was not independently fetched here. Rainfold trims that processed recording, applies gain and converts it to the renderer format. It is an actual recording, with no synthetic rain placeholder.

The runtime overlap gives a 29.5-second repeated period. The recording is loaded and validated once per renderer start, off the main thread and before AudioTrack creation; playback needs no decoder, disk reads or network. The WAV adds 2.88 MB of uncompressed assets and about 5.5 MiB of retained float PCM, plus temporary loading buffers. Source links, hashes, processing and reproducible preparation are in [docs/audio/RAIN.md](docs/audio/RAIN.md). The APK includes `RAIN_NOTICE.txt` and the full CC0 text.

## Build and verification

Open in Android Studio with JDK 17 or later. Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Cloud verification: debug APK built; lint has 0 errors and 10 warnings (dependencies unchanged). 63 deterministic unit tests pass with no failures, errors or skips: the original 41 regressions plus mixing/headroom, independent mute/gain, rapid layer edits, loop period/overlap, shipped-asset checksum/PCM decoding, persistence migration and rain changes during focus interruption. Tests also verify one output and the unchanged deadline while rain is added, muted, adjusted and removed. Regression tests cover focus interruption sequences, commands at timer expiry, partial PCM writes, rapid renderer resume and output cleanup on failure. Renderer tests use a fake audio output; Android focus callbacks, actual AudioTrack behavior, wake locks and foreground transitions still require device acceptance. The cloud run used `source /workspace/.rainfold-env/env.sh`, `--offline`, `-Pkotlin.compiler.execution.strategy=in-process` and `-Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"`. The first combined run exhausted the default daemon Metaspace during lint; rerunning with these process-local memory limits succeeded. The repository has no configured formatter task; follow `.editorconfig`, including no final newline in text files.

Device verification must be explicitly authorized before installing or controlling a build. The initial emulator smoke check confirmed Play, background system Pause/Resume and an active foreground media notification with the screen off. This was a short functional check, not listening or endurance acceptance.

Still required for stage 1 device acceptance (deferred, still open):

- Two hours on a physical device with the screen off, checking underruns, battery use and audio continuity.
- Listening checks for start/pause/resume/stop, noise changes, volume changes and timer fade-out.
- Timer expiry on device, rotation and Activity destruction, process restart and saved preferences.
- Audio focus interruptions, wired/Bluetooth disconnection, notification and lock-screen controls.
- Android 8 and Android 15+ device coverage; Russian, TalkBack, large font and landscape checks.

Stage 2 device acceptance is also open: listen to rain alone and with each noise, verify layer transitions and at least 30 minutes of loop continuity, test focus/timer/lifecycle with layers, then measure CPU, buffer headroom, underruns and battery during a two-hour physical-device run. **Seamlessness by ear, long-term stability and resource consumption have not been confirmed.** No adb, devices or emulators were used in this iteration.

Only noise and one rain loop are implemented in this slice. Other ambience, accent layers, saved scenes, Sleep/Focus modes and widgets remain later work.