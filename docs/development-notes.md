# 開発履歴・旧README

このファイルは、Android Audio Sync Playerの初期開発過程でREADMEに追加された詳細な実験記録を保存したものである。各節は追加時点の仕様を表す場合があり、最新の動作を必ずしも反映しない。

最新の仕様は[動作仕様](product-spec.md)と[機能一覧](feature-inventory.md)を優先する。Gitの変更履歴とPull Requestも各機能の実装経緯を確認するために利用できる。

---

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
5. In the initial-seek-only experiment (v0.2.3-dev), subsequent microphone matches **only display estimated timeline differences** and never automatically seek, pause, or alter playback speed. A manual offset control changes the next initial alignment position.

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

- **Reference preprocessing:** a direct streaming PCM16 WAV parser for RIFF/WAVE files; for compressed formats, Android `MediaExtractor` and `MediaCodec`, opening the selected document through its file descriptor when possible. Both compute mono spectral features at 16 kHz.
- **Features:** a self-contained, streaming Kotlin implementation of Hann-windowed spectral features (2048-point Fourier transform, 512-sample hop, 16 frequency bands).
- **Recognition:** the last 96 feature frames (about 3 seconds) are compared against the whole reference using a coarse-to-fine search. The minimum similarity is user-adjustable (0.50–0.95, default 0.72); ambiguous matches remain rejected via an independent fixed margin and two-observation confirmation.
- **Continuity check:** two strong, temporally consistent observations are required before the initial automatic seek. Repeated or ambiguous candidates are still rejected.
- **Playback (v0.2.5-dev):** Media3 ExoPlayer seeks once on initial lock. While the microphone continues matching, no further automatic seeks, pauses, or playback-speed changes are issued. To retry an initial alignment, stop and start the microphone again.
- **Diagnostics:** while tracking, the app shows the median of recent estimated media-time differences (external minus local), their change since the first accepted post-start observation, and the sample count. They are **not measurements of physical audio output latency**.
- **Calibration:** a saved manual offset control allows −600 to +600 ms in 10 ms steps and affects the next initial alignment only. Positive values advance the local position. Playback begins with a 600 ms fade-in.
- **Privacy:** microphone audio is processed on-device; recordings are not stored or uploaded by the app.

**Not yet implemented:** sample-accurate output compensation, automatic Bluetooth latency measurement, playback-speed drift control, automatic reacquisition after an external seek (the mic needs to be restarted), and alignment of karaoke instrumental versions to the vocal recording.

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

## Investigating a locally stored audio file that Android cannot decode

Version 0.2.4-dev adds a **file header diagnostic** to the main screen. After selecting a local recording, it reads only the first 128 KiB through the Android document URI (without saving or uploading any audio). This is an independent check from MediaPlayer and MediaExtractor.

For RIFF/WAVE files, it shows the WAV format code (integer PCM, IEEE float, WAVEFORMATEXTENSIBLE or unknown), bit depth, sample rate and channel count. It also recognizes common MP3, MP4/M4A, FLAC and Ogg signatures when a file has an incorrect extension.

If the player shows a generic error and the extractor says "Failed to instantiate extractor", the header result helps distinguish nonstandard WAV encoding from a valid-looking WAV that Android still cannot open. A valid header does **not** prove the remainder of the file is valid or that the phone supports the encoding. The same source file can be playable by one app while failing in MediaPlayer/MediaExtractor.

The code also prints the exception type and message if playback preparation throws synchronously. No source audio files are included in the repository.

## WAV loading fallback (version 0.2.5-dev)

On some Android devices, a file that appears as ordinary **RIFF/WAVE, PCM integer, 16-bit, 44100 Hz, stereo** fails in the platform's \`MediaExtractor\` and \`MediaPlayer\` while other audio apps can open it.

This version changes the two independent paths:

