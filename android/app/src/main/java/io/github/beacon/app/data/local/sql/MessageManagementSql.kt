package io.github.beacon.app.data.local.sql

object MessageManagementSql {
    const val MARK_ALL_READ = "UPDATE messages SET is_read=1 WHERE is_read=0 AND delete_token=''"
    const val REQUEST_REMINDER =
        "INSERT INTO notification_intents(server,id) SELECT server,id FROM messages WHERE server=? AND id=? AND delete_token='' AND NOT EXISTS(SELECT 1 FROM notification_intents WHERE server=? AND id=?)"
    const val REMINDER =
        "SELECT n.seq,m.source_created,m.title,m.body,m.priority,m.application_id FROM notification_intents n JOIN messages m ON m.server=n.server AND m.id=n.id WHERE n.server=? AND n.id=? AND m.delete_token='' ORDER BY n.seq LIMIT 1"
    const val READ =
        "UPDATE messages SET is_read=? WHERE server=? AND id=? AND delete_token='' AND is_read<>?"
    const val DELETE = "DELETE FROM messages WHERE server=? AND id=?"
    const val TOMBSTONE = "INSERT OR IGNORE INTO tombstones(server,id) VALUES(?,?)"
    const val CANCEL_NOTICE = "DELETE FROM notification_intents WHERE server=? AND id=?"
}
