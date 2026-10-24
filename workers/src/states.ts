import { reject, token, type Sender } from "./domain";
import { validateBlocks } from "./blocks";
import { checkStateVersion, validStateStatus, validTopicKey, type StateVersion } from "./state-version";

export interface StateRecord extends Record<string, unknown> {
  topic_key: string; revision: number; application_id: string;
  deleted: boolean; accepted_at_ms: number; expires_at_ms: number;
}
const bytes=(s:string)=>new TextEncoder().encode(s).length;
export function validateState(p:Record<string,unknown>,now:number):void{
 if(p.mode!=="state"||p.schema_version!==1||typeof p.topic_key!=="string"||!validTopicKey(p.topic_key)||!Number.isSafeInteger(p.revision)||Number(p.revision)<1)reject(400,"invalid_message");
 if(p.operation==="delete"){
  if(Object.keys(p).some(k=>!["mode","schema_version","operation","topic_key","revision"].includes(k)))reject(400,"invalid_message");return;
 }
 if(p.operation!==undefined&&p.operation!=="upsert")reject(400,"invalid_message");
 if(p.state_type!==undefined&&p.state_type!=="persistent"&&p.state_type!=="task")reject(400,"invalid_message");
 if(!validStateStatus(p.state_type==="task",String(p.status??"")))reject(400,"invalid_message");
 const observed=p.observed_at_ms??0,fresh=p.fresh_for_seconds??0,ttl=p.ttl||604800;
 if(!Number.isSafeInteger(observed)||Number(observed)<0||Number(observed)>now+300000||!Number.isInteger(fresh)||Number(fresh)<0||Number(fresh)>Number(ttl))reject(400,"invalid_message");
 if(typeof p.body!=="string"||!p.body||bytes(p.body)>65536)reject(400,"invalid_message");
 for(const k of ["title","subtitle"])if(p[k]!==undefined&&(typeof p[k]!=="string"||bytes(p[k] as string)>1024))reject(400,"invalid_message");
 validateBlocks(p.schema_version,p.blocks);
}
export function stateSchema(sql:SqlStorage):void{
 for(const q of [
 "CREATE TABLE IF NOT EXISTS current_states(device TEXT NOT NULL REFERENCES devices(id),application TEXT NOT NULL,topic TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,deleted INTEGER NOT NULL,task INTEGER NOT NULL,status TEXT NOT NULL,payload TEXT NOT NULL,expires INTEGER NOT NULL,PRIMARY KEY(device,application,topic))",
 "CREATE TABLE IF NOT EXISTS state_changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,device TEXT NOT NULL,application TEXT NOT NULL,topic TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)",
 "CREATE INDEX IF NOT EXISTS state_changes_device ON state_changes(device,seq)",
 "CREATE TABLE IF NOT EXISTS state_snapshots(token TEXT PRIMARY KEY,device TEXT NOT NULL,payload TEXT NOT NULL,watermark INTEGER NOT NULL,expires INTEGER NOT NULL)",
 "CREATE TABLE IF NOT EXISTS state_rate(key TEXT PRIMARY KEY,minute INTEGER NOT NULL,n INTEGER NOT NULL)",
 ])sql.exec(q);
}
export class StateStore {
 constructor(private storage:DurableObjectStorage,private server:string){}
 private rows(q:string,...args:SqlStorageValue[]){return [...this.storage.sql.exec(q,...args)]}
 adminList(p:Record<string,unknown>,now:number){
 const limit=p.limit===0?50:p.limit??50,offset=p.offset??0,device=p.device_id??"",search=p.search??"",state=p.state||"all",app=p.application_id;
 if(!Number.isInteger(limit)||Number(limit)<1||Number(limit)>100||!Number.isInteger(offset)||Number(offset)<0||Number(offset)>1000000||typeof device!=="string"||typeof search!=="string"||[...search].length>128||app!==undefined&&app!==null&&typeof app!=="string"||!["all","active","stale","expired","deleted"].includes(String(state)))reject(400,"invalid_message");
 const classify="CASE WHEN s.expires>0 AND s.expires<=? THEN 'expired' WHEN s.deleted=1 THEN 'deleted' WHEN coalesce(json_extract(s.payload,'$.stale_at_ms'),0)>0 AND json_extract(s.payload,'$.stale_at_ms')<=? THEN 'stale' ELSE 'active' END";
 const where=" FROM current_states s JOIN devices d ON d.id=s.device LEFT JOIN applications a ON a.id=s.application WHERE (?='' OR s.device=?) AND (?=1 OR s.application=?) AND (?='' OR instr(lower(s.topic||' '||coalesce(json_extract(s.payload,'$.title'),'')||' '||coalesce(a.name,'')||' '||d.name),lower(?))>0) AND (?='all' OR ("+classify+")=?)";
 const args:SqlStorageValue[]=[device,device,app==null?1:0,String(app??""),search,search,String(state),now,now,String(state)];
 const total=Number(this.rows("SELECT count(*) AS n"+where,...args)[0].n);
 const topics=this.rows("SELECT s.device,d.name AS device_name,s.application,coalesce(a.name,'') AS application_name,s.payload,("+classify+") AS state"+where+" ORDER BY s.device,s.application,s.topic LIMIT ? OFFSET ?",now,now,...args,Number(limit),Number(offset)).map(row=>{
 const record=JSON.parse(String(row.payload));return {device_id:row.device,device_name:row.device_name,application_id:row.application,source_name:row.application?row.application_name||row.application:"默认来源",topic_key:record.topic_key,title:record.title??"",revision:record.revision,state:row.state,status:record.status??"",observed_at_ms:record.observed_at_ms??0,accepted_at_ms:record.accepted_at_ms,expires_at_ms:record.expires_at_ms};
 });return {topics,total,has_more:Number(offset)+topics.length<total,next_offset:Number(offset)+topics.length};
 }
 adminDetail(p:Record<string,unknown>){
 if(typeof p.device_id!=="string"||typeof p.topic_key!=="string"||p.application_id!==undefined&&p.application_id!==null&&typeof p.application_id!=="string")reject(400,"invalid_message");
 const row=this.rows("SELECT s.payload,d.name FROM current_states s JOIN devices d ON d.id=s.device WHERE s.device=? AND s.application=? AND s.topic=?",p.device_id,String(p.application_id??""),p.topic_key)[0];
 if(!row)reject(400,"invalid_message");return {device_id:p.device_id,device_name:row.name,state:JSON.parse(String(row.payload))};
 }
 adminClear(p:Record<string,unknown>,now:number){return this.storage.transactionSync(()=>{
 if(!Number.isSafeInteger(p.revision)||Number(p.revision)<1)reject(400,"invalid_message");
 const record=this.adminDetail(p).state;
 if(record.revision!==p.revision)reject(409,"stale_state_revision");
 if(!record.deleted){
 const changed={...record,deleted:true,operation:"delete",title:"",subtitle:"",body:"",blocks:[],actions:[],expires_at_ms:0,stale_at_ms:0};
 const encoded=JSON.stringify(changed);
 this.rows("UPDATE current_states SET deleted=1,payload=?,expires=0 WHERE device=? AND application=? AND topic=?",encoded,String(p.device_id),String(p.application_id??""),String(p.topic_key));
 this.change(String(p.device_id),String(p.application_id??""),String(p.topic_key),encoded,now);
 }return {cleared:true,revision:record.revision};
 });}
 private change(device:string,app:string,topic:string,payload:string,now:number){
 this.rows("INSERT INTO state_changes(device,application,topic,payload,created) VALUES(?,?,?,?,?)",device,app,topic,payload,now);
 this.rows("DELETE FROM state_changes WHERE device=? AND (created<? OR seq < coalesce((SELECT seq FROM state_changes WHERE device=? ORDER BY seq DESC LIMIT 1 OFFSET 9999),0))",device,now-86400000,device);
 }
 save(sender:Sender,p:Record<string,unknown>,digest:string,now:number):StateRecord{
 validateState(p,now);
 return this.storage.transactionSync(()=>{
 const old=this.rows("SELECT * FROM current_states WHERE device=? AND application=? AND topic=?",sender.device,sender.application,String(p.topic_key))[0];
 const prev:StateVersion|null=old?{revision:Number(old.revision),digest:String(old.digest),deleted:!!old.deleted,task:!!old.task,status:String(old.status)}:null;
 const next:StateVersion={revision:Number(p.revision),digest,deleted:p.operation==="delete",task:p.state_type==="task",status:String(p.status??"")};
 if(prev&&next.deleted){next.task=prev.task;next.status=prev.status}
 if(prev&&prev.revision===next.revision&&prev.digest===next.digest&&prev.deleted&&!next.deleted)return {...JSON.parse(String(old.payload)),unchanged:true};
 const checked=checkStateVersion(prev,next);if(checked==="unchanged")return {...JSON.parse(String(old.payload)),unchanged:true};if(checked!=="changed")reject(checked==="invalid_message"?400:409,checked);
 for(const [key,max] of [[`device:${sender.device}`,300],[`application:${sender.device}:${sender.application}`,120]] as const){
 this.rows("INSERT INTO state_rate VALUES(?,?,1) ON CONFLICT(key) DO UPDATE SET n=CASE WHEN minute=excluded.minute THEN n+1 ELSE 1 END,minute=excluded.minute",key,Math.floor(now/60000));if(Number(this.rows("SELECT n FROM state_rate WHERE key=?",key)[0].n)>max)reject(429,"push_rate_limited");
 }
 const record:StateRecord={...p,application_id:sender.application,application_name:sender.name??"",topic_key:String(p.topic_key),revision:Number(p.revision),accepted_at_ms:now,expires_at_ms:next.deleted?0:now+Number(p.ttl||604800)*1000,stale_at_ms:p.fresh_for_seconds?Number(p.observed_at_ms??0)+Number(p.fresh_for_seconds)*1000:0,deleted:next.deleted,unchanged:false};
 const encoded=JSON.stringify(record);if(this.storage.sql.databaseSize+bytes(encoded)*3+524288>256*1024*1024)reject(507,"storage_capacity_reached");const usage=this.rows("SELECT count(*) AS n,coalesce(sum(length(CAST(payload AS BLOB))*(deleted=0)),0) AS bytes FROM current_states WHERE device=?",sender.device)[0];
 if(!old&&Number(usage.n)>=500||Number(usage.bytes)-(old&&!old.deleted?bytes(String(old.payload)):0)+bytes(encoded)>8*1024*1024)reject(507,"storage_capacity_reached");
 this.rows("INSERT INTO current_states VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(device,application,topic) DO UPDATE SET revision=excluded.revision,digest=excluded.digest,deleted=excluded.deleted,task=excluded.task,status=excluded.status,payload=excluded.payload,expires=excluded.expires",sender.device,sender.application,record.topic_key,next.revision,digest,Number(next.deleted),Number(next.task),next.status,encoded,record.expires_at_ms);
 this.change(sender.device,sender.application,record.topic_key,encoded,now);return record;
 });
 }
 meta(sender:Sender,topic:string){if(!validTopicKey(topic))reject(400,"invalid_message");const row=this.rows("SELECT revision,deleted,status FROM current_states WHERE device=? AND application=? AND topic=?",sender.device,sender.application,topic)[0];return row?{revision:Number(row.revision),deleted:!!row.deleted,status:String(row.status)}:{revision:0,deleted:false,status:""}}
 private cursor(device:string,seq:number){return btoa(`${this.server}:${device}:${seq}`).replaceAll("+","-").replaceAll("/","_").replaceAll("=","")}
 page(device:string,q:URLSearchParams,now:number){return this.storage.transactionSync(()=>{
 for(const key of q.keys())if(!["cursor","snapshot_token","page_cursor","limit"].includes(key))reject(400,"invalid_message");
 const limit=Number(q.get("limit")??100);if(!Number.isInteger(limit)||limit<1||limit>100)reject(400,"invalid_message");
 for(const row of this.rows("SELECT * FROM current_states WHERE device=? AND deleted=0 AND expires<=?",device,now)){
 const record={...JSON.parse(String(row.payload)),deleted:true,operation:"delete",blocks:[],body:"",title:""};const raw=JSON.stringify(record);this.rows("UPDATE current_states SET deleted=1,payload=? WHERE device=? AND application=? AND topic=?",raw,device,String(row.application),String(row.topic));this.change(device,String(row.application),String(row.topic),raw,now);
 }
 this.rows("DELETE FROM state_snapshots WHERE expires<=?",now);
 let watermark=Number(this.rows("SELECT coalesce(max(seq),0) AS n FROM state_changes WHERE device=?",device)[0].n);
 const cursor=q.get("cursor");let snapshot=q.get("snapshot_token");
 if(cursor){
 if(snapshot||q.has("page_cursor"))reject(400,"invalid_message");let parts:string[];try{parts=atob(cursor.replaceAll("-","+").replaceAll("_","/")).split(":")}catch{return reject(410,"state_cursor_expired")}
 const after=Number(parts[2]);const first=Number(this.rows("SELECT coalesce(min(seq),0) AS n FROM state_changes WHERE device=?",device)[0].n);
 if(parts.length!==3||parts[0]!==this.server||parts[1]!==device||!Number.isSafeInteger(after)||after<0||after>watermark||after<first-1)reject(410,"state_cursor_expired");
 const rows=this.rows("SELECT seq,payload FROM state_changes WHERE device=? AND seq>? ORDER BY seq LIMIT ?",device,after,limit+1),selected=rows.slice(0,limit);
 return {server_id:this.server,states:selected.map(r=>JSON.parse(String(r.payload))),next_cursor:this.cursor(device,Number(selected.at(-1)?.seq??after)),has_more:rows.length>limit};
 }
 let records:StateRecord[];
 if(!snapshot){if(q.has("page_cursor"))reject(400,"invalid_message");records=this.rows("SELECT payload FROM current_states WHERE device=? AND deleted=0 ORDER BY application,topic",device).map(r=>JSON.parse(String(r.payload)));snapshot=token();if(this.storage.sql.databaseSize+bytes(JSON.stringify(records))+524288>256*1024*1024)reject(507,"storage_capacity_reached");this.rows("DELETE FROM state_snapshots WHERE device=? AND token NOT IN (SELECT token FROM state_snapshots WHERE device=? ORDER BY expires DESC LIMIT 1)",device,device);this.rows("INSERT INTO state_snapshots VALUES(?,?,?,?,?)",snapshot,device,JSON.stringify(records),watermark,now+600000)}
 else{const row=this.rows("SELECT payload,watermark FROM state_snapshots WHERE token=? AND device=? AND expires>?",snapshot,device,now)[0];if(!row)reject(410,"state_cursor_expired");records=JSON.parse(String(row.payload));watermark=Number(row.watermark)}
 const offset=Number(q.get("page_cursor")??0);if(!Number.isInteger(offset)||offset<0||offset>records.length)reject(400,"invalid_message");const end=Math.min(offset+limit,records.length);return {server_id:this.server,states:records.slice(offset,end),snapshot_token:snapshot,has_more:end<records.length,...(end<records.length?{page_cursor:String(end)}:{next_cursor:this.cursor(device,watermark)})};
 });}
}
