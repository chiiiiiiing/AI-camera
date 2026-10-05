import {createRequire} from 'node:module';
import {mkdirSync} from 'node:fs';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE||'playwright');
const browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
const ctx=await browser.newContext({viewport:{width:1480,height:1100}});
const tab=await ctx.newPage();
const errors=[];tab.on('pageerror',e=>errors.push(e.message));
const base='http://127.0.0.1:4173';
mkdirSync(new URL('./artifacts/',import.meta.url),{recursive:true});
try{
 await tab.goto(base);
 await tab.waitForSelector('#catalog a');
 const routes=await tab.locator('#catalog a').evaluateAll(a=>a.map(e=>e.pathname));
 assert.equal(routes.length,23);
 for(const route of routes){
  await tab.goto(base+route);await tab.waitForSelector('#app > *');
  assert.ok(await tab.locator('#app').innerText(),route);
  const broken=await tab.locator('#app img').evaluateAll(imgs=>Promise.all(imgs.map(img=>new Promise(r=>{
   if(img.complete)r(img.naturalWidth===0?img.src:null);
   else{img.onload=()=>r(null);img.onerror=()=>r(img.src);}
  }))));
  assert.deepEqual(broken.filter(Boolean),[],route);
  const sources=await tab.locator('#sources a').evaluateAll(a=>a.map(e=>e.href));
  for(const src of sources)assert.equal((await tab.request.get(src)).status(),200,src);
 }
 await tab.goto(base+'/community.html');
 await tab.locator('[data-action="like:1"]').click();
 await tab.locator('[data-action="favorite:1"]').click();
 await tab.locator('[data-action="follow:Helena"]').first().click();
 await tab.goto(base+'/profile.html?state='+encodeURIComponent('点赞'));
 assert.equal(await tab.locator('.grid .card').count(),1);
 await tab.goto(base+'/following-feed.html');assert.equal(await tab.locator('.post').count(),2);
 await tab.goto(base+'/comments.html');
 await tab.locator('[name=text]').fill('浏览器验收联动检查');
 await tab.locator('#comment-form button').click();
 assert.ok((await tab.locator('.sheet').innerText()).includes('浏览器验收联动检查'));
 await tab.goto(base+'/filter-editor.html');
 await tab.locator('#filter-name').fill('验收滤镜');
 await tab.locator('[data-action=save-filter]').click();
 await tab.waitForURL('**/filters.html');
 assert.ok((await tab.locator('.filterstrip').innerText()).includes('验收滤镜'));
 await tab.goto(base+'/publish.html');
 await tab.locator('[name=body]').fill('新作品验收');
 await tab.locator('#publish-form button').click();
 await tab.waitForURL('**/community.html');
 assert.ok((await tab.locator('.post').first().innerText()).includes('新作品验收'));
 await tab.goto(base+'/profile.html');
 assert.equal(await tab.locator('.grid .card').count(),1);
 await tab.locator('#feedback').fill('检查按钮位置');
 await tab.locator('#save-feedback').click();
 await tab.reload();await tab.waitForSelector('#app > *');
 assert.equal(await tab.locator('#feedback').inputValue(),'检查按钮位置');
 await tab.goto(base+'/camera.html');
 const scenarios=await tab.locator('#scenario option').allTextContents();
 for(const s of scenarios){await tab.selectOption('#scenario',s);await tab.waitForTimeout(30)}
 await tab.selectOption('#scenario','预览');
 await tab.locator('#zoom').fill('1.7');assert.equal(await tab.locator('#zoom-value').textContent(),'1.7×');
 await tab.selectOption('#viewport','360');
 const bounds=await tab.locator('.probutton').boundingBox(),device=await tab.locator('#device').boundingBox();
 assert.ok(bounds.x+bounds.width<=device.x+device.width,'PRO within device');
 await tab.screenshot({path:new URL('./artifacts/camera-desktop.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
 await tab.selectOption('#viewport','landscape');
 await tab.screenshot({path:new URL('./artifacts/camera-landscape.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
 await tab.goto(base+'/community.html');
 await tab.screenshot({path:new URL('./artifacts/community.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
 await tab.goto(base+'/camera.html?state='+encodeURIComponent('超时提示'));
 await tab.waitForSelector('.take-now');
 await tab.waitForSelector('.take-now',{state:'detached',timeout:6000});
 assert.deepEqual(errors,[]);
 console.log('PASS: 23 pages, source/image links, community interactions, camera states, zoom, timeout and screenshots.');
}finally{await browser.close()}
