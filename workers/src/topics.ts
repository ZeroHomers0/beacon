import { reject, token, type Sender } from "./domain";
import { validTopicKey } from "./state-version";
const bytes=(value:string)=>new TextEncoder().encode(value).length;
export function topicSchema(sql:SqlStorage){
 const exists=[...sql.exec("SELECT name FROM sqlite_master WHERE type='table' AND name='topic_catalog'")].length>0;
 for(const q of [
 "CREATE TABLE IF NOT EXISTS topic_catalog(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,name TEXT NOT NULL,version INTEGER NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL,request_id TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(device,application,topic))",
 "CREATE UNIQUE INDEX IF NOT EXISTS topic_request ON topic_catalog(device,request_id) WHERE request_id<>''",
 "CREATE TABLE IF NOT EXISTS topic_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)",
 "CREATE INDEX IF NOT EXISTS topic_changes_device ON topic_changes(device,seq)",
 "CREATE TABLE IF NOT EXISTS topic_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)",
 ])sql.exec(q);
 if(!exists&&[...sql.exec("SELECT name FROM sqlite_master WHERE type='table' AND name='devices'")].length)sql.exec(`INSERT OR IGNORE INTO topic_catalog SELECT device,application,topic,'',1,0,0,0,'',json_object('device_id',device,'application_id',application,'topic_key',topic,'name','','application_name','','config_version',1,'created_at_ms',0,'updated_at_ms',0,'deleted',json('false')) FROM current_states`);
}
export class TopicStore {
 constructor(private storage:DurableObjectStorage,private server:string){}
 private rows(q:string,...args:SqlStorageValue[]){return [...this.storage.sql.exec(q,...args)]}
 check(sender:Sender,topic:string){const row=this.rows("SELECT deleted FROM topic_catalog WHERE device=? AND application=? AND topic=?",sender.device,sender.application,topic)[0];if(!row)reject(404,"topic_not_found");if(row.deleted)reject(409,"topic_deleted")}
 detail(p:Record<string,unknown>){const row=this.rows("SELECT payload FROM topic_catalog WHERE device=? AND application=? AND topic=?",String(p.device_id??""),String(p.application_id??""),String(p.topic_key??""))[0];if(!row)reject(404,"topic_not_found");return {topic:JSON.parse(String(row.payload))}}
 mutate(action:string,p:Record<string,unknown>,now:number){return this.storage.transactionSync(()=>{
 for(const key of ["device_id","application_id","topic_key","name","request_id"])if(p[key]!=null&&typeof p[key]!=="string")reject(400,"invalid_message");
 const device=String(p.device_id??""),app=String(p.application_id??""),name=String(p.name??"").trim(),request=String(p.request_id??"");
 if(!device||[...name].length>80||action==="create"&&(!name||!request||request.length>128)||action==="update"&&!name)reject(400,"invalid_message");
 // The generated identifier is stored under request_id, so retries return the same identity.
 const retry=action==="create"?this.rows("SELECT payload FROM topic_catalog WHERE device=? AND request_id=?",device,request)[0]:undefined;
 const key=String(p.topic_key|| (retry?JSON.parse(String(retry.payload)).topic_key:"topic_"+token().slice(0,24)));
 if(!validTopicKey(key))reject(400,"invalid_message");
 if(!this.rows("SELECT id FROM devices WHERE id=? AND revoked=0",device)[0])reject(400,"invalid_message");
 const application=app?this.rows("SELECT name FROM applications WHERE id=? AND device=? AND revoked=0",app,device)[0]:undefined;
 if(app&&!application)reject(400,"invalid_message");
 if(retry){const old=JSON.parse(String(retry.payload));if(old.application_id!==app||old.topic_key!==key||old.name!==name||old.deleted)reject(409,"topic_config_conflict");return {topic:old}}
 const old=this.rows("SELECT payload,request_id FROM topic_catalog WHERE device=? AND application=? AND topic=?",device,app,key)[0];let record:Record<string,unknown>;
 if(action==="create"){
 if(old)reject(409,"topic_config_conflict");if(Number(this.rows("SELECT count(*) AS n FROM topic_catalog WHERE device=?",device)[0].n)>=500)reject(507,"storage_capacity_reached");
 record={device_id:device,application_id:app,topic_key:key,name,application_name:application?.name??"",config_version:1,created_at_ms:now,updated_at_ms:now,deleted:false};
 }else{
 if(!old)reject(404,"topic_not_found");record=JSON.parse(String(old.payload));if(!Number.isSafeInteger(p.config_version)||record.config_version!==p.config_version||Number(record.config_version)>=Number.MAX_SAFE_INTEGER)reject(409,"topic_config_conflict");
 if(action==="update"){if(record.deleted)reject(409,"topic_deleted");record.name=name}else if(action==="delete")record.deleted=true;else reject(400,"invalid_message");
 record.config_version=Number(record.config_version)+1;record.updated_at_ms=now;record.application_name=application?.name??"";
 }
 const raw=JSON.stringify(record);if(action!=="delete"&&this.storage.sql.databaseSize+new TextEncoder().encode(raw).length*3+524288>256*1024*1024)reject(507,"storage_capacity_reached");
 if(action==="delete"){
 for(const table of ["state_changes"])this.rows(`DELETE FROM ${table} WHERE device=? AND application=? AND topic=?`,device,app,key);
 for(const table of ["state_snapshots","topic_snapshots"])this.rows(`DELETE FROM ${table} WHERE device=?`,device);
 this.rows("DELETE FROM topic_catalog WHERE device=? AND application=? AND topic=?",device,app,key);
 const oldState=this.rows("SELECT payload FROM current_states WHERE device=? AND application=? AND topic=?",device,app,key)[0];
 if(oldState){const previous=JSON.parse(String(oldState.payload));const state={mode:"state",schema_version:1,topic_key:key,application_id:app,revision:previous.revision,deleted:true,operation:"delete"};this.rows("INSERT INTO state_changes(device,application,topic,payload,created) VALUES(?,?,?,?,?)",device,app,key,JSON.stringify(state),now)}
 this.rows("DELETE FROM current_states WHERE device=? AND application=? AND topic=?",device,app,key);
 }else
 this.rows("INSERT INTO topic_catalog VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device,application,topic) DO UPDATE SET name=excluded.name,version=excluded.version,updated=excluded.updated,deleted=excluded.deleted,payload=excluded.payload",device,app,key,String(record.name),Number(record.config_version),Number(record.created_at_ms),now,record.deleted?1:0,String(old?.request_id??request),raw);
 this.rows("INSERT INTO topic_changes(device,application,topic,payload,created) VALUES(?,?,?,?,?)",device,app,key,raw,now);
 this.rows("DELETE FROM topic_changes WHERE device=? AND (created<? OR seq < coalesce((SELECT seq FROM topic_changes WHERE device=? ORDER BY seq DESC LIMIT 1 OFFSET 9999),0))",device,now-86400000,device);
 return {topic:record};
 });}
 list(p:Record<string,unknown>,now:number){
 for(const key of ["device_id","application_id","search","state"])if(p[key]!=null&&typeof p[key]!=="string")reject(400,"invalid_message");
 for(const key of ["limit","offset"])if(p[key]!=null&&typeof p[key]!=="number")reject(400,"invalid_message");
 const limit=p.limit===0?50:Number(p.limit??50),offset=Number(p.offset??0),search=String(p.search??"").toLowerCase(),state=String(p.state||"all");
 if(!Number.isInteger(limit)||limit<1||limit>100||!Number.isInteger(offset)||offset<0||offset>1000000||[...search].length>128||!["all","waiting","active","stale","expired","deleted"].includes(state))reject(400,"invalid_message");
 const topics=this.rows("SELECT t.payload,d.name AS device_name,coalesce(a.name,'') AS application_name,coalesce(s.payload,'') AS data FROM topic_catalog t JOIN devices d ON d.id=t.device LEFT JOIN applications a ON a.id=t.application LEFT JOIN current_states s ON s.device=t.device AND s.application=t.application AND s.topic=t.topic ORDER BY t.device,t.application,t.topic").map(row=>{
 const t=JSON.parse(String(row.payload)),s=row.data?JSON.parse(String(row.data)):{};
 const status=t.deleted?"deleted":!s.revision?"waiting":s.expires_at_ms>0&&s.expires_at_ms<=now?"expired":s.deleted?"waiting":s.stale_at_ms>0&&s.stale_at_ms<=now?"stale":"active";
 return {...t,device_name:row.device_name,source_name:t.application_id?row.application_name||t.application_id:"未关联应用",title:t.name||s.title||t.topic_key,state:status,revision:s.revision??0,status:s.status??"",observed_at_ms:s.observed_at_ms??0,accepted_at_ms:s.accepted_at_ms??0,expires_at_ms:s.expires_at_ms??0};
 }).filter(t=>(!p.device_id||t.device_id===p.device_id)&&(p.application_id==null||t.application_id===p.application_id)&&(state==="all"||t.state===state)&&(!search||`${t.title} ${t.topic_key} ${t.source_name} ${t.device_name}`.toLowerCase().includes(search)));
 const end=Math.min(offset+limit,topics.length);return {topics:topics.slice(offset,end),total:topics.length,has_more:end<topics.length,next_offset:end};
 }
 private cursor(device:string,seq:number){return btoa(`${this.server}:${device}:${seq}`).replaceAll("+","-").replaceAll("/","_").replaceAll("=","")}
 page(device:string,q:URLSearchParams,now:number){return this.storage.transactionSync(()=>{
 for(const key of q.keys())if(!["cursor","snapshot_token","page_cursor","limit"].includes(key))reject(400,"invalid_message");
 const limit=Number(q.get("limit")??100);if(!Number.isInteger(limit)||limit<1||limit>100)reject(400,"invalid_message");
 this.rows("DELETE FROM topic_snapshots WHERE expires<=?",now);
 let watermark=Number(this.rows("SELECT coalesce(max(seq),0) AS n FROM topic_changes WHERE device=?",device)[0].n);
 const cursor=q.get("cursor");let snapshot=q.get("snapshot_token");
 if(cursor){
 if(snapshot||q.has("page_cursor"))reject(400,"invalid_message");let parts:string[];try{parts=atob(cursor.replaceAll("-","+").replaceAll("_","/")).split(":")}catch{return reject(410,"state_cursor_expired")}
 const after=Number(parts[2]);const first=Number(this.rows("SELECT coalesce(min(seq),0) AS n FROM topic_changes WHERE device=?",device)[0].n);
 if(parts.length!==3||parts[0]!==this.server||parts[1]!==device+"/topics"||!Number.isSafeInteger(after)||after<0||after>watermark||after<first-1)reject(410,"state_cursor_expired");
 const rows=this.rows("SELECT seq,payload FROM topic_changes WHERE device=? AND seq>? ORDER BY seq LIMIT ?",device,after,limit+1),selected=rows.slice(0,limit);
 return {server_id:this.server,topics:selected.map(r=>JSON.parse(String(r.payload))),next_cursor:this.cursor(device+"/topics",Number(selected.at(-1)?.seq??after)),has_more:rows.length>limit};
 }
 let records:Record<string,unknown>[];
 if(!snapshot){if(q.has("page_cursor"))reject(400,"invalid_message");records=this.rows("SELECT payload FROM topic_catalog WHERE device=? AND deleted=0 ORDER BY application,topic",device).map(r=>JSON.parse(String(r.payload)));snapshot=token();if(this.storage.sql.databaseSize+bytes(JSON.stringify(records))+524288>256*1024*1024)reject(507,"storage_capacity_reached");this.rows("DELETE FROM topic_snapshots WHERE device=? AND token NOT IN (SELECT token FROM topic_snapshots WHERE device=? ORDER BY expires DESC LIMIT 1)",device,device);this.rows("INSERT INTO topic_snapshots VALUES(?,?,?,?,?)",snapshot,device,JSON.stringify(records),watermark,now+600000)}
 else{const row=this.rows("SELECT payload,watermark FROM topic_snapshots WHERE token=? AND device=? AND expires>?",snapshot,device,now)[0];if(!row)reject(410,"state_cursor_expired");records=JSON.parse(String(row.payload));watermark=Number(row.watermark)}
 const offset=Number(q.get("page_cursor")??0);if(!Number.isInteger(offset)||offset<0||offset>records.length)reject(400,"invalid_message");const end=Math.min(offset+limit,records.length);return {server_id:this.server,topics:records.slice(offset,end),snapshot_token:snapshot,has_more:end<records.length,...(end<records.length?{page_cursor:String(end)}:{next_cursor:this.cursor(device+"/topics",watermark)})};
 });}
}
