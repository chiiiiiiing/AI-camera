import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import {server} from './server.mjs';
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const url='http://127.0.0.1:'+server.address().port;
after(()=>server.close());
test('page shell, source links and original assets are served',async()=>{
 for(const path of ['/camera.html','/comments.html','/profile.html','/style.css','/app.js','/assets/community_cafe_portrait.png','/source?file=ui/screen/CameraScreen.kt']){
  const r=await fetch(url+path);assert.equal(r.status,200,path);assert.ok((await r.arrayBuffer()).byteLength>100);
 }
});
test('revision exposes independent Android and preview fingerprints',async()=>{
 const r=await fetch(url+'/api/revision').then(r=>r.json());
 assert.match(r.web,/^[a-f0-9]{16}$/);assert.match(r.android,/^[a-f0-9]{16}$/);
});
test('only review files and whitelisted Android source/assets are accessible',async()=>{
 for(const path of ['/server.mjs','/.git/config','/assets/..%2F..%2Fpackage.json','/source?file=../../../../../../local.properties']){
  assert.equal((await fetch(url+path)).status,404,path);
 }
});
