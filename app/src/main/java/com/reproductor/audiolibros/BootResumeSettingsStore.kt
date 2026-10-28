package com.reproductor.audiolibros

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Blocking I/O; callers serialize reads and writes on the playback disk executor. */
internal class BootResumeSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "boot_resume.json"))

    fun readEnabled(): Boolean = try {
        file.openRead().use { input ->
            if (input.channel.size() > MAX_BYTES) return false
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            if (count > MAX_BYTES) return false
            val text = String(bytes, 0, count, Charsets.UTF_8)
            val reader = android.util.JsonReader(text.reader())
            reader.use {
                var version: Long? = null
                var enabled: Boolean? = null
                it.beginObject()
                while (it.hasNext()) {
                    when (it.nextName()) {
                        "version" -> version = if (it.peek() == android.util.JsonToken.NUMBER) it.nextString().toLongOrNull() else { it.skipValue(); null }
                        "enabled" -> enabled = if (it.peek() == android.util.JsonToken.BOOLEAN) it.nextBoolean() else { it.skipValue(); null }
                        else -> it.skipValue()
                    }
                }
                it.endObject()
                version == 1L && enabled == true && it.peek() == android.util.JsonToken.END_DOCUMENT
            }
        }
    } catch (_: Exception) { false }

    fun enable(): Boolean {
        var stream: FileOutputStream? = null
        return try {
            val bytes = JSONObject().put("version", 1).put("enabled", true).toString().toByteArray(Charsets.UTF_8)
            stream = file.startWrite()
            stream.write(bytes)
            val written = Os.fstat(stream.fd)
            file.finishWrite(stream)
            stream = null
            val committed = file.openRead().use { Os.fstat(it.fd) }
            check(written.st_dev == committed.st_dev && written.st_ino == committed.st_ino && written.st_size == committed.st_size)
            true
        } catch (error: Exception) {
            Log.w("BootResumeSettingsStore", "Could not save boot resume setting", error)
            false
        } finally {
            stream?.let { file.failWrite(it) }
        }
    }

    private companion object { const val MAX_BYTES = 256 }
}
