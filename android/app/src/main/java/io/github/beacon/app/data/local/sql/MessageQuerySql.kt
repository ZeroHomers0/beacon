package io.github.beacon.app.data.local.sql

object MessageQuerySql {
    const val FILTER =
        "delete_token='' AND (? = '' OR server = ?) AND (? = '' OR message_group = ?) AND (? = '' OR application_id = ?) AND (? = '' OR instr(lower(title),lower(?)) > 0 OR instr(lower(body),lower(?)) > 0 OR instr(lower(subtitle),lower(?)) > 0 OR instr(lower(application_id),lower(?)) > 0 OR instr(lower(application_name),lower(?)) > 0 OR instr(lower(message_group),lower(?)) > 0) AND (? = '0' OR (? = '1' AND is_read=0) OR (? = '2' AND is_read=1))"
    const val COUNTS =
        "SELECT count(*),coalesce(sum(CASE WHEN is_read=0 THEN 1 ELSE 0 END),0) FROM messages WHERE $FILTER"
    const val PAGE =
        "SELECT rowid,server,id,title,substr(body,1,180),source_created,received,is_read,subtitle,priority,EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=messages.server AND n.id=messages.id),'',application_id,message_group,expires_at,application_name FROM messages WHERE $FILTER AND rowid < ? ORDER BY rowid DESC LIMIT ?"
    const val DETAIL =
        "SELECT server,id,title,body,source_created,received,is_read,subtitle,priority,EXISTS(SELECT 1 FROM notification_intents n WHERE n.server=messages.server AND n.id=messages.id),bark,application_id,message_group,expires_at,application_name,actions FROM messages WHERE server=? AND id=? AND delete_token=''"
}
