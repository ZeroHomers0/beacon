import { env } from "cloudflare:workers";
import { reset } from "cloudflare:test";
import { afterEach, expect, it } from "vitest";
import worker,{type Env} from "../src/index";
afterEach(reset);
const admin="test-only-admin-token-32-characters";
const call=(path:string,key:string,p?:unknown)=>worker.fetch(new Request(`https://beacon.test${path}`,{method:p===undefined?"GET":"POST",headers:{Authorization:`Bearer ${key}`,"Content-Type":"application/json"},body:p===undefined?undefined:JSON.stringify(p)}),env as unknown as Env);
it("precreates empty topics, enforces scope and config versions, and hard deletes without resurrection",async()=>{
 const code=await(await call("/api/admin/binding",admin,{name:"phone"})).json<any>();const d=await(await call("/api/v1/bind","",{binding_code:code.binding_code})).json<any>();
 const p={mode:"state",schema_version:1,topic_key:"quota",revision:1,body:"40%"};
 for(const path of ["/api/v1/push","/api/v1/states/meta?topic_key=quota"]){const r=await call(path,d.push_key,path.endsWith("push")?p:undefined);expect(r.status).toBe(404);expect(await r.json()).toEqual({error:"topic_not_found"})}
 const input={device_id:d.device_id,topic_key:"quota",name:"Quota",request_id:"one"};
 for(const key of [d.push_key,d.receive_token]){const r=await call("/api/admin/topics/create",key,input);expect(r.status).toBe(401);await r.text()}
 const results=await Promise.all(Array.from({length:5},()=>call("/api/admin/topics/create",admin,input).then(r=>r.json<any>())));expect(results.every(r=>r.topic.config_version===1)).toBe(true);
 const page=await(await call("/api/v1/topics",d.receive_token)).json<any>();expect(page.topics).toHaveLength(1);
 const waiting=await(await call("/api/admin/topics/list",admin,{state:"waiting"})).json<any>();expect(waiting.total).toBe(1);expect(waiting.topics[0].revision).toBe(0);
 expect((await(await call("/api/v1/states/meta?topic_key=quota",d.push_key)).json<any>()).revision).toBe(0);
 expect((await call("/api/v1/push",d.push_key,p)).status).toBe(200);
 expect((await call("/api/admin/topics/update",admin,{...input,name:"Renamed",config_version:1})).status).toBe(200);
 const conflict=await call("/api/admin/topics/delete",admin,{...input,config_version:1});expect(conflict.status).toBe(409);await conflict.text();
 expect((await call("/api/admin/topics/delete",admin,{...input,config_version:2})).status).toBe(200);
 expect((await(await call("/api/admin/topics/list",admin,{})).json<any>()).total).toBe(0);
 expect((await(await call("/api/v1/topics",d.receive_token)).json<any>()).topics).toHaveLength(0);
 const delta=await(await call(`/api/v1/topics?cursor=${page.next_cursor}`,d.receive_token)).json<any>();expect(delta.topics).toHaveLength(2);expect(delta.topics[1].deleted).toBe(true);
 const rejected=await call("/api/v1/push",d.push_key,p);expect(rejected.status).toBe(404);await rejected.text();
 expect((await call("/api/admin/topics/create",admin,{...input,request_id:"two"})).status).toBe(200);
 expect((await call("/api/v1/push",d.push_key,p)).status).toBe(200);
});
