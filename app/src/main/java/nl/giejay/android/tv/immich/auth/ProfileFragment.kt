package nl.giejay.android.tv.immich.auth

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
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
import nl.giejay.android.tv.immich.security.ProfileSummary
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** The only entry point after launch, process restoration, Home, or profile switching. */
class ProfileFragment : Fragment() {
    private var pinInput: EditText? = null
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
                runCatching { PreferenceManager.profiles.remove(profile.id) }
                    .onSuccess { (activity as? MainActivity)?.lockAndShowProfiles() }
            }.show()
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun showUnlock(profile: ProfileSummary) {
        if (busy || dialog?.isShowing == true) return
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_DONE
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        pinInput = input
        dialog = AlertDialog.Builder(requireContext()).setTitle(profile.name)
            .setMessage(R.string.profiles_enter_pin).setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.profiles_unlock, null).create()
        dialog?.setOnDismissListener { input.text.clear(); pinInput = null }
        dialog?.show()
        val unlockDialog = dialog!!
        val unlock = unlockDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        unlock.setOnClickListener {
            if (busy) return@setOnClickListener
            busy = true
            unlock.isEnabled = false
            val pin = input.text.toString()
            input.text.clear()
            viewLifecycleOwner.lifecycleScope.launch {
                val config = withContext(Dispatchers.IO) {
                    runCatching { PreferenceManager.profiles.unlock(profile.id, pin) }.getOrNull()
                }
                busy = false
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || dialog !== unlockDialog || !unlockDialog.isShowing) return@launch
                unlock.isEnabled = true
                if (config == null) {
                    input.error = getString(R.string.profiles_wrong_pin)
                    input.requestFocus()
                } else {
                    dialog?.dismiss()
                    PreferenceManager.activateProfile(profile.id, config)
                    (activity as? MainActivity)?.showUnlockedHome()
                }
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { unlock.performClick(); true } else false
        }
        input.requestFocus()
    }

    override fun onStop() {
        pinInput?.text?.clear()
        dialog?.dismiss()
        super.onStop()
    }

    override fun onDestroyView() {
        pinInput?.text?.clear()
        dialog?.dismiss()
        dialog = null
        busy = false
        super.onDestroyView()
    }
}
