package io.github.beacon.app.data.repository

import android.content.Context
import io.github.beacon.app.data.local.StateCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class StateListRepository(context: Context) {
    private val app = context.applicationContext
    suspend fun load(showHidden: Boolean) = withContext(Dispatchers.IO) {
        StateCache(app).use { cache ->
            cache.all().filter { row ->
                showHidden || cache.preference("hidden:" + row.optString("local_source") + ":" + row.optString("local_identity")) != "1"
            }
        }
    }
}
