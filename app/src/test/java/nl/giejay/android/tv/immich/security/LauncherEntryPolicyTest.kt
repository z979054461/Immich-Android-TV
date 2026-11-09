package nl.giejay.android.tv.immich.security

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class LauncherEntryPolicyTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    @Test fun `only plain phone or TV launcher entry can use a preexisting grant`() {
        assertTrue(LauncherEntryPolicy.permitsGrace(launcher()))
        assertTrue(LauncherEntryPolicy.permitsGrace(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(LauncherEntryPolicy.permitsGrace(null))
        assertFalse(LauncherEntryPolicy.permitsGrace(Intent()))
        assertFalse(LauncherEntryPolicy.permitsGrace(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `deep links payloads selectors and unexpected categories require PIN`() {
        val unexpected = listOf(
            launcher().setAction(Intent.ACTION_VIEW),
            launcher().setAction("synthetic.unexpected"),
            launcher().setData(Uri.parse("https://synthetic.invalid/private")),
            launcher().setType("image/png"),
            launcher().addCategory(Intent.CATEGORY_BROWSABLE),
            launcher().putExtra("profile", "other"),
            launcher().apply { selector = Intent(Intent.ACTION_VIEW) },
            launcher().apply { clipData = ClipData.newPlainText("synthetic", "payload") }
        )
        unexpected.forEach { assertFalse(LauncherEntryPolicy.permitsGrace(it)) }
    }
}
