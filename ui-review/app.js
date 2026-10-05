const S='ui/screen/', C='ui/components/';
const page=(id,title,group,source,states=['默认'],note='按当前 Compose 页面复现布局与交互；Web 字体和控件尺寸需与真机二次核对。')=>({id,title,group,source:Array.isArray(source)?source:[source],states,note});
export const pages=[
 page('camera','相机主页面','01 / 相机',[S+'CameraScreen.kt',C+'CaptureWorkflowOverlay.kt',C+'FocusExposureOverlay.kt'],['预览','分析中','旋转','俯仰','水平转向','移动','稳定验证','拍摄中','无主体','倒置','超时提示','对焦曝光','权限拒绝','拍摄失败']),
 page('filters','滤镜选择面板','01 / 相机',[S+'CameraScreen.kt','filter/PresetFilter.kt']),
 page('pro','Pro 手动参数面板','01 / 相机',C+'CompactProPanel.kt',['EV','ISO','快门','白平衡','对焦','变焦']),
 page('filter-editor','自定义滤镜编辑','01 / 相机',[S+'FilterEditorScreen.kt','filter/CustomFilterConfig.kt']),
 page('capture-result','照片已保存','01 / 相机',C+'CaptureResultSheet.kt'),
 page('publish','发布到社区','01 / 相机',S+'CameraScreen.kt'),
 page('gallery','系统相册入口','01 / 相机','ui/navigation/AppNavGraph.kt',['已有照片','空相册'],'App 通过 ACTION_VIEW 打开系统相册。此页只演示跳转边界与照片，不代表项目已有自研相册。'),
 page('community','社区推荐动态','02 / 社区',S+'CommunityScreen.kt',['推荐','空列表']),
 page('following-feed','关注动态','02 / 社区',S+'CommunityScreen.kt',['动态','未关注']),
 page('post','帖子详情','02 / 社区',S+'PostDetailScreen.kt'),
 page('comments','用户评论弹层','02 / 社区',S+'CommunityScreen.kt',['有评论','空评论']),
 page('filter-resource','社区滤镜资源','02 / 社区',[S+'PostDetailScreen.kt','filter/CustomFilterConfig.kt']),
 page('messages','消息占位页','02 / 社区',S+'CommunityScreen.kt',['默认'],'当前 Android 代码只有“暂无消息”占位，尚无通知列表或私信功能。'),
 page('profile','个人主页','03 / 个人',S+'ProfileScreen.kt',['作品','点赞','收藏','评论']),
 page('user-profile','他人 / 机器人主页','03 / 个人',S+'ProfileScreen.kt',['Helena','Daniel']),
 page('edit-profile','编辑个人资料','03 / 个人',S+'EditProfileScreen.kt'),
 page('followers','粉丝列表','03 / 个人',S+'UserListScreen.kt',['列表','空列表']),
 page('following','关注列表','03 / 个人',S+'UserListScreen.kt',['列表','空列表']),
 page('settings','设置','03 / 个人',S+'SettingsScreen.kt'),
 page('splash','启动页','04 / 账号',S+'SplashScreen.kt'),
 page('login','登录','04 / 账号',S+'LoginScreen.kt',['密码登录','验证码登录','错误提示']),
 page('register','注册','04 / 账号',S+'RegisterScreen.kt',['默认','错误提示']),
 page('forgot-password','找回密码','04 / 账号',S+'ForgotPasswordScreen.kt',['默认','错误提示'])
];
const $=s=>document.querySelector(s);
const siteBase=new URL('.',import.meta.url).pathname.replace(/\/$/,'');
const site=path=>siteBase+path;
const published=document.querySelector('meta[name="review-deployment"]')?.content==='static';
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function read(key,fallback){try{return JSON.parse(localStorage.getItem(key))??fallback}catch{return fallback}}
const initial={likes:[],favorites:[],following:[],comments:[],posts:[],name:'摄影师',bio:'用 AI 构图，记录每一帧美好',avatar:'',settings:{},filters:[]};
let data=read('genwow-review-data',structuredClone(initial));
let id=location.pathname.split('/').pop().replace('.html','');
if(!pages.some(p=>p.id===id))id='camera';
let current=pages.find(p=>p.id===id);
let state=new URLSearchParams(location.search).get('state')||current.states[0];
if(!current.states.includes(state))state=current.states[0];
if(id==='user-profile'&&['Helena','Daniel'].includes(new URLSearchParams(location.search).get('user')))state=new URLSearchParams(location.search).get('user');
let hidden=new Set(), filter=0, zoom=1, proControl='EV', mode='静物', front=false, captured=false, expanded=id==='filters', proOpen=id==='pro';
let otherTab='作品';
let revision={}, toastTimer, captureTimer, hintTimer;
const basePosts=[
 {id:'1',author:'Helena',image:'community_cafe_portrait',title:'窗边的下午',body:'柔和肤色与环境留白，让日常人像更有呼吸感。',likes:26},
 {id:'2',author:'Daniel',image:'community_blue_city',title:'蓝调城市',body:'对称与引导线让冷色建筑保留秩序。',likes:32},
 {id:'3',author:'Helena',image:'community_rainy_street',title:'雨后街角',body:'冷暖对比和地面倒影共同讲故事。',likes:18},
 {id:'4',author:'Daniel',image:'community_forest_macro',title:'森林里的微光',body:'用光线记录自然的细节。',likes:21}
];
const posts=()=>[...data.posts,...basePosts];
let selectedPost=sessionStorage.getItem('genwow-review-post')||'1';
const activePost=()=>posts().find(p=>p.id===selectedPost)||basePosts[0];
const image=(name='community_cafe_portrait')=>site('/assets/'+name+'.png');
const avatar=name=>image(name==='Daniel'?'avatar_daniel':'avatar_helena');
const filters=['原图','人像柔和','美食鲜艳','静物干净','风景通透','金色暖调','城市冷锐'];
const effects=['none','saturate(.88) contrast(.92) sepia(.13) brightness(1.05)','saturate(1.3) contrast(1.1) sepia(.12)','saturate(.82) brightness(1.075) contrast(1.12)','saturate(1.24) contrast(1.11) hue-rotate(7deg)','sepia(.32) saturate(1.15) brightness(1.035)','saturate(.72) contrast(1.22) hue-rotate(12deg)'];
const paths={
 camera:'M3 7h5l2-3h4l2 3h5v13H3z M16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
 person:'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M4 21v-2a8 8 0 0 1 16 0v2',
 share:'M7 12l10-6M7 12l10 6 M7 12a2 2 0 1 1-4 0 2 2 0 0 1 4 0 M21 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0 M21 19a2 2 0 1 1-4 0 2 2 0 0 1 4 0',
 settings:'M10 2h4l1 3 3 1 3 2-1 4 1 4-3 2-3 1-1 3h-4l-1-3-3-1-3-2 1-4-1-4 3-2 3-1z M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
 flip:'M4 8a8 8 0 0 1 14-3l2 3M20 3v5h-5 M20 16A8 8 0 0 1 6 19l-2-3M4 21v-5h5',
 heart:'M12 21 3 12C-3 4 7-1 12 6 17-1 27 4 21 12z',
 bookmark:'M6 3h12v18l-6-4-6 4z', comment:'M3 3h18v14H9l-6 4z',
 back:'m15 4-8 8 8 8', mountain:'m2 20 7-10 5 6 4-8 4 12z M7 5a2 2 0 1 1-4 0 2 2 0 0 1 4 0',
 coffee:'M4 7h12v8a5 5 0 0 1-10 0V7 M16 8h3a3 3 0 0 1 0 6h-3 M4 21h15 M7 2v2M12 2v2'
};
const svg=name=>'<svg viewBox="0 0 24 24" aria-hidden="true"><path d="'+(paths[name]||paths.camera)+'"/></svg>';
const btn=(label,action,cls='')=>'<button class="'+cls+'" data-action="'+action+'">'+label+'</button>';
const icon=(name,action,label,cls='')=>'<button class="icon '+cls+'" data-action="'+action+'" aria-label="'+label+'" title="'+label+'">'+svg(name)+'</button>';
const route=(label,dest,cls='')=>{const [path,query]=dest.split('?');return '<a class="'+cls+'" href="'+site('/'+path+'.html')+(query?'?'+query:'')+'">'+label+'</a>'};
const nav=active=>'<nav class="bottomnav" data-layer="全局底栏">'+['camera','community','profile'].map((p,i)=>'<a aria-label="'+['相机','社区','我的'][i]+'" href="'+site('/'+p+'.html')+'" class="icon '+(active===p?'selected':'')+'">'+svg(['camera','share','person'][i])+'</a>').join('')+'</nav>';
const top=(title,back='camera',right='')=>'<div class="top" data-layer="页面标题">'+route(svg('back'),back,'icon')+'<h3>'+title+'</h3>'+right+'</div>';
const field=(label,name,type='text',value='')=>'<label class="field">'+label+'<input name="'+name+'" type="'+type+'" value="'+escape(value)+'" required aria-label="'+label+'"></label>';
const empty=text=>'<div class="empty">'+svg('camera')+'<p>'+text+'</p></div>';
function toast(text){$('#toast').textContent=text;$('#toast').classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').classList.remove('show'),2600)}
function save(){localStorage.setItem('genwow-review-data',JSON.stringify(data))}
function goto(dest){location.href=site('/'+dest+'.html')}
function setState(next){state=next;const u=new URL(location.href);u.searchParams.set('state',next);history.replaceState({},'',u);$('#scenario').value=next;render();loadFeedback()}
function camera(){
 const denied=state==='权限拒绝';
 const guidance=['分析中','旋转','俯仰','水平转向','移动','稳定验证','拍摄中','无主体','倒置','拍摄失败'];
 const hints={'分析中':['正在分析构图','请保持画面稳定'],'旋转':['旋转手机','向左旋转'],'俯仰':['调整俯仰','向上倾斜'],'水平转向':['调整方向','向左转动'],'移动':['调整主体位置','移动画面中的主体，靠近目标点'],'稳定验证':['构图达标','保持不动 · 即将拍摄'],'拍摄中':['正在拍摄','请保持稳定，正在处理照片'],'无主体':['未识别到主体','请将人物放入画面'],'倒置':['手机拿反了','请翻转手机后继续拍摄'],'拍摄失败':['拍摄失败','演示保存失败状态']};
 let art='';
 if(state==='旋转')art='<img class="guide-art" src="'+image('guidance_roll_off')+'" alt="旋转引导素材">';
 if(state==='俯仰')art='<img class="guide-art" src="'+image('guidance_pitch_ccw_misaligned')+'" alt="俯仰引导素材">';
 if(state==='移动')art='<div class="move-line"><span>●</span><span>→</span><span class="cross-target">＋</span></div>';
 if(state==='稳定验证')art='<div class="cross-target">✓</div>';
 return '<div class="camera">'+
 '<div class="preview" data-layer="相机预览占位" data-action="focus"><img alt="静态相机占位照片" src="'+image()+'" style="filter:'+effects[filter]+';transform:scale('+zoom+')'+(front?' scaleX(-1)':'')+'"></div>'+
 (denied?'<div class="guide">'+btn('模拟授予权限','permit','primary')+'</div>':'')+
 '<div class="camtop" data-layer="顶部构图模式">'+[['coffee','静物'],['mountain','风景'],['person','人像']].map(([i,m])=>icon(i,'mode:'+m,m+'构图模式',mode===m?'selected':'')).join('')+'<span class="spacer"></span>'+route(svg('settings'),'settings','icon')+'</div>'+
 (guidance.includes(state)?'<div class="progress" data-layer="工作流进度"><div class="dots">● ━ ● ━ ●</div>'+escape(hints[state][0])+'</div><div class="guide '+(state==='稳定验证'?'good':'')+'" data-layer="旋转俯仰移动引导">'+art+'<h3>'+hints[state][0]+'</h3><p>'+hints[state][1]+'</p></div>':'<div class="ai" data-layer="引擎状态提示">● AI 已就绪 · 预览模拟</div>')+
 (state==='对焦曝光'?'<div class="focusbox" data-layer="对焦与曝光"><input aria-label="曝光补偿" type="range" min="-2" max="2" step=".1" value="0" data-ev><output>0 EV</output></div>':'')+
 (state==='超时提示'?btn('按当前画面立即拍摄','capture','take-now'):'')+
 (proOpen?proPanel():'<div class="filterdock" data-layer="滤镜组件">'+(expanded?filterStrip():'')+btn('◉ '+filters[filter]+' · 滤镜','filters')+'</div>')+
 '<div class="camtools" data-layer="快门相册翻转变焦"><div class="capture-row">'+btn(captured?'<img alt="最新照片" src="'+image()+'">':'','gallery','thumb')+btn('','shutter','shutter')+icon('flip','flip','切换前后摄像头')+'</div><div class="zoom"><label><output id="zoom-value">'+zoom.toFixed(1)+'×</output><input aria-label="变焦倍率" id="zoom" type="range" min="1" max="3" step=".1" value="'+zoom+'"></label>'+btn('PRO','pro','probutton')+'</div></div>'+nav('camera')+'</div>';
}
function filterStrip(){return '<div class="filterstrip">'+filters.map((f,i)=>btn('<img src="'+image()+'" style="filter:'+effects[i]+'" alt="'+f+'封面">'+f,'filter:'+i,i===filter?'selected':'')).join('')+route('<img src="'+image()+'" alt="自定义滤镜">＋ 自定义','filter-editor')+data.filters.map(f=>btn(escape(f.name),'custom-filter')).join('')+'</div>'}
function proPanel(){
 const keys=['EV','ISO','快门','白平衡','对焦','变焦'];
 const options={ISO:['AUTO','100','200','400','800','1600'],'快门':['AUTO','1/15','1/30','1/60','1/125','1/250','1/500'],'白平衡':['AUTO','日光','阴天','白炽灯','荧光灯'],'变焦':['1.0','1.5','2.0','3.0']};
 return '<div class="pro-panel" data-layer="Pro手动参数"><div class="prochips">'+keys.map(k=>btn(k,'pro-control:'+k,k===proControl?'selected':'')).join('')+btn('×','pro')+'</div>'+ (options[proControl]?'<div class="prochips" style="margin-top:12px">'+options[proControl].map(v=>btn(v,'pro-value:'+v)).join('')+'</div>':'<input type="range" aria-label="'+proControl+'" min="'+(proControl==='EV'?-2:0)+'" max="'+(proControl==='EV'?2:10)+'" step=".1" value="0" data-pro-range><output class="muted">0</output>')+'</div>';
}
function card(p){return '<article class="post" data-layer="动态卡片"><div class="row">'+route('<img class="avatar" src="'+avatar(p.author)+'" alt="'+escape(p.author)+'">','user-profile?user='+encodeURIComponent(p.author))+'<strong>'+escape(p.author)+'</strong><span class="spacer"></span>'+btn(data.following.includes(p.author)?'已关注':'+ 关注','follow:'+p.author,'quiet')+'</div><button class="icon full" data-action="post:'+p.id+'" style="padding:0"><img class="photo" src="'+image(p.image)+'" alt="'+escape(p.title)+'"></button><h4>'+escape(p.title)+'</h4><p class="muted">'+escape(p.body)+'</p><div class="actions">'+btn(svg('heart')+' '+(p.likes+Number(data.likes.includes(p.id))),'like:'+p.id,data.likes.includes(p.id)?'on':'')+btn(svg('comment')+' 评论','comments:'+p.id)+btn(svg('bookmark')+(data.favorites.includes(p.id)?'已收藏':'收藏'),'favorite:'+p.id,data.favorites.includes(p.id)?'on':'')+'</div></article>'}
function resource(){return '<div class="resource" data-layer="滤镜分享卡"><strong>🎨 雨后电影感</strong><p>冷色阴影 · 柔和高光 · 轻微暗角</p>'+btn(data.filters.some(f=>f.name==='雨后电影感')?'已导入本地':'一键导入本地滤镜','import-filter','primary')+'<p class="muted">网页只保存演示参数，不写入 Android 本地。</p></div>'}
function feed(){
 const following=id==='following-feed';let list=following?posts().filter(p=>data.following.includes(p.author)):posts();
 if(state==='未关注'||state==='空列表')list=[];
 return '<div class="screen"><div class="tabs" data-layer="社区顶部Tab">'+route('关注','following-feed',following?'selected':'')+route('为你推荐','community',id==='community'?'selected':'')+route('消息','messages',id==='messages'?'selected':'')+'</div>'+ (id==='messages'?empty('暂无消息<br>互动消息会显示在这里'):list.length?(!following?'<div class="bodypad">'+resource()+'</div>':'')+list.map(card).join(''):empty(following?'还没有关注动态<br>去推荐页关注喜欢的摄影师':'暂无作品'))+'</div>'+nav('community');
}
function comments(){
 const p=activePost();const list=state==='空评论'?[]:[{author:'Daniel',text:'光线和留白都很舒服。'},...data.comments.filter(c=>c.post===p.id)];
 return '<div class="screen">'+card(p)+'</div><div class="modalback" data-layer="评论遮罩"><section class="sheet" data-layer="评论弹层"><div class="handle"></div><div class="row between"><h3>评论 '+list.length+'</h3>'+route('关闭','community')+'</div>'+list.map(c=>'<div class="comment row"><img class="avatar" src="'+avatar(c.author)+'" alt=""><div><strong>'+escape(c.author)+'</strong><p>'+escape(c.text)+'</p></div></div>').join('')+(list.length?'':empty('还没有评论，来聊聊吧'))+resource()+'<form id="comment-form" class="composer"><input name="text" placeholder="说说你的想法…" required aria-label="评论内容"><button class="primary">发送</button></form>'+btn('分享我的自定义滤镜','share-filter','full quiet')+'</section></div>';
}
function profile(){
 let name=id==='profile'?data.name:state;
 const own=id==='profile';const tab=own?state:otherTab;
 let list=posts().filter(p=>p.author===name);
 if(tab==='点赞')list=own?posts().filter(p=>data.likes.includes(p.id)):[];
 if(tab==='收藏')list=own?posts().filter(p=>data.favorites.includes(p.id)):[];
 const avatarSrc=own&&data.avatar?data.avatar:avatar(name);
 return '<div class="screen"><div class="top">'+(own?'<span></span>':route('← 返回','community'))+route(svg('settings'),'settings','icon')+'</div><div class="bodypad"><div class="row" data-layer="头像昵称简介"><img class="avatar heroavatar" src="'+escape(avatarSrc)+'" alt="头像"><div><h2>'+escape(name)+'</h2><p class="muted">'+escape(own?data.bio:'热爱光影与日常 · GenWow 摄影爱好者')+'</p></div></div><div class="stats" data-layer="作品粉丝关注"><span><strong>'+posts().filter(p=>p.author===name).length+'</strong>作品</span>'+route('<strong>'+(own?0:Number(data.following.includes(name)))+'</strong>粉丝','followers')+route('<strong>'+(own?data.following.length:0)+'</strong>关注','following')+'</div>'+ (own?route('编辑资料','edit-profile','primary full row'):btn(data.following.includes(name)?'已关注':'+ 关注','follow:'+name,'primary full'))+'<div class="tabs" data-layer="作品点赞收藏评论">'+['作品','点赞','收藏','评论'].map(t=>btn(t,'profile-tab:'+t,t===tab?'selected':'')).join('')+'</div><div data-layer="个人内容列表">'+(tab==='评论'?data.comments.map(c=>'<div class="card">'+escape(c.text)+'</div>').join('')||empty('还没有评论'):list.length?'<div class="grid">'+list.map(p=>'<div class="card">'+btn('<img src="'+image(p.image)+'" alt="'+escape(p.title)+'">','post:'+p.id,'icon full')+'<p>'+escape(p.title)+'</p>'+btn('♡ '+(p.likes+Number(data.likes.includes(p.id))),'like:'+p.id,data.likes.includes(p.id)?'primary':'quiet')+'</div>').join('')+'</div>':empty('暂无'+tab))+'</div></div></div>'+nav('profile');
}
function userList(){
 let list=id==='following'?data.following:[];if(state==='空列表')list=[];
 return '<div class="screen">'+top(current.title,'profile')+'<div class="bodypad" data-layer="用户列表">'+(list.length?list.map(n=>'<div class="card row"><img class="avatar" src="'+avatar(n)+'" alt="">'+route(escape(n),'user-profile')+'<span class="spacer"></span>'+btn(data.following.includes(n)?'已关注':'+ 关注','follow:'+n,'quiet')+'</div>').join(''):empty('还没有'+(id==='following'?'关注':'粉丝')))+'<p class="note">验收数据与社区操作联动：可先在社区关注 Helena，再回此页查看。演示账号没有模拟粉丝。</p></div></div>';
}
function settings(){
 const groups={'辅助线与引导':['显示三分线网格','显示主体框','显示目标区','显示水平仪'],'拍摄与相册':['安全自动变焦','保存原图到相册','拍照音效','Pro 手动模式']};
 return '<div class="screen">'+top('设置')+'<div class="bodypad">'+Object.entries(groups).map(([title,items])=>'<section class="card" data-layer="'+title+'"><h4>'+title+'</h4>'+(title==='辅助线与引导'?'<label class="range-row">辅助线透明度<input aria-label="辅助线透明度" type="range" min="0" max="100" value="'+(data.settings.opacity??70)+'" data-setting="opacity"></label>':'')+items.map((n,i)=>'<label class="setting switchlabel"><div><h5>'+n+'</h5><span class="muted">'+(n==='安全自动变焦'?'真实人物场景 · 最高 1.8×':'调整此项的演示开关')+'</span></div><input type="checkbox" data-setting="'+n+'" '+((data.settings[n]??!['拍照音效','Pro 手动模式'].includes(n))?'checked':'')+'></label>').join('')+'</section>').join('')+'<div class="card" data-layer="关于"><h4>关于</h4><p class="muted">帧好 GenWow · 浏览器 UI 验收预览</p></div>'+route('退出登录','login','primary full row')+'</div></div>';
}
function editProfile(){return '<div class="screen">'+top('编辑资料','profile')+'<form class="bodypad" id="profile-form" data-layer="资料编辑表单"><label class="field">头像<input type="file" id="avatar-upload" accept="image/*"></label>'+field('昵称','name','text',data.name)+'<label class="field">个人简介<textarea name="bio">'+escape(data.bio)+'</textarea></label>'+field('个人链接','link','url','https://example.com')+'<button class="primary full">保存资料</button></form></div>'}
function auth(){
 if(id==='splash')return '<div class="screen splash"><div data-layer="启动品牌"><div class="logo">帧好<small>GenWow</small></div><p>让每一帧，都恰到好处。</p>'+route('进入相机','camera','primary')+'</div></div>';
 const register=id==='register',forgot=id==='forgot-password';
 return '<div class="screen"><form class="auth" id="auth-form" data-layer="账号表单"><div class="logo">帧好<small>GenWow</small></div><h2>'+ (register?'创建新账号':forgot?'找回密码':'欢迎回来')+'</h2>'+(register?field('新建用户名（2–20 字符）','username'):'')+field('手机号 / 邮箱','account')+(state==='验证码登录'||register||forgot?field('6 位验证码（预览无需验证）','code')+btn('获取验证码','code','quiet'):'')+(state!=='验证码登录'?field(forgot?'设置新密码':'密码（至少 6 位）','password','password'):'')+(state==='错误提示'?'<p style="color:#c44">请检查账号和密码后重试</p>':'')+(!register&&!forgot?'<div class="links">'+btn(state==='验证码登录'?'密码登录':'验证码登录','auth-mode','quiet')+route('忘记密码？','forgot-password')+'</div>':'')+'<button class="primary full">'+(register?'注册':forgot?'重置密码':'登录')+'</button><div class="links">'+route(register?'已有账号？登录':'创建账号',register?'login':'register')+route('游客体验','camera')+'</div><footer>仅模拟页面跳转；不会发送验证码或创建真实账号。</footer></form></div>';
}
const adjustments=[['曝光',-2,2,.1],['对比度',-100,100,1],['高光',-100,100,1],['阴影',-100,100,1],['饱和度',-100,100,1],['色温',-100,100,1],['色调',-100,100,1],['红色',-100,100,1],['绿色',-100,100,1],['蓝色',-100,100,1],['锐化',0,100,1],['暗角',0,100,1]];
function editor(){return '<div class="screen dark">'+top('自定义滤镜','filters',btn('保存','save-filter','quiet'))+'<img class="editor-photo" src="'+image()+'" alt="同一照片滤镜预览" data-layer="滤镜效果预览"><div class="bodypad" data-layer="滤镜调参"><label class="field">滤镜名称<input id="filter-name" value="我的滤镜" maxlength="20"></label><p class="muted">Web 颜色预览为近似效果；锐化、暗角等参数保留用于交互验收，最终成片请在 Android 核对。</p>'+adjustments.map(([n,min,max,step])=>'<div class="range-row"><label>'+n+'<output>0</output></label><input type="range" data-adjust="'+n+'" aria-label="'+n+'" min="'+min+'" max="'+max+'" step="'+step+'" value="0"></div>').join('')+'</div></div>'}
function result(){return camera()+'<div class="modalback" data-layer="保存结果遮罩"><div class="sheet result" data-layer="保存结果操作"><div class="handle"></div><img class="photo" src="'+image()+'" style="filter:'+effects[filter]+'" alt="刚拍摄的照片"><h2 style="color:var(--green)">✓ 照片已保存</h2><p class="muted">辅助构图 · 日常（演示）</p><div class="row">'+route('重拍','camera','primary')+route('发布到社区','publish','primary')+'</div>'+route('完成并返回相机','camera','icon full')+'</div></div>'}
function publish(){return camera()+'<div class="modalback" data-layer="发布遮罩"><form id="publish-form" class="sheet" data-layer="发布表单"><div class="handle"></div><h3>发布到社区</h3><img class="photo" style="height:220px" src="'+image()+'" alt="待发布照片"><label class="field">添加描述<textarea name="body" required placeholder="记录这一刻的故事…"></textarea></label><div class="row">'+route('取消','capture-result','quiet')+'<span class="spacer"></span><button class="primary">发布</button></div></form></div>'}
function render(){
 clearTimeout(hintTimer);
 if(id==='pro'&&current.states.includes(state))proControl=state;
 let html;
 if(['camera','filters','pro'].includes(id))html=camera();
 else if(['community','following-feed','messages'].includes(id))html=feed();
 else if(['profile','user-profile'].includes(id))html=profile();
 else if(['followers','following'].includes(id))html=userList();
 else if(id==='comments')html=comments();
 else if(id==='post')html='<div class="screen">'+top('帖子详情','community')+card(activePost())+resource()+route('查看所有评论','comments','primary row')+'</div>';
 else if(id==='filter-resource')html='<div class="screen">'+top('社区滤镜资源','community')+'<div class="bodypad"><img class="photo" src="'+image('community_rainy_street')+'" alt="滤镜示例">'+resource()+route('打开滤镜编辑器','filter-editor','primary row')+'</div></div>';
 else if(id==='settings')html=settings();
 else if(id==='edit-profile')html=editProfile();
 else if(['splash','login','register','forgot-password'].includes(id))html=auth();
 else if(id==='filter-editor')html=editor();
 else if(id==='capture-result')html=result();
 else if(id==='publish')html=publish();
 else html='<div class="screen dark">'+top('系统相册入口')+'<div class="bodypad"><p class="note" style="color:#333">'+escape(current.note)+'</p>'+(state==='空相册'?empty('暂无照片'):'<img class="photo" src="'+image()+'" alt="示例照片">')+'</div></div>';
 $('#app').innerHTML=html;
 if(id==='login'){
  const third=document.createElement('section');third.className='card';third.dataset.layer='第三方登录占位';
  third.innerHTML='<p class="muted">第三方账号登录</p><div class="row">'+btn('微信','third-party','quiet')+btn('QQ','third-party','quiet')+'</div><p class="muted">当前 App 图标占位，未接入登录 SDK。</p>';
  $('#auth-form').insertBefore(third,$('#auth-form footer'));
 }
 if(id==='camera'&&state==='拍摄失败'){
  const retry=document.createElement('div');retry.className='row';retry.style.pointerEvents='auto';
  retry.innerHTML=btn('取消','permit','quiet')+btn('重试','capture','primary');$('.guide').append(retry);
 }
 $('#page-title').textContent=current.title;
 $('#page-note').textContent=current.note;
 $('#fidelity').textContent='人工维护的 HTML 交互镜像，复用仓库图片和配色。不是 Compose 自动渲染，不能作为相机算法、Room 或权限的运行验收。';
 $('#sources').innerHTML=current.source.map(s=>'<a target="_blank" rel="noopener" href="'+(published?'https://github.com/chiiiiiiing/AI-camera/blob/main/aicamera-app/app/src/main/java/com/example/aicamera/'+s:site('/source?file='+encodeURIComponent(s)))+'">'+escape(s)+'</a>').join('');
 updateLayers();
 if(state==='超时提示')hintTimer=setTimeout(()=>{const t=$('.take-now');if(t)t.remove()},4000);
}
function updateLayers(){
 const names=[...new Set([...document.querySelectorAll('[data-layer]')].map(e=>e.dataset.layer))];
 $('#layers').innerHTML=names.map(n=>'<label class="check"><input type="checkbox" data-layer-toggle="'+n+'" '+(!hidden.has(n)?'checked':'')+'>'+n+'</label>').join('');
 document.querySelectorAll('[data-layer]').forEach(e=>e.classList.toggle('layer-hidden',hidden.has(e.dataset.layer)));
}
function toggle(key,v){data[key]=data[key].includes(v)?data[key].filter(x=>x!==v):[...data[key],v];save();render()}
document.addEventListener('click',e=>{
 const t=e.target.closest('[data-action]');if(!t)return;
 const [action,...rest]=t.dataset.action.split(':');const value=rest.join(':');
 switch(action){
 case 'mode':mode=value;render();break;
 case 'filters':expanded=!expanded;proOpen=false;render();break;
 case 'pro':proOpen=!proOpen;expanded=false;render();break;
 case 'filter':filter=Number(value);render();break;
 case 'flip':front=!front;render();toast(front?'前置摄像头 · 静态镜像模拟':'后置摄像头 · 静态模拟');break;
 case 'focus':setState('对焦曝光');break;
 case 'permit':setState('预览');break;
 case 'gallery':goto('gallery');break;
 case 'pro-control':proControl=value;if(id==='pro')setState(value);else render();break;
 case 'pro-value':if(proControl==='变焦'){zoom=Number(value);render()}else{t.parentElement.querySelectorAll('button').forEach(b=>b.classList.remove('selected'));t.classList.add('selected');toast('已选择 '+proControl+' '+value+'（硬件效果需真机）')}break;
 case 'shutter':if(['预览','默认','对焦曝光'].includes(state)){setState('分析中');clearTimeout(captureTimer);captureTimer=setTimeout(()=>setState('旋转'),900)}else{clearTimeout(captureTimer);captured=true;goto('capture-result')}break;
 case 'capture':captured=true;goto('capture-result');break;
 case 'like':toggle('likes',value);break;
 case 'favorite':toggle('favorites',value);break;
 case 'follow':toggle('following',value);break;
 case 'post':sessionStorage.setItem('genwow-review-post',value);goto('post');break;
 case 'comments':sessionStorage.setItem('genwow-review-post',value);goto('comments');break;
 case 'profile-tab':if(id==='profile')setState(value);else{otherTab=value;render()}break;
 case 'import-filter':if(!data.filters.some(f=>f.name==='雨后电影感'))data.filters.push({name:'雨后电影感'});save();render();toast('已导入浏览器演示滤镜库');break;
 case 'save-filter':{const name=$('#filter-name').value.trim();if(!name){toast('请输入滤镜名称');break}const params={};document.querySelectorAll('[data-adjust]').forEach(i=>params[i.dataset.adjust]=Number(i.value));data.filters.push({name,params});save();goto('filters');break}
 case 'custom-filter':toast('自定义参数已保存；实际 Android 参数导入需真机验收');break;
 case 'share-filter':if(!data.filters.length){toast('请先到自定义滤镜页保存一个滤镜');break}data.comments.push({post:activePost().id,author:data.name,text:'🎨 分享滤镜：'+data.filters.at(-1).name});save();render();break;
 case 'auth-mode':setState(state==='验证码登录'?'密码登录':'验证码登录');break;
 case 'code':toast('验证码入口交互演示，不发送短信');break;
 case 'third-party':toast('与当前 App 一致：此入口尚未接入第三方登录');break;
 }
});
document.addEventListener('submit',e=>{
 e.preventDefault();const form=e.target;const fields=new FormData(form);
 if(form.id==='comment-form'){const text=fields.get('text').trim();if(!text)return;data.comments.push({post:activePost().id,author:data.name,text});save();state='有评论';render()}
 if(form.id==='publish-form'){data.posts.unshift({id:Date.now().toString(),author:data.name,title:fields.get('body').slice(0,20),body:fields.get('body'),image:'community_cafe_portrait',likes:0});save();goto('community')}
 if(form.id==='profile-form'){const name=fields.get('name').trim();if(name.length<2||name.length>20){toast('昵称需要 2–20 个字符');return}data.posts.forEach(p=>{if(p.author===data.name)p.author=name});data.name=name;data.bio=fields.get('bio');save();goto('profile')}
 if(form.id==='auth-form'){if(fields.has('password')&&fields.get('password').length<6){toast('密码至少 6 位');return}goto(id==='forgot-password'?'login':'camera')}
});
document.addEventListener('input',e=>{
 const t=e.target;
 if(t.id==='zoom'){zoom=Number(t.value);$('#zoom-value').textContent=zoom.toFixed(1)+'×';$('.preview img').style.transform='scale('+zoom+')'+(front?' scaleX(-1)':'')}
 if(t.hasAttribute('data-ev')){t.nextElementSibling.textContent=t.value+' EV';$('.preview img').style.filter=effects[filter]+' brightness('+Math.pow(2,Number(t.value))+')'}
 if(t.hasAttribute('data-pro-range'))t.nextElementSibling.textContent=t.value;
 if(t.dataset.adjust){t.previousElementSibling.querySelector('output').textContent=t.value;const get=n=>Number(document.querySelector('[data-adjust="'+n+'"]').value);$('.editor-photo').style.filter='brightness('+Math.pow(2,get('曝光'))+') contrast('+(1+get('对比度')/100)+') saturate('+(1+get('饱和度')/100)+') sepia('+Math.abs(get('色温'))/200+') hue-rotate('+get('色调')/3+'deg)'}
 if(t.dataset.setting){data.settings[t.dataset.setting]=t.type==='checkbox'?t.checked:Number(t.value);save()}
});
document.addEventListener('change',e=>{
 const t=e.target;
 if(t.dataset.layerToggle){t.checked?hidden.delete(t.dataset.layerToggle):hidden.add(t.dataset.layerToggle);updateLayers()}
 if(t.id==='avatar-upload'&&t.files[0]){if(t.files[0].size>2000000){toast('演示头像请小于 2MB');return}const reader=new FileReader();reader.onload=()=>{data.avatar=reader.result;save();toast('头像已导入，保存资料后查看')};reader.readAsDataURL(t.files[0])}
});
let lastGroup='';
$('#catalog').innerHTML=pages.map(p=>{let head=p.group===lastGroup?'':'<div class="group">'+p.group+'</div>';lastGroup=p.group;return head+'<a href="'+site('/'+p.id+'.html')+'" class="'+(p.id===id?'active':'')+'">'+p.title+'<small>'+ (p.states.length>1?p.states.length+' 状态':'')+'</small></a>'}).join('');
$('#scenario').innerHTML=current.states.map(s=>'<option>'+s+'</option>').join('');$('#scenario').value=state;
$('#scenario').onchange=e=>setState(e.target.value);
const viewport=new URLSearchParams(location.search).get('viewport')||'390';$('#viewport').value=viewport;
function resize(){const v=$('#viewport').value;const device=$('#device');device.classList.toggle('landscape',v==='landscape');device.style.setProperty('--w',(v==='landscape'?844:Number(v))+'px');device.style.setProperty('--h',(v==='landscape'?390:v==='360'?800:v==='430'?932:844)+'px');const u=new URL(location.href);u.searchParams.set('viewport',v);history.replaceState({},'',u)}
$('#viewport').onchange=resize;resize();
$('#outline').onchange=e=>$('#app').classList.toggle('outlines',e.target.checked);
$('#copy-link').onclick=async()=>{try{await navigator.clipboard.writeText(location.href);toast('验收链接已复制')}catch{toast('请复制浏览器地址栏链接')}};
$('#reset').onclick=()=>{if(confirm('重置浏览器的演示互动数据？验收意见会保留。')){data=structuredClone(initial);save();render()}};
const feedbackKey=()=>id+'|'+state;
function loadFeedback(){const all=read('genwow-review-feedback',{}),item=all[feedbackKey()]||{};$('#feedback').value=item.text||'';$('#verdict').value=item.verdict||'待验收'}
$('#save-feedback').onclick=()=>{const all=read('genwow-review-feedback',{});all[feedbackKey()]={page:id,title:current.title,state,viewport:$('#viewport').value,verdict:$('#verdict').value,text:$('#feedback').value,revision,date:new Date().toISOString()};localStorage.setItem('genwow-review-feedback',JSON.stringify(all));toast('本页验收意见已保存')};
$('#export-feedback').onclick=()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(read('genwow-review-feedback',{}),null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='GenWow-验收意见-'+new Date().toISOString().slice(0,10)+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};
render();loadFeedback();
let webVersion='';
async function poll(){try{const endpoint=published?site('/review-version.json'):site('/api/revision');const r=await fetch(endpoint+'?t='+Date.now(),{cache:'no-store'});if(!r.ok)throw Error();revision=await r.json();if(webVersion&&webVersion!==revision.web){const u=new URL(location.href);u.searchParams.set('revision',revision.web);location.replace(u);return}webVersion=revision.web;$('#connection').textContent=published?'● 在线版本已连接':'● 本地服务已连接';const baseline=await fetch(site('/baseline.json'),{cache:'no-store'}).then(r=>r.ok?r.json():{});const changed=baseline.android&&baseline.android!==revision.android;$('#source-status').textContent=changed?'Android 源码已变化：HTML 需由开发同步后重新验收。':'预览按所列源文件人工复现。修改 HTML 自动刷新；Android 改动不会自动转换成网页。'}catch{$('#connection').textContent=published?'在线版本暂不可用':'服务断开 · 请重启验收站'} }
poll();setInterval(poll,published?10000:1800);
window.addEventListener('storage',e=>{if(e.key==='genwow-review-data'){data=read(e.key,structuredClone(initial));render()}});
