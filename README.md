# Rainfold

**Endless soundscapes for sleep and focus.**

Rainfold is an offline Android soundscape player for white, pink and brown noise with an optional bundled rain recording. Current app version: `0.1.0` (version code 1), with Android 8.0/API 26 as the minimum platform and API 36 as the target. The stage 1 player is implemented: background playback, system media controls, Play/Pause/Resume/Stop, a sleep timer and smooth transitions. The first stage 2 slice adds noise + rain in one PCM stream. By explicit user decision, stage 2 development proceeds while stage 1 device acceptance remains open; neither stage is accepted on devices. Stage 3 now adds saved scenes and a scene-centered home screen on the existing sound catalog; device acceptance remains open. See [ROADMAP.md](ROADMAP.md) for remaining coverage.

## Playback

- Play starts the configured mix; Pause freezes the remaining session time; Resume continues it.
- Minimizing the app keeps background playback active. Removing its card from recent apps ends the session and releases audio, audio focus, the wake lock and foreground service state after the short stop ramp.
- `Session actions → Stop session` ends playback while retaining the selected noise, volume and timer settings. The next Play starts a fresh session.
- Open **Timer** for no timer or 15, 30, 60 or 90 minutes. Timer and fade settings can be changed while stopped.
- Fade-in options: 0, 1, 5, 10 or 30 seconds. Fade-out options: 0, 5, 10, 30 or 60 seconds. Defaults are an endless session, 1-second fade-in and 10-second fade-out.
- Even a zero fade setting retains a short protection ramp. Pause and manual Stop use a short 50 ms ramp; the configured fade-out applies to the timer deadline.
- Layer cards provide separate noise/rain volume and mute. Add rain through **Add sound → Environment → Add rain**, and remove it from its card. The noise remains the base layer; mute it to hear rain alone. Removing rain retains its volume/mute settings for the next addition.
- The Play/Pause/Resume button stays below the scrollable controls and is also available in the bottom sheet. Opening the sheet does not preview audio. Adding a layer while stopped/paused only updates settings; explicit Play/Resume starts listening. During playback, an explicit layer edit changes the live mix.
- The app saves mix composition, both volumes, mute states and timer/fade preferences locally. Existing noise-only preferences migrate with rain absent. Process restart restores preferences in the stopped state and never starts audio automatically.
- The interface provides English/Russian resources, scrollable controls and system light/dark themes. The optional **AMOLED background** uses a black surface and is remembered locally. A static scene-colored gradient becomes subtler during Sleep scenes; no background motion is used.

## Saved scenes

- **Scenes** opens three localized recipes: **Deep Sleep** (brown noise), **Rainy Window** (rain with noise muted), and **Quiet Focus** (pink noise with quiet rain). Their Sleep/Focus labels describe intent; session modes and their timer defaults belong to stage 4.
- **Choose scene** applies its layers without starting stopped/paused playback. **Play** on a scene card explicitly applies and starts/resumes it. During playback, either action updates the existing mixer with its gain ramps, keeping the same audio output and timer deadline. Opening the library never previews audio.
- **Save as new scene** captures the service's current layer types, volumes and mute states, plus a name and Sleep/Focus label. Timer and fades remain session settings. Names contain 1–60 characters; up to 100 custom recipes can be kept locally.
- Each scene has a favorite toggle; the **Favorites** filter works for built-in and custom scenes. **Scene actions** offers copying, and custom scenes also support rename and deletion with confirmation. Built-in recipes cannot be overwritten or deleted. Copying keeps the current mix and selection; deleting a selected recipe retains the live mix and playback.
- Manual layer edits do not overwrite a recipe: its title gains an **edited** marker. Save those edits as a new recipe or select the original again to restore its sound. Restart restores both the last selected scene and the latest mix edits in the stopped state. The current scene title also appears in system media controls.
- Versioned JSON is stored alongside the current mix in private SharedPreferences, using stable IDs. Older installations retain their existing mix without an automatic preset selection. Invalid individual recipes are skipped while intact recipes remain available; dangling selection/favorite IDs are removed. No storage permission, network service or new dependency is needed. Scenes survive normal app updates; clearing app data or uninstalling removes them.

Cozy Storm and Airplane Cabin are deferred until the audio catalog can expand after rain acceptance. No storm or cabin recording is implied by the existing recipes.

## Stack and architecture

Kotlin, Jetpack Compose, Material 3, Coroutines/StateFlow, AndroidX Media3 and Android AudioTrack. No INTERNET permission, analytics or cloud dependencies. Media3 contributes the existing ACCESS_NETWORK_STATE permission during manifest merge; this slice adds no permissions.

