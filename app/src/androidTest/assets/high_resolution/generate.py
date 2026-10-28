#!/usr/bin/env python3
"""Generate deterministic stereo PCM24 WAV and lossless FLAC fixtures."""
import pathlib
import struct
import subprocess
import wave

out = pathlib.Path(__file__).resolve().parent
frames = 12000
with wave.open(str(out / 'sentinels-24.wav'), 'wb') as wav:
    wav.setnchannels(2)
    wav.setsampwidth(3)
    wav.setframerate(48000)
    samples = bytearray()
    for i in range(frames):
        for value in (((i * 7919) % 1000001) - 500000, ((i * 3571) % 900001) - 450000):
            samples.extend(struct.pack('<i', value)[:3])
    wav.writeframes(samples)
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-i',
                str(out / 'sentinels-24.wav'), '-c:a', 'flac', '-sample_fmt', 's32',
                str(out / 'sentinels-24.flac')], check=True)
