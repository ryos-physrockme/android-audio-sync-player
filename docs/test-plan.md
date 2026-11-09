# Test plan: measuring audible synchronization

**Current test coverage:** deterministic JVM tests exercise synthetic equal-recording matching, altered gain/noise, silence rejection, repeated segments, temporal agreement and resampling. The CI build tests compilation only; no acoustic room or headset measurements have been performed. The experiments below remain outstanding.

## Field troubleshooting for the first Android build

When a candidate time is displayed but the phone remains silent, capture these on-screen diagnostics: match score, score margin, confirmation status, and player status. Distinguish low-confidence or discontinuous candidate streams from a successful seek with missing playback. Do not treat a displayed candidate position as a successful lock.

Version 0.2.1-dev uses a bounded fallback for the MediaPlayer seek-completion callback. Check whether the fallback was used and measure any extra audible timing error if it is.

## One-initial-seek experiment (v0.2.3-dev)

The immediate user-observed failure was repeated forward/backward seeking during continuous music playback. The experimental app now **allows only the initial automatic seek** in each microphone session, then keeps recognizing external audio for diagnostics without seeking or changing playback speed.

Procedure:

1. Select an identical reference file on Android and the external playback device.
2. Connect wired headphones, begin microphone monitoring, and play the source on the external device.
3. Record whether initial automatic playback starts, and whether any automatic jumps occur after it starts.
4. Watch the on-screen **estimated timeline difference (external minus phone)**, its recent median, change since the first accepted post-start estimate, and the observation count. The estimate is not the acoustic offset at the ear.
5. Listen for persistent constant offset, increasing lag/lead, and random jitter; make a note of duration.
6. If the external source is paused, skipped, or restarted, note that re-alignment requires stopping and restarting microphone monitoring in this trial.

The success criterion for this experiment is absence of automatic back-and-forth seeks during a continuously playing same-source recording, **not** a claim that the two acoustic outputs are aligned to any fixed precision.

A possible subsequent strategy, inspired by manual correction, is to pause the local player when it runs *ahead* of the external media timeline and resume only after the external source catches up. This must be evaluated separately because pause/resume itself creates an audible gap and the signal estimate can be noisy. It is **not implemented** in v0.2.3-dev.

## Direct PCM WAV data-length compatibility

The synthetic parser tests cover four distinct cases: a correct finite `data` size; an unknown `0xFFFFFFFF` length typical of streaming output; an overstated finite length with sufficient complete audio frames; and a truncated file too short to obtain the required 96 spectral frames. The first three should produce a usable feature index; the last must fail.

With a real recording, verify that the app displays `解析完了` and permits microphone monitoring. For inconsistent lengths, the UI must also report the declared length (if known) and the count actually read. Successful indexing does **not** demonstrate that a damaged file has no missing tail.

## Adjustable matching threshold and playback fade-in (v0.2.7-dev)

Acceptance tests for the new UI:

- With the threshold at 0.72, first-lock recognition should be comparable to the previous build.
- Lowering the threshold to 0.60 can permit weaker matches, **but may lead to false lock**; the fixed runner-up margin of 0.075 and the second consistent match must remain active.
- Setting a high threshold (e.g. 0.90) should visibly cause `再生保留：一致度が不足しています` when matches are below the selected score.
- Confirm that changing the threshold during microphone listening changes matching decisions on subsequent windows, without restarting or jumping the already-playing reference.
- Confirm that automatic start and manual playback each begin audibly at low gain and rise to normal volume in about 600 ms. Verify that pausing/switching files does not leave the player muted.
- Confirm offset and threshold settings persist through normal rotation and application restarts, but changing the headphone offset does not seek during active playback.
- Record any unusual behavior with Bluetooth: fading the *player* gain is independent from device/system volume and from headset latency.

MP3 and M4A are not yet validated end-to-end; test those separately with licensed reference recordings once the WAV baseline remains stable.

## Manual ±50 ms playback nudge (v0.2.8-dev)

Test with the same WAV recording playing externally and locally, after the automatic initial lock.

- During local playback, tap `−50 ms 戻る` and `＋50 ms 進む` separately. The local time should move in 50 ms steps, while **the audio continues playing**; it must not invoke `pause()` or cancel microphone matching.
- Rapid repeated taps should accumulate their requested offsets; try near the beginning and end of the track to verify bounds.
- The cumulative command display should reset after choosing another recording or beginning a new microphone-listening session. The manual initial offset slider must remain unchanged.
- Paused local playback should not respond to the nudge buttons; they should become active again after manual resume.
- Verify that manual nudging does not re-enable the old automatic seeking loop. The microphone may still show new timeline errors because the player has been moved.
- Record any audible click, repeated fragment or buffering glitch. Millisecond seek targets do not guarantee millisecond acoustic accuracy.

## MP3/M4A local decoding checks (v0.2.9-dev)