```text
MainActivity → RainfoldScreen → PlaybackViewModel
    → MediaController → MediaSession → PlaybackService (MediaSessionService)
    → NoisePlayer → noise crossfade + rain loop → layer gain ramps/headroom
    → session/deadline envelope → final limiter → one AudioTrack
```

The service owns the authoritative Android-independent `PlaybackSession`, the Media3 `SimpleBasePlayer` adapter and the audio engine. UI and system media controls send commands to the same session. The ViewModel owns the controller connection and appearance preferences; scene commands and recipes are owned by the service; Activity recreation does not stop active playback; explicit removal from recent apps sends Stop. Media3 publishes the foreground media notification and lock-screen metadata.

Before requesting audio focus, the service enters foreground mode, including on Android 15+. Transient focus loss pauses playback and focus gain resumes it. Repeated transient losses and changes to noise or volume during that pause preserve the intent to resume. Permanent focus loss, an explicit user Pause/Stop, disconnected headphones or an audio failure cancel automatic resumption. Playback holds a partial wake lock, released with audio resources on pause, stop or failure. Paused playback keeps its media session and notification available for Resume. Stop removes foreground state; service destruction releases the media session. A controller still bound to an idle service can start another session.

`PlaybackSession` calculates remaining time from `SystemClock.elapsedRealtime`, excluding paused time. Each command evaluates expiry and its transition with one clock reading. A 250 ms observer publishes snapshots and detects expiry; it does not decrement a counter. The renderer independently applies the session's absolute monotonic deadline, so command setup time and a delayed observer do not prolong audible playback. The Android-independent `SoundLayerMixer` uses 50 ms sample-counted ramps for noise changes, layer addition/removal, volume and mute. A 500 ms linear tail/head overlap repeats rain without a hard wrap. Fixed per-layer gain of 0.4 reserves headroom: two full-scale sources sum to at most 0.8 before the final limiter. This reduces the noise-only maximum compared with stage 1; adding/removing rain never renormalizes the noise. Layer edits retain the session, absolute deadline and AudioTrack. Source phase advances while muted or absent within a live renderer.

A single audio-priority thread handles partial blocking PCM writes and releases AudioTrack in `finally`. Pause/stop first render silence, then release the track after a drain delay derived from the buffer size. Rapid Resume cancels the pending release while preserving renderer error handling. A fresh Play after timer expiry or a rapid Stop → Play applies the configured fade-in even if the output has not yet been released. Rapid Pause → Resume ramps from the current gain. Failures return to stopped state with a localized message; renderer errors are reported after output cleanup and carry their renderer identity, so a delayed error cannot stop a replacement renderer. Debug builds log underruns, buffer frames and maximum block rendering time every ten seconds; release builds do not log these metrics.

## Bundled rain

