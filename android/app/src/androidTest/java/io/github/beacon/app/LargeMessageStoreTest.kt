package io.github.beacon.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.domain.model.MessageQuery
import io.github.beacon.app.domain.model.MessageReadFilter
import io.github.beacon.app.ipc.RuntimeMessageClient
import io.github.beacon.app.runtime.RuntimeProbeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LargeMessageStoreTest {
    @Test
    fun tenThousandMessagesKeepPagesBoundedAndSearchCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "large-fixture-${java.util.UUID.randomUUID()}"
        var binder: IBinder? = null
        val bound = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder = service
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {}
            }
        assertTrue(
            context.bindService(
                Intent(context, RuntimeProbeService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        try {
            assertTrue(bound.await(15, TimeUnit.SECONDS))
            val client = RuntimeMessageClient(requireNotNull(binder))
            RuntimeMessageStore(context).use { store ->
                try {
                    store.writableDatabase.execSQL(
                        """WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<10000)
                    INSERT INTO messages(server,id,title,body,source_created,received,is_read)
                    SELECT ?,'load-'||x,'Title '||x,'Searchable body '||x,x,x,x%2 FROM n""",
                        arrayOf(namespace),
                    )
                    val start = android.os.SystemClock.elapsedRealtime()
                    val restored = client.query(MessageQuery(serverId = namespace, anchor = io.github.beacon.app.domain.model.MessageKey(namespace, "load-5555"))).page
                    assertEquals("load-5555", restored.items.first().id)
                    assertEquals(10000L, restored.total)
                    assertEquals(5000L, restored.unread)
                    val continuation = client.query(MessageQuery(serverId = namespace, before = requireNotNull(restored.nextBefore))).page
                    assertEquals("load-5535", continuation.items.first().id)
                    var query = MessageQuery(serverId = namespace)
                    val ids = mutableSetOf<String>()
                    repeat(10) {
                        val page = client.query(query).page
                        assertEquals(10000L, page.total)
                        assertEquals(5000L, page.unread)
                        assertEquals(MessageQuery.PAGE_SIZE, page.items.size)
                        page.items.forEach { assertTrue("duplicate across pages", ids.add(it.id)) }
                        query = query.copy(before = requireNotNull(page.nextBefore))
                    }
                    val search =
                        client.query(MessageQuery(search = "body 10000", serverId = namespace)).page
                    assertEquals(listOf("load-10000"), search.items.map { it.id })
                    assertEquals(
                        5000L,
                        client
                            .query(
                                MessageQuery(
                                    serverId = namespace,
                                    readFilter = MessageReadFilter.UNREAD,
                                )
                            )
                            .page
                            .total,
                    )
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    println(
                        "BEACON_PERF real Binder, 10000 rows, 10 pages plus search/filter: ${elapsed}ms"
                    )
                    assertTrue("bounded operations exceeded 10 seconds", elapsed < 10000)
                } finally {
                    store.writableDatabase.delete("messages", "server=?", arrayOf(namespace))
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
