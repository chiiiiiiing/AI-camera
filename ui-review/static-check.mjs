import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import {readFileSync, mkdirSync} from 'node:fs';
import {resolve, dirname, extname, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE||'playwright');
const root=resolve(dirname(fileURLToPath(import.meta.url)),'dist');
const prefix=process.env.STATIC_PREFIX??'/AI-camera';
const mime={'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.png':'image/png'};
const server=createServer((req,res)=>{
 try{
  const url=new URL(req.url,'http://localhost');
  if(!url.pathname.startsWith(prefix+'/'))throw Error('bad prefix');
  const name=decodeURIComponent(url.pathname.slice((prefix+'/').length))||'index.html';
  const path=resolve(root,name);
  if(!path.startsWith(root+sep)||!mime[extname(path)])throw Error('bad path');
  res.setHeader('Content-Type',mime[extname(path)]);
  res.end(readFileSync(path));
 }catch{res.writeHead(404);res.end('Not found')}
});
await new Promise(done=>server.listen(0,'127.0.0.1',done));
const origin='http://127.0.0.1:'+server.address().port;
let browser;
try{
 browser=await chromium.launch({headless:true,executablePath:process.env.BROWSER_EXECUTABLE||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage();
 const errors=[];
 page.on('pageerror',error=>errors.push(error.message));
 page.on('response',response=>{if(response.status()>=400)errors.push(response.status()+' '+response.url())});
 await page.goto(origin+prefix+'/');
 const routes=await page.locator('#catalog a').evaluateAll(nodes=>nodes.map(node=>node.getAttribute('href')));
 assert.equal(routes.length,24);
 for(const route of routes){
  assert.ok(route.startsWith(prefix+'/'),route);
  await page.goto(origin+route);
  await page.waitForSelector('#app > *');
  assert.ok(await page.locator('#app').innerText(),route);
 }
 await page.goto(origin+prefix+'/community.html');
 await page.locator('[data-action="like:1"]').click();
 await page.goto(origin+prefix+'/profile.html?state='+encodeURIComponent('点赞'));
 assert.equal(await page.locator('.grid .card').count(),1);
 await page.goto(origin+prefix+'/camera.html?state='+encodeURIComponent('分步引导'));
 await page.locator('[data-action="step-next"]').click();
 assert.equal(await page.locator('.cp-subject').innerText(),'主体中心');
 assert.equal(await page.locator('.cp-target').innerText(),'目标位置');
 assert.equal(await page.locator('[data-action^="mode:"]').count(),0);
 const row=await page.locator('.capture-row').boundingBox();
 const pro=await page.locator('.camtools .probutton').boundingBox();
 assert.ok(pro.y>=row.y && pro.y+pro.height<=row.y+row.height+1,'camera controls stay on one row');
 await page.goto(origin+prefix+'/experience.html');
 assert.ok((await page.locator('a.primary').getAttribute('href')).endsWith('/GenWow-v5-debug.apk'));
 await page.setViewportSize({width:390,height:844});
 mkdirSync(resolve(root,'..','artifacts'),{recursive:true});
 await page.screenshot({path:resolve(root,'..','artifacts','experience-v5-mobile.png'),fullPage:true});
 assert.deepEqual(errors,[]);
 console.log(`Static ${prefix||'root'} path: 24 pages and interactions passed`);
} finally {await browser?.close();server.close()}
