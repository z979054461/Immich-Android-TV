package nl.giejay.android.tv.immich.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.giejay.android.tv.immich.api.ApiClientConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var session = ApiClientConfig("https://synthetic.invalid", "synthetic-key", false, false)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private fun passed(step: DiagnosticStep) = DiagnosticResult(step, DiagnosticStatus.PASSED,
        version = if (step == DiagnosticStep.VERSION) DiagnosticVersion(3, 3, 5) else null)

    @Test fun constructionAndResumeDoNotStartChecksAndPeopleIsOptIn() = runTest {
        val calls = mutableListOf<DiagnosticStep>()
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls += step
            passed(step)
        }, { session })
        vm.validateSession()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        vm.start(false)
        advanceUntilIdle()
        assertEquals(listOf(DiagnosticStep.PING, DiagnosticStep.VERSION, DiagnosticStep.METADATA), calls)
        assertEquals(DiagnosticStatus.NOT_TESTED, vm.state.value.results.single { it.step == DiagnosticStep.PHOTO_VIEW }.status)
        assertEquals(DiagnosticStatus.NOT_TESTED, vm.state.value.results.single { it.step == DiagnosticStep.PEOPLE }.status)
        vm.start(true)
        advanceUntilIdle()
        assertEquals(DiagnosticStep.PEOPLE, calls.last())
        assertEquals(7, calls.size)
        vm.clear()
    }

    @Test fun repeatedClicksDoNotStartConcurrentRunsAndCancelImmediatelyClearsResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls++
            gate.await()
            passed(step)
        }, { session })
        vm.start(false)
        assertEquals(DiagnosticRunState.RUNNING, vm.state.value.runState)
        vm.start(true)
        runCurrent()
        assertEquals(1, calls)
        vm.cancel()
        assertEquals(DiagnosticRunState.CANCELLED, vm.state.value.runState)
        assertTrue(vm.state.value.results.isEmpty())
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(DiagnosticRunState.CANCELLED, vm.state.value.runState)
    }

    @Test fun lateNonCooperativeResultCannotRefillAfterCancelAndRetry() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            val first = calls++ == 0
            if (first) {
                try { withContext(NonCancellable) { gate.await() } } catch (_: CancellationException) { /* simulate a late callback */ }
            }
            if (first) DiagnosticResult(step, DiagnosticStatus.FORBIDDEN) else passed(step)
        }, { session })
        vm.start(false)
        runCurrent()
        vm.cancel()
        vm.start(false)
        runCurrent()
        assertEquals(DiagnosticRunState.FINISHED, vm.state.value.runState)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DiagnosticStatus.PASSED, vm.state.value.results.single { it.step == DiagnosticStep.PING }.status)
        assertEquals(4, calls)
        vm.clear()
    }

    @Test fun equalCredentialsAfterRelockStillInvalidateAllOldResults() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls++
            gate.await()
            passed(step)
        }, { session })
        vm.start(true)
        runCurrent()
        session = session.copy()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DiagnosticRunState.LOCKED, vm.state.value.runState)
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun leavingClearsCompletedResultsAndDoesNotRerunOnResume() = runTest {
        var calls = 0
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls++
            passed(step)
        }, { session })
        vm.start(false)
        advanceUntilIdle()
        assertEquals(DiagnosticRunState.FINISHED, vm.state.value.runState)
        vm.clear()
        vm.validateSession()
        advanceUntilIdle()
        assertEquals(DiagnosticRunState.IDLE, vm.state.value.runState)
        assertTrue(vm.state.value.results.isEmpty())
        assertEquals(3, calls)
    }

    @Test fun closedSessionBlocksStartAndCompletedResultsCannotCrossProfiles() = runTest {
        var calls = 0
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls++
            passed(step)
        }, { session })
        vm.start(false)
        advanceUntilIdle()
        session = session.copy(apiKey = "different-synthetic-key")
        vm.validateSession()
        assertEquals(DiagnosticRunState.LOCKED, vm.state.value.runState)
        assertTrue(vm.state.value.results.isEmpty())
        session = session.copy(hostName = "", apiKey = "")
        vm.start(false)
        advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test fun transportFailureStopsFurtherRequestsAndVersion404SkipsPermissionChecks() = runTest {
        val calls = mutableListOf<DiagnosticStep>()
        var failure = DiagnosticStatus.CERTIFICATE_FAILURE
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls += step
            if (failure == DiagnosticStatus.NOT_FOUND && step != DiagnosticStep.VERSION) passed(step)
            else DiagnosticResult(step, failure)
        }, { session })
        vm.start(true)
        advanceUntilIdle()
        assertEquals(listOf(DiagnosticStep.PING), calls)
        assertEquals(DiagnosticStatus.NOT_RUN, vm.state.value.results.single { it.step == DiagnosticStep.METADATA }.status)
        failure = DiagnosticStatus.NOT_FOUND
        calls.clear()
        vm.start(true)
        advanceUntilIdle()
        assertEquals(listOf(DiagnosticStep.PING, DiagnosticStep.VERSION), calls)
        assertEquals(DiagnosticStatus.NOT_FOUND, vm.state.value.results.single { it.step == DiagnosticStep.VERSION }.status)
        for (step in listOf(DiagnosticStep.METADATA, DiagnosticStep.PEOPLE)) {
            assertEquals(DiagnosticStatus.VERSION_UNAVAILABLE, vm.state.value.results.single { it.step == step }.status)
        }
        vm.clear()
    }

    @Test fun onlyTheInspected335ContractEnablesPermissionRequests() = runTest {
        val calls = mutableListOf<DiagnosticStep>()
        var version: DiagnosticVersion? = null
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls += step
            if (step == DiagnosticStep.VERSION) DiagnosticResult(step, DiagnosticStatus.PASSED, version = version)
            else passed(step)
        }, { session })
        val unsupported = listOf(DiagnosticVersion(2, 6, 0), DiagnosticVersion(3, 2, 0),
            DiagnosticVersion(3, 3, 4), DiagnosticVersion(3, 3, 6), DiagnosticVersion(4, 0, 0), null)
        unsupported.forEach { candidate ->
            version = candidate
            calls.clear()
            vm.start(true)
            advanceUntilIdle()
            assertEquals(listOf(DiagnosticStep.PING, DiagnosticStep.VERSION), calls)
            assertEquals(DiagnosticRunState.FINISHED, vm.state.value.runState)
            for (step in listOf(DiagnosticStep.METADATA, DiagnosticStep.PEOPLE)) {
                assertEquals(if (candidate == null) DiagnosticStatus.VERSION_UNAVAILABLE else DiagnosticStatus.UNSUPPORTED_VERSION,
                    vm.state.value.results.single { it.step == step }.status)
            }
        }
        version = DiagnosticVersion(3, 3, 5)
        calls.clear()
        vm.start(true)
        advanceUntilIdle()
        assertEquals(listOf(DiagnosticStep.PING, DiagnosticStep.VERSION, DiagnosticStep.METADATA, DiagnosticStep.PEOPLE), calls)
        assertEquals(DiagnosticStatus.PASSED, vm.state.value.results.single { it.step == DiagnosticStep.METADATA }.status)
        vm.clear()
    }

    @Test fun unknownVersionAfterPreviousSuccessCannotReuseOldContractEligibility() = runTest {
        var versionPasses = true
        val calls = mutableListOf<DiagnosticStep>()
        val vm = ConnectionDiagnosticsViewModel(DiagnosticProbe { step, _ ->
            calls += step
            if (step == DiagnosticStep.VERSION && !versionPasses)
                DiagnosticResult(step, DiagnosticStatus.UNEXPECTED_RESPONSE) else passed(step)
        }, { session })
        vm.start(true)
        advanceUntilIdle()
        assertEquals(4, calls.size)
        versionPasses = false
        calls.clear()
        vm.start(true)
        advanceUntilIdle()
        assertEquals(listOf(DiagnosticStep.PING, DiagnosticStep.VERSION), calls)
        assertEquals(DiagnosticStatus.VERSION_UNAVAILABLE, vm.state.value.results.single { it.step == DiagnosticStep.METADATA }.status)
        vm.clear()
    }
}
