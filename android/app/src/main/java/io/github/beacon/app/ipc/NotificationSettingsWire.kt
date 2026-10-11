package io.github.beacon.app.ipc

import android.os.Parcel
import io.github.beacon.app.domain.model.NotificationSettings
import io.github.beacon.app.domain.model.OutdatedReminder

internal object NotificationSettingsWire {
    fun read(parcel: Parcel): NotificationSettings =
        NotificationSettings(
                parcel.readInt(),
                OutdatedReminder.valueOf(requireNotNull(parcel.readString())),
                parcel.readInt().also { require(it in 0..1) } == 1,
            )
            .also { it.validate() }

    fun write(parcel: Parcel, value: NotificationSettings) {
        value.validate()
        parcel.writeInt(value.freshMinutes)
        parcel.writeString(value.outdatedReminder.name)
        parcel.writeInt(if (value.lockscreenPreview) 1 else 0)
    }
}
