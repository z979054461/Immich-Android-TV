package nl.giejay.android.tv.immich.security

import android.content.Intent

/** An ordinary launcher intent can USE an existing grant, never create one. */
internal object LauncherEntryPolicy {
    fun permitsGrace(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_MAIN || intent.data != null ||
            intent.type != null || intent.selector != null || intent.clipData != null) return false
        val categories = intent.categories ?: return false
        if (categories.isEmpty() || categories.any {
                it != Intent.CATEGORY_LAUNCHER && it != Intent.CATEGORY_LEANBACK_LAUNCHER
            }) return false
        // No app-specific extras/deep links are supported by this entry point.
        return runCatching { intent.extras?.isEmpty != false }.getOrDefault(false)
    }
}
