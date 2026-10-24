import { reject } from "./domain";
import { validTopicKey } from "./state-version";

import type { StructuredBlock } from "./domain";
export function validateBlocks(version: unknown, input: unknown): StructuredBlock[] {
  const fail = (): never => reject(400, "invalid_message");
  if (version !== undefined && version !== 0 && version !== 1) fail();
  if (input === undefined) return [];
  if (!Array.isArray(input) || input.length > 16 ||
      new TextEncoder().encode(JSON.stringify(input)).length > 32768) return fail();
  if (input.length && version !== 1) fail();
  const ids = new Set<string>();
  const fields = new Set(["kind","id","label","value","min","max","unit","tone","trend","indeterminate","items","points","bands","text","target_at_ms","total"]);
  for (const b of input) {
    if (!b || typeof b !== "object" || Array.isArray(b) || Object.keys(b).some(k=>!fields.has(k))) fail();
    if (typeof b.id !== "string" || !validTopicKey(b.id) || new TextEncoder().encode(b.id).length > 64 || ids.has(b.id)) fail();
    ids.add(b.id);
    for (const [key, limit] of [["label",128],["unit",32]] as const) {
      if (b[key] !== undefined && (typeof b[key] !== "string" || new TextEncoder().encode(b[key]).length > limit || /\p{Cs}/u.test(b[key]))) fail();
    }
    if (b.tone !== undefined && !["","normal","warning","critical"].includes(b.tone)) fail();
    if (b.indeterminate !== undefined && typeof b.indeterminate !== "boolean") fail();
    for (const key of ["value","min","max"]) if (b[key] !== undefined &&
      (typeof b[key] !== "number" || !Number.isFinite(b[key]) || Math.abs(b[key]) > 1e15)) fail();
    if (b.kind !== "progress" && b.kind !== "metric") {if(!validRich(b))fail();continue;}
    if(b.items?.length||b.points?.length||b.text||b.target_at_ms!==undefined||b.total!==undefined)fail();
    if (b.indeterminate) {
      if (b.kind !== "progress" || b.value !== undefined || b.min !== undefined || b.max !== undefined || b.trend) fail();
      continue;
    }
    if (b.value === undefined) fail();
    if (b.kind === "progress") { if (b.max === undefined || b.trend) fail(); }
    else {
      if ((b.min === undefined) !== (b.max === undefined)) fail();
      if (b.trend !== undefined && !["","up","down","flat"].includes(b.trend)) fail();
    }
    if(b.bands?.length){if(!Array.isArray(b.bands)||b.bands.length>5||b.max===undefined)fail();let last=b.min??0;for(const band of b.bands){if(!band||Object.keys(band).some(k=>!["until","tone"].includes(k))||!numeric(band.until)||band.until<=last||!tone(band.tone))fail();last=band.until;}if(last!==b.max)fail();}
    if (b.max !== undefined && (b.max <= (b.min ?? 0) || b.value < (b.min ?? 0) || b.value > b.max)) fail();
  }
  return input as StructuredBlock[];
}

export const BLOCK_KINDS=["progress","metric","kv","list","countdown","timeline","pipeline","alert","sparkline","segments"];
const numeric=(v:unknown):v is number=>typeof v==="number"&&Number.isFinite(v)&&Math.abs(v)<=1e15;
const tone=(v:unknown)=>v===undefined||["","normal","warning","critical"].includes(String(v));
const text=(v:unknown,max:number)=>v===undefined||typeof v==="string"&&new TextEncoder().encode(v).length<=max&&!/\p{Cs}/u.test(v);
function validRich(b:Record<string,any>):boolean{
 if(b.value!==undefined||b.min!==undefined||b.max!==undefined||b.indeterminate||b.trend||b.bands?.length||!text(b.text,2048))return false;
 if(b.kind==="alert")return typeof b.text==="string"&&!!b.text&&!b.items?.length&&!b.points?.length&&b.target_at_ms===undefined&&b.total===undefined;
 if(b.kind==="countdown")return Number.isSafeInteger(b.target_at_ms)&&b.target_at_ms>=0&&!b.items?.length&&!b.points?.length&&b.total===undefined&&!b.text;
 if(b.kind==="sparkline"){if(!Array.isArray(b.points)||!b.points.length||b.points.length>120||b.items?.length||b.target_at_ms!==undefined||b.total!==undefined||b.text)return false;let last=-1;for(const p of b.points){if(!p||Object.keys(p).some(k=>!["at_ms","value"].includes(k))||!Number.isSafeInteger(p.at_ms)||p.at_ms<=last||!numeric(p.value))return false;last=p.at_ms;}return true;}
 if(!["kv","list","timeline","pipeline","segments"].includes(b.kind)||!Array.isArray(b.items)||!b.items.length||b.items.length>12||b.points?.length||b.target_at_ms!==undefined||b.text)return false;
 if(b.kind==="segments"){if(b.items.length>6||!numeric(b.total)||b.total<=0)return false;}else if(b.total!==undefined)return false;
 const ids=new Set();let sum=0;
 for(const item of b.items){if(!item||Object.keys(item).some(k=>!["id","label","text","tone","status","at_ms","value"].includes(k))||typeof item.id!=="string"||!validTopicKey(item.id)||new TextEncoder().encode(item.id).length>64||ids.has(item.id)||!text(item.label,128)||!text(item.text,2048)||!tone(item.tone))return false;ids.add(item.id);
 if(b.kind==="kv"&&(!item.label||item.value!==undefined||item.at_ms!==undefined||item.status))return false;
 if(b.kind==="list"&&(!item.text||item.value!==undefined||item.at_ms!==undefined||item.status))return false;
 if(b.kind==="segments"){if(!item.label||!numeric(item.value)||item.value<0||item.at_ms!==undefined||item.status)return false;sum+=item.value;}
 if(b.kind==="timeline"&&(!item.label||!Number.isSafeInteger(item.at_ms)||item.at_ms<0||item.value!==undefined||!["pending","active","completed","failed"].includes(item.status)))return false;
 if(b.kind==="pipeline"&&(!item.label||item.value!==undefined||item.at_ms!==undefined||!["pending","running","completed","failed","skipped"].includes(item.status)))return false;
 }return b.kind!=="segments"||sum<=b.total;
}
