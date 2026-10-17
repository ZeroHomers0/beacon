package io.github.beacon.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class AsyncPageStateTest {
    @Test
    fun initialRequestDistinguishesIdleLoadingAndFailure() {
        assertEquals(AsyncPageState.IDLE, AsyncPageState.resolve(false, false, false))
        assertEquals(AsyncPageState.INITIAL_LOADING, AsyncPageState.resolve(false, true, false))
        assertEquals(AsyncPageState.INITIAL_ERROR, AsyncPageState.resolve(false, false, true))
    }

    @Test
    fun emptyRequiresACompletedSuccessfulLoad() {
        assertEquals(AsyncPageState.IDLE, AsyncPageState.resolve(false, false, false, true))
        assertEquals(AsyncPageState.EMPTY, AsyncPageState.resolve(true, false, false, true))
        assertEquals(AsyncPageState.CONTENT, AsyncPageState.resolve(true, false, false))
    }

    @Test
    fun refreshAndRefreshFailureRetainExistingContent() {
        assertEquals(AsyncPageState.REFRESHING, AsyncPageState.resolve(true, true, false))
        assertEquals(AsyncPageState.REFRESH_ERROR, AsyncPageState.resolve(true, false, true))
        assertTrue(AsyncPageState.REFRESHING.hasContent)
        assertTrue(AsyncPageState.REFRESH_ERROR.hasContent)
        assertTrue(AsyncPageState.EMPTY.hasContent)
        assertFalse(AsyncPageState.INITIAL_ERROR.hasContent)
    }

    @Test
    fun retryTakesPrecedenceOverPreviousFailure() {
        assertEquals(AsyncPageState.INITIAL_LOADING, AsyncPageState.resolve(false, true, true))
        assertEquals(AsyncPageState.REFRESHING, AsyncPageState.resolve(true, true, true, true))
    }
}
