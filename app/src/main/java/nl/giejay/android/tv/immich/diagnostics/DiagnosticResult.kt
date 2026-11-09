package nl.giejay.android.tv.immich.diagnostics

/** Only these bounded values may leave the transport. Never retain a URL, exception or body. */
enum class DiagnosticStep { CONFIGURATION, PING, VERSION, METADATA, PEOPLE, PHOTO_VIEW }
enum class DiagnosticStatus {
    NOT_RUN, NOT_TESTED, RUNNING, PASSED, INVALID_CONFIGURATION, DNS_FAILURE,
    CONNECTION_FAILURE, TIMEOUT, CERTIFICATE_FAILURE, REDIRECT_BLOCKED,
    UNAUTHORIZED, FORBIDDEN, NOT_FOUND, BAD_REQUEST, SERVER_ERROR, HTTP_ERROR,
    UNEXPECTED_RESPONSE, NETWORK_FAILURE, SESSION_EXPIRED,
    UNSUPPORTED_VERSION, VERSION_UNAVAILABLE, REQUEST_CONTRACT_REJECTED
}
data class DiagnosticVersion(val major: Int, val minor: Int, val patch: Int) {
    // Deliberately conservative: only this exact server contract has been inspected.
    // This does not claim that other releases reject the request or lack permissions.
    fun supportsPermissionDiagnostics(): Boolean = major == 3 && minor == 3 && patch == 5
}
data class DiagnosticResult(
    val step: DiagnosticStep,
    val status: DiagnosticStatus,
    val elapsedMillis: Long? = null,
    val version: DiagnosticVersion? = null
)
enum class DiagnosticRunState { IDLE, RUNNING, FINISHED, CANCELLED, LOCKED }
data class DiagnosticsState(
    val runState: DiagnosticRunState = DiagnosticRunState.IDLE,
    val results: List<DiagnosticResult> = emptyList()
)
