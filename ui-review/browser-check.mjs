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
 await tab.goto(base+'/following-feed.html');assert.equal(await tab.locator('.post').count(),4);
 await tab.goto(base+'/comments.html');
 await tab.locator('[name=text]').fill('浏览器验收联动检查');
 await tab.locator('#comment-form button').click();
 assert.ok((await tab.locator('.sheet').innerText()).includes('浏览器验收联动检查'));
 await tab.goto(base+'/filter-editor.html');
 await tab.waitForFunction(()=>document.querySelector('.editor-photo')?.getContext('2d').getImageData(0,0,1,1).data[3]>0);
 const canvasHash=()=>tab.locator('.editor-photo').evaluate(canvas=>{
  const pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;
  let hash=2166136261;for(let i=0;i<pixels.length;i+=4){hash=Math.imul(hash^pixels[i],16777619);hash=Math.imul(hash^pixels[i+1],16777619);hash=Math.imul(hash^pixels[i+2],16777619)}
  return hash>>>0;
 });
 const originalHash=await canvasHash();
 for(const name of ['高光','阴影','红色','绿色','蓝色','锐化','暗角']){
  const slider=tab.locator('[data-adjust="'+name+'"]');
  await slider.fill('80');await tab.waitForTimeout(45);
  assert.notEqual(await canvasHash(),originalHash,name+' must change actual preview pixels');
  await slider.fill('0');await tab.waitForTimeout(45);
 }
 await tab.locator('#filter-name').fill('验收滤镜');
 await tab.locator('[data-adjust="暗角"]').fill('80');
 await tab.locator('.filter-editor-screen .bodypad').evaluate(el=>el.scrollTop=0);
 await tab.screenshot({path:new URL('./artifacts/filter-editor.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
 await tab.locator('[data-action=save-filter]').click();
 await tab.waitForURL('**/filters.html');
 assert.ok((await tab.locator('.filterstrip').innerText()).includes('验收滤镜'));
 await tab.waitForFunction(()=>document.querySelector('.preview img')?.src.startsWith('data:image/png'));
 assert.ok((await tab.locator('[data-custom-cover]').first().getAttribute('src')).startsWith('data:image/png'),'saved custom filter cover must render');
 await tab.goto(base+'/capture-result.html');
 await tab.waitForFunction(()=>document.querySelector('[data-custom-output]')?.src.startsWith('data:image/png'));
 await tab.goto(base+'/login.html');
 await tab.locator('[data-action="auth-mode"]').click();
 assert.equal(await tab.locator('[name="code"]').count(),1,'login mode toggle must not submit form');
 await tab.goto(base+'/camera.html?state='+encodeURIComponent('移动'));
 assert.equal(await tab.locator('.preview-subject').count(),1);
 assert.equal(await tab.locator('.preview-target').count(),1);
 await tab.goto(base+'/settings.html');
 await tab.locator('[data-setting="显示主体框"]').uncheck();
 await tab.locator('[data-setting="显示目标区"]').uncheck();
 await tab.goto(base+'/camera.html?state='+encodeURIComponent('移动'));
 assert.equal(await tab.locator('.preview-subject').count(),0);
 assert.equal(await tab.locator('.preview-target').count(),0);
 await tab.goto(base+'/settings.html');
 await tab.locator('[data-setting="显示主体框"]').check();
 await tab.locator('[data-setting="显示目标区"]').check();
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
 await tab.locator('[data-action="shutter"]').click();
 await tab.waitForSelector('.axis-card.roll',{timeout:3000});
 assert.equal(await tab.locator('.preview-grid i').count(),4,'shutter must reveal composition grid');
 await tab.screenshot({path:new URL('./artifacts/camera-guidance.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
 await tab.selectOption('#scenario','水平已对齐');
 assert.ok((await tab.locator('.guide').getAttribute('class')).includes('good'));
 await tab.screenshot({path:new URL('./artifacts/camera-aligned.png',import.meta.url).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true});
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
