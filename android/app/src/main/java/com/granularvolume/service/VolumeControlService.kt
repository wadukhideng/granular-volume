package com.granularvolume.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PatternMatcher
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.granularvolume.InfoSheetActivity
import com.granularvolume.PaywallActivity
import com.granularvolume.R
import com.granularvolume.audio.AudioController
import com.granularvolume.audio.FullRangeCoordinator
import com.granularvolume.audio.StreamVolumeController
import com.granularvolume.util.AccessState
import com.granularvolume.util.BillingManager
import com.granularvolume.util.ControlLive
import com.granularvolume.util.Entitlement
import com.granularvolume.util.KeyCheck
import com.granularvolume.util.Prefs
import com.granularvolume.util.ProAccess
import com.granularvolume.util.TrialNotices
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service: audio effect + notification control.
 * Overlay dihapus; semua kontrol lewat tombol action di notifikasi.
 */
class VolumeControlService : Service() {

    private val tag = "GranularVolume:Service"

    companion object {
        private const val CHANNEL_ID = "gv_volume_control"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP          = "com.granularvolume.ACTION_STOP"
        const val ACTION_STEP_UP       = "com.granularvolume.ACTION_STEP_UP"
        const val ACTION_STEP_DOWN     = "com.granularvolume.ACTION_STEP_DOWN"
        const val ACTION_TOGGLE_MUTE   = "com.granularvolume.ACTION_TOGGLE_MUTE"
        const val ACTION_KEY_INSTALLED = "com.granularvolume.ACTION_KEY_INSTALLED"
        const val ACTION_ACCESS_CHANGED = "com.granularvolume.ACTION_ACCESS_CHANGED"

        const val EXTRA_FROM_BOOT = "com.granularvolume.EXTRA_FROM_BOOT"

        private const val PENDING_STEP_TTL_MS = 10L * 60L * 1000L
        private const val LAST_DAY_NOTICE_DELAY_MS = 4000L
        private const val MIDWEEK_NUDGE_DAYS_LEFT = 3
        private const val BILLING_PREFETCH_DELAY_MS = 1500L
        private const val KEY_APP_PACKAGE = "com.granularvolume.key"

        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("VolumeControlService")
    )

    private lateinit var audioController: AudioController
    private lateinit var streamVolumeController: StreamVolumeController
    private lateinit var coordinator: FullRangeCoordinator

