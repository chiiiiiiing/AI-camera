# GenWow HTML 验收站

独立 UI 验收环境，使用项目图片、主题色与 Compose 源文件映射。无需 npm install。

## 队友在线验收

### 推荐：Vercel 固定网址

仓库根目录的 `vercel.json` 已配置 `node ui-review/export.mjs` 构建及 `ui-review/dist` 输出；部署后入口是 Vercel 分配的固定项目域名（例如 `https://项目名.vercel.app/`），23 个独立页面在域名根路径下可直接打开。连接 Git 仓库后，`main` 的后续推送会自动部署到同一个生产域名；网页每 10 秒检查部署版本，必要时自动刷新。

首次连接需仓库所有者 `chiiiiiiing` 登录 Vercel：**Add New → Project → Import Git Repository → AI-camera**，授权 Vercel GitHub App 访问该私有仓库；Root Directory 保持仓库根目录，Build Command 和 Output Directory 使用 `vercel.json` 的值，然后部署。Vercel 官方说明：个人账号名下私有仓库不能由协作者创建 Git 集成项目，需要仓库所有者连接。[Vercel GitHub 集成说明](https://vercel.com/docs/git/vercel-for-github)

此静态预览公开与否取决于 Vercel 项目访问设置；不要上传密钥、未公开的客户照片或真实账号数据。网页的点赞、评论及验收意见仍只在访问者浏览器中，**不会自动同步给队友**。

### 备选：GitHub Pages

仓库 `main` 分支每次推送 `ui-review/`、Android 页面源码或预览使用的图片改动，GitHub Actions 会导出静态验收站并保存为 `ui-review-static` 构建产物。仓库目前为**私有**，Pages 尚未启用，因此固定入口 [https://chiiiiiiing.github.io/AI-camera/](https://chiiiiiiing.github.io/AI-camera/) **暂不可访问**。不能在未确认公开权限的情况下直接开放站点。

若团队同意公开预览，且 GitHub 计划允许此仓库使用 Pages：仓库管理员 `chiiiiiiing` 在 **Settings → Pages → Build and deployment → Source** 选择 **GitHub Actions**；随后在 **Actions → Publish UI review** 手动运行一次。之后每次推送将自动部署，无须再设置 Actions 变量。当前连接的 `pengjinrui278` 账号只有写入权限，无法完成这项仓库管理设置。即使仓库是私有的，Pages 站点也可能面向互联网公开，启用前请核对站点内的素材与源码链接是否适合公开。[GitHub Pages 发布源文档](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)

队友打开后可以复制具体页面/场景的链接。在线页面每 10 秒检查已部署版本，发现预览版本变化会自动刷新；这意味着更新须先提交并推送到 `main`，不是本机未提交文件的实时直播。Android 源码改变会提示预览可能过期，不会自动生成对应 HTML。GitHub Pages 公开可访问，不要放未公开的客户照片、账号或密钥。网页验收意见仍只在各自浏览器，需使用「导出全部验收意见」把 JSON 发回开发者。

本地验证静态站：运行 `node export.mjs`，产物在忽略提交的 `dist/` 目录。`index.html` 与 23 个独立页面都会被导出，供 CI 发布。

## 启动

在此目录执行 `node server.mjs`，或 PowerShell 执行 `./start.ps1`（隐藏窗口后台启动）。打开 http://127.0.0.1:4173 。默认仅监听本机。

同一局域网内需要即时看**未推送的本地修改**时，在 PowerShell 运行 `$env:HOST='0.0.0.0'; node server.mjs`，队友用 `http://你的电脑局域网IP:4173/` 打开。若无法访问，检查 Windows 防火墙是否允许该端口；该方式需要你的电脑保持开机并运行服务，且会把预览及代码查看入口暴露给同一网络，请只在可信网络使用。默认启动仍只监听本机。

端口被占用时，可设置 `$env:PORT='4174'; node server.mjs` 后打开对应端口。

## 页面清单

每个地址都可以独立打开或分享给本机的开发任务；服务路由为每个页面提供 HTML 文档与共享样式/组件。

| 独立地址 | 对应页面或图层 |
|---|---|
| /camera.html | 相机预览、顶部模式、底部控件、对焦曝光、引导与异常状态 |
| /filters.html | 滤镜展开面板、同图封面 |
| /pro.html | EV、ISO、快门、白平衡、对焦、变焦 |
| /filter-editor.html | 自定义滤镜参数编辑 |
| /capture-result.html | 保存结果与重拍/发布 |
| /publish.html | 发布到社区弹层 |
| /gallery.html | 系统相册跳转边界演示，非自研相册 |
| /community.html | 社区推荐 |
| /following-feed.html | 关注动态 |
| /post.html | 帖子详情 |
| /comments.html | 用户评论弹层 |
| /filter-resource.html | 社区滤镜分享与导入 |
| /messages.html | 消息占位（Android 尚未实现消息列表） |
| /profile.html | 本人作品/点赞/收藏/评论 |
| /user-profile.html | Helena / Daniel 他人主页 |
| /edit-profile.html | 头像、昵称、简介编辑 |
| /followers.html | 粉丝列表 |
| /following.html | 关注列表 |
| /settings.html | 引导、拍摄与关于设置 |
| /splash.html | 启动页 |
| /login.html | 密码/验证码登录 |
| /register.html | 注册 |
| /forgot-password.html | 找回密码 |

## 验收方式

- 左侧选择页面，上方选择场景、窄屏或横屏尺寸。链接包含页面/状态/画布，可复现指定视图。
- 右侧按名称显隐图层，或开启边界检查遮挡。相机照片只是预览层，其他控件可单独检查。
- 快门首次点击模拟分析，再次点击进入保存结果；场景下拉可直接进入各个引导状态。
- 点赞、收藏、关注、评论、发布和个人资料使用共享 localStorage，跨页面和浏览器标签页同步，仅用于验收交互。
- 每页意见按“页面 + 场景”保存，点击导出可生成 JSON。导出文件提供给开发后，按页面反馈修改。
- `app.js`、`style.css`、HTML 变动自动刷新。Android Kotlin 文件变动会提示预览需同步，不会自动转换成 HTML。

## 同步约定与边界

每次产品 UI 修改应同时核对 Android Compose 文件和此站对应组件，在二者更新并检查后更新 `baseline.json` 的 Android 指纹。该指纹仅标识已对照版本，不能证明像素一致。

预览是手工维护的 HTML 镜像，不是 Android APK/Compose 在浏览器运行。滤镜 CSS 只近似颜色；锐化、IMU、模型输出、自动变焦、CameraX、权限、Room、认证及真实存储必须另做真机验收。设置页仅保存预览状态。原 App 的消息页面、系统相册边界不伪装成完整功能。不要把浏览器验收通过等同于 Android 功能通过。

此服务不调用摄像头、不发送短信、不修改 Android 用户数据；本地服务仅监听本机，GitHub Pages 版本公开可访问。验收意见保存在当前浏览器，请定期导出。

## 检查

`npm test` 检查服务资源、指纹与路径边界。`node browser-check.mjs` 使用环境变量 `PLAYWRIGHT_PACKAGE` 指定的 Playwright 模块以及本机 Edge 做浏览器检查；可用 `BROWSER_EXECUTABLE` 指定其他 Chromium。运行前启动服务。
