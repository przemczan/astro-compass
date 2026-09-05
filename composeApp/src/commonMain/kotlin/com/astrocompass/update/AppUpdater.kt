package com.astrocompass.update

/**
 * Checks GitHub Releases for newer or alternate builds of the app, and can install one. The app is
 * sideloaded (no Play Store), so this is its only update channel -- [isSupported] is false on iOS,
 * where there is no APK to install and the concept doesn't apply at all.
 */
interface AppUpdater {
    val isSupported: Boolean

    /** The running app's own version name (e.g. "2.3.0-beta"), read from the platform's package
     *  info rather than a compiled-in constant -- it should reflect what's actually installed. */
    val currentVersion: String

    suspend fun fetchReleases(): Result<List<AppRelease>>

    /** True once the OS has granted this app permission to install APKs it downloads itself --
     *  always true below API 26, where there is no per-app grant, only a system-wide setting. */
    fun canInstallPackages(): Boolean

    /** Opens the system settings screen where [canInstallPackages] can be granted. No-op where
     *  unsupported. */
    fun openInstallPermissionSettings()

    /** Downloads [release]'s APK asset, reporting progress in `0f..1f`, then requests the system
     *  install prompt. Checks [canInstallPackages] first and fails fast with
     *  [InstallOutcome.PermissionRequired] rather than downloading ~90 MB just to be refused at
     *  the last step. */
    suspend fun downloadAndInstall(release: AppRelease, onProgress: (Float) -> Unit): InstallOutcome
}

/** One GitHub release, narrowed to what the Settings screen needs. [version] is the tag name
 *  (e.g. "v2.3.0-beta"), used both for display and for [isNewerVersion] comparisons. */
data class AppRelease(
    val version: String,
    val name: String,
    val notes: String,
    val apkDownloadUrl: String?,
)

/** Mirrors [com.astrocompass.telescope.SlewOutcome]'s shape: a fixed set of outcomes, one of which
 *  carries a mount- (here, permission-) specific reason rather than a generic error string. */
sealed interface InstallOutcome {
    data object Started : InstallOutcome
    data object PermissionRequired : InstallOutcome
    data class Failed(val reason: String) : InstallOutcome
}
