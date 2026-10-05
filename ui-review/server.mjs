import http from 'node:http';
import {readFileSync, readdirSync, statSync, existsSync} from 'node:fs';
import {resolve, dirname, extname, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const root = dirname(fileURLToPath(import.meta.url));
const repo = resolve(root, '..');
const res = resolve(repo, 'aicamera-app/app/src/main/res/drawable-nodpi');
const src = resolve(repo, 'aicamera-app/app/src/main/java/com/example/aicamera');
const mime = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.json':'application/json; charset=utf-8','.png':'image/png'};
function files(dir) {
  return readdirSync(dir,{withFileTypes:true}).flatMap(e => e.isDirectory() ? files(resolve(dir,e.name)) : [resolve(dir,e.name)]);
}
function digest(paths) {
  const hash=createHash('sha256');
  for(const p of paths.sort()) hash.update(p).update(readFileSync(p));
  return hash.digest('hex').slice(0,16);
}
let cache, checked=0;
function revision() {
  if(Date.now()-checked<1000 && cache) return cache;
  checked=Date.now();
  const source=files(src).filter(p=>p.endsWith('.kt'));
  cache={web:digest(files(root).filter(p=>/\.(html|js|css|json)$/.test(p))),android:digest(source)};
  return cache;
}
export const server=http.createServer((req,resp)=>{
  try {
    const url=new URL(req.url,'http://localhost');
    resp.setHeader('Cache-Control','no-store');
    resp.setHeader('X-Content-Type-Options','nosniff');
    if(url.pathname==='/api/revision') {
      resp.setHeader('Content-Type',mime['.json']);
      return resp.end(JSON.stringify(revision()));
    }
    let path;
    if(url.pathname.startsWith('/assets/')) {
      const name=decodeURIComponent(url.pathname.slice(8));
      if(!/^[a-z0-9_]+\.png$/.test(name)) throw Error('Invalid asset');
      path=resolve(res,name);
    } else if(url.pathname==='/source') {
      const name=url.searchParams.get('file')||'';
      path=resolve(src,name);
      if(!path.startsWith(src+sep)||!path.endsWith('.kt')) throw Error('Invalid source');
      resp.setHeader('Content-Type','text/plain; charset=utf-8');
      return resp.end(readFileSync(path));
    } else {
      const name=decodeURIComponent(url.pathname).slice(1)||'index.html';
      path=resolve(root,name);
      if(!path.startsWith(root+sep)) throw Error('Invalid path');
      if(name.endsWith('.html')&&!existsSync(path)) path=resolve(root,'index.html');
      if(!['.html','.js','.css','.json'].includes(extname(path))) throw Error('Invalid type');
    }
    resp.setHeader('Content-Type',mime[extname(path)]||'application/octet-stream');
    resp.end(readFileSync(path));
  } catch {resp.writeHead(404);resp.end('Not found');}
});
if(process.argv[1]===fileURLToPath(import.meta.url)){
  server.listen(Number(process.env.PORT||4173),'127.0.0.1',()=>console.log('GenWow review: http://127.0.0.1:'+server.address().port));
}