- **Reference feature extraction:** WAV PCM16 is parsed directly from the selected document input stream (RIFF chunk parsing, optional metadata chunks, mono/stereo, PCM data conversion, and resampling). This avoids Android's native \`MediaExtractor.setDataSource()\` for the target recording.
- **Playback:** the app now uses \`androidx.media3:media3-exoplayer\` (version 1.11.1). Media3 has its own WAV container extractor, so playback does not depend on the old platform \`MediaPlayer\` container parsing path.

Other file formats still use the original MediaExtractor/MediaCodec reference decoding path, and ExoPlayer for playback. This is an experimental compatibility fix and has not yet been validated on every device/file.

The first-initial-seek-only control behavior of v0.2.3 is preserved. No continuous automatic seeking was added.

### How to verify

1. Install the \`android-debug-apk\` from a successful GitHub Actions run for the new commit. This build may require uninstalling a previous CI debug build if its temporary signing certificate differs.
2. Choose the **same PCM16 WAV file** that previously displayed both \`Failed to instantiate extractor\` and the MediaPlayer error.
3. Report whether the app now shows the reference-feature analysis complete and whether the manual **再生** button produces sound. After those both work, test the microphone auto-start separately.

If either side still fails, retain the full on-screen error text: playback now surfaces Media3's descriptive error name, and the reference decoder reports failures independently.

## WAV data length recovery (v0.2.6-dev)

Some playable RIFF/WAVE PCM16 files contain an unknown `data` chunk length (`0xFFFFFFFF`) or a declared length that exceeds the bytes accessible through the Android document provider. The earlier direct WAV indexer discarded the entire feature index on end-of-file with `WAV audio data ended unexpectedly`, even after reading several minutes of usable audio.

The direct WAV indexer now processes all **complete interleaved PCM frames** available before end-of-file. It may finish with a warning rather than an error **only when at least 96 spectral frames are available** (about 3 seconds, the minimum matching window).

- If `data` size is `0xFFFFFFFF`, the screen reports the unspecified length and the actual number of bytes read.
- If a finite declared `data` size is larger than the bytes available, the screen reports both sizes and warns that the file may be truncated.
- Any incomplete trailing PCM frame is discarded with a warning.
- A file too short for useful matching is still rejected. Damaged or truncated audio is **not silently declared healthy**.

This addresses feature indexing, not playback, which continues using Media3 ExoPlayer. Automatic playback still performs only the first initial seek per microphone session. Real-device validation remains necessary.

## Playback sensitivity, fade-in and manual headphone offset (v0.2.7-dev)

Once the phone can load and recognize the same recording, the two adjustable controls address different stages:

- **再生開始の一致度閾値** is a similarity-score threshold from **0.50 to 0.95**, in steps of 0.01; the default remains **0.72**. Decreasing it may make the first match easier, at the risk of false starts. Increasing it rejects weaker matches. The separate **0.075 runner-up margin** and **two consecutive temporally consistent matches** are always required, so the slider does not disable safeguards for repeated choruses. Settings take effect on the next microphone matching window, even while listening.
- **次の初回同期位置の補正** is the existing headphone timing offset from **−600 to +600 ms** in 10 ms steps. A positive number seeks the local recording ahead on the next initial synchronization. Changing it after playback begins does **not** jump the song or correct that playback session.
- Both slider values are retained in the app's private settings across normal restarts and display rotation. Uninstalling the app clears them.

Automatic starts and manual Play/Resume now use a **600 ms smooth fade-in** of player volume: initially silent, progressively reaching normal level. The operation does not alter media position or playback rate; only audio gain changes. If play is cancelled, the volume returns to normal to prevent a player remaining muted.

**Limitations:** these settings do not measure or automatically compensate for Bluetooth headphone latency. The app continues the *one initial seek per microphone listening session* behavior to avoid audible oscillating re-seeks. There are no continuous automatic adjustments after initial lock.

### MP3 and M4A compatibility

Media3 playback supports commonly used MP3 and M4A containers, subject to codec/device support. However, the *reference-feature indexing* pipeline still uses Android `MediaExtractor` / `MediaCodec` for non-WAV formats and has not been validated on real MP3/M4A recordings. Thus the current build must **not** be advertised as fully supporting automatic synchronization for those formats. Separate format-by-format device tests should come after validating the waveform and user controls with known-working WAV recordings.

## Manual fine adjustment during playback (v0.2.8-dev)

Two buttons directly beneath Play/Pause allow the listener to adjust by 50 ms. **In v0.2.10 they use small playback-speed changes instead of seeks.** See the newer section below.

- Each tap moves the **local** playhead by about 50 milliseconds. Consecutive quick taps accumulate by using the most recently requested seek position.
- The interface displays the most recent requested correction and the sum of manual nudges in the current listening session. This is a command log, not a measurement of the audible time shift.
- The buttons are available only after a recording is prepared and playback has been requested. They are disabled during the initial automatic seek and while playback is paused.
- **The existing headphone offset slider is separate**: it affects the *next* initial automatic alignment. The ±50 ms buttons act immediately on the current playback and do not modify the stored offset.
- The app never seeks automatically again during continuous playback in this experimental mode. However, Media3 seeks may briefly buffer or make an audible discontinuity. They cannot guarantee artifact-free correction, especially for compressed audio.

This small seek is a deliberate first experiment. If even ±50 ms seeks are distracting, a future approach could briefly vary playback speed to accumulate a correction smoothly rather than jumping.

## Compressed local audio: MP3 and M4A/AAC (v0.2.9-dev)

The local player already uses Media3 ExoPlayer. This revision improves the independent **reference-feature indexing** path for compressed recordings:

- MP3, including constant- and variable-bitrate files, and M4A containing AAC use Android `MediaExtractor` and `MediaCodec` to decode the selected file to mono samples for the spectral matcher.
- Instead of relying only on `MediaExtractor.setDataSource(Context, Uri)`, the app first attempts the system-picker-granted file descriptor, including its offset/length. If this fails, it retries the original URI with a new extractor and reports the failed path.
- The local header diagnostic now recognizes raw MPEG audio frame sync when an MP3 has no `ID3` header. This is for diagnostics, not decoding.
- The existing direct PCM16 WAV indexing path remains unchanged.

**Format scope:** this is experimental support for MP3 and AAC-in-M4A, not an assertion that every MP3, MP4 profile, codec, device or external recording will work. Other audio formats are not validated. Audio input remains offline and no media data is uploaded.

### Reproducible Android codec test

A device/emulator instrumentation test covers reference indexing and Media3 preparation of three original, generated 8-second recordings: constant-bitrate MP3, variable-bitrate MP3, and AAC-in-M4A. Files are generated as required and are not committed to the repository:

```bash
bash scripts/generate-audio-format-fixtures.sh
./gradlew :app:connectedDebugAndroidTest
```

Use a supported Android device or an emulator. The dedicated [compressed-audio CI workflow](.github/workflows/audio-formats.yml) performs the same test using an Android emulator when audio-indexing code changes. This test verifies local container/decoder handling, **not** acoustic synchronization against an external speaker or headphone latency.

The next physical test should select the *same compressed file* on the external player and the phone, wait for `解析完了`, then check automatic synchronization and the manual ±50 ms adjustment. Re-encoded versions of a song can have different encoder delay or padding and should be measured separately.

## Smooth timing adjustment without repeated seeks (v0.2.10-dev)

**Why the change:** On a real Android device, pressing the `+50 ms` button during playback made the music sound paused or reloading. Version 0.2.9 used `Media3.seekTo()` for every button press, and a seek can trigger buffering even when playback intent (`playWhenReady`) is still true.

In v0.2.10, pressing `＋50 ms 進める` or `−50 ms 遅らせる` **does not seek or pause the local media**. Instead, the app requests a temporary small speed change through Media3:

- `+50 ms` queues ~50 ms of forward correction by temporarily playing at **1.05x**, which takes approximately 1 second at that speed.
- `-50 ms` queues ~50 ms of backward correction by temporarily playing at **0.95x**, also roughly 1 second.
- Media3's speed adjustment aims to **preserve pitch**. Audible time-stretch artifacts or buffering can still depend on the device and selected codec.
- Repeated taps queue additional corrections; taps in the opposite direction can cancel remaining correction. Pending correction is capped at ±500 ms to avoid very long adjustments.
- The screen shows the requested playback speed and the *approximate remaining correction*, not an independently measured acoustic offset. The implementation uses elapsed monotonic time while playback is active to estimate how much of the request has been applied.
- The speed returns to **1.00x** at the end of the correction and whenever playback is paused, the file is changed, or the Activity stops. The initial synchronization seek is unchanged.
- The separate headphone offset slider remains a calibration for the **next initial lock**, and is not modified by these buttons.

This experiment prioritizes continuous listening over instantaneous 50 ms movement. Changing the speed may itself introduce temporary buffering, so compare both directions on the actual device.

**Testing:** pure JVM tests cover forward/backward changes, repeated taps, cancellation, buffering time and bounded queues. A dedicated Android emulator test exercises Media3's speed setting and playback intent. Neither substitutes for an audible end-to-end device test.

## Four manual timing controls (v0.2.11-dev)

The four timing adjustment buttons are arranged in a single row, with the larger corrections on the outside:

```text
[−100 ms] [−50 ms] [+50 ms] [+100 ms]
```

- Negative values delay local playback relative to the external recording; positive values bring local playback ahead.
- **The existing smooth adjustment method is unchanged.** At 0.95x or 1.05x speed, a 50 ms request takes roughly 1 second, while a 100 ms request takes roughly 2 seconds. Playback is not paused or sought.
- Multiple taps, including combinations of 50 ms and 100 ms, add to the pending correction; opposite signs cancel outstanding time. The ±500 ms maximum pending correction remains in force.
- The buttons are enabled only while the local recording is playing. The small controls use short labels and reduced minimum width for narrow portrait screens.
- The separate saved headset offset slider still applies only to the next initial synchronization. The correction buttons do not change its value.

This is still experimental speed-based time adjustment. Audio speed changes may sound different or trigger buffering on some Android devices; audible continuity and physical timing precision still require on-device evaluation.

## Development status and build instructions

The committed Android screen supports:

- Choosing a local audio file and indexing its spectral features offline.
- Playing, pausing, and manually seeking that file.
- Requesting microphone permission, displaying the input level, and estimating the playback position.
- Automatically seeking/starting the player only after a stable match, with an adjustable timing offset.
- Pausing microphone capture when the app is no longer in the foreground.

**This is an experimental synchronizer, not yet verified by physical measurements.** Spectral matching and software playback positions are not proof of audio reaching a listener at the correct time. Room acoustics, codec delays, audio route changes and repeated musical passages may cause errors. Begin tests with a wired headset and the identical local recording played by a separate speaker.

### One-seek trial (v0.2.3-dev)

1. Select the same local recording that an external speaker will play; allow indexing to finish.
2. Connect headphones and tap **マイクを開始**. Do not manually start the phone's player.
3. Start the external speaker, including from partway into the recording if desired. After a confirmed match, the phone should seek to the estimated position and play.
4. Continue listening without touching the controls. The phone should no longer jump forward/backward due to estimated error; watch the estimated offset and its change on screen.
5. To try another initial lock, **stop and restart the microphone**. The current app cannot automatically follow an external pause/seek in this mode.

The estimated difference displayed on the phone is derived from a microphone-based position estimate and MediaPlayer's software playback position. It does not measure acoustic sound at your ears. A constant difference may reflect device latency or feature estimation bias. Record separately whether the audible sync sounds steady, late, or early.

**Update installation:** GitHub Actions debug builds may be signed using different temporary debug keys. If Android refuses an in-place upgrade, the old debug app may need to be uninstalled before installing the new APK. Do not share or commit a production signing key.

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
