package com.astrocompass.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubReleaseFetcherTest {

    @Test
    fun decodesReleasesAndPicksTheApkAsset() {
        val body = """
            [
              {
                "tag_name": "v2.3.0-beta",
                "name": "AstroCompass v2.3.0-beta",
                "body": "Release notes here.",
                "draft": false,
                "assets": [
                  { "name": "composeApp-release.apk", "browser_download_url": "https://example.com/v2.3.0-beta/composeApp-release.apk" }
                ]
              }
            ]
        """.trimIndent()

        val releases = parseReleases(body)

        assertEquals(1, releases.size)
        val release = releases.single()
        assertEquals("v2.3.0-beta", release.version)
        assertEquals("AstroCompass v2.3.0-beta", release.name)
        assertEquals("Release notes here.", release.notes)
        assertEquals("https://example.com/v2.3.0-beta/composeApp-release.apk", release.apkDownloadUrl)
    }

    @Test
    fun dropsDraftReleases() {
        val body = """
            [
              { "tag_name": "v2.4.0-beta", "draft": true, "assets": [] },
              { "tag_name": "v2.3.0-beta", "draft": false, "assets": [] }
            ]
        """.trimIndent()

        val releases = parseReleases(body)

        assertEquals(listOf("v2.3.0-beta"), releases.map { it.version })
    }

    @Test
    fun releaseWithNoApkAssetGetsNullDownloadUrl() {
        val body = """
            [
              {
                "tag_name": "v1.0.0",
                "draft": false,
                "assets": [
                  { "name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt" }
                ]
              }
            ]
        """.trimIndent()

        val release = parseReleases(body).single()

        assertNull(release.apkDownloadUrl)
    }

    @Test
    fun missingNameAndBodyFallBackToEmptyString() {
        val body = """[ { "tag_name": "v1.0.0", "draft": false, "assets": [] } ]"""

        val release = parseReleases(body).single()

        assertEquals("", release.name)
        assertEquals("", release.notes)
    }
}