    private var pendingQuietStepDb: Float? = null
    private var pendingQuietStepAtMs = 0L
    private var sessionUnlocked = false
    private var keyWelcomed = false

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION) return
            val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream < 0) return
            val to = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
            val from = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, to)
            if (to < 0) return
            coordinator.onExternalVolumeChange(stream, from, to)
            updateNotification(audioController.attenuationDb.value)
        }
    }

    private val keyInstalledReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
            if (intent.data?.schemeSpecificPart != KEY_APP_PACKAGE) return
            mainHandler.post { onKeyArrived("package-added") }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
            coordinator.refreshCurve(); coordinator.onRouteChanged()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
            coordinator.refreshCurve(); coordinator.onRouteChanged()
        }
    }

    private val modeListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            AudioManager.OnModeChangedListener { coordinator.onAudioModeChanged() }
        else null

    @Volatile private var stopRequestedByUser = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stepAccumulator = 0
    private var stepJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "Service starting (notification-only build)")

        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(0f), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(0f))
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground failed: ${e.message}", e)
        }

        audioController = AudioController(applicationContext)
        audioController.onEffectState = { available, preferred ->
            ControlLive.effect = when {
                !available -> ControlLive.Effect.NONE
                preferred -> ControlLive.Effect.PREFERRED
                else -> ControlLive.Effect.FALLBACK
            }
        }
        audioController.onEffectControl = { hasControl ->
            mainHandler.post {
                val taken = !hasControl
                if (taken != ControlLive.effectTaken) {
                    ControlLive.effectTaken = taken
                    if (taken) {
                        Toast.makeText(applicationContext, R.string.gv_effect_taken, Toast.LENGTH_LONG).show()
                        // Coba pasang ulang efek setelah jeda singkat
                        mainHandler.postDelayed({
                            if (ControlLive.running) {
                                Log.i("GranularVolume", "Mencoba reattach efek setelah diambil alih...")
                                serviceScope.launch(Dispatchers.Default) {
                                    audioController.reattach()
                                }
                            }
                        }, 1000)
                    }
                    if (ControlLive.running) updateNotification(audioController.attenuationDb.value)
                }
            }
        }

        ProAccess.evaluateGrandfather(applicationContext)
        val carried = Prefs.consumeSessionCarry(applicationContext)
        sessionUnlocked = ProAccess.isPro(applicationContext) || carried
        if (carried) Log.i(tag, "Session carried across an app update")
        publishSession()
        keyWelcomed = ProAccess.hasPaidUnlock(applicationContext)
        audioController.proProvider = ::unlockedThisSession

        streamVolumeController = StreamVolumeController(applicationContext)
        coordinator = FullRangeCoordinator(applicationContext, audioController, streamVolumeController)
        coordinator.lockedProvider = { !gesturesOpen() }
        coordinator.lockedDisplayProvider = { lockDisplay() == FullRangeCoordinator.LockDisplay.LOCKED }
        coordinator.lockDisplayProvider = ::lockDisplay
        coordinator.onLockedInteraction = { pendingStep ->
            mainHandler.post {
                pendingQuietStepDb = pendingStep
                pendingQuietStepAtMs = SystemClock.elapsedRealtime()
                Log.i(tag, "Locked gesture refused (pendingQuietStep=$pendingStep)")
                refreshAccessSurfaces()
                openPaywall()
            }
        }
        coordinator.onQuietUnavailable = {
            mainHandler.post {
                Toast.makeText(applicationContext, getString(R.string.gv_quiet_unavailable_in_call), Toast.LENGTH_SHORT).show()
            }
        }

        TrialNotices.arm(applicationContext)
        if (ProAccess.isOnTrial(applicationContext)) {
            mainHandler.postDelayed({ TrialNotices.maybePostLastDay(applicationContext) }, LAST_DAY_NOTICE_DELAY_MS)
        }

        serviceScope.launch(Dispatchers.Default) {
            audioController.initialize()
            coordinator.syncZoneToAppliedGain()
            if (!audioController.isEffectAvailable) {
                Log.e(tag, "No audio effect available")
                if (!Prefs.wasNoEffectTold(applicationContext)) {
                    Prefs.setNoEffectTold(applicationContext)
                    toast(getString(R.string.gv_effect_none))
                }
            }
            coordinator.refreshCurve()
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this, volumeChangeReceiver, IntentFilter(VOLUME_CHANGED_ACTION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(
            this, keyInstalledReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                addDataScheme("package")
                addDataSchemeSpecificPart(KEY_APP_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(deviceCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
            am.addOnModeChangedListener({ r -> mainHandler.post(r) }, modeListener)
        }

        Prefs.setServiceWasRunning(applicationContext, true)
        ControlLive.running = true

        mainHandler.postDelayed({
            if (!ControlLive.running) return@postDelayed
            BillingManager.prefetch(applicationContext) { owned ->
                if (!ControlLive.running) return@prefetch
                if (owned == true) onKeyArrived("play-record") else refreshAccessSurfaces()
            }
        }, BILLING_PREFETCH_DELAY_MS)

        audioController.attenuationDb
            .onEach { dB -> updateNotification(dB) }
            .launchIn(serviceScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { 
                Log.i(tag, "Stop action received from notification")
                stopRequestedByUser = true 
                // Hapus notifikasi dari status bar secara paksa
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf() 
            }
            ACTION_STEP_UP -> handleStep(+1)
            ACTION_STEP_DOWN -> handleStep(-1)
            ACTION_TOGGLE_MUTE -> { coordinator.toggleMute(); updateNotification(audioController.attenuationDb.value) }
            ACTION_KEY_INSTALLED -> onKeyArrived("activity")
            ACTION_ACCESS_CHANGED -> {
                if (ProAccess.hasPaidUnlock(applicationContext)) onKeyArrived("access-changed")
                refreshAccessSurfaces()
            }
            null -> if (intent != null) {
                onOpenedByUser(intent.getBooleanExtra(EXTRA_FROM_BOOT, false))
            } else if (ProAccess.isTrialExpired(applicationContext)) {
                TrialNotices.maybePostEnded(applicationContext, stillRunning = false)
            }
        }
        return START_STICKY
    }

    private fun unlockedThisSession(): Boolean {
        if (sessionUnlocked) return true
        if (!ProAccess.isPro(applicationContext)) return false
        sessionUnlocked = true
        publishSession()
        Log.i(tag, "Full range opened mid-session")
        if (!keyWelcomed) mainHandler.post { onKeyArrived("gesture") }
        return true
    }

    private fun gesturesOpen(): Boolean {
        if (!ProAccess.isPro(applicationContext)) return false
        if (!sessionUnlocked) return unlockedThisSession()
        if (!keyWelcomed && ProAccess.hasPaidUnlock(applicationContext)) {
            mainHandler.post { onKeyArrived("gesture") }
        }
        return true
    }

    private fun lockDisplay(): FullRangeCoordinator.LockDisplay = when {
        ProAccess.isPro(applicationContext) -> FullRangeCoordinator.LockDisplay.OPEN
        sessionUnlocked -> FullRangeCoordinator.LockDisplay.HELD
        else -> FullRangeCoordinator.LockDisplay.LOCKED
    }

    private fun publishSession() {
        ControlLive.sessionOpen = sessionUnlocked
        Prefs.setSessionOpen(applicationContext, sessionUnlocked)
    }

    private fun refreshAccessSurfaces() {
        updateNotification(audioController.attenuationDb.value)
    }

    private fun onOpenedByUser(fromBoot: Boolean) {
        if (fromBoot) return
        if (AccessState.of(applicationContext).weekOver) { openInfoSheet(); return }
        maybeLastDayNudge()
    }

    private fun maybeLastDayNudge(): Boolean {
        if (Prefs.wasLastDayNudgeShown(applicationContext)) return false
        if (!ProAccess.isOnTrial(applicationContext)) return false
        val daysLeft = Entitlement.daysLeftInTrial(applicationContext)
        if (daysLeft > 1) {
            if (daysLeft != MIDWEEK_NUDGE_DAYS_LEFT || Prefs.wasMidweekNudgeShown(applicationContext)) return false
            Prefs.setMidweekNudgeShown(applicationContext)
            Log.i(tag, "Midweek sheet: $daysLeft days left")
            openInfoSheet(); return true
        }
        Prefs.setLastDayNudgeShown(applicationContext)
        openInfoSheet(); return true
    }

    private fun openInfoSheet() = startSheet(InfoSheetActivity::class.java)
    private fun openPaywall() = startSheet(PaywallActivity::class.java)

    private fun startSheet(activity: Class<*>) {
        try {
            startActivity(Intent(applicationContext, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(tag, "Could not open ${activity.simpleName}: ${e.message}")
        }
    }

    private fun onKeyArrived(source: String) {
        if (keyWelcomed) return
        if (!ProAccess.hasPaidUnlock(applicationContext)) {
            Log.w(tag, "Key signal ($source), but no valid key installed: ignoring")
            return
        }
        val wasLocked = !sessionUnlocked
        keyWelcomed = true
        unlockedThisSession()
        Log.i(tag, "Key arrived ($source), session was ${if (wasLocked) "locked" else "open"}")
        TrialNotices.cancel(applicationContext)
        if (wasLocked) landAfterUnlock() else landRefusedStep()
        if (!Entitlement.isGrandfathered(applicationContext)) {
            Prefs.setUnlockAcknowledged(applicationContext)
            Toast.makeText(applicationContext, R.string.gv_paywall_unlocked, Toast.LENGTH_LONG).show()
        }
        updateNotification(audioController.attenuationDb.value)
    }

    private fun landAfterUnlock() {
        val pending = takeFreshPendingStep()
        if (coordinator.isMuted) { Log.i(tag, "Landing skipped: muted"); return }
        if (coordinator.uiState().quietUnavailable) { Log.i(tag, "Landing skipped: cellular call"); return }
        val stored = Prefs.getAttenuation(applicationContext)
        val place = stored.takeIf { s -> FullRangeCoordinator.STEP_DB.any { it < 0f && abs(it - s) < 0.01f } }
        val target = pending ?: place
        if (target == null) {
            Log.i(tag, "Landing: no place to return to (stored ${stored}dB)")
            coordinator.syncZoneToAppliedGain(); return
        }
        Log.i(tag, "Landing on ${if (pending != null) "refused step" else "stored place"} (${target}dB)")
        coordinator.applyQuiet(target)
    }

    private fun takeFreshPendingStep(): Float? {
        val pending = pendingQuietStepDb
        pendingQuietStepDb = null
        val fresh = SystemClock.elapsedRealtime() - pendingQuietStepAtMs <= PENDING_STEP_TTL_MS
        return if (fresh) pending else null
    }

    private fun landRefusedStep() {
        val pending = takeFreshPendingStep() ?: return
        if (coordinator.isMuted) { Log.i(tag, "Refused step skipped: muted"); return }
        if (coordinator.uiState().quietUnavailable) { Log.i(tag, "Refused step skipped: call"); return }
        Log.i(tag, "Landing on refused step (${pending}dB)")
        coordinator.applyQuiet(pending)
    }


    // Fungsi anti-lag: Mengumpulkan tap dan mengeksekusinya di Background Thread
    private fun handleStep(direction: Int) {
        stepAccumulator += direction
        
        // Kalau job belum jalan, jalankan di background
        if (stepJob?.isActive != true) {
            stepJob = serviceScope.launch(Dispatchers.Default) {
                while (stepAccumulator != 0) {
                    val dir = if (stepAccumulator > 0) 1 else -1
                    stepAccumulator -= dir
                    coordinator.step(dir)
                }
                // Update notifikasi HANYA sekali di akhir antrean (biar UI mulus)
                mainHandler.post { updateNotification(audioController.attenuationDb.value) }
            }
        }
    }

    override fun onDestroy() {
        Log.i(tag, "Service stopping (userRequested=$stopRequestedByUser)")
        ControlLive.reset()
        runCatching { unregisterReceiver(volumeChangeReceiver) }
        runCatching { unregisterReceiver(keyInstalledReceiver) }
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(deviceCallback)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
                am.removeOnModeChangedListener(modeListener)
            }
        }
        coordinator.release()
        audioController.release()
        serviceScope.cancel()
        if (stopRequestedByUser) Prefs.setServiceWasRunning(applicationContext, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gv_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.gv_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(dB: Float): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, VolumeControlService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val downIntent = PendingIntent.getService(
            this, 2, Intent(this, VolumeControlService::class.java).apply { action = ACTION_STEP_DOWN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val upIntent = PendingIntent.getService(
            this, 3, Intent(this, VolumeControlService::class.java).apply { action = ACTION_STEP_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val muteIntent = PendingIntent.getService(
            this, 4, Intent(this, VolumeControlService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val tapIntent = PendingIntent.getActivity(
            this, 1, Intent(this, com.granularvolume.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val display = lockDisplay()
        val level = if (dB == 0f) getString(R.string.gv_notif_on) else "%.0f dB".format(dB)
        val dbText = when (display) {
            FullRangeCoordinator.LockDisplay.LOCKED -> getString(R.string.gv_notif_locked)
            FullRangeCoordinator.LockDisplay.HELD -> getString(R.string.gv_notif_held, level)
            FullRangeCoordinator.LockDisplay.OPEN -> level
        }
        val text = when {
            ControlLive.effectTaken -> getString(R.string.gv_effect_taken)
            coordinator.isMuted -> "Volume dibisukan"
            else -> dbText
        }
        
        // Warna dinamis: Merah (Mute), Amber (Locked), Biru (Normal)
        val color = when {
            coordinator.isMuted -> 0xFFDC2626.toInt()
            display != FullRangeCoordinator.LockDisplay.OPEN -> 0xFFF59E0B.toInt()
            else -> 0xFF3B82F6.toInt()
        }

        // RemoteViews untuk Compact
        val smallView = android.widget.RemoteViews(packageName, R.layout.notification_small)
        smallView.setTextViewText(R.id.notif_title_small, getString(R.string.app_name))
        smallView.setTextViewText(R.id.notif_text_small, text)

        // RemoteViews untuk Expanded
        val largeView = android.widget.RemoteViews(packageName, R.layout.notification_large)
        largeView.setTextViewText(R.id.notif_title, getString(R.string.app_name))
        largeView.setTextViewText(R.id.notif_text, text)
        
        // Progress Bar Visual (0-100)
        val percent = if (coordinator.isMuted) 0 else (((dB + 30f) / 30f * 100f).coerceIn(0f, 100f)).toInt()
        largeView.setProgressBar(R.id.notif_progress, 100, percent, false)

        // Ganti Ikon Mute secara dinamis
        largeView.setImageViewResource(
            R.id.btn_mute,
            if (coordinator.isMuted) R.drawable.ic_volume_up else R.drawable.ic_volume_off
        )

        // Pasang aksi klik ke tombol
        largeView.setOnClickPendingIntent(R.id.btn_down, downIntent)
        largeView.setOnClickPendingIntent(R.id.btn_up, upIntent)
        largeView.setOnClickPendingIntent(R.id.btn_mute, muteIntent)
        largeView.setOnClickPendingIntent(R.id.btn_stop, stopIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_slider)
            .setContentIntent(tapIntent)
            .setCustomContentView(smallView)
            .setCustomBigContentView(largeView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setColor(color) // Pewarnaan background notifikasi (jika didukung OS)
            .build()
    }

    private fun updateNotification(dB: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(dB))
    }
}
