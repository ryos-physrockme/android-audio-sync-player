# Android Audio Sync Player

An Android app project for synchronizing local audio playback with sound captured from the phone's microphone.

**Status: experimental automatic synchronization for the same recording is implemented. The Android app builds and its synthetic-signal tests run in CI, but real-world acoustic synchronization accuracy has not yet been measured on a physical Android device.**

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

Stage 1 has an experimental, offline implementation. Stages 2 and 3 are future experiments, **not implemented features**.

### Key distinction: detection is not synchronization

Recognizing a song and locating its position is only the first part. The listener hears the local audio after decoding, buffering, and headphone latency, while the microphone hears the external speaker after acoustic propagation and input buffering. A useful system needs to estimate and correct that timing difference. Bluetooth output and room noise make this harder.

## Current implementation

- **Reference preprocessing:** Android `MediaExtractor` and `MediaCodec` decode the selected local file, downmix to mono and linearly resample it to 16 kHz.
- **Features:** a self-contained, streaming Kotlin implementation of Hann-windowed spectral features (2048-point Fourier transform, 512-sample hop, 16 frequency bands).
- **Recognition:** the last 96 feature frames (about 3 seconds) are compared against the whole reference using a coarse-to-fine search. Insufficient or ambiguous matches are rejected.
- **Continuity check:** two strong, temporally consistent observations are required before starting/repositioning playback. An interrupted reference can be reacquired.
- **Playback:** Android `MediaPlayer` can automatically seek/start and periodically correct large estimated timing errors, subject to conservative rate limits.
- **Calibration:** a manual offset control allows −600 to +600 ms in 10 ms steps. Positive values advance the local audio position.
- **Privacy:** microphone audio is processed on-device; recordings are not stored or uploaded by the app.

**Not yet implemented:** sample-accurate output compensation, automatic Bluetooth latency measurement, playback-speed drift control, automatic correction for changing song tempo, and alignment of karaoke instrumental versions to the vocal recording.

The matching code is original Kotlin code. [Olaf](https://github.com/JorenSix/Olaf) remains a reference, **not a dependency**. Its AGPL-3.0 license and patent notices must be considered before any integration.

## Screen rotation and state preservation

Version 0.2.2-dev keeps the existing Activity and its in-memory microphone capture, audio-reference index and player alive across ordinary portrait/landscape orientation changes. The UI uses a single responsive ScrollView rather than separate portrait/landscape resources.

For other system-driven Activity recreations, the selected document URI is saved in Activity state and automatically reloaded and reindexed when possible. This fallback restores the file selection, but it does not promise uninterrupted playback in every situation.

Leaving the app (e.g., switching to another app or locking the screen) still stops microphone capture. Running sync in the background requires a separate foreground-service design and is not implemented.

## Practical trial and acceptance criteria

1. Load the same recording that a **separate** speaker or computer will play.
2. Connect wired headphones to the Android phone and tap **マイクを開始**.
3. Start the external speaker at an arbitrary point in the song. The phone should display a candidate position and start local playback after two consistent detections.
4. Adjust the manual timing offset if the headphone audio consistently leads or lags.
5. Pause or seek the external player and observe whether the phone reacquires it rather than jumping to a repeated passage.

**Important:** This remains a test procedure, not a guarantee that those actions will work reliably in different rooms or on different Android devices.

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

## Troubleshooting: position shown, but playback does not begin

The displayed candidate timestamp is a *possible* match, not an automatic-start confirmation. The app requires both a sufficiently distinctive match and a second temporally consistent match. The current numerical thresholds are experimental, not calibrated probabilities.

Version 0.2.1-dev adds separate diagnostic text:

- `再生保留：一致度が不足しています` — the spectral similarity is too low; use a louder, clearer playback of the **same exact recording**.
- `再生保留：似た候補が複数あります` — multiple parts of the reference sound similar; the app will not guess which chorus it is.
- `再生待機：2回目の位置確認が必要です` — one candidate passed the initial gate; confirmation is pending.
- `再生保留：候補時刻の進み方が一致しません` — the candidate did not advance consistently with time.
- `プレーヤー：シーク完了待ち` — the match was accepted and an asynchronous seek was requested. If that completion callback is delayed beyond 1.2 s, the app tries to start anyway and reports the attempt.
- `プレーヤー：再生中` — Android's MediaPlayer reports playback has started. This does **not** prove audio reaches the headphones at a correctly synchronized instant.

For the first trial, select the identical file on both devices, use wired headphones, keep the Android app visible, tap `マイクを開始`, and then play the recording from an external speaker. Do **not** tap the manual `再生` button: it intentionally stops microphone synchronization.

If playback does not start, report the full lines shown for `推定位置`, `一致度`, `他候補との差`, and `プレーヤー`; these distinguish recognition failures from player failures without collecting recordings.

## Development status and build instructions

The committed Android screen supports:

- Choosing a local audio file and indexing its spectral features offline.
- Playing, pausing, and manually seeking that file.
- Requesting microphone permission, displaying the input level, and estimating the playback position.
- Automatically seeking/starting the player only after a stable match, with an adjustable timing offset.
- Pausing microphone capture when the app is no longer in the foreground.

**This is an experimental synchronizer, not yet verified by physical measurements.** Spectral matching and software playback positions are not proof of audio reaching a listener at the correct time. Room acoustics, codec delays, audio route changes and repeated musical passages may cause errors. Begin tests with a wired headset and the identical local recording played by a separate speaker.

Build prerequisites: Android Studio, Android SDK API 36, Java 17, Gradle 8.13 and a compatible Android Gradle Plugin/Kotlin toolchain (versions are pinned in the Gradle build files). Open the project root in Android Studio, then run the `app` configuration on an Android device with a microphone. The minimum Android API level is 26.

The official Gradle 8.13 Wrapper is committed and its distribution SHA-256 is pinned. On Linux or macOS, run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows, run `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`. Android Studio can also open the repository root and use the wrapper directly.

[GitHub Actions](.github/workflows/android.yml) runs these tasks on every push, validates the Gradle Wrapper, and uploads a short-lived `android-debug-apk` artifact when the build succeeds. That APK includes **experimental same-recording synchronization**. It is not yet a verified karaoke-synchronization app, and tests on a real Android device are still outstanding.

Do not describe the APK as a validated synchronization product until acoustic matching, latency and repeatability have been measured on physical devices.

## Privacy and audio rights

Microphone input should be processed on-device, and reference tracks remain local. Do not commit copyrighted recordings, device logs with personal data, or large media assets to the repository. Audio files are selected by the device user and are not supplied with the app.

## License

No project license has been selected yet. Third-party software and audio assets retain their respective licenses and rights.
