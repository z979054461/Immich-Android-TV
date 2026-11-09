package nl.giejay.android.tv.immich.security

/**
 * A single, memory-only, once-consumable background grant. This never authenticates a transport.
 * Supply a monotonic elapsed-realtime clock and a cancellable, asynchronous one-shot scheduler.
 * The scheduler callback captures only a token, never the pending credentials.
 */
class SessionGraceController<T>(
    private val clock: () -> Long,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Cancellation,
) {
    fun interface Cancellation { fun cancel() }

    // Deliberately not a data class: credentials must not appear in generated toString/copy APIs.
    private class Pending<T>(
        val profileId: String,
        val value: T,
        val startedAt: Long,
        val durationMillis: Long,
        val token: Any = Any(),
        var lastObservedAt: Long = startedAt,
    )

    private var pending: Pending<T>? = null
    private var expiry: Cancellation? = null

    /** Repeated background notifications cannot refresh or replace an existing grant. */
    @Synchronized
    fun arm(profileId: String, value: T, durationMillis: Long = DEFAULT_DURATION_MILLIS) {
        if (profileId.isBlank() || durationMillis !in ALLOWED_DURATIONS || durationMillis == 0L) {
            clear()
            return
        }
        val previous = pending
        if (previous != null) {
            if (profileId != previous.profileId) clear()
            else validPending() // Expired grants are removed, never refreshed by another notification.
            return
        }
        val now = clock()
        if (now < 0L) return
        val grant = Pending(profileId, value, now, durationMillis)
        pending = grant
        scheduleExpiry(grant.token, durationMillis)
    }

    @Synchronized
    fun peekProfileId(): String? = validPending()?.profileId

    @Synchronized
    fun hasPending(): Boolean = validPending() != null

    /** A wrong profile fails closed and destroys the grant, rather than leaving it available. */
    @Synchronized
    fun consume(profileId: String): T? {
        val grant = validPending() ?: return null
        clear()
        return if (grant.profileId == profileId) grant.value else null
    }

    /** Hard lock: drop all pending configuration and cancel its cleanup timer. */
    @Synchronized
    fun clear() {
        pending = null
        val oldExpiry = expiry
        expiry = null
        oldExpiry?.cancel()
    }

    private fun validPending(): Pending<T>? {
        val grant = pending ?: return null
        val now = clock()
        if (now < grant.lastObservedAt || now - grant.startedAt >= grant.durationMillis) {
            clear()
            return null
        }
        grant.lastObservedAt = now
        return grant
    }

    private fun scheduleExpiry(token: Any, delayMillis: Long) {
        try {
            expiry = schedule(delayMillis) { expire(token) }
        } catch (error: RuntimeException) {
            clear()
            throw error
        }
    }

    @Synchronized
    private fun expire(token: Any) {
        // A canceled callback may already be queued. It must not touch a newer grant.
        if (pending?.token !== token) return
        val grant = validPending() ?: return
        // An early callback must not shorten the interval; a late callback already cleared it.
        expiry?.cancel()
        expiry = null
        scheduleExpiry(token, grant.durationMillis - (grant.lastObservedAt - grant.startedAt))
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 5 * 60_000L
        private val ALLOWED_DURATIONS = setOf(0L, 60_000L, DEFAULT_DURATION_MILLIS, 15 * 60_000L)

        /** Invalid stored preferences fail closed; the caller defaults missing preferences to five minutes. */
        fun durationMillis(minutes: Int): Long = when (minutes) {
            0, 1, 5, 15 -> minutes * 60_000L
            else -> 0L
        }
    }
}
