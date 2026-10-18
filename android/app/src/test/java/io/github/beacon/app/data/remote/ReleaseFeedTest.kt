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
        // The tag is shown in the update dialog, so a path-shaped tag must never be offered.
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
}
