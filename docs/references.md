# Technical references

Last reviewed: 2026-11-09. These references inform architecture; none establishes that this app already works.

## Matching and alignment

- Joren Six, **Olaf: Overly Lightweight Acoustic Fingerprinting** (portable C implementation and documentation): https://github.com/JorenSix/Olaf
  - Upstream license: AGPL-3.0. Read upstream patent notices and seek review before incorporating or redistributing.
  - The upstream browser demonstration includes live matching; this does **not** mean that the Android application is already implemented.
- Meinard Müller, **Fundamentals of Music Processing** (Springer, 2015). Reference on audio features, temporal alignment, and music retrieval: https://www.audiolabs-erlangen.de/fau/professor/mueller/bookFMP
- Simon Dixon, **Live Tracking of Musical Performances Using On-Line Time Warping**, Proceedings of the International Conference on Digital Audio Effects, 2005. A classic reference for online musical alignment: https://www.dafx.de/paper-archive/2005/P_107.pdf

## Android platform documentation

- Microphone capture (`AudioRecord`): https://developer.android.com/reference/android/media/AudioRecord
- Media playback (Media3 ExoPlayer): https://developer.android.com/media/media3/exoplayer
- Storage Access Framework: https://developer.android.com/guide/topics/providers/document-provider
- Requesting microphone permission: https://developer.android.com/training/permissions/requesting
- Audio timestamps: https://developer.android.com/reference/android/media/AudioTimestamp
- Audio routing: https://developer.android.com/reference/android/media/AudioRecord#setPreferredDevice(android.media.AudioDeviceInfo)
- Foreground service type: https://developer.android.com/develop/background-work/services/fgs/service-types

## Licensing and rights

This repository does not currently vendor Olaf or other matching libraries. License and patent questions must be resolved before copying code or distributing a compiled product. Media files used for tests must be appropriately licensed and should not be committed.
