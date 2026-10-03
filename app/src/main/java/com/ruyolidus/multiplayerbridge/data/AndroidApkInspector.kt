package com.ruyolidus.multiplayerbridge.data

import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

class AndroidApkInspector(private val packageManager: PackageManager) : ApkInspector {
    override fun inspect(apk: File): AppIdentity {
        val hasManifest = runCatching {
            ZipFile(apk).use { it.getEntry("AndroidManifest.xml") != null }
        }.getOrDefault(false)
        require(hasManifest) { "Choose an Android APK file. ZIP bundles, APKS and XAPK files are not supported yet." }
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.path, 0)
        }
        requireNotNull(info) { "Android could not read this APK. The file may be incomplete or unsupported." }
        require(info.splitNames.isNullOrEmpty()) { "This game needs multiple APK files. Split APK imports are not supported yet." }
        val application = requireNotNull(info.applicationInfo) { "This APK has no application information." }
        application.sourceDir = apk.path
        application.publicSourceDir = apk.path
        val label = runCatching { application.loadLabel(packageManager).toString() }
            .getOrDefault(info.packageName).ifBlank { info.packageName }
        return AppIdentity(label, info.packageName, info.versionName.orEmpty(), application.minSdkVersion)
    }
}
