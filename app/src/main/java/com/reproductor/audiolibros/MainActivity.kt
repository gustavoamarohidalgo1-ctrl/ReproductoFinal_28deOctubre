package com.reproductor.audiolibros

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.ListenableFuture
import com.reproductor.audiolibros.databinding.ActivityMainBinding
import com.reproductor.audiolibros.databinding.ItemAudioBinding
import java.util.Locale
import java.util.Collections
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AudioAdapter
    private var catalogue: LibrarySnapshotCache.Snapshot<AudioItem, CatalogDetails>? = null
    private val audios: List<AudioItem> get() = catalogue?.items.orEmpty()
    private var seekScale = SeekBarScale(0L)
    private val currentTimeLabel = DurationLabel()
    private val totalTimeLabel = DurationLabel()
    private val unknownDurationText by lazy { getString(R.string.duration_unknown) }
    private var cacheTicket: Long? = null
    private var sizeLoad: Future<*>? = null
    private var sizeCancellation: CancellationSignal? = null
    private var sizeLookupItem: AudioItem? = null
    private var resolvedSize = 0L

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val bootResumeCommand = SessionCommand(BootResumeCommands.ACTION_SET_ENABLED, Bundle.EMPTY)
    private var bootResumePending: ListenableFuture<SessionResult>? = null
    private var updatingBootResumeSwitch = false
    private var confirmedBootResume: Boolean? = null
    private var awaitingBootResumeExtra: Boolean? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var seekingMediaId: String? = null
    private var activityStarted = false
    private var audioLoadGeneration = 0
    private var audioLoad: Future<*>? = null
    private var audioLoadCancellation: CancellationSignal? = null
    private var observingLibrary = false
    private var nextBatteryOptimizationCheckMs = 0L
    private var libraryError: Snackbar? = null
    private val preferences by lazy { getSharedPreferences("interface", MODE_PRIVATE) }
    private val reconnectController = Runnable { connectController() }
    private val refreshLibrary = Runnable { loadAudios() }
    private val libraryObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Una copia de archivos puede generar muchos eventos consecutivos.
            scheduleLibraryRefresh(500, force = true)
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            if (this@MainActivity.controller === controller) {
                updateAllUi()
            }
        }

        override fun onAvailableSessionCommandsChanged(controller: MediaController, commands: SessionCommands) {
            if (this@MainActivity.controller === controller) updateBootResumeSwitch()
        }

        override fun onDisconnected(controller: MediaController) {
            if (this@MainActivity.controller !== controller) return
            releaseController()
            updateAllUi()
            scheduleControllerReconnect()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayPauseIcon()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlayPauseIcon()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                    Player.EVENT_MEDIA_METADATA_CHANGED
                )
            ) updateProgress()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) = updateBitrate()
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            // También detecta cambios desde otra ventana mientras esta sigue visible.
            // Reutiliza el refresco de pantalla: no deja trabajo en segundo plano.
            if (SystemClock.elapsedRealtime() >= nextBatteryOptimizationCheckMs) {
                updateBatteryOptimizationButton()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val batterySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // RESULT_OK no acredita la exención: el sistema es la única fuente de verdad.
            updateBatteryOptimizationButton()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAudioPermission()) {
                observeLibrary()
                scheduleLibraryRefresh(force = true)
            } else {
                showPermissionNeeded()
            }
            updateBatteryOptimizationButton()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (hasAudioPermission()) catalogue = catalogCache.snapshot()
        adapter = AudioAdapter(catalogue) { index -> playAudio(index) }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        setupControls()
        if (catalogue != null) updateLibraryEmptyState()

        if (!hasAudioPermission()) {
            showPermissionNeeded()
            if (!preferences.getBoolean("audio_permission_requested", false)) requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        connectController()
        observeLibrary()
    }

    private fun connectController() {
        if (!activityStarted || controllerFuture != null) return
        val future = try {
            val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
            MediaController.Builder(this, sessionToken)
                .setListener(controllerListener)
                .buildAsync()
        } catch (error: Exception) {
            Log.w("MainActivity", "No se pudo conectar al servicio de reproducción", error)
            scheduleControllerReconnect()
            return
        }
        controllerFuture = future
        future.addListener({
            // Una conexión anterior puede terminar después de cerrar o recrear la pantalla.
            if (!activityStarted || controllerFuture !== future) return@addListener
            try {
                val connected = future.get()
                if (!connected.isConnected) {
                    releaseController()
                    scheduleControllerReconnect()
                    return@addListener
                }
                controller = connected
                connected.addListener(playerListener)
                updateAllUi()
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                Log.w("MainActivity", "La conexión al servicio se interrumpió", error)
                releaseController()
                scheduleControllerReconnect()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun scheduleControllerReconnect() {
        handler.removeCallbacks(reconnectController)
        if (activityStarted) handler.postDelayed(reconnectController, 1000)
    }

    private fun releaseController() {
        bootResumePending = null
        awaitingBootResumeExtra = null
        isSeeking = false
        seekingMediaId = null
        binding.playPauseButton.isEnabled = false
        binding.seekBar.isEnabled = false
        binding.bootResumeSwitch.isEnabled = false
        val future = controllerFuture
        controllerFuture = null
        controller?.removeListener(playerListener)
        controller = null
        future?.let { MediaController.releaseFuture(it) }
    }

    override fun onResume() {
        super.onResume()
        // También detecta permisos concedidos o revocados desde Ajustes.
        if (hasAudioPermission()) {
            observeLibrary()
            scheduleLibraryRefresh()
        } else {
            showPermissionNeeded()
        }
        updateBatteryOptimizationButton()
        handler.removeCallbacks(progressUpdater)
        handler.post(progressUpdater)
    }

    override fun onPause() {
        // Un gesto interrumpido al salir no debe bloquear futuras actualizaciones.
        isSeeking = false
        seekingMediaId = null
        updateProgress()
        handler.removeCallbacks(progressUpdater)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Algunos fabricantes presentan la autorización como un diálogo superpuesto.
        if (hasFocus && ::binding.isInitialized) updateBatteryOptimizationButton()
    }

    override fun onStop() {
        activityStarted = false
        handler.removeCallbacks(reconnectController)
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(refreshLibrary)
        stopObservingLibrary()
        cancelAudioLoad()
        cancelSizeLoad()
        libraryError?.dismiss()
        libraryError = null
        releaseController()
        super.onStop()
    }

    // ---------- Permisos ----------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        preferences.edit().putBoolean("audio_permission_requested", true).apply()
        permissionLauncher.launch(requiredPermissions())
    }

    private fun showPermissionNeeded() {
        stopObservingLibrary()
        cancelAudioLoad()
        cancelSizeLoad()
        catalogCache.clear()
        catalogue = null
        adapter.clear()
        binding.emptyView.visibility = View.VISIBLE
        binding.emptyText.text = getString(R.string.permission_needed)
        binding.permissionButton.visibility = View.VISIBLE
        val openSettings = preferences.getBoolean("audio_permission_requested", false) &&
            !shouldShowRequestPermissionRationale(audioPermission())
        binding.permissionButton.setText(
            if (openSettings) R.string.permission_settings else R.string.grant_permission
        )
        binding.permissionButton.setOnClickListener {
            if (openSettings) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else requestPermissions()
        }
    }

    private fun updateBatteryOptimizationButton() {
        val pm = getSystemService(PowerManager::class.java)
        binding.batteryOptimizationButton.visibility =
            if (hasAudioPermission() && pm?.isIgnoringBatteryOptimizations(packageName) == false) {
                View.VISIBLE
            } else View.GONE
        nextBatteryOptimizationCheckMs = SystemClock.elapsedRealtime() + 5_000L
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            updateBatteryOptimizationButton()
            return
        }
        try {
            batterySettingsLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            try {
                batterySettingsLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Snackbar.make(binding.root, R.string.battery_settings_unavailable, Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    // ---------- Lista de audios ----------

    private fun observeLibrary() {
        if (!activityStarted || observingLibrary || !hasAudioPermission()) return
        try {
            contentResolver.registerContentObserver(AudioStore.collectionUri, true, libraryObserver)
            observingLibrary = true
        } catch (error: SecurityException) {
            Log.w("MainActivity", "No se puede observar la biblioteca", error)
        }
    }

    private fun stopObservingLibrary() {
        if (observingLibrary) {
            contentResolver.unregisterContentObserver(libraryObserver)
            observingLibrary = false
        }
    }

    private fun scheduleLibraryRefresh(delayMs: Long = 0, force: Boolean = false) {
        if (force) catalogCache.invalidateSource()
        handler.removeCallbacks(refreshLibrary)
        if (activityStarted) handler.postDelayed(refreshLibrary, delayMs)
    }

    private fun cancelAudioLoad() {
        audioLoadGeneration++
        cacheTicket?.let { catalogCache.cancel(it) }
        cacheTicket = null
        audioLoadCancellation?.cancel()
        audioLoadCancellation = null
        audioLoad?.cancel(true)
        audioLoad = null
        libraryExecutor.purge()
    }

    private fun loadAudios() {
        if (!activityStarted) return
        if (!hasAudioPermission()) {
            showPermissionNeeded()
            return
        }
        cancelAudioLoad()
        val generation = ++audioLoadGeneration
        val ticket = catalogCache.beginLoad()
        cacheTicket = ticket
        val previous = catalogue
        val cancellation = CancellationSignal()
        audioLoadCancellation = cancellation
        val context = applicationContext
        audioLoad = libraryExecutor.submit {
            val result = try {
                val before = AudioStore.querySourceStamp(context, cancellation)
                val snapshot = catalogCache.reuse(ticket, before) ?: run {
                    val items = AudioStore.queryAudios(context, cancellation)
                    cancellation.throwIfCanceled()
                    val after = AudioStore.querySourceStamp(context, cancellation)
                    // A concurrent import may change generations during the scan.
                    // Publish its rows, but do not validate cache reuse from that scan.
                    catalogCache.update(ticket, items, before.takeIf { it == after }) { immutable ->
                        buildCatalogDetails(immutable, cancellation)
                    }
                } ?: return@submit
                cancellation.throwIfCanceled()
                val diff = if (previous === snapshot) null else LibraryDiff.calculate(
                    AudioDiff(previous?.items.orEmpty(), snapshot.items), cancellation::throwIfCanceled
                )
                cancellation.throwIfCanceled()
                snapshot to diff
            } catch (_: OperationCanceledException) {
                return@submit
            } catch (error: Exception) {
                Log.w("MainActivity", "No se pudo consultar la biblioteca de audios", error)
                runOnUiThread {
                    if (!activityStarted || generation != audioLoadGeneration) return@runOnUiThread
                    if (!hasAudioPermission()) showPermissionNeeded() else showLibraryError()
                }
                return@submit
            }
            runOnUiThread {
                if (!activityStarted || generation != audioLoadGeneration) return@runOnUiThread
                libraryError?.dismiss()
                libraryError = null
                if (catalogue !== result.first) {
                    // A large reorder may exhaust the diff budget. Keep the visible
                    // audio and its offset anchored, using the precomputed ID index.
                    val layout = binding.recyclerView.layoutManager as LinearLayoutManager
                    val anchorPosition = if (result.second == LibraryDiff.Result.FullRefresh) {
                        layout.findFirstVisibleItemPosition()
                    } else RecyclerView.NO_POSITION
                    val anchorId = audios.getOrNull(anchorPosition)?.mediaId
                    val anchorOffset = if (anchorPosition != RecyclerView.NO_POSITION) {
                        layout.findViewByPosition(anchorPosition)?.let {
                            layout.getDecoratedTop(it) - binding.recyclerView.paddingTop
                        } ?: 0
                    } else 0
                    catalogue = result.first
                    adapter.submit(result.first, result.second)
                    if (anchorPosition != RecyclerView.NO_POSITION && audios.isNotEmpty()) {
                        val nextAnchor = catalogue?.derived?.indexById?.get(anchorId)
                            ?: anchorPosition.coerceAtMost(audios.lastIndex)
                        layout.scrollToPositionWithOffset(nextAnchor, anchorOffset)
                    }
                }
                updateLibraryEmptyState()
                // La biblioteca puede terminar de cargar después de conectar el controlador.
                updateAllUi()
            }
        }
    }

    private fun updateLibraryEmptyState() {
        if (audios.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.text = getString(R.string.empty_list)
            binding.permissionButton.visibility = View.VISIBLE
            binding.permissionButton.setText(R.string.refresh_library)
            binding.permissionButton.setOnClickListener { scheduleLibraryRefresh(force = true) }
        } else {
            binding.emptyView.visibility = View.GONE
        }
    }

    private fun findAudio(mediaId: String?): AudioItem? =
        catalogue?.derived?.indexById?.get(mediaId)?.let { audios.getOrNull(it) }

    private fun cancelSizeLoad() {
        sizeCancellation?.cancel()
        sizeCancellation = null
        sizeLoad?.cancel(true)
        sizeLoad = null
        libraryExecutor.purge()
        sizeLookupItem = null
        resolvedSize = 0L
    }

    private fun resolveActiveSize(item: AudioItem) {
        if (!activityStarted || sizeLookupItem == item) return
        cancelSizeLoad()
        sizeLookupItem = item
        val cancellation = CancellationSignal()
        sizeCancellation = cancellation
        val context = applicationContext
        sizeLoad = libraryExecutor.submit {
            val size = try {
                AudioStore.querySize(context, item.contentUri, cancellation)
            } catch (_: OperationCanceledException) {
                return@submit
            } catch (_: Exception) {
                0L // Optional metadata must never affect playback or the catalogue.
            }
            handler.post {
                if (activityStarted && sizeCancellation === cancellation &&
                    !cancellation.isCanceled && sizeLookupItem == item &&
                    controller?.currentMediaItem?.mediaId == item.mediaId
                ) {
                    resolvedSize = size
                    updateBitrate()
                }
            }
        }
    }

    private fun showLibraryError() {
        if (audios.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyText.setText(R.string.library_load_failed)
            binding.permissionButton.visibility = View.VISIBLE
            binding.permissionButton.setText(R.string.retry_library)
            binding.permissionButton.setOnClickListener { scheduleLibraryRefresh(force = true) }
        } else {
            libraryError?.dismiss()
            libraryError = Snackbar.make(binding.root, R.string.library_load_failed, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(binding.playerBar)
                .setAction(R.string.retry_library) { scheduleLibraryRefresh(force = true) }
                .also { it.show() }
        }
    }

    private fun playAudio(index: Int) {
        if (!hasRestoredPlayback()) return
        val c = controller ?: return
        val snapshot = catalogue ?: return
        val items = snapshot.derived.mediaItems
        val item = items.getOrNull(index) ?: return
        // MediaController masks native commands immediately, so rapid A → B → A taps
        // keep the latest selection and remain ordered with pause/seek commands.
        if (c.currentMediaItem?.mediaId == item.mediaId) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            else if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            if (!c.playWhenReady) c.play()
        } else {
            // The library is a picker, not a playback queue. Transfer only the
            // selected audiobook; TIME_UNSET restores that file's own bookmark.
            c.setMediaItems(listOf(item), 0, C.TIME_UNSET)
            c.prepare()
            if (!c.playWhenReady) c.play()
        }
        updateAllUi()
    }

    // ---------- Controles ----------

    private fun setupControls() {
        binding.bootResumeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingBootResumeSwitch && checked) enableBootResume()
        }
        binding.batteryOptimizationButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.playPauseButton.setOnClickListener {
            if (!hasRestoredPlayback()) return@setOnClickListener
            controller?.let {
                if (it.playWhenReady) {
                    it.pause()
                } else {
                    if (it.playbackState == Player.STATE_IDLE) it.prepare()
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    it.play()
                }
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && (!isSeeking || seekingMediaId == controller?.currentMediaItem?.mediaId)) {
                    updateCurrentTime(seekScale.positionForProgress(progress))
                    // Teclado y accesibilidad no emiten onStopTrackingTouch.
                    if (!isSeeking) seekToProgress(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                seekingMediaId = controller?.currentMediaItem?.mediaId
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Una transición mientras se arrastra no debe aplicar el gesto al archivo nuevo.
                if (isSeeking && seekingMediaId == controller?.currentMediaItem?.mediaId) {
                    seekToProgress(seekBar?.progress ?: 0)
                }
                isSeeking = false
                seekingMediaId = null
                updateProgress()
            }
        })
    }

    private fun seekToProgress(progress: Int) {
        if (!hasRestoredPlayback()) return
        controller?.takeIf {
            it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }?.seekTo(seekScale.positionForProgress(progress))
    }

    // ---------- Actualización de UI ----------

    private fun hasRestoredPlayback(): Boolean =
        controller?.sessionExtras?.getBoolean(PlaybackService.EXTRA_STATE_RESTORED) == true

    private fun updateAllUi() {
        updatePlayPauseIcon()
        updateNowPlaying()
        updateProgress()
        updateBootResumeSwitch()
    }

    private fun updateBootResumeSwitch() {
        val c = controller
        val ready = hasRestoredPlayback() && c != null &&
            c.isSessionCommandAvailable(bootResumeCommand)
        if (bootResumePending == null) {
            if (ready && c!!.sessionExtras.containsKey(BootResumeCommands.EXTRA_ENABLED)) {
                val reported = c.sessionExtras.getBoolean(BootResumeCommands.EXTRA_ENABLED)
                if (awaitingBootResumeExtra == null || awaitingBootResumeExtra == reported) {
                    confirmedBootResume = reported
                    awaitingBootResumeExtra = null
                }
            }
            updatingBootResumeSwitch = true
            binding.bootResumeSwitch.isChecked = confirmedBootResume == true
            updatingBootResumeSwitch = false
        }
        val showPrompt = confirmedBootResume == false
        binding.bootResumeSwitch.visibility = if (showPrompt) View.VISIBLE else View.GONE
        binding.bootResumeHelp.visibility = if (showPrompt) View.VISIBLE else View.GONE
        binding.bootResumeSwitch.isEnabled = ready && confirmedBootResume != null &&
            bootResumePending == null && awaitingBootResumeExtra == null
    }

    private fun enableBootResume() {
        val c = controller
        if (!hasRestoredPlayback() || c == null || !c.isSessionCommandAvailable(bootResumeCommand) ||
            bootResumePending != null || awaitingBootResumeExtra != null) {
            updateBootResumeSwitch()
            return
        }
        val args = Bundle().apply { putBoolean(BootResumeCommands.ARG_ENABLED, true) }
        val future = try {
            c.sendCustomCommand(bootResumeCommand, args)
        } catch (_: Exception) {
            updateBootResumeSwitch()
            Snackbar.make(binding.root, R.string.boot_resume_save_failed, Snackbar.LENGTH_LONG).show()
            return
        }
        bootResumePending = future
        updateBootResumeSwitch()
        future.addListener({
            if (controller !== c || bootResumePending !== future) return@addListener
            bootResumePending = null
            val success = try {
                future.get().resultCode == SessionResult.RESULT_SUCCESS
            } catch (_: Exception) {
                false
            }
            if (success) {
                confirmedBootResume = true
                awaitingBootResumeExtra = true
            }
            updateBootResumeSwitch()
            if (!success) Snackbar.make(binding.root, R.string.boot_resume_save_failed, Snackbar.LENGTH_LONG).show()
        }, ContextCompat.getMainExecutor(this))
    }

    // Muestra la calidad nativa del archivo en reproducción:
    // - Sin pérdida (FLAC/WAV/PCM): especificaciones nativas, ej. "96 kHz · 24-bit".
    // - Con pérdida (MP3/AAC/OGG): bitrate declarado por el formato seleccionado.
    //   Si falta, tamaño × 8 ÷ duración se muestra como aproximación: incluye metadatos.
    // Si no hay datos, se oculta.
    private fun updateBitrate() {
        if (!hasRestoredPlayback()) return
        val currentId = controller?.currentMediaItem?.mediaId
        val activeItem = findAudio(currentId)
        if (sizeLookupItem != null && sizeLookupItem != activeItem) cancelSizeLoad()
        val estimateSize = activeItem?.sizeBytes?.takeIf { it > 0L }
            ?: resolvedSize.takeIf { activeItem != null && sizeLookupItem == activeItem } ?: 0L
        val estimateDuration = activeItem?.durationMs?.takeIf { it > 0L }
            ?: controller?.duration?.takeIf { it > 0L } ?: 0L
        val estimatedKbps = if (estimateSize > 0L && estimateDuration > 0L) {
            (estimateSize.toDouble() * 8.0 / estimateDuration).toInt()
        } else -1

        var sampleRate = -1
        var bitDepth = 0
        var lossless = false
        var formatKbps = -1L
        var foundAudioFormat = false
        controller?.currentTracks?.groups?.forEach { group ->
            if (!foundAudioFormat && group.type == C.TRACK_TYPE_AUDIO &&
                group.isSelected && group.length > 0
            ) {
                val selectedIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
                    ?: return@forEach
                val format = group.getTrackFormat(selectedIndex)
                foundAudioFormat = true
                // El extractor puede devolver 320001 bps para un archivo de 320 kbps.
                if (format.bitrate > 0) formatKbps = (format.bitrate.toLong() + 500L) / 1000L
                sampleRate = format.sampleRate
                bitDepth = when (format.pcmEncoding) {
                    C.ENCODING_PCM_8BIT -> 8
                    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                    else -> 0 // Float no acredita la profundidad original del archivo.
                }
                val mime = format.sampleMimeType.orEmpty()
                lossless = mime.contains("flac") || mime.contains("wav") ||
                    mime.contains("pcm") || mime.contains("raw")
            }
        }

        if (formatKbps <= 0L && !(lossless && sampleRate > 0) &&
            activeItem != null && activeItem.sizeBytes <= 0L
        ) resolveActiveSize(activeItem)

        val text = when {
            lossless && sampleRate > 0 && bitDepth > 0 ->
                "${formatSampleRate(sampleRate)} · $bitDepth-bit"
            lossless && sampleRate > 0 ->
                formatSampleRate(sampleRate)
            formatKbps > 0 ->
                "$formatKbps kbps"
            estimatedKbps > 0 ->
                "≈ $estimatedKbps kbps"
            else -> null
        }

        if (text != null) {
            binding.bitrateText.text = text
            binding.bitrateText.visibility = View.VISIBLE
        } else {
            binding.bitrateText.text = ""
            binding.bitrateText.visibility = View.GONE
        }
    }

    private fun formatSampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000} kHz"
        else String.format(Locale.US, "%.1f kHz", hz / 1000.0)

    private fun updatePlayPauseIcon() {
        binding.playPauseButton.isEnabled = hasRestoredPlayback() &&
            controller?.currentMediaItem != null
        if (!hasRestoredPlayback()) return
        val playing = controller?.playWhenReady == true
        binding.playPauseButton.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.playPauseButton.contentDescription = getString(
            if (playing) R.string.pause_audio else R.string.play_audio
        )
    }

    private fun updateNowPlaying() {
        if (!hasRestoredPlayback()) return
        val title = controller?.currentMediaItem?.mediaMetadata?.title
        binding.nowPlayingTitle.text = title ?: getString(R.string.not_playing)
        adapter.currentPlayingId = controller?.currentMediaItem?.mediaId
        updateBitrate()
    }

    private fun updateProgress() {
        if (!hasRestoredPlayback()) return
        val c = controller ?: return
        val item = c.currentMediaItem
        // Al restaurar en pausa, la posición ya existe aunque ExoPlayer aún no
        // conozca la duración. No hay que preparar ni reproducir para mostrarla.
        val duration = c.duration.takeIf { it > 0 }
            ?: findAudio(item?.mediaId)?.durationMs
                ?.takeIf { it > 0 }
            ?: item?.mediaMetadata?.durationMs?.takeIf { it > 0 }
            ?: 0L
        val totalText = when {
            duration > 0 -> totalTimeLabel.duration(duration)
            item != null -> totalTimeLabel.unknown(unknownDurationText)
            else -> totalTimeLabel.duration(0L)
        }
        totalText?.let { binding.timeTotal.text = it }
        if (!isSeeking) {
            val position = c.currentPosition.coerceAtLeast(0)
            updateCurrentTime(position)
            binding.seekBar.isEnabled = duration > 0 &&
                c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            if (seekScale.durationMs != duration) seekScale = SeekBarScale(duration)
            binding.seekBar.max = seekScale.maximum
            binding.seekBar.keyProgressIncrement = seekScale.keyboardIncrement
            binding.seekBar.progress = seekScale.progressForPosition(position)
        }
    }

    // ---------- Utilidades ----------

    private fun updateCurrentTime(milliseconds: Long) {
        currentTimeLabel.duration(milliseconds)?.let { binding.timeCurrent.text = it }
    }

    private data class CatalogDetails(
        val mediaItems: List<MediaItem>,
        val indexById: Map<String, Int>,
        val queueRevision: String
    )

    companion object {
        private val catalogCache = LibrarySnapshotCache<AudioItem, CatalogDetails>()

        private fun buildCatalogDetails(items: List<AudioItem>, cancellation: CancellationSignal): CatalogDetails {
            val indices = HashMap<String, Int>(items.size)
            val media = ArrayList<MediaItem>(items.size)
            val revision = QueueRevision()
            items.forEachIndexed { index, item ->
                cancellation.throwIfCanceled()
                indices[item.mediaId] = index
                media.add(item.toMediaItem())
                revision.add(item.mediaId, item.contentUri.toString(), item.title,
                    item.artist, item.album, item.durationMs)
            }
            return CatalogDetails(Collections.unmodifiableList(media), Collections.unmodifiableMap(indices), revision.finish())
        }

        // Compartido entre recreaciones: nunca se crea un hilo por cada recarga.
        private val libraryExecutor = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(),
            BackgroundThreadFactory("audio-library")
        )

        fun formatDuration(ms: Long): String = DurationText.format(ms)
    }

    // ---------- Adaptador de la lista ----------

    private class AudioDiff(
        private val old: List<AudioItem>,
        private val new: List<AudioItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = old.size
        override fun getNewListSize(): Int = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return old[oldItemPosition].mediaId == new[newItemPosition].mediaId
        }
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return old[oldItemPosition] == new[newItemPosition]
        }
    }

    private class AudioAdapter(
        private var snapshot: LibrarySnapshotCache.Snapshot<AudioItem, CatalogDetails>?,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<AudioAdapter.ViewHolder>() {
        private val items: List<AudioItem> get() = snapshot?.items.orEmpty()

        init {
            stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
            setHasStableIds(true)
        }

        @SuppressLint("NotifyDataSetChanged") // Only the bounded-diff fallback replaces all visible rows.
        fun submit(next: LibrarySnapshotCache.Snapshot<AudioItem, CatalogDetails>, diff: LibraryDiff.Result?) {
            snapshot = next
            when (diff) {
                is LibraryDiff.Result.Incremental -> diff.updates.dispatchUpdatesTo(this)
                LibraryDiff.Result.FullRefresh -> notifyDataSetChanged()
                null -> Unit
            }
        }

        fun clear() {
            val count = itemCount
            snapshot = null
            if (count > 0) notifyItemRangeRemoved(0, count)
        }

        var currentPlayingId: String? = null
            set(value) {
                if (field == value) return
                val oldIndex = snapshot?.derived?.indexById?.get(field)
                field = value
                val newIndex = snapshot?.derived?.indexById?.get(value)
                oldIndex?.let { notifyItemChanged(it, SELECTION_PAYLOAD) }
                newIndex?.let { notifyItemChanged(it, SELECTION_PAYLOAD) }
            }

        class ViewHolder(val binding: ItemAudioBinding) : RecyclerView.ViewHolder(binding.root) {
            private val typedValue = android.util.TypedValue()
            val playingColor: Int
            val normalColor: Int
            val normalTypeface = binding.itemTitle.typeface
            val selectedTypeface = android.graphics.Typeface.create(normalTypeface, android.graphics.Typeface.BOLD)
            init {
                val theme = binding.root.context.theme
                theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
                playingColor = typedValue.data
                theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
                normalColor = typedValue.data
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemAudioBinding.inflate(LayoutInflater.from(parent.context), parent, false)).also { holder ->
                holder.itemView.setOnClickListener {
                    val position = holder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) onClick(position)
                }
            }

        override fun getItemCount(): Int = items.size
        override fun getItemId(position: Int): Long = items[position].id

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == SELECTION_PAYLOAD }) {
                bindSelection(holder, position)
            } else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.itemTitle.text = item.title
            holder.binding.itemSubtitle.text = buildString {
                append(item.folder)
                if (item.artist.isNotBlank() && item.artist != "<unknown>") {
                    append(" · ").append(item.artist)
                }
                append(" · ").append(if (item.durationMs > 0) formatDuration(item.durationMs) else "--:--")
            }
            bindSelection(holder, position)
        }

        private fun bindSelection(holder: ViewHolder, position: Int) {
            val selected = items[position].mediaId == currentPlayingId
            holder.binding.itemTitle.setTextColor(if (selected) holder.playingColor else holder.normalColor)
            holder.binding.itemTitle.typeface = if (selected) holder.selectedTypeface else holder.normalTypeface
            holder.itemView.isSelected = selected
            ViewCompat.setStateDescription(holder.itemView,
                if (selected) holder.itemView.context.getString(R.string.current_audio) else null)
        }

        companion object {
            private const val SELECTION_PAYLOAD = "selection"
        }
    }
}
