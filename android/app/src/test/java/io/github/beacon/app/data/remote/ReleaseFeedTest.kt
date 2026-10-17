package io.github.beacon.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Payloads captured from the release feed: the sandbox cannot reach api.github.com, so the decoding
 * of a real answer — and of hostile ones — is pinned here instead.
 */
class ReleaseFeedTest {
    private fun payload(
        tag: String = "v1.0.2",
        asset: String = "beacon-release.apk",
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "body": "修复若干问题",
          "assets": [
            {"name": "SHA256SUMS-android", "size": 64, "browser_download_url": "https://example.com/sums"},
            {"name": "$asset", "size": 2570240, "browser_download_url": "https://example.com/$asset"}
          ]
        }
        """.trimIndent()

    @Test
    fun signedAssetIsPreferred() {
        val release = ReleaseFeed.parse(payload())
        assertEquals("1.0.2", release?.version)
        assertEquals("https://example.com/beacon-release.apk", release?.downloadUrl)
        assertEquals(2570240L, release?.sizeBytes)
        assertEquals("修复若干问题", release?.notes)
    }

    @Test
    fun unsignedAssetIsFallbackWhenSignedOneIsAbsent() {
        val release = ReleaseFeed.parse(payload(asset = "beacon-release-unsigned.apk"))
        assertEquals("https://example.com/beacon-release-unsigned.apk", release?.downloadUrl)
    }

    @Test
    fun draftAndPrereleaseAreIgnored() {
        assertNull(ReleaseFeed.parse(payload(draft = true)))
        assertNull(ReleaseFeed.parse(payload(prerelease = true)))
    }

    @Test
    fun releaseWithoutAnApkAssetIsIgnored() {
        assertNull(
            ReleaseFeed.parse(
                """{"tag_name":"v1.0.2","assets":[{"name":"SHA256SUMS-android","size":64}]}"""
            )
        )
        assertNull(ReleaseFeed.parse("""{"tag_name":"v1.0.2"}"""))
    }

    @Test
    fun unusableTagIsRejected() {
        // The tag becomes a staged file name, so a path-shaped tag must never reach the downloader.
        assertNull(ReleaseFeed.parse(payload(tag = "../../evil")))
        assertNull(ReleaseFeed.parse(payload(tag = "")))
        assertNull(ReleaseFeed.parse(payload(tag = "x".repeat(80))))
    }

    @Test
    fun malformedBodyIsIgnored() {
        assertNull(ReleaseFeed.parse("not json"))
        assertNull(ReleaseFeed.parse(""))
    }

    @Test
    fun notesAreBounded() {
        val long = "n".repeat(9000)
        val release = ReleaseFeed.parse(payload().replace("修复若干问题", long))
        assertEquals(4000, release?.notes?.length)
    }

    @Test
    fun publishedDigestIsAttachedToTheChosenAsset() {
        val digest = "a".repeat(64)
        val sums = "$digest  beacon-release.apk\n${"b".repeat(64)}  other.apk\n"
        val release = ReleaseFeed.parse(payload(), sums)
        assertEquals(digest, release?.sha256)
    }

    @Test
    fun digestSurvivesAPathAndBinaryModeMarker() {
        // sha256sum writes the path it was given, and "*" for binary mode.
        val digest = "c".repeat(64)
        val release =
            ReleaseFeed.parse(payload(), "$digest *dist/release/beacon-release.apk\n")
        assertEquals(digest, release?.sha256)
    }

    @Test
    fun absentOrUnusableSumsLeaveTheDigestEmpty() {
        assertEquals("", ReleaseFeed.parse(payload(), null)?.sha256)
        assertEquals("", ReleaseFeed.parse(payload(), "")?.sha256)
        // A digest for a different asset must not be attributed to the APK.
        assertEquals("", ReleaseFeed.parse(payload(), "${"d".repeat(64)}  other.apk\n")?.sha256)
        // Malformed digests are skipped rather than trusted.
        assertEquals("", ReleaseFeed.parse(payload(), "notahash  beacon-release.apk\n")?.sha256)
        assertEquals("", ReleaseFeed.parse(payload(), "${"e".repeat(63)}  beacon-release.apk\n")?.sha256)
    }

    @Test
    fun checksumsAssetIsAdvertisedSeparately() {
        assertEquals("https://example.com/sums", ReleaseFeed.checksumsUrl(payload()))
        assertNull(ReleaseFeed.checksumsUrl("""{"tag_name":"v1.0.2","assets":[]}"""))
    }
}
