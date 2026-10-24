import cases from "../../contract/structured-blocks.json";
import { env } from "cloudflare:workers";
import { reset, runInDurableObject } from "cloudflare:test";
import { afterEach, expect, it } from "vitest";
import worker, { BeaconInstance, type Env } from "../src/index";
import { validateBlocks } from "../src/blocks";

afterEach(reset);
it("rejects invalid numeric blocks",()=>{
 for(const blocks of [null,[{kind:"html",id:"x"}],[{kind:"progress",id:"x",value:101,max:100}],
 [{kind:"metric",id:"x",value:1,min:0}],[{kind:"metric",id:"x",value:1,unexpected:true}]])
  expect(()=>validateBlocks(1,blocks)).toThrow();
 expect(validateBlocks(1,[{kind:"progress",id:"x",indeterminate:true}])).toHaveLength(1);
});
it("persists blocks, accounts bytes and rejects changed idempotent requests",async()=>{
 const admin="test-only-admin-token-32-characters";
 const call=(path:string, credential:string,payload?:unknown,key="")=>worker.fetch(new Request(`https://beacon.test${path}`,{
 method:payload===undefined?"GET":"POST",headers:{Authorization:`Bearer ${credential}`,"Content-Type":"application/json","Idempotency-Key":key},body:payload===undefined?undefined:JSON.stringify(payload),
 }),env as unknown as Env);
 const binding=await (await call("/api/admin/binding",admin,{name:"blocks"})).json<any>();
 const device=await (await worker.fetch(new Request("https://beacon.test/api/v1/bind",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({binding_code:binding.binding_code})}),env as unknown as Env)).json<any>();
 const payload={body:"download",schema_version:1,blocks:[{kind:"progress",id:"download",value:40,max:100}]};
 const response=await call("/api/v1/push",device.push_key,payload,"blocks-1");expect(response.status).toBe(200);const first=await response.json<any>();
 expect(first.blocks).toEqual(payload.blocks);
 expect(await (await call("/api/v1/push",device.push_key,payload,"blocks-1")).json()).toEqual(first);
 const conflict=await call("/api/v1/push",device.push_key,{...payload,blocks:[{...payload.blocks[0],value:41}]},"blocks-1");expect(conflict.status).toBe(409);await conflict.text();
 const page=await (await call("/api/v1/messages",device.receive_token)).json<any>();expect(page.messages[0].blocks).toEqual(payload.blocks);
 const bindings=env as unknown as Env;const stub=bindings.BEACON.get(bindings.BEACON.idFromName(bindings.BEACON_INSTANCE));
 await runInDurableObject(stub,(_instance:BeaconInstance,state)=>{
 const usage=[...state.storage.sql.exec("SELECT pending_bytes FROM devices WHERE id=?",device.device_id)][0];
 expect(Number(usage.pending_bytes)).toBe(new TextEncoder().encode(payload.body+JSON.stringify(payload.blocks)).length);
 });
});

it("matches the shared numeric block contract",()=>{
 for(const tc of cases){if(tc.valid)expect(()=>validateBlocks(tc.version,tc.blocks),tc.name).not.toThrow();else expect(()=>validateBlocks(tc.version,tc.blocks),tc.name).toThrow();}
});
