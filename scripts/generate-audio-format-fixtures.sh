#!/usr/bin/env bash
set -euo pipefail
# From the repository root; requires ffmpeg with MP3 and AAC encoders.
# Synthetic tones only: no copyrighted music in test fixtures.
mkdir -p app/src/androidTest/assets
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=523:sample_rate=44100:duration=8" -ac 2 -ar 44100 -c:a libmp3lame -b:a 192k app/src/androidTest/assets/fixture-cbr.mp3
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=659:sample_rate=44100:duration=8" -ac 2 -ar 44100 -c:a libmp3lame -q:a 4 app/src/androidTest/assets/fixture-vbr.mp3
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=784:sample_rate=44100:duration=8" -ac 2 -ar 44100 -c:a aac -b:a 160k -movflags +faststart app/src/androidTest/assets/fixture-aac.m4a
echo "Generated 8-second MP3 CBR, MP3 VBR and M4A AAC test recordings."
