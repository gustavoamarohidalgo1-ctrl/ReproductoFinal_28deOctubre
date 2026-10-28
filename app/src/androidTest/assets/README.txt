silence-320kbps.mp3 is a generated, silent test fixture (12 seconds, 44.1 kHz,
stereo, CBR MP3 320 kbps). No user audio was used or transformed.

Generation command (FFmpeg with libmp3lame):
ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 12 -c:a libmp3lame -b:a 320k -metadata "title=Continuidad 320 kbps" silence-320kbps.mp3

Instrumentation checks the original encoded track format and the file checksum.
These checks do not measure the physical audio output of a device.

high_resolution/sentinels-24.wav and sentinels-24.flac are deterministic,
250 ms stereo 48 kHz PCM24 samples with nonzero low bits. Regenerate with
`python3 app/src/androidTest/assets/high_resolution/generate.py` and FFmpeg.
The instrumented test checks decoded samples entering the production audio sink,
including their low bits and unchanged source files. It does not measure the
platform mixer, DAC, speaker, loudness, or physical audio output. A decoder that
only produces 16-bit PCM fails the precision check rather than silently passing.
