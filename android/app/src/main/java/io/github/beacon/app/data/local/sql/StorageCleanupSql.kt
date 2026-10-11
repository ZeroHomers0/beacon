package io.github.beacon.app.data.local.sql

object StorageCleanupSql {
    const val VISIBLE = "m.delete_token=''"
    const val CANDIDATE =
        "(m.received<? OR m.rowid IN(SELECT rowid FROM messages WHERE delete_token='' ORDER BY received,rowid LIMIT ?))"
    const val SAFE =
        "(?='0' OR m.is_read=1) AND NOT EXISTS(SELECT 1 FROM pending_ack p WHERE p.server=m.server AND p.id=m.id) AND NOT EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=m.server AND n.id=m.id)"
    const val COUNTS =
        "SELECT count(*),coalesce(sum(is_read=0),0),coalesce(sum(EXISTS(SELECT 1 FROM pending_ack p WHERE p.server=m.server AND p.id=m.id)),0) FROM messages m WHERE $VISIBLE"
    const val PENDING_NOTIFICATIONS =
        "SELECT count(*) FROM messages m WHERE $VISIBLE AND EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=m.server AND n.id=m.id)"
    const val PREVIEW =
        "SELECT count(*),coalesce(sum(CASE WHEN $SAFE THEN 1 ELSE 0 END),0) FROM messages m WHERE $VISIBLE AND $CANDIDATE"
    const val KEYS =
        "SELECT m.server,m.id FROM messages m WHERE $VISIBLE AND $CANDIDATE AND $SAFE ORDER BY m.received,m.rowid LIMIT 100"
    const val STILL_SAFE =
        "SELECT 1 FROM messages m WHERE m.server=? AND m.id=? AND $VISIBLE AND $SAFE"
}
