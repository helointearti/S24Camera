package com.helointearti.obturateur

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Écran de configuration : autorisations, interrupteur général, liste des applis photo. */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        root.removeAllViews()
        val prefs = Prefs.get(this)
        val serviceOn = isServiceEnabled()
        val dndOn = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted

        text("Coupe le son de l'obturateur dans toutes les applis photo : le téléphone passe " +
            "en silencieux quand une appli photo s'ouvre, et le son revient quand tu la quittes.", 15f)

        // --- Étape 1 : service d'accessibilité ---
        title("1. Détection des applis photo")
        status(serviceOn, "Activé", "Désactivé")
        if (!serviceOn) {
            text("Ouvre Accessibilité → Applications installées → Obturateur silencieux → Activer.\n" +
                "Si l'option est grisée : Paramètres → Applications → Obturateur silencieux → ⋮ " +
                "(en haut à droite) → « Autoriser les paramètres restreints », puis réessaie.", 13f)
            button("Ouvrir les réglages d'accessibilité") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        // --- Étape 2 : accès Ne pas déranger ---
        title("2. Mode silencieux complet")
        status(dndOn, "Autorisé", "Non autorisé (le téléphone passera en vibreur)")
        if (!dndOn) {
            button("Autoriser « Ne pas déranger »") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }

        // --- Réglages ---
        title("Réglages")
        switch("Activer le silence automatique", prefs.getBoolean(Prefs.KEY_ENABLED, true)) {
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, it).apply()
        }
        switch("Couper aussi le son multimédia (pour les applis qui jouent leur son de déclencheur comme une musique)",
            prefs.getBoolean(Prefs.KEY_MUTE_MEDIA, false)) {
            prefs.edit().putBoolean(Prefs.KEY_MUTE_MEDIA, it).apply()
        }

        // --- Applis surveillées ---
        title("Applis photo surveillées")
        val excluded = (prefs.getStringSet(Prefs.KEY_EXCLUDED, emptySet()) ?: emptySet()).toMutableSet()
        Prefs.detectCameraApps(this).forEach { (pkg, label) ->
            root.addView(CheckBox(this).apply {
                text = label
                isChecked = pkg !in excluded
                setOnCheckedChangeListener { _, checked ->
                    if (checked) excluded -= pkg else excluded += pkg
                    prefs.edit().putStringSet(Prefs.KEY_EXCLUDED, excluded.toSet()).apply()
                }
            })
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, ShutterSilencerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // --- Petits utilitaires d'interface ---

    private fun title(t: String) = root.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(8))
    })

    private fun text(t: String, size: Float) = root.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, 0, 0, dp(8))
    })

    private fun status(ok: Boolean, yes: String, no: String) = root.addView(TextView(this).apply {
        text = if (ok) "✅ $yes" else "⚠️ $no"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun button(t: String, onClick: () -> Unit) = root.addView(Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    })

    private fun switch(t: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        root.addView(SwitchCompat(this).apply {
            text = t
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, c -> onChange(c) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
