package com.reproductor.audiolibros

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** Starts only an explicitly enabled recovery, using the actual system boot event. */
class BootResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            !(action == Intent.ACTION_BOOT_COMPLETED && Build.VERSION.SDK_INT < 35)
        ) return
        // The option promises unattended recovery on a phone without a credential
        // lock. Do not hold a playback service waiting indefinitely for a PIN.
        if (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != false) return
        val pending = goAsync()
        // The receiver and service share :playback and the same disk executor:
        // AtomicFile reads must never race an in-flight settings transaction.
        PlaybackService.disk.execute {
            try {
                if (BootResumeSettingsStore(context).readEnabled()) {
                    ContextCompat.startForegroundService(context,
                        Intent(context, PlaybackService::class.java)
                            .setAction(PlaybackService.ACTION_RESUME_AFTER_BOOT))
                }
            } catch (error: RuntimeException) {
                Log.w("BootResumeReceiver", "Android no permitió recuperar el audio al encender", error)
            } finally {
                pending.finish()
            }
        }
    }
}
