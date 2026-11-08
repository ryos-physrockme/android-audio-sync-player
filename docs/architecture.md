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

### 1. Same recording (experimental implementation)

The current implementation uses a **short-time spectral feature sequence** for coarse matching. The reference is resampled to 16 kHz, the features use a 2048-sample Hann window and a 512-sample hop, and each normalized vector contains 16 log-frequency-band energies. A ~3 second rolling microphone window is compared against candidates across the selected track by average cosine similarity (coarse scan plus local refinement). The current feature positions have a 32 ms frame hop.

Initial confidence gates require a mean similarity of at least 0.72 and a margin of at least 0.075 over alternatives more than four seconds away, followed by two temporally consistent observations. **These thresholds are provisional and have not been calibrated with acoustic microphone recordings.** They must not be interpreted as statistical confidence probabilities. In particular, matches can still fail for repeated choruses, noisy rooms, quiet recordings, or speaker equalization.

The reference index is held in memory and currently capped at ~21 minutes. Native Android `MediaCodec` does the decoding; linear resampling can alias high frequencies if the source sample rate is above 16 kHz. Better anti-aliasing and sub-frame temporal alignment are possible improvements.

[Olaf](https://github.com/JorenSix/Olaf) remains a reference option and is **not included**. It carries AGPL-3.0 licensing and upstream patent notices; do not silently copy its implementation.

A separate physical measurement is needed to verify **audible** synchronization, which can differ from successful feature matching by the microphone-input and headphone-output latencies.

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

## Implementation status and unresolved decisions

- Same-recording spectral matching: implemented in pure Kotlin with deterministic synthetic-audio unit tests.
- Local-file decoding and playback: implemented using Android MediaExtractor/MediaCodec and MediaPlayer, respectively; still needs hardware tests.
- Timestamp mapping: use AudioRecord hardware timestamps when available, otherwise estimate from blocking reads; this does not remove all input/output latency.
- Next: quantify acquisition time, false matches and audible offset with physical devices; compare with alternatives if the current prototype is unreliable.
- No external matching library is bundled. Project licensing and relevant third-party restrictions should be decided before redistribution.

## References

See [references.md](references.md) and [test-plan.md](test-plan.md).
