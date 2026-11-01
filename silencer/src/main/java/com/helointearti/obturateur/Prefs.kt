package com.helointearti.obturateur

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore

/** Réglages partagés entre l'écran et le service. */
object Prefs {
    private const val FILE = "obturateur"

    const val KEY_ENABLED = "enabled"
    const val KEY_MUTE_MEDIA = "muteMedia"
    const val KEY_EXCLUDED = "excluded"
    const val KEY_EXTRA = "extra"

    // État sauvegardé pendant qu'une appli photo est ouverte
    const val KEY_ACTIVE = "active"
    const val KEY_SAVED_RINGER = "savedRinger"
    const val KEY_SAVED_MEDIA_MUTED = "savedMediaMuted"

    /** Applis photo connues, au cas où elles ne se déclarent pas comme « appareil photo ». */
    private val KNOWN_CAMERAS = setOf(
        "com.sec.android.app.camera",          // Appareil photo Samsung
        "com.samsung.android.app.galaxyraw",   // Expert RAW
        "com.google.android.GoogleCamera",     // Google Camera
        "com.s24camera.app",                   // S24 Caméra
        "net.sourceforge.opencamera",          // Open Camera
        "com.instagram.android",
        "com.snapchat.android"
    )

    fun get(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Toutes les applis photo installées : nom de paquet -> nom affiché. */
    fun detectCameraApps(context: Context): Map<String, String> {
        val pm = context.packageManager
        val actions = listOf(
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            MediaStore.ACTION_IMAGE_CAPTURE,
            MediaStore.INTENT_ACTION_VIDEO_CAMERA,
            MediaStore.ACTION_VIDEO_CAPTURE
        )
        val pkgs = mutableSetOf<String>()
        actions.forEach { action ->
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                .forEach { pkgs += it.activityInfo.packageName }
        }
        KNOWN_CAMERAS.forEach { p ->
            try { pm.getPackageInfo(p, 0); pkgs += p } catch (_: Exception) { }
        }
        get(context).getStringSet(KEY_EXTRA, emptySet())?.let { pkgs += it }
        pkgs -= context.packageName
        return pkgs.associateWith { p ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }
        }.toList().sortedBy { it.second.lowercase() }.toMap()
    }

    /** Applis photo actuellement surveillées (détectées moins celles décochées). */
    fun watchedPackages(context: Context): Set<String> {
        val excluded = get(context).getStringSet(KEY_EXCLUDED, emptySet()) ?: emptySet()
        return detectCameraApps(context).keys - excluded
    }
}
