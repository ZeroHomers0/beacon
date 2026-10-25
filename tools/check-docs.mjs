import { readFileSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { resolve, dirname, relative, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const staged = process.argv.includes("--staged");
const roots = ["README.md", "API.md", "FRONTEND_GUIDELINES.md", "BACKEND_GUIDELINES.md", "REPOSITORY_GUIDELINES.md", "AGENTS.md", "CLAUDE.md"];
const index = new Set(execFileSync("git", ["ls-files", "--cached", "-z"], {cwd:root,encoding:"utf8"}).split("\0").filter(Boolean));
function read(path) { return staged ? execFileSync("git", ["show", ":" + path], {cwd:root,encoding:"utf8",maxBuffer:8*1024*1024}) : readFileSync(resolve(root,path),"utf8"); }
function prose(source) { return source.replace(/^(```|~~~)[^\n]*\n[\s\S]*?^\1[^\n]*$/gm, ""); }
function headings(source) {
  const found = new Set(); const counts = new Map();
  for (const match of prose(source).matchAll(/^#{1,6} +(.+)$/gm)) {
    const slug = match[1].trim().toLowerCase().replace(/[`*_~]/g, "").replace(/[^\p{L}\p{N}_\- ]/gu, "").replace(/ /g, "-");
    const n=counts.get(slug)??0;counts.set(slug,n+1);found.add(slug+(n?"-"+n:""));
  }
  return found;
}
const errors=[];let links=0;
for(const file of roots){
  let source;try{source=read(file)}catch{errors.push(`${file}: missing document`);continue}
  for(const match of prose(source).matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g)){
    let target=match[1].trim().split(/\s+"/)[0].replace(/^<|>$/g, "");
    if(/^[a-z]+:/i.test(target))continue;
    const [path,anchor]=target.split("#");
    const absolute=resolve(root,dirname(file),decodeURIComponent(path||""));
    const dest=path?relative(root,absolute).replaceAll("\\","/"):file;
    if(isAbsolute(dest)||dest.startsWith("../")){errors.push(`${file}: outside repository ${target}`);continue}
    const present=staged?(index.has(dest)||[...index].some(p=>p.startsWith(dest.replace(/\/$/,"")+"/"))):existsSync(absolute);
    links++;
    if(!present){errors.push(`${file}: missing target ${target}`);continue}
    if(anchor&&dest.endsWith(".md")){try{if(!headings(read(dest)).has(decodeURIComponent(anchor)))errors.push(`${file}: missing heading ${target}`)}catch{errors.push(`${file}: cannot read ${target}`)}}
  }
}
if(errors.length){console.error("Documentation check failed:\n"+errors.join("\n"));process.exitCode=1}else console.log(`Documentation check passed: ${roots.length} documents, ${links} local links.`);
