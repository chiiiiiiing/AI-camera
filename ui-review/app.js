const S='ui/screen/', C='ui/components/';
const page=(id,title,group,source,states=['默认'],note='按当前 Compose 页面复现布局与交互；Web 字体和控件尺寸需与真机二次核对。')=>({id,title,group,source:Array.isArray(source)?source:[source],states,note});
export const pages=[
 page('camera','相机主页面','01 / 相机',[S+'CameraScreen.kt',C+'CaptureWorkflowOverlay.kt',C+'FocusExposureOverlay.kt'],['预览','分析中','旋转','水平已对齐','俯仰','水平转向','移动','主体已对齐','稳定验证','拍摄中','无主体','倒置','超时提示','对焦曝光','权限拒绝','拍摄失败']),
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
const initial={likes:[],favorites:[],following:[],comments:[],posts:[],name:'摄影师',bio:'用 AI 构图，记录每一帧美好',avatar:'',settings:{},filters:[],activeCustomId:null};
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
 {id:'4',author:'Daniel',image:'feed_tulips',title:'清晨的第一缕光穿过花丛',body:'低角度让花朵与天空对话。',likes:21},
 {id:'5',author:'Helena',image:'gallery_watermelon',title:'市集的色彩',body:'用对角线把视觉重心引向最鲜亮的那一抹。',likes:29},
 {id:'6',author:'Daniel',image:'gallery_flowers',title:'谁说俯拍是美食唯一解？',body:'45度角让层次感翻倍。',likes:24},
 {id:'7',author:'Helena',image:'community_forest_macro',title:'雨后森林的蘑菇王国',body:'逆光拍摄让菌褶透出琥珀色。',likes:20}
];
const seedComments={
 '1':['构图很棒！','学习了','颜色真好看'],
 '2':['城市真的太美了','好想也拍一张'],
 '3':['倒影构图绝了','🎨 滤镜资源｜雨后电影感'],
 '4':['花和光线的搭配太绝了！','低角度学到了'],
 '5':[],
 '6':['45°真的不一样','下次试试','层次感好强','已收藏'],
 '7':['蘑菇好可爱','逆光太厉害了']
};
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
const btn=(label,action,cls='')=>'<button type="button" class="'+cls+'" data-action="'+action+'">'+label+'</button>';
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
 const activeCustom=(data.filters||[]).find(f=>f.id===data.activeCustomId);
 const denied=state==='权限拒绝';
 const guidance=['分析中','旋转','水平已对齐','俯仰','水平转向','移动','主体已对齐','稳定验证','拍摄中','无主体','倒置','拍摄失败'];
 const hints={'分析中':['正在分析构图','请保持画面稳定'],'旋转':['调整水平','逆时针旋转手机，让粉线贴合白色虚线'],'水平已对齐':['水平已对齐','绿线表示这一项已完成'],'俯仰':['调整俯仰','抬高手机镜头，让箭头回到中心'],'水平转向':['调整方向','向左转动手机，让目标回到中心'],'移动':['调整主体位置','沿箭头移动手机，让粉框与白框重合'],'主体已对齐':['主体已对齐','保持绿色框内的构图'],'稳定验证':['构图达标','保持不动 · 即将拍摄'],'拍摄中':['正在拍摄','请保持稳定，正在处理照片'],'无主体':['未识别到主体','请将人物放入画面'],'倒置':['手机拿反了','请翻转手机后继续拍摄'],'拍摄失败':['拍摄失败','演示保存失败状态']};
 const aligned=['水平已对齐','主体已对齐','稳定验证'].includes(state);
 let art='';
 if(['旋转','水平已对齐'].includes(state))art='<div class="axis-card roll"><div class="axis-caption">画面水平线</div><div class="reference-line"></div><div class="moving-line"></div><div class="axis-arrow">'+(aligned?'✓':'↶')+'</div><small>白色虚线 = 目标水平 · '+(aligned?'绿色实线 = 已对齐':'粉色实线 = 当前角度')+'</small></div>';
 if(state==='俯仰')art='<div class="axis-card pitch"><div class="axis-caption">镜头俯仰</div><div class="pitch-track"><span class="pitch-target">目标</span><span class="pitch-current">↑</span></div><small>向上抬高镜头 · 让粉色箭头靠近白色目标</small></div>';
 if(state==='水平转向')art='<div class="axis-card yaw"><div class="axis-caption">镜头朝向</div><div class="yaw-track"><span class="yaw-current">←</span><span class="yaw-target">目标</span></div><small>向左转动手机 · 不必平移身体</small></div>';
 if(['移动','主体已对齐'].includes(state))art='<div class="axis-card position"><div class="axis-caption">主体位置</div><div class="position-track"><span class="subject-marker">人物</span><span class="position-arrow">'+(aligned?'✓':'→')+'</span><span class="target-marker">目标</span></div><small>粉框 = 当前主体 · 白框 = 构图目标</small></div>';
 if(state==='稳定验证')art='<div class="cross-target">✓</div>';
 return '<div class="camera">'+
 '<div class="preview" data-layer="相机预览占位" data-action="focus"><img alt="静态相机占位照片" src="'+image()+'" style="filter:'+(activeCustom?'none':effects[filter])+';transform:scale('+zoom+')'+(front?' scaleX(-1)':'')+'">'+((data.settings['显示三分线网格']??true)&&guidance.includes(state)?'<div class="preview-grid" data-layer="拍摄后辅助三分线" style="opacity:'+((data.settings.opacity??100)/100)+'"><i></i><i></i><i></i><i></i></div>':'')+(['移动','主体已对齐','稳定验证'].includes(state)&&data.settings['显示主体框']!==false?'<div class="preview-subject '+(aligned?'aligned':'')+'" data-layer="真实主体框"></div>':'')+(['移动','主体已对齐','稳定验证'].includes(state)&&data.settings['显示目标区']!==false?'<div class="preview-target" data-layer="构图目标区"></div>':'')+'</div>'+
 (denied?'<div class="guide">'+btn('模拟授予权限','permit','primary')+'</div>':'')+
 '<div class="camtop" data-layer="顶部构图模式">'+[['coffee','静物'],['mountain','风景'],['person','人像']].map(([i,m])=>icon(i,'mode:'+m,m+'构图模式',mode===m?'selected':'')).join('')+'<span class="spacer"></span>'+route(svg('settings'),'settings','icon')+'</div>'+
 (guidance.includes(state)?'<div class="progress" data-layer="工作流进度"><div class="dots">● ━ ● ━ ●</div>分析 · 调整 · 拍摄</div><div class="guide '+(aligned?'good':'')+'" data-layer="旋转俯仰移动引导">'+art+'<h3>'+hints[state][0]+'</h3><p>'+hints[state][1]+'</p></div>':'<div class="ai" data-layer="引擎状态提示">● AI 已就绪 · 预览模拟</div>')+
 (state==='对焦曝光'?'<div class="focusbox" data-layer="对焦与曝光"><input aria-label="曝光补偿" type="range" min="-2" max="2" step=".1" value="0" data-ev><output>0 EV</output></div>':'')+
 (state==='超时提示'?btn('按当前画面立即拍摄','capture','take-now'):'')+
 (proOpen?proPanel():'<div class="filterdock" data-layer="滤镜组件">'+(expanded?filterStrip()+'<small class="filter-hint">实时取景仅显示基础色彩；高光、阴影、锐化、暗角在成片中完整应用。</small>':'')+btn('◉ '+(activeCustom?escape(activeCustom.name):filters[filter])+' · 滤镜','filters')+'</div>')+
 '<div class="camtools" data-layer="快门相册翻转变焦"><div class="capture-row">'+btn(captured?'<img alt="最新照片" src="'+image()+'">':'','gallery','thumb')+btn('','shutter','shutter')+icon('flip','flip','切换前后摄像头')+'</div><div class="zoom"><label><output id="zoom-value">'+zoom.toFixed(1)+'×</output><input aria-label="变焦倍率" id="zoom" type="range" min="1" max="3" step=".1" value="'+zoom+'"></label>'+btn('PRO','pro','probutton')+'</div></div>'+nav('camera')+'</div>';
}
function filterStrip(){return '<div class="filterstrip">'+filters.map((f,i)=>btn('<img src="'+image()+'" style="filter:'+effects[i]+'" alt="'+f+'封面">'+f,'filter:'+i,!data.activeCustomId&&i===filter?'selected':'')).join('')+route('<img src="'+image()+'" alt="自定义滤镜">＋ 自定义','filter-editor')+(data.filters||[]).map(f=>btn('<img src="'+image()+'" data-custom-cover="'+escape(f.id||f.name)+'" alt="'+escape(f.name)+'封面">'+escape(f.name),'custom-filter:'+(f.id||f.name),data.activeCustomId===(f.id||f.name)?'selected':'')).join('')+'</div>'}
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
 const p=activePost();const list=state==='空评论'?[]:[...(seedComments[p.id]||[]).map(text=>({author:p.author==='Helena'?'Daniel':'Helena',text})),...data.comments.filter(c=>c.post===p.id)];
 return '<div class="screen">'+card(p)+'</div><div class="modalback" data-layer="评论遮罩"><section class="sheet" data-layer="评论弹层"><div class="handle"></div><div class="row between"><h3>评论 '+list.length+'</h3>'+route('关闭','community')+'</div>'+list.map(c=>'<div class="comment row"><img class="avatar" src="'+avatar(c.author)+'" alt=""><div><strong>'+escape(c.author)+'</strong><p>'+escape(c.text)+'</p>'+(c.text.includes('🎨 滤镜资源')?btn('一键导入滤镜','import-filter','quiet'):'')+'</div></div>').join('')+(list.length?'':empty('还没有评论，来聊聊吧'))+'<form id="comment-form" class="composer"><input name="text" placeholder="说说你的想法…" required aria-label="评论内容"><button class="primary">发送</button></form>'+btn('分享我的自定义滤镜','share-filter','full quiet')+'</section></div>';
}
function postDetail(){
 const p=activePost(),list=[...(seedComments[p.id]||[]).map(text=>({author:p.author==='Helena'?'Daniel':'Helena',text})),...data.comments.filter(c=>c.post===p.id)];
 return '<div class="screen post-detail"><div class="top" data-layer="帖子标题栏">'+route('← 返回','community')+'<strong>'+escape(p.author)+'</strong>'+btn(data.following.includes(p.author)?'已关注':'+ 关注','follow:'+p.author,'quiet')+'</div><img class="detail-photo" src="'+image(p.image)+'" alt="'+escape(p.title)+'"><div class="bodypad"><div class="row">'+route('<img class="avatar" src="'+avatar(p.author)+'" alt="">','user-profile?user='+encodeURIComponent(p.author))+'<strong>'+escape(p.author)+'</strong><span class="spacer"></span><small>刚刚</small></div><p>'+escape(p.title)+' | '+escape(p.body)+'</p><div class="actions">'+btn('赞 '+(p.likes+Number(data.likes.includes(p.id))),'like:'+p.id,data.likes.includes(p.id)?'on':'')+btn(data.favorites.includes(p.id)?'已收藏':'收藏','favorite:'+p.id,data.favorites.includes(p.id)?'on':'')+route('评论 '+list.length,'comments')+'</div><h4>评论 ('+list.length+')</h4>'+(list.length?list.map(c=>'<div class="comment row"><img class="avatar" src="'+avatar(c.author)+'" alt=""><div><strong>'+escape(c.author)+'</strong><p>'+escape(c.text)+'</p>'+(c.text.includes('🎨 滤镜资源')?btn('一键导入滤镜','import-filter','quiet'):'')+'</div></div>').join(''):empty('暂无评论，来说两句吧'))+'</div><form id="comment-form" class="composer detail-composer"><input name="text" placeholder="写评论..." required aria-label="评论内容"><button class="quiet">发送</button></form></div>';
}
function profile(){
 let name=id==='profile'?data.name:state;
 const own=id==='profile';const tab=own?state:otherTab;
 let list=posts().filter(p=>p.author===name);
 if(tab==='点赞')list=own?posts().filter(p=>data.likes.includes(p.id)):[];
 if(tab==='收藏')list=own?posts().filter(p=>data.favorites.includes(p.id)):[];
 const avatarSrc=own&&data.avatar?data.avatar:avatar(name);
 const bio=own?data.bio:name==='Helena'?'AI 构图助手 Helena | 热爱光影与咖啡':name==='Daniel'?'AI 构图助手 Daniel | 城市与风景观察者':'AI 构图爱好者';
 return '<div class="screen"><div class="top">'+(own?'<span></span>':route('← 返回','community'))+(own?route(svg('settings'),'settings','icon'):'<span></span>')+'</div><div class="bodypad"><div class="row" data-layer="头像昵称简介"><img class="avatar heroavatar" src="'+escape(avatarSrc)+'" alt="头像"><div><h2>'+escape(name)+'</h2><p class="muted">'+escape(bio)+'</p></div></div><div class="stats" data-layer="作品粉丝关注"><span><strong>'+posts().filter(p=>p.author===name).length+'</strong>作品</span>'+route('<strong>'+(own?0:Number(data.following.includes(name)))+'</strong>粉丝','followers')+route('<strong>'+(own?data.following.length:0)+'</strong>关注','following')+'</div>'+ (own?route('编辑资料','edit-profile','quiet full row'):btn(data.following.includes(name)?'已关注':'+ 关注','follow:'+name,'primary full'))+'<div class="tabs" data-layer="作品点赞收藏评论">'+['作品','点赞','收藏','评论'].map(t=>btn(t,'profile-tab:'+t,t===tab?'selected':'')).join('')+'</div><div data-layer="个人内容列表">'+(tab==='评论'?data.comments.map(c=>'<div class="card">'+escape(c.text)+'</div>').join('')||empty('还没有评论'):list.length?'<div class="grid">'+list.map(p=>'<div class="card">'+btn('<img src="'+image(p.image)+'" alt="'+escape(p.title)+'">','post:'+p.id,'icon full')+'<p>'+escape(p.title)+'</p>'+btn('♡ '+(p.likes+Number(data.likes.includes(p.id))),'like:'+p.id,data.likes.includes(p.id)?'primary':'quiet')+'</div>').join('')+'</div>':empty('暂无'+tab))+'</div></div></div>'+(own?nav('profile'):'');
}
function userList(){
 let list=id==='following'?data.following:[];if(state==='空列表')list=[];
 return '<div class="screen">'+top(current.title,'profile')+'<div class="bodypad" data-layer="用户列表">'+(list.length?list.map(n=>'<div class="card row"><img class="avatar" src="'+avatar(n)+'" alt="">'+route(escape(n),'user-profile')+'<span class="spacer"></span>'+btn(data.following.includes(n)?'已关注':'+ 关注','follow:'+n,'quiet')+'</div>').join(''):empty('还没有'+(id==='following'?'关注':'粉丝')))+'<p class="note">验收数据与社区操作联动：可先在社区关注 Helena，再回此页查看。演示账号没有模拟粉丝。</p></div></div>';
}
function settings(){
 const groups={'辅助线与引导':[
  ['显示三分线网格','按下快门后显示三分线参考'],['显示主体框','检测到主体时绘制边界框'],['显示目标区','展示推荐构图位置参考框'],['显示水平仪','IMU 实时水平参考线']],
  '拍摄与相册':[
  ['安全自动变焦','仅识别到真实人物时轻微调整，最高 1.8×'],['保存原图到相册','默认开启，拍摄后直接写入系统相册'],['拍照音效','默认关闭，减少公共场景干扰'],['Pro 手动模式','开启后可手动调节 EV/ISO/白平衡/对焦']]};
 return '<div class="screen">'+top('设置')+'<div class="bodypad">'+Object.entries(groups).map(([title,items])=>'<section class="card" data-layer="'+title+'"><h4>'+title+'</h4>'+(title==='辅助线与引导'?'<label class="range-row">辅助线透明度 · '+(data.settings.opacity??100)+'%<input aria-label="辅助线透明度" type="range" min="25" max="100" value="'+(data.settings.opacity??100)+'" data-setting="opacity"><small>明亮背景下保持可见又不过度遮挡</small></label>':'')+items.map(([name,subtitle])=>'<label class="setting switchlabel"><div><h5>'+name+'</h5><span class="muted">'+subtitle+'</span></div><input type="checkbox" data-setting="'+name+'" '+((data.settings[name]??!['拍照音效','Pro 手动模式'].includes(name))?'checked':'')+'></label>').join('')+'</section>').join('')+'<div class="card" data-layer="关于"><h4>关于</h4><div class="setting">产品名称 <strong>帧好 GenWow</strong></div><div class="setting">版本 <strong>1.0.0-debug</strong></div><div class="setting">定位 <strong>AI 构图相机</strong></div></div>'+route('退出登录','login','primary full row')+'</div></div>';
}
function editProfile(){return '<div class="screen">'+top('编辑资料','profile')+'<form class="bodypad" id="profile-form" data-layer="资料编辑表单"><label class="edit-avatar"><img class="avatar heroavatar" src="'+escape(data.avatar||avatar(data.name))+'" alt="当前头像"><span>点击更换头像</span><input type="file" id="avatar-upload" accept="image/*" aria-label="更换头像"></label><section class="card" data-layer="基础信息"><h4>基础信息</h4>'+field('昵称','name','text',data.name)+field('个人简介','bio','text',data.bio)+'</section><section class="card" data-layer="展示信息"><h4>展示信息</h4>'+field('个人链接','link','url','https://')+'</section><button class="primary full">保存资料</button></form></div>'}
function auth(){
 if(id==='splash')return '<div class="screen splash"><div data-layer="启动品牌"><div class="logo">帧好<small>GenWow</small></div><p>让每一帧，都恰到好处。</p>'+route('进入相机','camera','primary')+'</div></div>';
 const register=id==='register',forgot=id==='forgot-password',codeMode=state==='验证码登录';
 const codeField=field('输入 6 位验证码','code')+btn('获取验证码','code','quiet code-button');
 const body=register?field('新建用户名（2–20 字符）','username')+field('新建密码（至少 6 位）','password','password')+field('输入手机号/邮箱','account')+codeField:
  forgot?field('输入手机号/邮箱','account')+codeField+field('设置新密码（至少 6 位）','password','password'):
  field('输入手机号/邮箱','account')+(codeMode?codeField:field('输入密码','password','password'));
 const links=register?route('已有账号？返回登录','login','auth-switch'):
  forgot?route('返回登录','login','auth-switch'):
  '<div class="links">'+route('忘记密码','forgot-password')+route('注册账号','register')+'</div>';
 return '<div class="screen"><form class="auth" id="auth-form" data-layer="账号表单"><h2>'+(register?'注册':forgot?'忘记密码':'登录')+'</h2>'+(forgot?'<p class="muted">通过验证码重置密码</p>':'')+body+(state==='错误提示'?'<p class="auth-error">请检查输入内容后重试</p>':'')+links+'<button class="primary full">'+(register?'注册':forgot?'重置密码':'登录')+'</button>'+(register||forgot?'':btn(codeMode?'密码登录':'验证码登录','auth-mode','auth-switch'))+'<div class="agreement">'+(register?'注册同意':forgot?'重置密码同意':'登录同意')+'用户协议与隐私政策</div><footer>交互仅供验收；网页不会发送验证码或创建真实账号。</footer></form></div>';
}
const adjustments=[['曝光',-2,2,.1],['对比度',-100,100,1],['高光',-100,100,1],['阴影',-100,100,1],['饱和度',-100,100,1],['色温',-100,100,1],['色调',-100,100,1],['红色',-100,100,1],['绿色',-100,100,1],['蓝色',-100,100,1],['锐化',0,100,1],['暗角',0,100,1]];
let editorOriginal=null,editorFrame=0;
function editor(){return '<div class="screen dark filter-editor-screen">'+top('自定义滤镜','filters',btn('保存','save-filter','quiet'))+'<canvas class="editor-photo" width="480" height="300" role="img" aria-label="同一照片滤镜预览" data-layer="滤镜效果预览"></canvas><div class="bodypad" data-layer="滤镜调参"><label class="field">滤镜名称<input id="filter-name" value="我的滤镜" maxlength="20"></label><h4>光线</h4>'+adjustments.slice(0,4).map(([n,min,max,step])=>'<div class="range-row"><label>'+n+'<output>0</output></label><input type="range" data-adjust="'+n+'" aria-label="'+n+'" min="'+min+'" max="'+max+'" step="'+step+'" value="0"></div>').join('')+'<h4>颜色</h4>'+adjustments.slice(4,10).map(([n,min,max,step])=>'<div class="range-row"><label>'+n+'<output>0</output></label><input type="range" data-adjust="'+n+'" aria-label="'+n+'" min="'+min+'" max="'+max+'" step="'+step+'" value="0"></div>').join('')+'<h4>细节</h4>'+adjustments.slice(10).map(([n,min,max,step])=>'<div class="range-row"><label>'+n+'<output>0</output></label><input type="range" data-adjust="'+n+'" aria-label="'+n+'" min="'+min+'" max="'+max+'" step="'+step+'" value="0"></div>').join('')+'<p class="muted">原片始终保留；保存滤镜后可在社区评论中分享参数。</p></div></div>'}
function initEditorPreview(){
 editorOriginal=null;
 const canvas=$('.editor-photo'),photo=new Image();
 photo.onload=()=>{
  if(!canvas.isConnected)return;
  const width=canvas.width,height=canvas.height,ratio=Math.max(width/photo.width,height/photo.height);
  const sw=width/ratio,sh=height/ratio;
  const ctx=canvas.getContext('2d',{willReadFrequently:true});
  ctx.drawImage(photo,(photo.width-sw)/2,(photo.height-sh)/2,sw,sh,0,0,width,height);
  editorOriginal=ctx.getImageData(0,0,width,height);
  renderEditorPreview();
 };
 photo.src=image();
}
function renderStoredCustomPreview(){
 const selected=(data.filters||[]).find(f=>f.id===data.activeCustomId);
 const covers=[...document.querySelectorAll('[data-custom-cover]')];
 if(!selected&&!covers.length)return;
 const photo=new Image();photo.onload=()=>{
  const canvas=document.createElement('canvas');canvas.width=480;canvas.height=300;
  const ctx=canvas.getContext('2d',{willReadFrequently:true}),ratio=Math.max(canvas.width/photo.width,canvas.height/photo.height);
  const sw=canvas.width/ratio,sh=canvas.height/ratio;
  ctx.drawImage(photo,(photo.width-sw)/2,(photo.height-sh)/2,sw,sh,0,0,canvas.width,canvas.height);
  const original=ctx.getImageData(0,0,canvas.width,canvas.height);
  for(const f of (data.filters||[]).filter(f=>covers.some(c=>c.dataset.customCover===(f.id||f.name)))){
   processCustomPixels(canvas,original,f.params||{},true);
   const url=canvas.toDataURL('image/png');
   document.querySelectorAll('[data-custom-cover="'+CSS.escape(f.id||f.name)+'"]').forEach(c=>c.src=url);
  }
  if(selected){
   processCustomPixels(canvas,original,selected.params||{},true);
   const preview=$('.preview img');if(preview)preview.src=canvas.toDataURL('image/png');
   if(document.querySelector('[data-custom-output]')){
    processCustomPixels(canvas,original,selected.params||{});
    document.querySelectorAll('[data-custom-output]').forEach(img=>img.src=canvas.toDataURL('image/png'));
   }
  }
 };photo.src=image();
}
function renderEditorPreview(){
 const canvas=$('.editor-photo');if(!canvas||!editorOriginal)return;
 const p=Object.fromEntries([...document.querySelectorAll('[data-adjust]')].map(el=>[el.dataset.adjust,Number(el.value)]));
 processCustomPixels(canvas,editorOriginal,p);
}
function processCustomPixels(canvas,editorOriginal,p,liveOnly=false){
 const get=name=>Number(p[name]||0);
 const w=canvas.width,h=canvas.height,source=editorOriginal.data,toned=new Uint8ClampedArray(source.length);
 const exposure=Math.pow(2,get('曝光')),contrast=1+get('对比度')/100,saturation=Math.max(0,1+get('饱和度')/100);
 const offset=(1-contrast)*127.5,temperature=get('色温')/100,tint=get('色调')/100;
 const scales=[1+get('红色')/100+temperature*.12,1+get('绿色')/100-tint*.08,1+get('蓝色')/100-temperature*.12];
 for(let i=0;i<source.length;i+=4){
  const lum=source[i]*.2126+source[i+1]*.7152+source[i+2]*.0722;
  const r=Math.max(0,Math.min(255,(lum+(source[i]-lum)*saturation)*exposure*contrast*scales[0]+offset));
  const g=Math.max(0,Math.min(255,(lum+(source[i+1]-lum)*saturation)*exposure*contrast*scales[1]+offset));
  const b=Math.max(0,Math.min(255,(lum+(source[i+2]-lum)*saturation)*exposure*contrast*scales[2]+offset));
  toned[i]=r;toned[i+1]=g;toned[i+2]=b;toned[i+3]=source[i+3];
 }
 const output=new ImageData(w,h),pixels=output.data,cx=(w-1)/2,cy=(h-1)/2,maxRadius=Math.hypot(cx,cy)||1;
 const sharpness=get('锐化')/100*.35,vignette=get('暗角')/100*.8;
 for(let y=0;y<h;y++)for(let x=0;x<w;x++){
  const i=(y*w+x)*4,left=(y*w+Math.max(0,x-1))*4,right=(y*w+Math.min(w-1,x+1))*4;
  const up=(Math.max(0,y-1)*w+x)*4,down=(Math.min(h-1,y+1)*w+x)*4;
  const light=(toned[i]*.2126+toned[i+1]*.7152+toned[i+2]*.0722)/255;
  const delta=liveOnly?0:get('阴影')*.85*(1-light)**2+get('高光')*.85*light**2;
  const edge=Math.max(0,Math.min(1,(Math.hypot(x-cx,y-cy)/maxRadius-.45)/.55));
  const falloff=liveOnly?1:1-vignette*edge*edge;
  for(let c=0;c<3;c++)pixels[i+c]=(toned[i+c]+(liveOnly?0:sharpness*(4*toned[i+c]-toned[left+c]-toned[right+c]-toned[up+c]))+delta)*falloff;
  pixels[i+3]=toned[i+3];
 }
 canvas.getContext('2d').putImageData(output,0,0);
}
function queueEditorPreview(){cancelAnimationFrame(editorFrame);editorFrame=requestAnimationFrame(renderEditorPreview)}
function result(){return camera()+'<div class="modalback" data-layer="保存结果遮罩"><div class="sheet result" data-layer="保存结果操作"><div class="handle"></div><img class="photo" data-custom-output src="'+image()+'" style="filter:'+(data.activeCustomId?'none':effects[filter])+'" alt="刚拍摄的照片"><h2 style="color:var(--green)">✓ 照片已保存</h2><p class="muted">辅助构图 · 日常（演示）</p><div class="row">'+route('重拍','camera','primary')+route('发布到社区','publish','primary')+'</div>'+route('完成并返回相机','camera','icon full')+'</div></div>'}
function publish(){return camera()+'<div class="modalback" data-layer="发布遮罩"><form id="publish-form" class="sheet" data-layer="发布表单"><div class="handle"></div><h3>发布到社区</h3><img class="photo" data-custom-output style="height:220px" src="'+image()+'" alt="待发布照片"><label class="field">添加描述<textarea name="body" required placeholder="记录这一刻的故事…"></textarea></label><div class="row">'+route('取消','capture-result','quiet')+'<span class="spacer"></span><button class="primary">发布</button></div></form></div>'}
function render(){
 clearTimeout(hintTimer);
 if(id==='pro'&&current.states.includes(state))proControl=state;
 let html;
 if(['camera','filters','pro'].includes(id))html=camera();
 else if(['community','following-feed','messages'].includes(id))html=feed();
 else if(['profile','user-profile'].includes(id))html=profile();
 else if(['followers','following'].includes(id))html=userList();
 else if(id==='comments')html=comments();
 else if(id==='post')html=postDetail();
 else if(id==='filter-resource')html='<div class="screen">'+top('社区滤镜资源','community')+'<div class="bodypad"><img class="photo" src="'+image('community_rainy_street')+'" alt="滤镜示例">'+resource()+route('打开滤镜编辑器','filter-editor','primary row')+'</div></div>';
 else if(id==='settings')html=settings();
 else if(id==='edit-profile')html=editProfile();
 else if(['splash','login','register','forgot-password'].includes(id))html=auth();
 else if(id==='filter-editor')html=editor();
 else if(id==='capture-result')html=result();
 else if(id==='publish')html=publish();
 else html='<div class="screen dark">'+top('系统相册入口')+'<div class="bodypad"><p class="note" style="color:#333">'+escape(current.note)+'</p>'+(state==='空相册'?empty('暂无照片'):'<img class="photo" src="'+image()+'" alt="示例照片">')+'</div></div>';
 $('#app').innerHTML=html;
 if(id==='filter-editor')initEditorPreview();
 if(['camera','filters','pro','capture-result','publish'].includes(id))renderStoredCustomPreview();
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
 case 'filter':filter=Number(value);data.activeCustomId=null;save();render();break;
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
 case 'import-filter':if(!data.filters.some(f=>f.name==='雨后电影感'))data.filters.push({id:'community-rain-cinema',name:'雨后电影感',params:{'曝光':-.12,'对比度':18,'高光':-24,'阴影':14,'饱和度':-8,'色温':-10,'色调':5,'蓝色':8,'锐化':18,'暗角':16}});save();render();toast('已导入浏览器演示滤镜库');break;
 case 'save-filter':{const name=$('#filter-name').value.trim();if(!name){toast('请输入滤镜名称');break}const params={};document.querySelectorAll('[data-adjust]').forEach(i=>params[i.dataset.adjust]=Number(i.value));const id='custom-'+Date.now();data.filters.push({id,name,params});data.activeCustomId=id;save();goto('filters');break}
 case 'custom-filter':data.activeCustomId=value;save();render();toast('已选择 '+((data.filters||[]).find(f=>f.id===value)?.name||'自定义滤镜'));break;
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
 if(t.dataset.adjust){t.previousElementSibling.querySelector('output').textContent=t.value;queueEditorPreview()}
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
