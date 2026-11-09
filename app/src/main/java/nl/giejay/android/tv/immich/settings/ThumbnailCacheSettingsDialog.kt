package nl.giejay.android.tv.immich.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.security.PrivateThumbnailCache
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

internal object ThumbnailCacheSettingsDialog {
    fun show(context: Context, profileId: String): AlertDialog? {
        if (!PreferenceManager.isLoggedId() || PreferenceManager.activeProfileId != profileId) return null
        val connection = PreferenceManager.connectionSnapshot()
        val current = PreferenceManager.thumbnailCacheCapacity()
        val capacities = PrivateThumbnailCache.Capacity.values().toList()
        val labels = listOf(R.string.thumbnail_cache_off, R.string.thumbnail_cache_64,
            R.string.thumbnail_cache_128, R.string.thumbnail_cache_256)
        val choices = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val radios = capacities.mapIndexed { index, capacity ->
            RadioButton(context).apply {
                id = View.generateViewId()
                tag = "thumbnail_cache_choice_${capacity.name}"
                setText(labels[index])
                choices.addView(this)
                isChecked = capacity == current
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(TextView(context).apply { setText(R.string.thumbnail_cache_description); textSize = 16f })
            addView(choices)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.thumbnail_cache_title)
            .setView(content).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.thumbnail_cache_clear, null)
            .setPositiveButton(R.string.profiles_grace_save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        val clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        val main = Handler(Looper.getMainLooper())
        clear.setOnClickListener {
            clear.isEnabled = false
            clear.setText(R.string.thumbnail_cache_clearing)
            runCatching {
                PreferenceManager.clearThumbnailCache(profileId, connection) { success ->
                    main.post {
                        if (dialog.isShowing && PreferenceManager.activeProfileId == profileId &&
                            PreferenceManager.connectionSnapshot() === connection) {
                            clear.isEnabled = true
                            clear.setText(R.string.thumbnail_cache_clear)
                            Toast.makeText(context, if (success) R.string.thumbnail_cache_cleared else
                                R.string.thumbnail_cache_clear_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.onFailure {
                clear.isEnabled = true
                clear.setText(R.string.thumbnail_cache_clear)
                Toast.makeText(context, R.string.thumbnail_cache_clear_failed, Toast.LENGTH_LONG).show()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val index = radios.indexOfFirst { it.id == choices.checkedRadioButtonId }
            if (index < 0) return@setOnClickListener
            runCatching { PreferenceManager.setThumbnailCacheCapacity(profileId, capacities[index]) }
                .onSuccess { dialog.dismiss() }
                .onFailure { Toast.makeText(context, R.string.profiles_grace_save_failed, Toast.LENGTH_LONG).show() }
        }
        radios[capacities.indexOf(current).coerceAtLeast(0)].requestFocus()
        return dialog
    }
}
