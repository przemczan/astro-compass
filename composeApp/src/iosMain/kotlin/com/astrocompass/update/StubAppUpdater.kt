package com.astrocompass.update

/** iOS has no sideloaded APK to install -- see [AppUpdater.isSupported]'s doc. Every member is
 *  unreachable in practice since the Settings screen never shows the Updates section here. */
class StubAppUpdater : AppUpdater {
    override val isSupported = false
    override val currentVersion = ""
    override suspend fun fetchReleases(): Result<List<AppRelease>> =
        Result.failure(UnsupportedOperationException("Updates are not supported on this platform"))
    override fun canInstallPackages() = false
    override fun openInstallPermissionSettings() = Unit
    override suspend fun downloadAndInstall(release: AppRelease, onProgress: (Float) -> Unit): InstallOutcome =
        InstallOutcome.Failed("Updates are not supported on this platform")
}
