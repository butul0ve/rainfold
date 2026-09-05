# Rainfold agent rules

## Working agreement

- Use Kotlin and the current stable Android stack: Jetpack Compose, Material 3, Coroutines/Flow and AndroidX Media3. Prefer platform APIs and stable dependencies over custom frameworks.
- Keep DSP/domain code independent of Android SDK types. Write deterministic unit tests for generators, mixing, limiting and time calculations.
- The audio engine belongs to `MediaSessionService`, never an Activity, Composable or ViewModel. Release `AudioTrack`, media session and foreground-service state when playback ends.
- Preserve offline operation. Do not add analytics, ads, network access or cloud dependencies without explicit approval.
- Calculate session duration from a monotonic clock. Avoid periodic decrementing counters.
- Prevent audible clicks, clipping and underruns: use ramped fades, headroom/limiting and appropriately sized audio buffers.

## Devices and emulators

- Obtain explicit user approval before running adb/emulator commands, installing or launching an app, or interacting with a connected phone or emulator. A connected device may be occupied by unrelated work; its presence is not authorization.
- Agree on the target device and scope of the check first. Do not start, stop, wake, lock, reconfigure or otherwise control a device outside that scope.
- Local unit tests, lint and APK builds can run without device approval. Building an APK does not authorize installing it.

## Code quality

- Keep changes focused, readable and idiomatic. Do not mix refactors with feature work unless necessary.
- Run the narrowest relevant formatter, unit tests and build task before handing work off; report anything that cannot be run.
- Update README or other docs when public behaviour or project setup changes.
- Every text file must have **no trailing newline**. `.editorconfig` enforces this; verify before committing.

## Git

- Make atomic commits: one coherent, independently reviewable intent per commit.
- Never amend, reset, force-push or discard existing work unless the user explicitly requests it.
- Inspect `git status` before staging; stage only files belonging to the current change.