# Android Audio Sync Player

An Android app project for synchronizing local audio playback with sound captured from the phone's microphone.

**Status: early development. A local-file player and microphone-input monitor have been committed, but automatic acoustic synchronization has not yet been implemented or verified on a physical device.**

## What it is for

Imagine a karaoke machine or an external speaker playing a song. You want to hear a local recording of that song through headphones, aligned with the sound in the room, without manually starting both devices at exactly the same time.

The intended workflow is:

1. Choose a **locally stored** audio file on the phone.
2. Tap **Listen / Sync** and allow microphone access.
3. Start playback on the external device (or join while it is already playing).
4. The app estimates the external playback position and starts/seeks the local player to the corresponding position.
5. While playing, it corrects drift without frequent audible jumps. A manual timing-offset control compensates for headphone and device latency.

The target is **acoustic synchronization**, not clock synchronization between two cooperating players. No network connection or modification to the external playback device should be required.

## Scope

| Stage | External sound | Local reference | Goal |
| --- | --- | --- | --- |
| 1 | Speaker playing the same recording | The same audio file | Establish reliable offline acoustic alignment |
| 2 | Karaoke accompaniment | Original song including vocals | Align different mixes/recordings |
| 3 | Live musical performance | Reference recording | Follow tempo changes and pauses |

Stage 1 is the immediate target. Stages 2 and 3 are future experiments, **not implemented features**.

### Key distinction: detection is not synchronization

Recognizing a song and locating its position is only the first part. The listener hears the local audio after decoding, buffering, and headphone latency, while the microphone hears the external speaker after acoustic propagation and input buffering. A useful system needs to estimate and correct that timing difference. Bluetooth output and room noise make this harder.

## Proposed Android components

- **Audio input:** Android `AudioRecord`, with an explicit microphone permission prompt and an input-device choice where supported.
- **Reference preprocessing:** decode and index the chosen local audio file on-device.
- **Position estimation:** start with a same-recording matcher; investigate [Olaf](https://github.com/JorenSix/Olaf) and alternative fingerprints, then music-alignment features for other recordings.
- **Playback:** an Android local-file player (`Media3 / ExoPlayer` is a candidate); coarse seek first, then small controlled corrections.
- **Calibration and diagnostics:** adjustable timing offset; log confidence, estimated position, and correction size without storing raw microphone recordings.

The exact matching library is **not selected yet**. Olaf is distributed under **AGPL-3.0** and includes patent notices; check licensing and relevant intellectual-property constraints before integrating its code. No Olaf code is vendored into this project.

## Success criteria for the first prototype

Use a speaker playing the **same recording** as the one selected in the app. Start the app both before and after the speaker starts.

- Correctly recognize the playback position from ordinary microphone input.
- Synchronize after starting in the middle of a track, not only at time zero.
- Recover from the speaker being paused or moved to another part of the track.
- Avoid jumping to a repeated chorus unless the match is sufficiently confident.
- Measure audible timing error using a wired output first, then evaluate Bluetooth.
- Run entirely offline and avoid uploading any microphone or music data.

These are acceptance tests, **not measured performance claims**.

## Repository layout

- `docs/architecture.md` — synchronization model, design options, failure cases.
- `docs/test-plan.md` — repeatable experiments, measures and acceptance criteria.
- `docs/references.md` — technical references and licensing considerations.
- `app/` — Android project containing a basic local-file player, microphone-level monitor, and unit-tested timing calculation.
- `.github/workflows/android.yml` — automated JVM unit tests and Android debug build.

## Development status and build instructions

The committed Android screen supports:

- Choosing a local audio file using Android's system file picker.
- Playing, pausing and seeking that file.
- Requesting the microphone permission and displaying the microphone input level in dBFS (without saving the capture).
- Stopping the microphone when the activity leaves the foreground.
- A separately tested function for calculating a playback target from a timestamped reference position.

**It does not yet recognize a track position or synchronize playback.** The microphone-level display is a capture smoke test, not a matcher.

Build prerequisites: Android Studio, Android SDK API 36, Java 17, Gradle 8.13 and a compatible Android Gradle Plugin/Kotlin toolchain (versions are pinned in the Gradle build files). Open the project root in Android Studio, then run the `app` configuration on an Android device with a microphone. The minimum Android API level is 26.

The official Gradle 8.13 Wrapper is committed and its distribution SHA-256 is pinned. On Linux or macOS, run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows, run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`. Android Studio can also open the repository root and use the wrapper directly.

[GitHub Actions](.github/workflows/android.yml) runs these tasks on every push, validates the Gradle Wrapper, and uploads a short-lived `android-debug-apk` artifact when the build succeeds. That APK is **only a playback/microphone smoke-test build**, not an automatic synchronization app. The first tests on a real Android device are still outstanding.

Do not distribute APKs as a working synchronization product until acoustic matching and headphone latency have been implemented and measured.

## Privacy and audio rights

Microphone input should be processed on-device, and reference tracks remain local. Do not commit copyrighted recordings, device logs with personal data, or large media assets to the repository. Audio files are selected by the device user and are not supplied with the app.

## License

No project license has been selected yet. Third-party software and audio assets retain their respective licenses and rights.
