package com.helointearti.obturateur

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Surveille l'appli au premier plan. Quand c'est une appli photo, le téléphone passe en
 * silencieux (ce qui coupe le son de l'obturateur) ; en la quittant, le son est rétabli.
 */
class ShutterSilencerService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var audio: AudioManager
    private lateinit var notif: NotificationManager
    private lateinit var prefs: SharedPreferences
    private var watched: Set<String> = emptySet()

    /** Fenêtres qui s'ouvrent par-dessus une appli sans la quitter. */
    private val ignored = setOf(
        "com.android.systemui",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.app.cocktailbarservice",
        "android"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notif = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        watched = Prefs.watchedPackages(this)
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.KEY_EXCLUDED || key == Prefs.KEY_EXTRA) watched = Prefs.watchedPackages(this)
        if (key == Prefs.KEY_ENABLED && !prefs.getBoolean(Prefs.KEY_ENABLED, true)) restore()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored || pkg == packageName) return

        if (pkg in watched && prefs.getBoolean(Prefs.KEY_ENABLED, true)) silence()
        else restore()
    }

    private fun silence() {
        if (prefs.getBoolean(Prefs.KEY_ACTIVE, false)) return
        val mediaWasMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        prefs.edit()
            .putBoolean(Prefs.KEY_ACTIVE, true)
            .putInt(Prefs.KEY_SAVED_RINGER, audio.ringerMode)
            .putBoolean(Prefs.KEY_SAVED_MEDIA_MUTED, mediaWasMuted)
            .apply()
        try {
            // Silencieux complet si l'accès « Ne pas déranger » est accordé, sinon vibreur.
            audio.ringerMode = if (notif.isNotificationPolicyAccessGranted)
                AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_VIBRATE
            if (prefs.getBoolean(Prefs.KEY_MUTE_MEDIA, false) && !mediaWasMuted) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossible de passer en silencieux", e)
        }
    }

    private fun restore() {
        if (!::prefs.isInitialized || !prefs.getBoolean(Prefs.KEY_ACTIVE, false)) return
        try {
            audio.ringerMode = prefs.getInt(Prefs.KEY_SAVED_RINGER, AudioManager.RINGER_MODE_NORMAL)
            if (!prefs.getBoolean(Prefs.KEY_SAVED_MEDIA_MUTED, false) &&
                audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Impossible de rétablir le son", e)
        }
        prefs.edit().putBoolean(Prefs.KEY_ACTIVE, false).apply()
    }

    override fun onInterrupt() = restore()

    override fun onDestroy() {
        restore()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    companion object { private const val TAG = "Obturateur" }
}
