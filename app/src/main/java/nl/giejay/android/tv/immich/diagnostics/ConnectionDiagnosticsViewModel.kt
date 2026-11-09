package nl.giejay.android.tv.immich.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.api.ServerPolicy
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager

class ConnectionDiagnosticsViewModel(
    private val probe: DiagnosticProbe = ConnectionDiagnostics(),
    private val currentSession: () -> ApiClientConfig = PreferenceManager::connectionSnapshot
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var boundSession: ApiClientConfig? = null

    /** No work is started by construction, navigation, login or lifecycle resume. */
    fun start(includePeople: Boolean) {
        if (mutableState.value.runState == DiagnosticRunState.RUNNING) return
        val session = currentSession()
        if (!ServerPolicy.isValidApiKey(session.apiKey) || runCatching { ServerPolicy.apiBase(session.hostName) }.isFailure) {
            stop(DiagnosticRunState.LOCKED)
            return
        }
        val token = ++generation
        boundSession = session
        val steps = listOf(DiagnosticStep.PING, DiagnosticStep.VERSION, DiagnosticStep.METADATA) +
            if (includePeople) listOf(DiagnosticStep.PEOPLE) else emptyList()
        val initial = DiagnosticStep.entries.map { step ->
            DiagnosticResult(step, when (step) {
                DiagnosticStep.CONFIGURATION -> DiagnosticStatus.PASSED
                DiagnosticStep.PHOTO_VIEW -> DiagnosticStatus.NOT_TESTED
                DiagnosticStep.PEOPLE -> if (includePeople) DiagnosticStatus.NOT_RUN else DiagnosticStatus.NOT_TESTED
                else -> DiagnosticStatus.NOT_RUN
            })
        }
        mutableState.value = DiagnosticsState(DiagnosticRunState.RUNNING, initial)
        job = viewModelScope.launch {
            var verifiedVersion: DiagnosticVersion? = null
            for (step in steps) {
                if (!isCurrent(token, session)) return@launch
                if (step == DiagnosticStep.METADATA || step == DiagnosticStep.PEOPLE) {
                    val version = verifiedVersion
                    if (version == null || !version.supportsPermissionDiagnostics()) {
                        replace(DiagnosticResult(step, if (version == null) DiagnosticStatus.VERSION_UNAVAILABLE
                            else DiagnosticStatus.UNSUPPORTED_VERSION))
                        continue
                    }
                }
                replace(DiagnosticResult(step, DiagnosticStatus.RUNNING))
                val result = try {
                    probe.check(step, session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DiagnosticResult(step, DiagnosticStatus.NETWORK_FAILURE)
                }
                if (!isCurrent(token, session)) return@launch
                if (result.status == DiagnosticStatus.SESSION_EXPIRED) {
                    stop(DiagnosticRunState.LOCKED)
                    return@launch
                }
                replace(result)
                if (step == DiagnosticStep.VERSION && result.status == DiagnosticStatus.PASSED) {
                    verifiedVersion = result.version
                }
                // After a failed version probe, dependent checks are explicitly skipped above.
                if (step != DiagnosticStep.VERSION && result.status in TRANSPORT_FAILURES) break
            }
            if (isCurrent(token, session)) mutableState.value = mutableState.value.copy(runState = DiagnosticRunState.FINISHED)
        }
    }

    fun cancel() = stop(DiagnosticRunState.CANCELLED)
    /** Called when leaving/backgrounding; drops even completed results and the captured credentials. */
    fun clear() = stop(DiagnosticRunState.IDLE)
    fun validateSession() {
        val current = currentSession()
        if (!ServerPolicy.isValidApiKey(current.apiKey) || runCatching { ServerPolicy.apiBase(current.hostName) }.isFailure ||
            (boundSession != null && boundSession !== current)) stop(DiagnosticRunState.LOCKED)
    }

    private fun isCurrent(token: Long, session: ApiClientConfig): Boolean {
        if (token != generation) return false
        if (session !== currentSession()) {
            stop(DiagnosticRunState.LOCKED)
            return false
        }
        return true
    }
    private fun replace(result: DiagnosticResult) {
        mutableState.value = mutableState.value.copy(results = mutableState.value.results.map { if (it.step == result.step) result else it })
    }
    private fun stop(runState: DiagnosticRunState) {
        generation++
        job?.cancel()
        job = null
        boundSession = null
        mutableState.value = DiagnosticsState(runState)
    }
    override fun onCleared() {
        clear()
        super.onCleared()
    }
    companion object {
        private val TRANSPORT_FAILURES = setOf(DiagnosticStatus.DNS_FAILURE, DiagnosticStatus.CONNECTION_FAILURE,
            DiagnosticStatus.TIMEOUT, DiagnosticStatus.CERTIFICATE_FAILURE, DiagnosticStatus.REDIRECT_BLOCKED,
            DiagnosticStatus.NETWORK_FAILURE, DiagnosticStatus.UNEXPECTED_RESPONSE, DiagnosticStatus.INVALID_CONFIGURATION)
    }
}
