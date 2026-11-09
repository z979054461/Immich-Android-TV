package nl.giejay.android.tv.immich.settings

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

internal object PinGraceSettingsDialog {
    fun show(context: Context, profileId: String): AlertDialog? {
        if (PreferenceManager.activeProfileId != profileId) return null
        val current = PreferenceManager.activeGraceMinutes() ?: return null
        val minutes = listOf(0, 1, 5, 15)
        val labels = listOf(R.string.profiles_grace_immediate, R.string.profiles_grace_one,
            R.string.profiles_grace_five, R.string.profiles_grace_fifteen)
        val choices = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val radios = minutes.mapIndexed { index, value ->
            RadioButton(context).apply {
                id = View.generateViewId()
                tag = "pin_grace_choice_$value"
                setText(labels[index])
                choices.addView(this)
                isChecked = value == current
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(TextView(context).apply {
                setText(R.string.profiles_grace_description)
                textSize = 16f
            })
            addView(choices)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.profiles_grace_title)
            .setView(content).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profiles_grace_save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val index = radios.indexOfFirst { it.id == choices.checkedRadioButtonId }
            if (index < 0) return@setOnClickListener
            runCatching { PreferenceManager.setActiveGraceMinutes(profileId, minutes[index]) }
                .onSuccess { dialog.dismiss() }
                .onFailure { Toast.makeText(context, R.string.profiles_grace_save_failed, Toast.LENGTH_LONG).show() }
        }
        radios[minutes.indexOf(current).coerceAtLeast(0)].requestFocus()
        return dialog
    }
}
