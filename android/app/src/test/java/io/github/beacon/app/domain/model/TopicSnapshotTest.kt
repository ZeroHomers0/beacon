package io.github.beacon.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class TopicSnapshotTest {
    private val identity = TopicIdentity("server/device", "", "quota")
    @Test fun freshnessAndExpiryUseTheSameBoundary() {
        val snapshot = TopicSnapshot(identity, staleAtMs=100, expiresAtMs=200)
        assertEquals(DataStatus.ACTIVE,snapshot.status(99))
        assertEquals(DataStatus.STALE,snapshot.status(100))
        assertEquals(DataStatus.EXPIRED,snapshot.status(200))
    }
    @Test fun catalogAndClearStatesAreNotInventedActiveValues() {
        assertEquals(DataStatus.WAITING,TopicSnapshot(identity,waiting=true).status(500))
        assertEquals(DataStatus.CLEARED,TopicSnapshot(identity,cleared=true,expiresAtMs=100).status(500))
        assertEquals(DataStatus.MISSING,TopicSnapshot(identity,directoryDeleted=true,waiting=true).status(500))
    }
    @Test fun identityDoesNotDependOnDisplayNameAndRetainsApplicationScope() {
        assertEquals(":quota",identity.localIdentity)
        assertNotEquals(identity,identity.copy(application="another"))
        assertNotEquals(identity,identity.copy(source="another/device"))
        assertEquals(DataStatus.ACTIVE,TopicSnapshot(identity,staleAtMs=-1).status(1))
    }
}
