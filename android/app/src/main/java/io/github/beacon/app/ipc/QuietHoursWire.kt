package io.github.beacon.app.ipc

import android.os.Parcel
import io.github.beacon.app.domain.model.QuietHours
import io.github.beacon.app.domain.model.QuietHoursBehavior

internal object QuietHoursWire {
    fun read(parcel: Parcel): QuietHours =
        QuietHours(
                parcel.readInt().also { require(it in 0..1) } == 1,
                parcel.readInt(),
                parcel.readInt(),
                parcel.readInt(),
                QuietHoursBehavior.valueOf(requireNotNull(parcel.readString())),
            )
            .also { it.validate() }

    fun write(parcel: Parcel, value: QuietHours) {
        value.validate()
        parcel.writeInt(if (value.enabled) 1 else 0)
        parcel.writeInt(value.startMinute)
        parcel.writeInt(value.endMinute)
        parcel.writeInt(value.weekdays)
        parcel.writeString(value.behavior.name)
    }
}
