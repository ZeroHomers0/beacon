package io.github.beacon.app

import android.os.Parcel
import io.github.beacon.app.domain.model.RealtimeHeartbeat
import io.github.beacon.app.domain.model.ReceivePlan
import io.github.beacon.app.ipc.ReceivePlanWire
import org.junit.Assert.*
import org.junit.Test

class ReceivePlanWireTest {
    @Test
    fun oldWireDefaultsTo60AndNewWirePreservesEveryChoice() {
        for (seconds in RealtimeHeartbeat.options) {
            val parcel = Parcel.obtain()
            try {
                val plan =
                    ReceivePlan(
                        heartbeatSeconds = seconds,
                        adaptiveHeartbeat = false,
                        batchOrdinaryMessages = true,
                    )
                ReceivePlanWire.writeExtras(parcel, plan)
                parcel.setDataPosition(0)
                assertEquals(plan, ReceivePlanWire.readExtras(parcel, ReceivePlan()))
                assertEquals(0, parcel.dataAvail())
                parcel.setDataSize(24)
                parcel.setDataPosition(0)
                assertEquals(60, ReceivePlanWire.readExtras(parcel, ReceivePlan()).heartbeatSeconds)
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test
    fun invalidHeartbeatIsRejectedAcrossBinder() {
        val parcel = Parcel.obtain()
        try {
            ReceivePlanWire.writeExtras(parcel, ReceivePlan())
            parcel.setDataPosition(24)
            parcel.writeInt(181)
            parcel.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) {
                ReceivePlanWire.readExtras(parcel, ReceivePlan())
            }
        } finally {
            parcel.recycle()
        }
    }
}