`assets/rain.wav` is a 30-second mono PCM16 field recording at 48 kHz: **Gentle Rain.wav by shelbyshark**, [Freesound #501243](https://freesound.org/people/shelbyshark/sounds/501243/), **CC0 1.0 Universal**. The file comes from a pinned, checksum-verified Ronumi mirror with explicit CC0 attribution; its original Freesound page was not independently fetched here. Rainfold trims that processed recording, applies gain and converts it to the renderer format. It is an actual recording, with no synthetic rain placeholder.

The runtime overlap gives a 29.5-second repeated period. The recording is loaded and validated once per renderer start, off the main thread and before AudioTrack creation; playback needs no decoder, disk reads or network. The WAV adds 2.88 MB of uncompressed assets and about 5.5 MiB of retained float PCM, plus temporary loading buffers. Source links, hashes, processing and reproducible preparation are in [docs/audio/RAIN.md](docs/audio/RAIN.md). The APK includes `RAIN_NOTICE.txt` and the full CC0 text.

## Build and verification

Open in Android Studio with JDK 21 (used by CI), Android SDK Platform 36 and Build Tools 36.0.0. Run:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

### Continuous integration

[Android CI](.github/workflows/android-ci.yml) runs on every pull request, pushes to `develop`/`main`, and manual dispatch from the Actions tab once the workflow is on the default branch. It uses Ubuntu 24.04, Temurin JDK 21 and the repository's Gradle Wrapper to run unit tests (including Robolectric), build the debug APK and run Android Lint. It does not start an emulator or use a connected device.

Gradle dependencies are cached; only branch push runs can update the cache. New runs cancel older runs for the same PR or branch, and each job has a 30-minute timeout. Actions are pinned to commit SHAs and the workflow token only has read access to repository contents. No repository secrets or release signing keys are required.

The run's **Artifacts** section provides `rainfold-debug` (APK, only when verification succeeds) and `verification-reports` (JUnit XML, HTML test reports and lint reports, also uploaded after a failed check when available). Both expire after 3 days to limit storage use. A failed test, lint error or build failure fails the job; existing lint warnings remain visible in the report. This CI does not replace the listening and device acceptance below.

### Verification status

Saved-scenes verification (6 November 2026): **87 tests pass with no failures, errors or skips; debug APK builds; lint reports 0 errors / 12 warnings**. This includes all 74 develop regressions and 13 new tests for recipe validation, immutable edits, favorites/limits, JSON recovery, service recreation, authoritative mix capture, media titles, and selection/copy/deletion without a new output or deadline. Idle selection stays silent; paused selection requires an explicit Play to resume. No device or emulator was used; large-font/TalkBack/landscape checks and real process/update acceptance remain open.

The managed cloud run used the retained JDK/SDK environment, the process-local heap/Metaspace limits below, and a temporary Gradle init script setting test JVM `user.home` to a writable directory and `robolectric.dependency.repo.url` to `https://repo.maven.apache.org/maven2`. This avoids Robolectric's download lock in the read-only `/home/agent`. The first run downloaded the existing develop test dependency and API 35 sandbox; the final run passed offline. These environment settings do not change the project or CI. See [HANDOFF.md](HANDOFF.md) for the exact command.

Local regression verification (6 November 2026): all 74 tests pass (0 failures, errors or skips), the debug APK builds, and lint reports 0 errors / 18 warnings. New coverage uses Robolectric on API 35 for actual Bundle transport and service Stop/destruction, plus deterministic fake-output tests for stale renderer callbacks, posted service errors and rapid Stop/Play versus Pause/Resume. The Bundle, stale callback and skipped fade-in regressions were reproduced before their fixes. No emulator or device was used. Robolectric is a test-only dependency; the APK dependencies are unchanged.

Earlier cloud verification: debug APK built; lint has 0 errors and 10 warnings (dependencies unchanged). 63 deterministic unit tests pass with no failures, errors or skips: the original 41 regressions plus mixing/headroom, independent mute/gain, rapid layer edits, loop period/overlap, shipped-asset checksum/PCM decoding, persistence migration and rain changes during focus interruption. Tests also verify one output and the unchanged deadline while rain is added, muted, adjusted and removed. Regression tests cover focus interruption sequences, commands at timer expiry, partial PCM writes, rapid renderer resume and output cleanup on failure. Renderer tests use a fake audio output; Android focus callbacks, actual AudioTrack behavior, wake locks and foreground transitions still require device acceptance. The cloud run used `source /workspace/.rainfold-env/env.sh`, `--offline`, `-Pkotlin.compiler.execution.strategy=in-process` and `-Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"`. The first combined run exhausted the default daemon Metaspace during lint; rerunning with these process-local memory limits succeeded. The repository has no configured formatter task; follow `.editorconfig`, including no final newline in text files.

Device verification must be explicitly authorized before installing or controlling a build. The initial emulator smoke check confirmed Play, background system Pause/Resume and an active foreground media notification with the screen off. This was a short functional check, not listening or endurance acceptance.

Still required for stage 1 device acceptance (deferred, still open):

- Two hours on a physical device with the screen off, checking underruns, battery use and audio continuity.
- Listening checks for start/pause/resume/stop, noise changes, volume changes and timer fade-out.
- Timer expiry on device, rotation and Activity destruction, process restart and saved preferences.
- Audio focus interruptions, wired/Bluetooth disconnection, notification and lock-screen controls.
- Android 8 and Android 15+ device coverage; Russian, TalkBack, large font and landscape checks.

Stage 2 device acceptance is also open: listen to rain alone and with each noise, verify layer transitions and at least 30 minutes of loop continuity, test focus/timer/lifecycle with layers, then measure CPU, buffer headroom, underruns and battery during a two-hour physical-device run. **Seamlessness by ear, long-term stability and resource consumption have not been confirmed.** No adb, devices or emulators were used in this iteration.

The audio catalog still contains only noise and one rain loop. Other ambience, accent layers, the remaining scene presets, Sleep/Focus session modes and widgets remain later work. Stage 3 UI acceptance also remains open: EN/RU, TalkBack, large font, narrow/landscape layouts, scene dialogs, favorite filtering and rapid scene changes during playback need device checks.