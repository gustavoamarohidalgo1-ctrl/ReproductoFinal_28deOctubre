package com.reproductor.audiolibros

import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A seekable, silent PCM source with a real duration, without allocating hours of audio. */
internal object SparseWaveFixture {
    fun create(file: File, durationSeconds: Long) {
        val sampleRate = 8_000
        val bytesPerFrame = 2 // Signed PCM16 mono: sparse zero-filled regions are silence.
        val dataBytes = durationSeconds * sampleRate * bytesPerFrame
        require(durationSeconds > 0 && dataBytes <= 0xffff_ffffL - 36)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataBytes).toInt())
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * bytesPerFrame)
            .putShort(bytesPerFrame.toShort()).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
            .array()
        RandomAccessFile(file, "rw").use {
            it.setLength(0)
            it.write(header)
            it.setLength(header.size + dataBytes)
        }
        check(Os.stat(file.absolutePath).st_blocks * 512L < 1_048_576L) {
            "The long-audio fixture must remain sparse"
        }
    }
}
