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
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS

/** Manual-only setup. Credentials never enter saved-instance state or logging. */
class AuthFragmentStep2 : Fragment() {
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
        val submitButton = Button(context).apply {
            id = View.generateViewId()
            text = getString(R.string.submit)
            setOnClickListener { submit(server, key) }
        }
        form.addView(submitButton)
        if (PreferenceManager.isLoggedId()) {
            form.addView(Button(context).apply {
                text = getString(R.string.private_disconnect)
                setOnClickListener {
                    PreferenceManager.removeApiSettings()
                    key.text.clear()
                    server.text.clear()
                    isEnabled = false
                    server.requestFocus()
                }
            })
        }
        server.nextFocusDownId = key.id
        key.nextFocusUpId = server.id
        key.nextFocusDownId = submitButton.id
        submitButton.nextFocusUpId = key.id
        key.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitButton.performClick(); true } else false
        }
        server.requestFocus()
        return ScrollView(context).apply { addView(form); isSaveEnabled = false }
    }

    private fun submit(server: EditText, key: EditText) {
        if (submitting || warning?.isShowing == true) return
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
        try {
            PreferenceManager.saveApiSettings(host, key.text.toString())
            PreferenceManager.save(SCREENSAVER_ALBUMS, emptySet())
            key.text.clear()
            findNavController().navigate(
                R.id.action_global_homeFragment, null,
                NavOptions.Builder().setPopUpTo(R.id.authFragment, true).build()
            )
        } catch (_: Exception) {
            // No exception text: encryption/network implementations may include private data.
            Toast.makeText(context, R.string.private_save_failed, Toast.LENGTH_LONG).show()
            submitting = false
        }
    }

    override fun onDestroyView() {
        warning?.dismiss()
        warning = null
        keyInput?.text?.clear()
        keyInput = null
        submitting = false
        super.onDestroyView()
    }
}
