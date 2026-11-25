package ng.ainow.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.listening.VoiceService

internal const val JARVIS_PERMISSIONS = 1

internal fun Activity.hasJarvisPermission(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

internal fun Activity.startJarvisIfAllowed(prefs: Prefs) {
    if (!prefs.userStopped && !VoiceService.running && hasJarvisPermission(Manifest.permission.RECORD_AUDIO)) {
        startForegroundService(Intent(this, VoiceService::class.java))
    }
}

internal fun Activity.askJarvisPermissions(permissions: List<String>) {
    val missing = permissions.filterNot { hasJarvisPermission(it) }
    if (missing.isEmpty()) return
    val seen = getSharedPreferences("jarvis", Activity.MODE_PRIVATE)
    val requestable = missing.filter { !seen.getBoolean("asked_$it", false) || shouldShowRequestPermissionRationale(it) }
    if (requestable.isEmpty()) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        return
    }
    seen.edit().apply { requestable.forEach { putBoolean("asked_$it", true) } }.apply()
    requestPermissions(requestable.toTypedArray(), JARVIS_PERMISSIONS)
}
