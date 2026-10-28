package com.reproductor.audiolibros

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Player

/**
 * Persistencia sencilla en SharedPreferences:
 * - Posición de escucha por archivo (para retomar audiolibros largos donde quedaron).
 * - Modo de bucle elegido.
 * Propietario único: proceso :playback. No acceder desde la interfaz.
 */
class PositionStore(context: Context) {

    private val prefs = context.getSharedPreferences("reproductor_prefs", Context.MODE_PRIVATE)
    private val positions = PositionCheckpointCache(
        readPosition = { mediaId ->
            val key = "pos_$mediaId"
            try {
                prefs.getLong(key, 0L)
            } catch (error: ClassCastException) {
                // A malformed legacy preference must not crash the playback
                // thread when selecting this file. Other bookmarks stay intact.
                Log.w("PositionStore", "Se descartó un marcador con formato incompatible")
                prefs.edit().remove(key).apply()
                0L
            }
        },
        writePosition = { mediaId, position ->
            prefs.edit().putLong("pos_$mediaId", position).apply()
        },
        removePosition = { mediaId -> prefs.edit().remove("pos_$mediaId").apply() },
        nowMs = SystemClock::elapsedRealtime
    )

    fun savePosition(mediaId: String, positionMs: Long, force: Boolean = false) =
        positions.save(mediaId, positionMs, force)

    fun getPosition(mediaId: String): Long = positions.get(mediaId)

    fun clearPosition(mediaId: String) = positions.clear(mediaId)

    var repeatMode: Int
        get() = Player.REPEAT_MODE_ONE
        set(_) {
            if (prefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF) != Player.REPEAT_MODE_ONE) {
                prefs.edit().putInt("repeat_mode", Player.REPEAT_MODE_ONE).apply()
            }
        }
}
