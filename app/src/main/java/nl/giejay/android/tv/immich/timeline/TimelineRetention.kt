package nl.giejay.android.tv.immich.timeline

/**
 * Keep a small window of month payloads, while preserving the incoming month and current focus.
 * Empty month markers are cheap and remain cached so initial empty-month scans make progress.
 * A month is an atomic server response: protected months may exceed the asset budget.
 */
internal object TimelineRetention {
    const val MAX_MONTHS = 4
    const val ASSET_BUDGET = 8_000

    fun <T> retain(
        loaded: Map<String, List<T>>,
        orderedKeys: List<String>,
        incoming: String,
        focused: String?,
        maxMonths: Int = MAX_MONTHS,
        assetBudget: Int = ASSET_BUDGET
    ): Map<String, List<T>> {
        require(maxMonths >= 2 && assetBudget > 0)
        val retained = loaded.toMutableMap()
        val positions = orderedKeys.withIndex().associate { it.value to it.index }
        val anchor = positions[incoming] ?: 0
        val removable = loaded.keys.filter { it != incoming && it != focused && loaded[it].orEmpty().isNotEmpty() }
            .sortedWith(compareByDescending<String> { kotlin.math.abs((positions[it] ?: Int.MAX_VALUE / 2) - anchor) }.thenBy { it })
        var count = retained.values.count { it.isNotEmpty() }
        var assets = retained.values.sumOf { it.size.toLong() }
        for (key in removable) {
            if (count <= maxMonths && assets <= assetBudget) break
            assets -= retained.remove(key)!!.size
            count--
        }
        return retained
    }
}
