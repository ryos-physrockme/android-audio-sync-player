# Test plan: measuring audible synchronization

**Current test coverage:** deterministic JVM tests exercise synthetic equal-recording matching, altered gain/noise, silence rejection, repeated segments, temporal agreement and resampling. The CI build tests compilation only; no acoustic room or headset measurements have been performed. The experiments below remain outstanding.

## Field troubleshooting for the first Android build

When a candidate time is displayed but the phone remains silent, capture these on-screen diagnostics: match score, score margin, confirmation status, and player status. Distinguish low-confidence or discontinuous candidate streams from a successful seek with missing playback. Do not treat a displayed candidate position as a successful lock.

Version 0.2.1-dev uses a bounded fallback for the MediaPlayer seek-completion callback. Check whether the fallback was used and measure any extra audible timing error if it is.

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
