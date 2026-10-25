package io.github.beacon.app.core

import io.github.beacon.app.R
import io.github.beacon.app.domain.model.DataStatus

/** Shared labels for lists, details and rasterized widgets; status calculation stays in domain. */
val DataStatus.labelResource: Int get() = when (this) {
    DataStatus.MISSING -> R.string.data_status_missing
    DataStatus.WAITING -> R.string.data_status_waiting
    DataStatus.ACTIVE -> R.string.data_status_active
    DataStatus.STALE -> R.string.data_status_stale
    DataStatus.EXPIRED -> R.string.data_status_expired
    DataStatus.CLEARED -> R.string.data_status_cleared
}
