package nl.giejay.android.tv.immich.diagnostics

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R

/** A separate opaque screen. Opening it never starts a probe. */
class ConnectionDiagnosticsFragment : Fragment(R.layout.fragment_connection_diagnostics) {
    private val model: ConnectionDiagnosticsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val start = view.findViewById<Button>(R.id.diagnostics_start)
        val cancel = view.findViewById<Button>(R.id.diagnostics_cancel)
        val people = view.findViewById<CheckBox>(R.id.diagnostics_people)
        val status = view.findViewById<TextView>(R.id.diagnostics_status)
        val results = view.findViewById<TextView>(R.id.diagnostics_results)
        start.setOnClickListener { model.start(people.isChecked) }
        cancel.setOnClickListener { model.cancel() }
        view.findViewById<Button>(R.id.diagnostics_back).setOnClickListener { findNavController().popBackStack() }
        start.requestFocus()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previouslyRunning = false
                model.state.collect { state ->
                    val running = state.runState == DiagnosticRunState.RUNNING
                    val wasCancelFocused = cancel.hasFocus()
                    start.isEnabled = !running && state.runState != DiagnosticRunState.LOCKED
                    cancel.isEnabled = running
                    people.isEnabled = !running
                    status.setText(when (state.runState) {
                        DiagnosticRunState.IDLE -> R.string.diagnostics_idle
                        DiagnosticRunState.RUNNING -> R.string.diagnostics_running
                        DiagnosticRunState.FINISHED -> R.string.diagnostics_finished
                        DiagnosticRunState.CANCELLED -> R.string.diagnostics_cancelled
                        DiagnosticRunState.LOCKED -> R.string.diagnostics_locked
                    })
                    results.text = state.results.joinToString("\n\n") { result ->
                        val detail = result.version?.let { getString(R.string.diagnostics_version_value, it.major, it.minor, it.patch) }
                            ?: getString(statusText(result.status))
                        val elapsed = result.elapsedMillis?.let { getString(R.string.diagnostics_elapsed, it) }.orEmpty()
                        getString(R.string.diagnostics_result_row, getString(stepText(result.step)), detail, elapsed)
                    }
                    if (running && !previouslyRunning) cancel.requestFocus()
                    else if (!running && wasCancelFocused && start.isEnabled) start.requestFocus()
                    previouslyRunning = running
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        model.validateSession()
    }
    override fun onStop() {
        model.clear()
        // Clear visible text synchronously as well as cancelling the collector/transport.
        view?.findViewById<TextView>(R.id.diagnostics_results)?.text = ""
        super.onStop()
    }
    override fun onDestroyView() {
        model.clear()
        super.onDestroyView()
    }

    private fun stepText(step: DiagnosticStep): Int = when (step) {
        DiagnosticStep.CONFIGURATION -> R.string.diagnostics_step_configuration
        DiagnosticStep.PING -> R.string.diagnostics_step_ping
        DiagnosticStep.VERSION -> R.string.diagnostics_step_version
        DiagnosticStep.METADATA -> R.string.diagnostics_step_metadata
        DiagnosticStep.PEOPLE -> R.string.diagnostics_step_people
        DiagnosticStep.PHOTO_VIEW -> R.string.diagnostics_step_photo
    }
    private fun statusText(status: DiagnosticStatus): Int = when (status) {
        DiagnosticStatus.NOT_RUN -> R.string.diagnostics_not_run
        DiagnosticStatus.NOT_TESTED -> R.string.diagnostics_not_tested
        DiagnosticStatus.RUNNING -> R.string.diagnostics_running
        DiagnosticStatus.PASSED -> R.string.diagnostics_passed
        DiagnosticStatus.INVALID_CONFIGURATION -> R.string.diagnostics_invalid_configuration
        DiagnosticStatus.DNS_FAILURE -> R.string.diagnostics_dns_failure
        DiagnosticStatus.CONNECTION_FAILURE -> R.string.diagnostics_connection_failure
        DiagnosticStatus.TIMEOUT -> R.string.diagnostics_timeout
        DiagnosticStatus.CERTIFICATE_FAILURE -> R.string.diagnostics_certificate_failure
        DiagnosticStatus.REDIRECT_BLOCKED -> R.string.diagnostics_redirect_blocked
        DiagnosticStatus.UNAUTHORIZED -> R.string.diagnostics_unauthorized
        DiagnosticStatus.FORBIDDEN -> R.string.diagnostics_forbidden
        DiagnosticStatus.NOT_FOUND -> R.string.diagnostics_not_found
        DiagnosticStatus.BAD_REQUEST -> R.string.diagnostics_bad_request
        DiagnosticStatus.SERVER_ERROR -> R.string.diagnostics_server_error
        DiagnosticStatus.HTTP_ERROR -> R.string.diagnostics_http_error
        DiagnosticStatus.UNEXPECTED_RESPONSE -> R.string.diagnostics_unexpected_response
        DiagnosticStatus.NETWORK_FAILURE -> R.string.diagnostics_network_failure
        DiagnosticStatus.SESSION_EXPIRED -> R.string.diagnostics_locked
        DiagnosticStatus.UNSUPPORTED_VERSION -> R.string.diagnostics_unsupported_version
        DiagnosticStatus.VERSION_UNAVAILABLE -> R.string.diagnostics_version_unavailable
        DiagnosticStatus.REQUEST_CONTRACT_REJECTED -> R.string.diagnostics_request_contract_rejected
    }
}
