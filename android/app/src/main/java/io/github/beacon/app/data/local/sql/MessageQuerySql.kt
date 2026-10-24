package io.github.beacon.app.data.local.sql

import io.github.beacon.app.domain.model.MessageQuery

/**
 * Builds the message-list predicate from the filters that are actually set.
 *
 * The previous form was one constant string with `(? = '' OR col = ?)` guards for every
 * optional filter. SQLite cannot use an index when a column sits inside an `OR` against a
 * bound parameter, so every page — including the common unsearched, unfiltered one — was a
 * full table scan plus a second full scan for the counts.
 *
 * Emitting only the active conjuncts keeps the unfiltered query on `messages_received`, and
 * keeps a single-filter query on its own index. Values are still bound, never interpolated.
 */
object MessageQuerySql {
    // SQLite's LIKE is case-insensitive for ASCII only, and the columns are read through
    // lower() so a case-insensitive search covers Latin text as well as CJK substrings.
    private const val TEXT =
        "(instr(lower(title),lower(?)) > 0 OR instr(lower(body),lower(?)) > 0 OR " +
            "instr(lower(subtitle),lower(?)) > 0 OR instr(lower(application_id),lower(?)) > 0 OR " +
            "instr(lower(application_name),lower(?)) > 0 OR instr(lower(message_group),lower(?)) > 0)"

    /** `SELECT` list shared by the page query and its cursor re-anchor. */
    const val PAGE_COLUMNS =
        "rowid,server,id,title,substr(body,1,2048),source_created,received,is_read,subtitle,priority," +
            "EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=messages.server AND n.id=messages.id)," +
            "''" +
            ",application_id,message_group,expires_at,application_name"

    const val DETAIL =
        "SELECT server,id,title,body,source_created,received,is_read,subtitle,priority," +
            "EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=messages.server AND n.id=messages.id)," +
            "bark,application_id,message_group,expires_at,application_name,actions,blocks" +
            " FROM messages WHERE server=? AND id=? AND delete_token=''"

    /** The predicate for [query] and the values it binds, in one pass. */
    fun filter(query: MessageQuery): Pair<String, Array<String>> {
        val clauses = mutableListOf("delete_token=''")
        val args = mutableListOf<String>()
        if (query.serverId.isNotEmpty()) {
            clauses += "server=?"
            args += query.serverId
        }
        // `group:` and `app:` are the same column filters as the chip row, just typed as text.
        with(query.groupFilter) {
            if (isNotEmpty()) {
                clauses += "message_group=?"
                args += this
            }
        }
        with(query.applicationFilter) {
            if (isNotEmpty()) {
                clauses += "application_id=?"
                args += this
            }
        }
        with(query.textSearch) {
            if (isNotEmpty()) {
                clauses += TEXT
                // One bound value per `?` in TEXT, all holding the same needle.
                repeat(6) { args += this }
            }
        }
        when (query.readFilter.wireId) {
            1 -> clauses += "is_read=0"
            2 -> clauses += "is_read=1"
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    fun counts(query: MessageQuery): Pair<String, Array<String>> {
        val (where, args) = filter(query)
        return "SELECT count(*),coalesce(sum(CASE WHEN is_read=0 THEN 1 ELSE 0 END),0)" +
            " FROM messages WHERE $where" to args
    }

    fun page(query: MessageQuery): Pair<String, Array<String>> {
        val (where, args) = filter(query)
        return "SELECT $PAGE_COLUMNS FROM messages WHERE $where AND rowid < ?" +
            " ORDER BY rowid DESC LIMIT ?" to
            (args + arrayOf(query.before.toString(), (MessageQuery.PAGE_SIZE + 1).toString()))
    }
}
