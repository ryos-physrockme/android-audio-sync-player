# Android Audio Sync Player

An Android app project for synchronizing local audio playback with sound captured from the phone's microphone.

**Status: early development. There is not yet a verified, automatically synchronizing Android app in this repository.**

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
- `app/` — Android implementation as development proceeds.

## Development status

This repository was created to develop the application. Build instructions will be added alongside an actual, buildable Android project. Do not assume an installable APK exists until it has been built and tested.

## Privacy and audio rights

Microphone input should be processed on-device, and reference tracks remain local. Do not commit copyrighted recordings, device logs with personal data, or large media assets to the repository. Audio files are selected by the device user and are not supplied with the app.

## License

No project license has been selected yet. Third-party software and audio assets retain their respective licenses and rights.
