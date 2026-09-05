package com.astrocompass.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The repo this app checks for updates against -- see [AndroidAppUpdater][com.astrocompass.update.AndroidAppUpdater]. */
const val UPDATE_REPO = "przemczan/astro-compass"

private val json = Json { ignoreUnknownKeys = true }

/**
 * Decodes the JSON body of `GET /repos/{owner}/{repo}/releases` into [AppRelease]s. Pure, so it's
 * unit-testable without a network call -- the platform updater supplies the actual body.
 *
 * Drafts are dropped (never a real release); each release keeps only its first asset ending in
 * `.apk`, matching this project's own release convention of exactly one APK per release -- a
 * release with none gets a null [AppRelease.apkDownloadUrl] rather than being dropped, so it can
 * still show up (with install disabled) instead of silently vanishing from the list.
 */
fun parseReleases(responseBody: String): List<AppRelease> =
    json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        .filterNot { it.draft }
        .map { dto ->
            AppRelease(
                version = dto.tagName,
                name = dto.name.orEmpty(),
                notes = dto.body.orEmpty(),
                apkDownloadUrl = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.browserDownloadUrl,
            )
        }

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)
