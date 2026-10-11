package io.github.beacon.app.ipc

import android.os.Parcel
import io.github.beacon.app.domain.model.ReceivePlan

/** Same-version Binder extension; bounded primitive fields and validation on both ends. */
internal object ReceivePlanWire {
    fun writeExtras(parcel: Parcel, plan: ReceivePlan) {
        plan.validate()
        parcel.writeInt(if (plan.scheduleEnabled) 1 else 0)
        parcel.writeInt(plan.scheduleStart)
        parcel.writeInt(plan.scheduleEnd)
        parcel.writeInt(if (plan.scheduleRealtime) 1 else 0)
        parcel.writeInt(if (plan.lowBatteryEnabled) 1 else 0)
        parcel.writeInt(plan.lowBatteryPercent)
        parcel.writeInt(plan.heartbeatSeconds)
    }

    fun readExtras(parcel: Parcel, base: ReceivePlan): ReceivePlan {
        fun boolean() = parcel.readInt().also { require(it in 0..1) } == 1
        return base
            .copy(
                scheduleEnabled = boolean(),
                scheduleStart = parcel.readInt(),
                scheduleEnd = parcel.readInt(),
                scheduleRealtime = boolean(),
                lowBatteryEnabled = boolean(),
                lowBatteryPercent = parcel.readInt(),
                // Older plans end after lowBatteryPercent. Never reorder existing fields.
                heartbeatSeconds = if (parcel.dataAvail() >= 4) parcel.readInt() else 60,
            )
            .also { it.validate() }
    }
}
