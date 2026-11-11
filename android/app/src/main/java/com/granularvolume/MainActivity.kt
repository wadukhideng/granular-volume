package com.granularvolume

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.granularvolume.service.GranularVolumeTileService
import com.granularvolume.service.VolumeControlService
import com.granularvolume.util.AccessState
import com.granularvolume.util.BillingManager
import com.granularvolume.util.ControlLive
import com.granularvolume.util.Entitlement
import com.granularvolume.util.Links
import com.granularvolume.util.PermissionHelper
import com.granularvolume.util.Prefs
import com.granularvolume.util.ProAccess
import com.granularvolume.util.PurchaseFlow
import com.granularvolume.util.ReviewHelper
import com.granularvolume.util.TrialNotices

class MainActivity : AppCompatActivity() {

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startServiceAndOfferTile() }

    companion object {
        private const val STATE_TRIAL_CARD = "trial_card_showing"
        const val TERMS_VERSION = 3
        private const val TRIAL_CARD_FROM_DAYS = 3
        private const val URL_TERMS = "https://rzuss.github.io/granular-volume-privacy/terms-of-use.html"
        private const val URL_PRIVACY = "https://rzuss.github.io/granular-volume-privacy/"
    }

    private var trialCardShowing = false
    private var trialCardRestored = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRIAL_CARD, trialCardShowing)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ProAccess.evaluateGrandfather(this)
        TrialNotices.arm(this)
        hideOverlayUi()
        setupConsentGate()
        findViewById<TextView>(R.id.tv_link_licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        findViewById<TextView>(R.id.tv_link_help).setOnClickListener { Links.open(this, Links.HELP) }

        if (isSetUp() && ControlLive.running) {
            ReviewHelper.maybeRequestReview(this) { openAccessAndClose() }
            return
        }
        trialCardRestored = savedInstanceState?.getBoolean(STATE_TRIAL_CARD, false) == true
        val cardShowing = setupTipjarCard()
        if (isSetUp() && !cardShowing) {
            ReviewHelper.maybeRequestReview(this) { launchService() }
            return
        }
        updateStatus()
        findViewById<Button>(R.id.btn_start_service).setOnClickListener {
            if (!hasAcceptedTerms()) {
                Toast.makeText(this, R.string.gv_toast_grant_first, Toast.LENGTH_SHORT).show()
            } else {
                launchService()
            }
        }
    }

    private fun hideOverlayUi() {
        for (id in listOf(
            R.id.btn_grant_overlay,
            R.id.tv_overlay_state,
            R.id.gv_overlay_retry_card,
            R.id.btn_retry_open,
            R.id.btn_retry_help
        )) {
            try { findViewById<View>(id)?.visibility = View.GONE } catch (_: Exception) {}
        }
    }

    private fun isSetUp(): Boolean =
        PermissionHelper.hasModifyAudioSettings(this) && hasAcceptedTerms()

    private fun openAccessAndClose() {
        runCatching {
            applicationContext.startActivity(
                Intent(applicationContext, InfoSheetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("GranularVolume", "open access: ${it.message}") }
        if (!isFinishing && !isDestroyed) finish()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        recheckOwnership()
    }

    private fun recheckOwnership() {
        val before = AccessState.of(this)
        BillingManager.verifyOwnership(applicationContext) { _ ->
            if (isFinishing || isDestroyed) return@verifyOwnership
            val after = AccessState.of(this)
            if (after != before) {
                Log.i("GranularVolume", "Access changed on resume: $before -> $after")
                if (after == AccessState.UNLOCKED && ControlLive.running) PurchaseFlow.notifyService(this)
                setupTipjarCard()
                updateStatus()
            }
        }
    }

    private fun setupTipjarCard(): Boolean {
        setupTrialOrientation()
        val card = findViewById<View>(R.id.gv_tipjar_card)
        if (BuildConfig.FLAVOR == "play" && !Entitlement.isGrandfathered(this) &&
            !Prefs.wasUnlockAcknowledged(this) && ProAccess.hasPaidUnlock(this)
        ) {
            card.visibility = View.VISIBLE
            findViewById<TextView>(R.id.tv_card_title).setText(R.string.gv_unlocked_title)
            findViewById<TextView>(R.id.tv_card_body).setText(R.string.gv_unlocked_body)
            findViewById<TextView>(R.id.btn_tipjar_support).visibility = View.GONE
            findViewById<View>(R.id.btn_card_restore).visibility = View.GONE
            findViewById<TextView>(R.id.btn_tipjar_dismiss).apply {
                visibility = View.VISIBLE
                setText(R.string.gv_unlocked_dismiss)
                setOnClickListener {
                    Prefs.setUnlockAcknowledged(this@MainActivity)
                    card.visibility = View.GONE
                }
            }
            return true
        }
        return setupTrialCard(card)
    }

    private fun setupTrialOrientation() {
        val line = findViewById<TextView>(R.id.tv_trial_orientation) ?: return
        if (!AccessState.of(this).onTrial) { line.visibility = View.GONE; return }
        line.text = if (Entitlement.isInGrace(this)) {
            if (!Prefs.wasLastDayNudgeShown(this)) Prefs.setLastDayNudgeShown(this)
            getString(R.string.gv_trial_grace)
        } else {
            val days = Entitlement.daysLeftInTrial(this)
            resources.getQuantityString(R.plurals.gv_trial_orientation_days, days, days)
        }
        line.visibility = View.VISIBLE
    }

    private fun setupTrialCard(card: View): Boolean {
        if (BuildConfig.FLAVOR != "play") { card.visibility = View.GONE; return false }
        val title = findViewById<TextView>(R.id.tv_card_title)
        val body = findViewById<TextView>(R.id.tv_card_body)
        val support = findViewById<TextView>(R.id.btn_tipjar_support)
        val dismiss = findViewById<TextView>(R.id.btn_tipjar_dismiss)
        val restore = findViewById<TextView>(R.id.btn_card_restore)
        restore.visibility = View.GONE
        val pending = Entitlement.isPurchasePending(this)

        if (ProAccess.isOnTrial(this)) {
            val daysLeft = Entitlement.daysLeftInTrial(this)
            if (!hasAcceptedTerms()) { card.visibility = View.GONE; return false }
            val shownToday = Prefs.getTrialCardShownForDay(this) == daysLeft &&
                !trialCardRestored && !trialCardShowing
            if (daysLeft > TRIAL_CARD_FROM_DAYS || shownToday) {
                card.visibility = View.GONE; return false
            }
            Prefs.setTrialCardShownForDay(this, daysLeft)
            trialCardShowing = true
            card.visibility = View.VISIBLE
            title.text = resources.getQuantityString(R.plurals.gv_trial_days_left, daysLeft, daysLeft)
            body.setText(if (pending) R.string.gv_purchase_pending else R.string.gv_trial_body)
            wireBuyButton(support, pending)
            dismiss.visibility = View.VISIBLE
            dismiss.setText(R.string.gv_trial_dismiss)
            dismiss.setOnClickListener {
                trialCardShowing = false
                card.visibility = View.GONE
                if (isSetUp()) launchService()
            }
            return true
        }

        if (!ProAccess.isTrialExpired(this)) { card.visibility = View.GONE; return false }
        card.visibility = View.VISIBLE
        title.setText(R.string.gv_trial_over_title)
        body.setText(if (pending) R.string.gv_purchase_pending else R.string.gv_trial_over_body)
        wireBuyButton(support, pending)
        dismiss.visibility = View.GONE
        restore.visibility = View.VISIBLE
        restore.setOnClickListener { PurchaseFlow.restore(this) { setupTipjarCard(); updateStatus() } }
        return true
    }

    private fun wireBuyButton(button: TextView, pending: Boolean) {
        if (pending) { button.visibility = View.GONE; return }
        button.visibility = View.VISIBLE
        button.text = PurchaseFlow.ctaLabel(this)
        button.isEnabled = !PurchaseFlow.isInFlight()
        refreshCardPrice(button)
        button.setOnClickListener {
            if (PurchaseFlow.isInFlight()) return@setOnClickListener
            PurchaseFlow.start(
                this,
                onSettled = {
                    if (!isFinishing && !isDestroyed) {
                        button.isEnabled = true
                        button.text = PurchaseFlow.ctaLabel(this)
                    }
                },
                onUnlocked = { if (!isFinishing && !isDestroyed) { setupTipjarCard(); updateStatus() } })
            button.isEnabled = false
            button.text = PurchaseFlow.ctaLabel(this)
        }
    }

    private fun refreshCardPrice(button: TextView) {
        BillingManager.refreshPrice(this) {
            if (!isFinishing && !isDestroyed) button.text = PurchaseFlow.ctaLabel(this)
        }
    }

    private fun hasAcceptedTerms(): Boolean =
        Prefs.getTermsAcceptedVersion(this) >= TERMS_VERSION

    private fun setupConsentGate() {
        val block = findViewById<View>(R.id.gv_consent_block)
        if (hasAcceptedTerms()) { block.visibility = View.GONE; return }
        block.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_link_terms).setOnClickListener { openUrl(URL_TERMS) }
        findViewById<TextView>(R.id.tv_link_privacy).setOnClickListener { openUrl(URL_PRIVACY) }
        findViewById<Button>(R.id.btn_accept_terms).setOnClickListener {
            Prefs.setTermsAcceptedVersion(this, TERMS_VERSION)
            block.visibility = View.GONE
            updateStatus()
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, url, Toast.LENGTH_LONG).show() }
    }

    private fun launchService() {
        if (isFinishing || isDestroyed) {
            runCatching {
                applicationContext.startForegroundService(Intent(applicationContext, VolumeControlService::class.java))
            }.onFailure { Log.w("GranularVolume", "launchService after destroy: ${it.message}") }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: IllegalStateException) {
                Log.w("GranularVolume", "notification permission prompt unavailable: ${e.message}")
                startServiceAndOfferTile()
            }
        } else {
            startServiceAndOfferTile()
        }
    }

    private fun startServiceAndOfferTile() {
        val offerTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Prefs.wasQsTileOffered(this)
        startForegroundService(Intent(this, VolumeControlService::class.java))
        Toast.makeText(this, R.string.gv_toast_started, Toast.LENGTH_SHORT).show()
        if (offerTile) offerQsTile() else finish()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun offerQsTile() {
        Prefs.setQsTileOffered(this, true)
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val release = {
            if (released.compareAndSet(false, true)) {
                if (!isFinishing && !isDestroyed) finish()
            }
        }
        try {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, GranularVolumeTileService::class.java),
                getString(R.string.app_name),
                Icon.createWithResource(this, R.drawable.ic_qs_tile),
                mainExecutor
            ) { release() }
        } catch (e: RuntimeException) {
            Log.w("GranularVolume", "Add-tile dialog unavailable: ${e.message}")
            release()
        }
    }

    private fun updateStatus() {
        val audioOk = PermissionHelper.hasModifyAudioSettings(this)
        val consented = hasAcceptedTerms()
        try { renderStatePill(findViewById(R.id.tv_audio_state), audioOk) } catch (_: Exception) {}
        renderNotificationRow()
        renderBatteryHint()
        val state = AccessState.of(this)
        findViewById<TextView>(R.id.tv_hint).text = getString(
            when {
                !consented -> R.string.gv_hint_consent
                state == AccessState.LOCKED -> R.string.gv_hint_locked
                else -> R.string.gv_hint_ready
            })
        findViewById<View>(R.id.tv_hint).visibility = View.VISIBLE
        findViewById<Button>(R.id.btn_start_service).apply {
            visibility = View.VISIBLE
            isEnabled = consented
        }
    }

    private fun renderNotificationRow() {
        val on = NotificationManagerCompat.from(this).areNotificationsEnabled()
        findViewById<TextView>(R.id.tv_notif_desc).setText(
            if (AccessState.of(this).onTrial) R.string.gv_perm_notif_desc_trial
            else R.string.gv_perm_notif_desc)
        val pill = findViewById<TextView>(R.id.tv_notif_state)
        pill.setText(if (on) R.string.gv_state_on else R.string.gv_state_off)
        pill.setTextColor(ContextCompat.getColor(this, if (on) R.color.gv_success else R.color.gv_text_secondary))
        pill.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (on) R.color.gv_success_dim else R.color.gv_surface_stroke))
    }

    private fun renderBatteryHint() {
        val exempt = runCatching {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(true)
        findViewById<View>(R.id.tv_battery_hint).visibility = if (exempt) View.GONE else View.VISIBLE
    }

    private fun renderStatePill(pill: TextView, granted: Boolean) {
        val textRes = if (granted) R.string.gv_state_granted else R.string.gv_state_needed
        val fg = if (granted) R.color.gv_success else R.color.gv_warning
        val bg = if (granted) R.color.gv_success_dim else R.color.gv_warning_dim
        pill.text = getString(textRes)
        pill.setTextColor(ContextCompat.getColor(this, fg))
        pill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
    }
}
