import { env } from "cloudflare:workers";
import { reset, runInDurableObject, evictDurableObject } from "cloudflare:test";
import { afterEach, expect, it } from "vitest";
import worker, { BeaconInstance, type Env } from "../src/index";
import { validateMessageActions as actions, message } from "../src/domain";
import { MessageStore } from "../src/store/messages";
import { migrate } from "../src/store/schema";
const bindings = env as unknown as Env;
const stub = () => bindings.BEACON.get(bindings.BEACON.idFromName(bindings.BEACON_INSTANCE));
async function call(path:string, credential:string, payload?:unknown, key="") {
 return worker.fetch(new Request(`https://beacon.test${path}`, {method:payload===undefined?"GET":"POST",
 headers:{Authorization:`Bearer ${credential}`,"Content-Type":"application/json","Idempotency-Key":key},
 body:payload===undefined?undefined:JSON.stringify(payload)}),bindings);
}
async function bind() {
 const code=await (await call("/api/admin/binding","test-only-admin-token-32-characters",{name:"phone"})).json<any>();
 return (await call("/api/v1/bind","",{binding_code:code.binding_code})).json<any>();
}
afterEach(async()=>reset());
it("validates strict action limits and HTTPS destinations",()=>{
 expect(actions(undefined)).toEqual([]); expect(actions([])).toEqual([]);
 for(const value of [null,{},[{}],Array(4).fill({title:"go",url:"https://example.com"}),
 [{title:" ",url:"https://example.com"}],[{title:"a\n",url:"https://example.com"}],
 [{title:"中".repeat(22),url:"https://example.com"}],[{title:"go",url:"http://example.com"}],
 [{title:"go",url:"https://user:pw@example.com"}],[{title:"go",url:"https://@example.com"}],
 ...["https:///example.com","https://example.com:","https://example.com:0","https://example.com/%zz","https://example.com/path with space","https://example.com\\path"].map(url=>[{title:"go",url}]),
 [{title:"go",url:"https://example.com:65536"}],[{title:"go",url:"https://-bad.test"}],
 [{title:"go",url:"https://example.com/"+"x".repeat(2048)}],
 [{title:"go",url:"https://example.com",extra:true}]]) expect(()=>actions(value)).toThrow();
 expect(actions([{title:"查看日志",url:"https://example.com/log#result"}])).toHaveLength(1);
 expect(actions([{title:"a".repeat(64),url:"https://[::1]:8443/log"}])).toHaveLength(1);
});
it("persists actions across eviction, pages and realtime rows and detects retry conflicts",async()=>{
 const device=await bind();
 const payload={body:"failed",actions:[{title:"查看日志",url:"https://example.com/log"}]};
 const first=await (await call("/api/v1/push",device.push_key,payload,"build")).json<any>();
 expect(first.actions).toEqual(payload.actions);
 await evictDurableObject(stub());
 expect(await (await call("/api/v1/push",device.push_key,payload,"build")).json()).toEqual(first);
 for(const action of [{title:"查看结果",url:"https://example.com/log"},{title:"查看日志",url:"https://example.com/result"}])
  expect((await call("/api/v1/push",device.push_key,{...payload,actions:[action]},"build")).status).toBe(409);
 const page=await (await call("/api/v1/messages",device.receive_token)).json<any>(); expect(page.messages[0].actions).toEqual(payload.actions);
 await runInDurableObject(stub(),(_instance:BeaconInstance,state)=>{
  const store=new MessageStore(state.storage);expect(message(store.due(Date.now(),device.device_id)[0]).actions).toEqual(payload.actions);
  const usage=[...state.storage.sql.exec("SELECT pending_bytes FROM devices")][0];
  expect(usage.pending_bytes).toBe(new TextEncoder().encode(payload.body+JSON.stringify(payload.actions)).length);
  store.ack(device.device_id,[first.id],Date.now());
  expect([...state.storage.sql.exec("SELECT pending_bytes FROM devices")][0].pending_bytes).toBe(0);
 });
 const plain=await (await call("/api/v1/push",device.push_key,{body:"old"},"plain")).json<any>();expect(plain).not.toHaveProperty("actions");
 expect(await (await call("/api/v1/push",device.push_key,{body:"old",actions:[]},"plain")).json()).toEqual(plain);
 expect((await call("/api/v1/push",device.push_key,{body:"bad",actions:null})).status).toBe(400);
 const cap=await (await call("/api/v1/capabilities",device.receive_token)).json<any>();expect(cap.message_fields).toContain("actions");
});
it("upgrades v2 without changing legacy message identity or quotas",async()=>{
 const device=await bind();const first=await (await call("/api/v1/push",device.push_key,{body:"old"})).json<any>();
 await runInDurableObject(stub(),(_instance:BeaconInstance,state)=>{
  const sql=state.storage.sql;
  for(const t of ["messages_insert","messages_delete","messages_ack"])sql.exec(`DROP TRIGGER ${t}`);
  sql.exec("ALTER TABLE messages DROP COLUMN actions");sql.exec("UPDATE metadata SET value='2' WHERE key='schema_version'");
  migrate(state.storage);migrate(state.storage);
  expect([...sql.exec("SELECT id,actions FROM messages")][0]).toMatchObject({id:first.id,actions:""});
  expect([...sql.exec("SELECT pending_bytes FROM devices")][0].pending_bytes).toBe(3);
 });
});
