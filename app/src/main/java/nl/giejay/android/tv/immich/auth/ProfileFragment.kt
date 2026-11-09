package nl.giejay.android.tv.immich.auth

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.MainActivity
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.security.PinHasher
import nl.giejay.android.tv.immich.security.ProfileSummary
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** The only entry point after launch, process restoration, Home, or profile switching. */
class ProfileFragment : Fragment() {
    private var clearPinSecrets: (() -> Unit)? = null
    private var dialog: AlertDialog? = null
    private var busy = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val space = (40 * resources.displayMetrics.density).toInt()
            setPadding(space, space, space, space)
            setBackgroundColor(resources.getColor(R.color.tv_background, null))
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        form.addView(TextView(context).apply {
            setText(R.string.profiles_title)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(8))
        })
        form.addView(TextView(context).apply {
            setText(R.string.profiles_intro)
            textSize = 17f
            setTextColor(context.getColor(R.color.tv_text_secondary))
            setPadding(0, 0, 0, dp(24))
        })
        if (PreferenceManager.sharedPreference.getBoolean("profile_upgrade_required", false)) {
            form.addView(TextView(context).apply { setText(R.string.profiles_upgrade); textSize = 18f })
        }
        val profiles = runCatching { PreferenceManager.profiles.profiles() }.getOrElse {
            form.addView(TextView(context).apply { setText(R.string.private_save_failed) })
            return form
        }
        val cards = GridLayout(context).apply {
            columnCount = if (resources.configuration.screenWidthDp < 700) 2 else 3
            useDefaultMargins = false
        }
        profiles.forEachIndexed { index, profile ->
            cards.addView(Button(context).apply {
                text = "${profile.name.take(1).uppercase()}\n${profile.name}"
                contentDescription = profile.name
                textSize = 21f
                isAllCaps = false
                gravity = Gravity.CENTER
                maxLines = 3
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = context.getDrawable(R.drawable.tv_profile_card)
                setTextColor(context.getColor(R.color.screen_white))
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / cards.columnCount),
                    GridLayout.spec(index % cards.columnCount, 1f)
                ).apply {
                    width = 0
                    height = dp(120)
                    setMargins(0, 0, dp(12), dp(12))
                }
                setOnClickListener { showUnlock(profile) }
            })
        }
        form.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(Button(context).apply {
            setText(R.string.profiles_add)
            isAllCaps = false
            textSize = 18f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = context.getDrawable(R.drawable.tv_menu_focus)
            isEnabled = profiles.size < 12
            setOnClickListener { findNavController().navigate(R.id.authFragment) }
        })
        form.addView(TextView(context).apply {
            setText(R.string.profiles_recovery)
            textSize = 15f
            setTextColor(context.getColor(R.color.tv_text_secondary))
            setPadding(0, dp(18), 0, dp(12))
        })
        if (profiles.isNotEmpty()) {
            form.addView(Button(context).apply {
                setText(R.string.profiles_remove)
                setOnClickListener {
                    dialog = AlertDialog.Builder(context).setTitle(R.string.profiles_remove)
                        .setItems(profiles.map { it.name }.toTypedArray()) { _, index -> confirmRemove(profiles[index]) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            })
        }
        return ScrollView(context).apply { setBackgroundColor(resources.getColor(R.color.tv_background, null)); isSaveEnabled = false; addView(form) }
    }

    private fun confirmRemove(profile: ProfileSummary) {
        dialog = AlertDialog.Builder(requireContext()).setTitle(profile.name)
            .setMessage(R.string.profiles_remove_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profiles_remove) { _, _ ->
                runCatching { PreferenceManager.removeProfile(profile.id) }
                    .onSuccess { (activity as? MainActivity)?.lockAndShowProfiles() }
            }.show()
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun showUnlock(profile: ProfileSummary) {
        if (busy || dialog?.isShowing == true) return
        val automatic = profile.pinLength == PinHasher.PIN_LENGTH
        val pad = TvPinPad(requireContext(), automatic).apply {
            setPrompt(getString(if (automatic) R.string.profiles_pin_auto_prompt else R.string.profiles_pin_legacy_prompt))
        }
        val unlockDialog = AlertDialog.Builder(requireContext()).setTitle(profile.name)
            .setView(pad).setNegativeButton(android.R.string.cancel, null).create()
        dialog = unlockDialog
        val clear = { pad.clear() }
        clearPinSecrets = clear
        unlockDialog.setOnDismissListener {
            clear()
            if (dialog === unlockDialog) clearPinSecrets = null
        }
        unlockDialog.setOnKeyListener { _, keyCode, event -> pad.handleKey(keyCode, event) }
        unlockDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        unlockDialog.show()
        pad.connectCancelButton(unlockDialog.getButton(AlertDialog.BUTTON_NEGATIVE))
        pad.onSubmit = submit@{ pin ->
            if (busy || !unlockDialog.isShowing) return@submit
            busy = true
            // Render feedback and disable every digit before dispatching the expensive KDF.
            pad.setBusy(true, getString(R.string.profiles_pin_verifying))
            viewLifecycleOwner.lifecycleScope.launch {
                val config = withContext(Dispatchers.IO) {
                    runCatching { PreferenceManager.profiles.unlock(profile.id, pin) }.getOrNull()
                }
                busy = false
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || dialog !== unlockDialog || !unlockDialog.isShowing) return@launch
                if (config == null) {
                    pad.setBusy(false)
                    pad.showError(getString(R.string.profiles_wrong_pin))
                } else {
                    unlockDialog.dismiss()
                    if (PinHasher.isLegacy(pin)) {
                        showPinChange(profile, pin.toCharArray())
                    } else {
                        // No animation, timer or network request delays a verified unlock.
                        PreferenceManager.activateProfile(profile.id, config)
                        (activity as? MainActivity)?.showUnlockedHome()
                    }
                }
            }
        }
        pad.focusFirstKey()
    }

    private fun showPinChange(profile: ProfileSummary, currentPin: CharArray) {
        val pad = TvPinPad(requireContext(), automatic = true)
        var newPin: CharArray? = null
        fun startAgain(error: String? = null) {
            newPin?.fill('\u0000')
            newPin = null
            pad.clear()
            pad.setPrompt(getString(R.string.profiles_pin_migrate_choose))
            if (error != null) pad.showError(error) else pad.focusFirstKey()
        }
        val changeDialog = AlertDialog.Builder(requireContext()).setTitle(R.string.profiles_new_pin)
            .setView(pad).setNegativeButton(android.R.string.cancel, null).create()
        dialog = changeDialog
        val clear = {
            currentPin.fill('\u0000')
            newPin?.fill('\u0000')
            newPin = null
            pad.clear()
        }
        clearPinSecrets = clear
        changeDialog.setOnDismissListener {
            clear()
            if (dialog === changeDialog) clearPinSecrets = null
        }
        changeDialog.setOnKeyListener { _, keyCode, event -> pad.handleKey(keyCode, event) }
        changeDialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        changeDialog.show()
        val cancel = changeDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        pad.connectCancelButton(cancel)
        pad.onSubmit = submit@{ entered ->
            if (busy || !changeDialog.isShowing) return@submit
            val chosen = newPin
            if (chosen == null) {
                newPin = entered.toCharArray()
                pad.setPrompt(getString(R.string.profiles_pin_migrate_confirm))
                pad.focusFirstKey()
                return@submit
            }
            if (String(chosen) != entered) {
                startAgain(getString(R.string.profiles_pin_mismatch))
                return@submit
            }
            busy = true
            pad.setBusy(true, getString(R.string.profiles_pin_saving))
            cancel.isEnabled = false
            // Submitted writes may complete while backgrounded. Never imply Cancel can undo
            // a write in flight, and never activate after dismissal/backgrounding.
            changeDialog.setCancelable(false)
            val oldPin = String(currentPin)
            chosen.fill('\u0000')
            newPin = null
            viewLifecycleOwner.lifecycleScope.launch {
                val config = withContext(Dispatchers.IO) {
                    runCatching { PreferenceManager.changeProfilePin(profile.id, oldPin, entered) }.getOrNull()
                }
                busy = false
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || dialog !== changeDialog || !changeDialog.isShowing) return@launch
                cancel.isEnabled = true
                changeDialog.setCancelable(true)
                if (config == null) {
                    pad.setBusy(false)
                    startAgain(getString(R.string.profiles_pin_save_failed))
                } else {
                    changeDialog.dismiss()
                    PreferenceManager.activateProfile(profile.id, config)
                    (activity as? MainActivity)?.showUnlockedHome()
                }
            }
        }
        startAgain()
    }

    override fun onStop() {
        clearPinSecrets?.invoke()
        dialog?.dismiss()
        super.onStop()
    }

    override fun onDestroyView() {
        clearPinSecrets?.invoke()
        clearPinSecrets = null
        dialog?.dismiss()
        dialog = null
        busy = false
        super.onDestroyView()
    }
}