- An Android emulator smoke test uses synthetic 8-second audio encoded as 192 kbps CBR MP3, variable-bitrate MP3 and AAC-LC in M4A. Fixtures are produced by `scripts/generate-audio-format-fixtures.sh` with FFmpeg and not committed.
- Each instrumented test must index more than 96 usable spectral frames and prepare Media3 ExoPlayer without a loading error.
- A separate *physical* experiment remains necessary for the microphone to follow externally played MP3 and M4A. Different encoders may add silent leading samples and timing offsets.
- Keep the WAV baseline and manual ±50 ms adjustment available; do not disable the independent ambiguity check to force compressed files to match.
- Record format metadata, audio duration, device, codec errors and whether the sync position deviates systematically from WAV of the same original recording.

## Smooth manual timing correction (v0.2.10-dev)

The user reported that pressing `+50 ms` in v0.2.9 effectively halted playback. The button used an asynchronous `Media3.seekTo()`, which may enter a buffering state; `playWhenReady` alone does not mean audio is being output.

The replacement uses 1.05x and 0.95x speed changes for approximately one second to accumulate a 50 ms correction, returning to 1.00x afterwards. Test:

1. With local WAV/MP3/M4A actually playing and microphone listening, press `＋50 ms 進める`; check that the time position never jumps and that playback speed returns to 1.00x after about a second.
2. Press `−50 ms 遅らせる`; listen for dropouts and pitch or time-stretch artifacts. A nonzero duration is necessary: this **does not** instantly move the local playhead 50 ms.
3. Press either button several times quickly; verify the corrections accumulate, opposite signs cancel, and the queue is bounded to ±500 ms.
4. Pause the music, choose another source, and navigate away. Verify the playback speed is restored to exactly 1.00x and no correction loop survives.
5. Check that the microphone continues tracking, the independent initial offset remains unchanged, and the app never resumes automatic seek oscillations.

Media3 may buffer when its playback parameters change. Record any such buffering separately from the original `seekTo()` stalls; do not claim artifact-free playback until device testing.

## What to measure

The goal is not just a correct track label. It is the difference between the acoustic signal heard from the external source and the locally played reference at the listener.

Record at least:

- **Acquisition time** [s]: interval from microphone listening start to the first sustained, correct position estimate.
- **Timeline error** [ms]: difference between estimated reference position and a known, externally generated ground-truth position.
- **Audible synchronization error** [ms]: the actual time offset between the external and local output.
- **False-lock rate**: incorrect track/segment alignments per attempt.
- **Recovery time** [s]: time to regain the correct position after an external pause, skip, or restart.
- **Correction behavior**: frequency and magnitude of seeks/speed adjustments and whether glitches are audible.
- **Battery, CPU, memory**: evaluate after correctness.

A correct audio fingerprint time estimate does not by itself demonstrate audible synchronization. Keep these measurements separate.

## Fixtures

Use an original or appropriately licensed WAV test recording containing several distinct passages, repeated passages, and short silences. The exact file, duration, sample rate, and hash must be recorded for reproducibility; do not commit commercial songs.

The speaker should be driven by a separate device. The Android phone should recognize the sound from its **own built-in microphone**; the local track should be played over wired headphones for baseline tests.

## Experiment A — clean same-recording match

1. Play the same WAV file on the external speaker and load it into the phone.
2. Start listening before the speaker starts, then repeat with listening started midway.
3. Repeat across beginning, middle, ending, and a repeating section.
4. Compare each estimated position to the externally logged ground truth.
5. Record acquisition time, position error and false-lock rate across repeated runs.

## Experiment B — robustness

Repeat A with different speaker volume, phone-to-speaker distance, room reverberation, background conversations, and music played through a compressed format. Explicitly report when the matcher cannot lock.

## Experiment C — discontinuities

Pause the external playback for several seconds, skip forward or backward, and resume. Report whether the app holds its previous position, enters an uncertain state, and re-locks correctly without jumping to unrelated repeated content.

## Experiment D — output and headset latency

Measure *audible*, not just software-reported, alignment. Record the external speaker and headset output simultaneously with appropriate recording hardware or an acoustic coupler. If such hardware is unavailable, report subjective listening tests only as subjective tests.

Run wired headphones first. Then repeat with several Bluetooth devices. Report the user calibration offset, codec when known, and whether reconnecting changes the offset.

## Experiment E — karaoke and alternative recordings (later phase)

Pair an instrumental backing track with the original recording. Determine whether a stable correspondence exists and where edited sections, tempo changes, or key differences break it. Compare recognition approaches on the same fixtures rather than relying on anecdotal successes.

## Provisional engineering targets (not claims)

These are acceptance targets for future iterations, not existing results:

- Initial matching from the middle of a same-recording track without user alignment.
- No confident false seek in repeated-section tests.
- Low enough audible offset for singing practice, to be quantified through repeated tests.
- Offline processing with no microphone recordings uploaded or saved by default.

Set numeric acceptance thresholds after an initial measurement series; early tests must report measured error distributions, not only successful demos.

## Reporting template

| Device / headset | Source file hash | Room condition | Start mode | Acquisition [s] | Position error p50/p95 [ms] | Audible error p50/p95 [ms] | False locks | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| (fill in) | (fill in) | (fill in) | start/mid | — | — | — | — | — |

Do not mix algorithm-only simulation results with physical-device measurements.
