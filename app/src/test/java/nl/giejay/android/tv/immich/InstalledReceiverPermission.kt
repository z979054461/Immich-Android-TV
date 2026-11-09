package nl.giejay.android.tv.immich

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/** Model the install-time grant of this app's own signature permission, not arbitrary permissions. */
internal object InstalledReceiverPermission {
    fun grant(): String {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val permission = "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val info = app.packageManager.getPermissionInfo(permission, 0)
        assertEquals("The app must own its non-exported receiver permission", app.packageName, info.packageName)
        assertEquals("The receiver permission must remain signature-only",
            PermissionInfo.PROTECTION_SIGNATURE, info.protectionLevel)
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue("The installed manifest must request its receiver permission", permission in requested)
        // Robolectric 4.16's Context.checkPermission consults a separate PID/UID grant map.
        // Real Android grants a package its own requested signature permission at installation.
        shadowOf(app).grantPermissions(permission)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(app, permission))
        return permission
    }
}
