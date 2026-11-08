# Architecture: local-audio synchronization from a microphone

## Objective

An Android phone listens to a source it does not control (external speaker, karaoke system, or live instrument) and plays a selected local audio recording in time with it. Audio must be processed on the phone without requiring network access.

The first implementation targets **the same recording** being played externally and locally. Different recordings and live performances require additional alignment methods and are explicitly out of scope for the first validation.

## System stages

1. **Select and decode a reference:** use Android's Storage Access Framework to obtain a user-granted content URI. Extract audio features, not a permanently duplicated recording.
2. **Capture:** use `AudioRecord` to collect short overlapping microphone windows; capture timestamps where available.
3. **Recognize:** compute audio features from each window and match several consecutive windows against the reference index.
4. **Track:** estimate the reference position and confidence, using the previous position, elapsed time, and allowed playback state changes.
5. **Play:** seek the local player after a confident initial lock; correct large discontinuities cautiously; use small bounded speed adjustments for modest drift if audible quality permits.
6. **Calibrate:** offer a user-adjustable offset because microphone position, audio output, and Bluetooth codecs introduce latency.

The microphone must stay active while playback occurs. Echo or leakage from the headphones can contaminate recognition; test with wired headphones first.

## Time coordinates

Define:

- `p_ref` [s]: matched position in the reference recording for a specific microphone sample or feature frame;
- `t_mic` [s]: timestamp when that acoustic sample reached the microphone, expressed on a monotonic clock;
- `t_now` [s]: current time on the same monotonic clock;
- `L_output` [s]: additional time from player scheduling until audio is actually heard, including buffering and headphone latency;
- `delta_user` [s]: user-adjustable offset, including room acoustic propagation differences.

A first approximate target player position for audio **heard** at `t_now + L_output` is:

`p_target = p_ref + (t_now - t_mic) + L_output + delta_user`.

The sign and meaning of `delta_user` must be specified in the UI and verified acoustically; it is not a replacement for latency measurement. The recording's decoded media position alone does not reveal when samples reach the listener.

If a feature window is centered in a buffer, `t_mic` must refer to the center, not to the time when processing finishes. Otherwise, algorithmic window delay is mistaken for playback delay.

## Matching approaches

### 1. Same recording (first milestone)

Candidate method: robust spectral landmarks or short-time spectrogram feature sequences. A single FFT frame is insufficient; matching multiple frames gives time resolution and reduces false detections, especially for repeated sections. Start with an offline evaluator before selecting an Android implementation.

Possible implementations:

- A small implementation of spectral features and window matching written specifically for this project, with explicitly measured memory and CPU costs.
- [Olaf](https://github.com/JorenSix/Olaf), a portable C acoustic-fingerprinting system, integrated through the Android Native Development Kit only after review of compatibility, licensing, and relevant patent notices.

Olaf code is **not included** in this repository. Its license is AGPL-3.0, and the upstream README discusses potentially relevant patents. Do not silently copy its implementation.

Do not assume fingerprint matching gives sub-frame timing accuracy. After a confident coarse match, a local cross-correlation / generalized cross-correlation refinement may be tested for recordings of the **same signal**; microphone coloration, noise, echo, and compression can reduce its precision.

### 2. Karaoke backing track versus commercial recording

Fingerprints of isolated recording-specific events may differ. Compare chroma, onset or other harmonic/rhythmic features, potentially with dynamic time warping or online alignment. Some karaoke tracks have extra count-ins, edited breaks, key changes, or different tempo; a single constant time offset may not exist.

### 3. Live performance

Tempo can change, and notes can be skipped, held, or repeated. A score/audio follower or online sequence-alignment method is needed. Do not promise applicability until independently tested.

## State machine

- `IDLE`: no microphone capture.
- `INDEXING`: preparing reference features.
- `LISTENING`: capturing but not yet confident in the match.
- `LOCKED`: a consistent match has been established; local playback is scheduled/playing.
- `UNCERTAIN`: insufficient matching confidence; maintain a cautious prediction, avoid large seeks.
- `RECOVERING`: external track stopped or jumped; seek only after robust re-identification.
- `ERROR`: unsupported file format, missing microphone permission, or unrecoverable audio route failure.

A track's repeated chorus can generate several plausible offsets. Keep multiple candidates if needed, require consistent evidence before seeking, and report confidence independently from estimated error.

## Android considerations

- Use `ACTION_OPEN_DOCUMENT` or a registered `OpenDocument` contract for user-selected local files. No broad storage permission is needed for this workflow.
- Use the `RECORD_AUDIO` runtime permission with an explicit in-app start/stop control.
- Run capture, decoding and matching on background threads, and close audio resources on stop.
- Do not require background microphone access in the first version. Later versions must follow Android foreground-service and notification rules.
- Provide a clear route/device indicator. Bluetooth headsets may change the input device or sample rate and add significant output latency.
- Persist only app settings and optional extracted features; by default, do not store microphone recordings.
- Music playback and live microphone capture should work offline.

## Unresolved decisions

1. Benchmark a straightforward same-recording matcher versus Olaf in an offline test, with noise and room-response changes.
2. Select the playback implementation (Media3 or built-in Android player) and verify actual audible output latency.
3. Decide whether matching is entirely in Kotlin or uses a native library.
4. Decide licensing for original code before redistribution or incorporation of third-party code.

## References

See [references.md](references.md) and [test-plan.md](test-plan.md).
