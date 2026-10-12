package io.github.beacon.app.data.local.sql

import io.github.beacon.app.domain.model.MessageQuery

fun MessageQuery.filterArgs() =
    arrayOf(
        serverId,
        serverId,
        groupFilter,
        groupFilter,
        applicationFilter,
        applicationFilter,
        textSearch,
        textSearch,
        textSearch,
        textSearch,
        textSearch,
        textSearch,
        textSearch,
        readFilter.wireId.toString(),
        readFilter.wireId.toString(),
        readFilter.wireId.toString(),
    )

fun MessageQuery.pageArgs() =
    filterArgs() + arrayOf(before.toString(), (MessageQuery.PAGE_SIZE + 1).toString())
