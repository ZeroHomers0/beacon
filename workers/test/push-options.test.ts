import { env } from "cloudflare:workers";
import { reset, runInDurableObject, evictDurableObject } from "cloudflare:test";
import { afterEach, expect, it } from "vitest";
import worker, { BeaconInstance, type Env } from "../src/index";
import cases from "../../contract/push-contract.json";
import { migrate } from "../src/store/schema";
import { MessageStore } from "../src/store/messages";

const bindings = env as unknown as Env;
const admin = "test-only-admin-token-32-characters";
const stub = () => bindings.BEACON.get(bindings.BEACON.idFromName(bindings.BEACON_INSTANCE));
async function call(path:string, credential:string, payload?:unknown, key="") {
 return worker.fetch(new Request(`https://beacon.test${path}`, {
 method:payload===undefined?"GET":"POST",
 headers:{Authorization:`Bearer ${credential}`,"Content-Type":"application/json","Idempotency-Key":key},
 body:payload===undefined?undefined:JSON.stringify(payload),
 }),bindings);
}
async function bind(){const code=await (await call("/api/admin/binding",admin,{name:"phone"})).json<any>();return (await call("/api/v1/bind","",{binding_code:code.binding_code})).json<any>();}
afterEach(async()=>reset());
it("matches the shared Go push contract",async()=>{
 const device=await bind();
 for(const c of cases){const response=await call("/api/v1/push",device.push_key,c.payload,c.key);expect(response.status,c.name).toBe(c.status);if(c.status===200){const m=await response.json<any>();expect(m.group ?? "").toBe(c.group);expect(m.expires_at_ms-m.created_at_ms).toBe(c.ttl!*1000);}}
});
it("persists one outbox result across concurrent retry, cleanup and object eviction",async()=>{
 const device=await bind(),payload={body:"complete",group:"CI",ttl:60};
 const results=await Promise.all(Array.from({length:12},async()=>{const r=await call("/api/v1/push",device.push_key,payload,"build-1");expect(r.status).toBe(200);return r.json<any>();}));
 for(const m of results)expect(m).toEqual(results[0]);
 const conflict=await call("/api/v1/push",device.push_key,{body:"changed"},"build-1");expect(conflict.status).toBe(409);await conflict.text();
 await runInDurableObject(stub(),(_instance:BeaconInstance,state)=>{expect([...state.storage.sql.exec("SELECT * FROM publish_outbox")]).toHaveLength(1);state.storage.sql.exec("DELETE FROM messages");});
 await evictDurableObject(stub());
 expect(await (await call("/api/v1/push",device.push_key,payload,"build-1")).json()).toEqual(results[0]);
 const app=await (await call("/api/admin/applications",admin,{device_id:device.device_id,name:"CI",default_priority:5})).json<any>();
 const independent=await call("/api/v1/push",app.token,payload,"build-1");expect(independent.status).toBe(200);const m=await independent.json<any>();expect(m.id).not.toBe(results[0].id);expect(m.application_id).toBe(app.id);expect(m.application_name).toBe("CI");
 await call("/api/admin/revoke",admin,{device_id:device.device_id});expect((await call("/api/v1/push",device.push_key,payload,"build-1")).status).toBe(401);
});
it("does not deliver expired messages and retains scoped retry receipts",async()=>{
 const device=await bind(),other=await bind();const payload={body:"short alert",ttl:1};const first=await (await call("/api/v1/push",device.push_key,payload,"short")).json<any>();
 await runInDurableObject(stub(),(_instance:BeaconInstance,state)=>{
  state.storage.sql.exec("UPDATE messages SET expires_at=? WHERE id=?",Date.now()-1,first.id);
  const store=new MessageStore(state.storage);expect(store.due(Date.now())).toHaveLength(0);store.maintain(Date.now(),30);
  expect([...state.storage.sql.exec("SELECT * FROM messages")]).toHaveLength(0);
 });
 const page=await (await call("/api/v1/messages",device.receive_token)).json<any>();expect(page.messages).toHaveLength(0);
 expect((await call("/api/v1/messages/ack",device.receive_token,{ids:[first.id]})).status).toBe(200);
 expect((await call("/api/v1/messages/ack",other.receive_token,{ids:[first.id]})).status).toBe(400);
 expect(await (await call("/api/v1/push",device.push_key,payload,"short")).json()).toEqual(first);
});
it("upgrades schema v1 while preserving identity and pending delivery",async()=>{
 const device=await bind();const first=await (await call("/api/v1/push",device.push_key,{body:"legacy"})).json<any>();
 await runInDurableObject(stub(),(_instance:BeaconInstance,state)=>{
  const sql=state.storage.sql; for(const t of ["messages_insert","messages_delete","messages_ack"])sql.exec(`DROP TRIGGER ${t}`);sql.exec("ALTER TABLE messages DROP COLUMN actions");sql.exec("DROP INDEX messages_expiry");for(const col of ["message_group","application_name","expires_at"])sql.exec(`ALTER TABLE messages DROP COLUMN ${col}`);sql.exec("DROP TABLE push_requests");sql.exec("UPDATE metadata SET value='1' WHERE key='schema_version'");
  migrate(state.storage);migrate(state.storage);const row=[...sql.exec("SELECT * FROM messages")][0];expect(row.id).toBe(first.id);expect(row.expires_at).toBe(Number(row.created)+604800000);expect([...sql.exec("SELECT value FROM metadata WHERE key='server_id'")][0].value).toBe(device.server_id);
 });
});
