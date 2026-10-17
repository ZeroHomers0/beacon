package io.github.beacon.app.domain.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The badge must only ever appear for a build that is genuinely newer. */
class ReleaseVersionPolicyTest {
    @Test
    fun newerPatchIsAnUpdate() {
        assertTrue(ReleaseVersionPolicy.isNewer("1.0.2", "1.0.1"))
        assertTrue(ReleaseVersionPolicy.isNewer("v1.0.2", "1.0.1"))
        assertTrue(ReleaseVersionPolicy.isNewer("1.1", "1.0.9"))
        assertTrue(ReleaseVersionPolicy.isNewer("2", "1.9.9"))
    }

    @Test
    fun sameOrOlderPatchIsNotAnUpdate() {
        assertFalse(ReleaseVersionPolicy.isNewer("1.0.1", "1.0.1"))
        assertFalse(ReleaseVersionPolicy.isNewer("1.0.0", "1.0.1"))
        assertFalse(ReleaseVersionPolicy.isNewer("1.0", "1.0.1"))
        assertFalse(ReleaseVersionPolicy.isNewer("0.9.9", "1.0.1"))
    }

    @Test
    fun suffixDoesNotOutrankTheRelease() {
        assertFalse(ReleaseVersionPolicy.isNewer("1.0.1-dev", "1.0.1"))
        assertTrue(ReleaseVersionPolicy.isNewer("1.0.2+build7", "1.0.1"))
    }

    @Test
    fun unorderableVersionsAreNeverAnUpdate() {
        assertNull(ReleaseVersionPolicy.parse("nightly"))
        assertNull(ReleaseVersionPolicy.parse("1.0.1.2.3"))
        assertNull(ReleaseVersionPolicy.parse(""))
        assertFalse(ReleaseVersionPolicy.isNewer("nightly", "1.0.1"))
        assertFalse(ReleaseVersionPolicy.isNewer("1.0.2", "nightly"))
    }
}
