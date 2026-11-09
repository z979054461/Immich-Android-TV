package nl.giejay.android.tv.immich.auth

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.MainActivity
import nl.giejay.android.tv.immich.security.PinHasher
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

/** Manual-only setup. Credentials never enter saved-instance state or logging. */
class AuthFragmentStep2 : Fragment() {
    private var nameInput: EditText? = null
    private var pinInput: EditText? = null
    private var confirmInput: EditText? = null
    private var keyInput: EditText? = null
    private var warning: AlertDialog? = null
    private var submitting = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val padding = (32 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun label(text: String, size: Float = 20f) {
            form.addView(TextView(context).apply { this.text = text; textSize = size })
        }
        label(getString(R.string.app_name), 28f)
        label(getString(R.string.private_manual_setup))
        label(getString(R.string.profiles_name))
        nameInput = EditText(context).apply { isSaveEnabled = false; setSingleLine(true) }
        form.addView(nameInput)
        label(getString(R.string.server_url_text))
        val server = EditText(context).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(PreferenceManager.hostName)
            hint = "https://immich.example.local"
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
        }
        form.addView(server)
        label(getString(R.string.api_key_text))
        val key = EditText(context).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            // Never show or prefill the stored key, including when changing servers.
            hint = getString(R.string.enter_api_key)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        keyInput = key
        form.addView(key)
        label(getString(R.string.profiles_new_pin))
        fun pinField() = EditText(context).apply {
            // Validate the full input on submit; a length filter would silently truncate pasted PINs.
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        pinInput = pinField()
        form.addView(pinInput)
        label(getString(R.string.profiles_confirm_pin))
        confirmInput = pinField()
        form.addView(confirmInput)
        val submitButton = Button(context).apply {
            id = View.generateViewId()
            text = getString(R.string.submit)
            setOnClickListener { submit(server, key) }
        }
        form.addView(submitButton)
        nameInput?.requestFocus()
        return ScrollView(context).apply { addView(form); isSaveEnabled = false }
    }

    private fun submit(server: EditText, key: EditText) {
        if (submitting || warning?.isShowing == true) return
        if (nameInput?.text.toString().trim().length !in 1..40) {
            nameInput?.error = getString(R.string.profiles_name)
            nameInput?.requestFocus()
            return
        }
        if (!PinHasher.valid(pinInput?.text.toString()) || pinInput?.text.toString() != confirmInput?.text.toString()) {
            pinInput?.error = getString(R.string.profiles_pin_invalid)
            pinInput?.requestFocus()
            return
        }
        val host = try {
            ServerPolicy.normalizeServer(server.text.toString())
        } catch (_: IllegalArgumentException) {
            server.error = getString(R.string.private_invalid_server)
            server.requestFocus()
            return
        }
        if (!ServerPolicy.isValidApiKey(key.text.toString())) {
            key.error = getString(R.string.enter_api_key)
            key.requestFocus()
            return
        }
        if (host.startsWith("http://")) {
            warning = AlertDialog.Builder(requireContext())
                .setTitle(R.string.private_http_warning_title)
                .setMessage(R.string.private_http_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_text) { _, _ -> save(host, key) }
                .show()
            // Default to the safe choice for TV remotes.
            warning?.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        } else {
            save(host, key)
        }
    }

    private fun save(host: String, key: EditText) {
        if (submitting || !isAdded) return
        submitting = true
        val name = nameInput!!.text.toString()
        val pin = pinInput!!.text.toString()
        val secret = key.text.toString()
        key.text.clear()
        pinInput?.text?.clear()
        confirmInput?.text?.clear()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PreferenceManager.profiles.add(name, host, secret, pin) }
            }
            submitting = false
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (result.isSuccess) {
                PreferenceManager.sharedPreference.edit().putBoolean("profile_upgrade_required", false).apply()
                // Enrollment does not unlock: verify the PIN through the same normal entry path.
                (activity as? MainActivity)?.lockAndShowProfiles()
            } else {
                Toast.makeText(context, R.string.private_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        warning?.dismiss()
        keyInput?.text?.clear()
        pinInput?.text?.clear()
        confirmInput?.text?.clear()
        super.onStop()
    }

    override fun onDestroyView() {
        warning?.dismiss()
        warning = null
        keyInput?.text?.clear()
        keyInput = null
        pinInput?.text?.clear()
        confirmInput?.text?.clear()
        pinInput = null
        confirmInput = null
        nameInput = null
        submitting = false
        super.onDestroyView()
    }
}
