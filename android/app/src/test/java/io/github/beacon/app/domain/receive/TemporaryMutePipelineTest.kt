package io.github.beacon.app.domain.receive

import io.github.beacon.app.domain.model.MessageKey
import org.junit.Assert.*
import org.junit.Test

class TemporaryMutePipelineTest {
    @Test fun suppressedRemindersAreConsumedBeforePermissionChecksAndNeverReplayed() {
        val pending=mutableListOf(NotificationIntent(1,1,key=MessageKey("source","a"),priority=10,applicationId="app"))
        var permissionCalls=0
        val store=object:NotificationIntentStore {
            override fun pendingNotifications()=pending.toList()
            override fun notificationsPosted(intents:List<NotificationIntent>){pending.removeAll(intents.toSet())}
        }
        NotificationPipeline(store,{permissionCalls++;false},io.github.beacon.app.domain.model.QuietHours(),
            muted={item,_->item.applicationId=="app"}).flush(100)
        assertTrue(pending.isEmpty());assertEquals(0,permissionCalls)
        NotificationPipeline(store,{permissionCalls++;true}).flush(200)
        assertEquals(0,permissionCalls)
    }
}
