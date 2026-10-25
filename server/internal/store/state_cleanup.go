package store

import (
	"database/sql"
	"encoding/json"
	"time"
)

func deleteApplicationStates(tx *sql.Tx, application string) error {
	rows, err := tx.Query("SELECT device,topic,payload FROM current_states WHERE application=? AND deleted=0", application)
	if err != nil {
		return err
	}
	type item struct{ device, topic, raw string }
	var items []item
	for rows.Next() {
		var i item
		if err = rows.Scan(&i.device, &i.topic, &i.raw); err != nil {
			rows.Close()
			return err
		}
		items = append(items, i)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, i := range items {
		var record map[string]any
		if err = json.Unmarshal([]byte(i.raw), &record); err != nil {
			return err
		}
		record["deleted"] = true
		delete(record, "blocks")
		record["body"] = ""
		record["title"] = ""
		raw, _ := json.Marshal(record)
		if err = appendStateChange(tx, i.device, application, i.topic, string(raw), time.Now().UnixMilli()); err != nil {
			return err
		}
	}
	if _, err = tx.Exec(`INSERT INTO topic_changes(device,application,topic,payload,created) SELECT device,application,topic,json_set(payload,'$.deleted',json('true'),'$.config_version',version+1),? FROM topic_catalog WHERE application=?`, time.Now().UnixMilli(), application); err != nil {
		return err
	}
	if _, err = tx.Exec("DELETE FROM topic_catalog WHERE application=?", application); err != nil {
		return err
	}
	_, err = tx.Exec("DELETE FROM current_states WHERE application=?", application)
	return err
}
