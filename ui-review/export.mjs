import {readFileSync, writeFileSync, mkdirSync, cpSync, readdirSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const root=dirname(fileURLToPath(import.meta.url));
const repo=resolve(root,'..');
const out=resolve(root,'dist');
const drawables=resolve(repo,'aicamera-app/app/src/main/res/drawable-nodpi');
const sourceRoot=resolve(repo,'aicamera-app/app/src/main/java/com/example/aicamera');
const app=readFileSync(resolve(root,'app.js'),'utf8');
const ids=[...app.matchAll(/\bpage\('([a-z-]+)'/g)].map(m=>m[1]);
if(ids.length!==23||new Set(ids).size!==ids.length) throw Error('Expected 23 unique review pages');

function files(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(entry=>entry.isDirectory()?files(resolve(dir,entry.name)):[resolve(dir,entry.name)]);}
function digest(paths){const hash=createHash('sha256');for(const path of paths.sort())hash.update(path).update(readFileSync(path));return hash.digest('hex').slice(0,16);}
const web=digest(['index.html','app.js','style.css'].map(name=>resolve(root,name)));
const android=digest(files(sourceRoot).filter(path=>path.endsWith('.kt')));
const html=readFileSync(resolve(root,'index.html'),'utf8')
 .replace('<head>','<head><meta name="review-deployment" content="static">')
 .replace('./style.css','./style.css?v='+web)
 .replace('./app.js','./app.js?v='+web);
mkdirSync(resolve(out,'assets'),{recursive:true});
for(const name of ['index.html',...ids.map(id=>id+'.html')])writeFileSync(resolve(out,name),html);
for(const name of ['app.js','style.css','baseline.json'])cpSync(resolve(root,name),resolve(out,name));
for(const name of readdirSync(drawables).filter(name=>/^[a-z0-9_]+\.png$/.test(name)))cpSync(resolve(drawables,name),resolve(out,'assets',name));
writeFileSync(resolve(out,'review-version.json'),JSON.stringify({web,android}));
writeFileSync(resolve(out,'.nojekyll'),'');
console.log(`Exported ${ids.length} review pages, version ${web}, to ${out}`);
