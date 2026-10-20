# GenWow HTML 验收站

独立 UI 验收环境，使用项目图片、主题色与 Compose 源文件映射。无需 npm install。

## 队友在线验收

### 已上线：固定网址

静态预览已发布到独立公开仓库 [pengjinrui278/genwow-ui-review](https://github.com/pengjinrui278/genwow-ui-review)，固定网址：[https://pengjinrui278.github.io/genwow-ui-review/](https://pengjinrui278.github.io/genwow-ui-review/)。它只包含导出的 24 个页面及图片，不包含 Android 工程、模型或本地账号数据。

在原 AI-camera 仓库执行 `./ui-review/publish.ps1`，脚本会验证目标仓库、快进更新、导出静态页面并推送到独立仓库；GitHub Pages 随后更新。原工程的普通 `git push` **不会自动跨仓库同步**。若要每次推送原工程后全自动同步，还需配置最小权限的跨仓库凭据。

### 可选：Vercel

独立仓库根目录已有 `vercel.json`。仓库所有者 `pengjinrui278` 可以在 Vercel **Add New → Project → Import Git Repository → genwow-ui-review**；Framework Preset 选 **Other**，Root Directory 保持根目录，无需 Build Command，输出目录为 `.`。完成首次部署后会得到另一个固定网址，未来独立仓库 `main` 的推送会自动更新。[Vercel GitHub 集成说明](https://vercel.com/docs/git/vercel-for-github)

在线页面每 10 秒检查已部署版本并在变化时刷新；本机未导出、未推送的修改不会出现在固定网址。Android 源码改变会提示预览可能过期，不会自动转换成 HTML。网页的点赞、评论和验收意见只在各自浏览器中，**不会自动同步给队友**；需使用「导出全部验收意见」交回开发者。

本地验证静态站：运行 `node export.mjs`，产物在忽略提交的 `dist/` 目录。`index.html` 与 24 个独立页面都会被导出，供 CI 发布。

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
| /replay.html | 功能回放：桌面 JVM 跑真模型 + 状态机的录制时间线 |

## 验收方式

- 左侧选择页面，上方选择场景、窄屏或横屏尺寸。链接包含页面/状态/画布，可复现指定视图。
- 右侧按名称显隐图层，或开启边界检查遮挡。相机照片只是预览层，其他控件可单独检查。
- 快门首次点击模拟分析，再次点击进入保存结果；场景下拉可直接进入各个引导状态。
- 点赞、收藏、关注、评论、发布和个人资料使用共享 localStorage，跨页面和浏览器标签页同步，仅用于验收交互。
- 每页意见按“页面 + 场景”保存，点击导出可生成 JSON。导出文件提供给开发后，按页面反馈修改。
- `app.js`、`style.css`、HTML 变动自动刷新。Android Kotlin 文件变动会提示预览需同步，不会自动转换成 HTML。

## 功能回放页

`/replay.html` 是桌面 JVM 生成的功能回放：在 AI-camera 仓库运行 `./gradlew.bat :app:replayEngine`（Linux/macOS 用 `./gradlew`），用真实 `student_model.onnx` + 406 模板索引 + 构图状态机对 `drawable-nodpi/gallery_*.png` 逐帧回放，把检索 Top5、NanoDet 检测、场景布局与状态机时间线写成 `ui-review/replay-data/replay.json`。本地服务直接读取；`publish.ps1` 发布时会随页面一并导出（缺失时导出空占位）。回放是桌面复现，不是真机运行验收。

## 同步约定与边界

每次产品 UI 修改应同时核对 Android Compose 文件和此站对应组件，在二者更新并检查后更新 `baseline.json` 的 Android 指纹。该指纹仅标识已对照版本，不能证明像素一致。

预览是手工维护的 HTML 镜像，不是 Android APK/Compose 在浏览器运行。滤镜 CSS 只近似颜色；锐化、IMU、模型输出、自动变焦、CameraX、权限、Room、认证及真实存储必须另做真机验收。设置页仅保存预览状态。原 App 的消息页面、系统相册边界不伪装成完整功能。不要把浏览器验收通过等同于 Android 功能通过。

此服务不调用摄像头、不发送短信、不修改 Android 用户数据；本地服务仅监听本机，GitHub Pages 版本公开可访问。验收意见保存在当前浏览器，请定期导出。

## 检查

`npm test` 检查服务资源、指纹与路径边界。`node browser-check.mjs` 使用环境变量 `PLAYWRIGHT_PACKAGE` 指定的 Playwright 模块以及本机 Edge 做浏览器检查；可用 `BROWSER_EXECUTABLE` 指定其他 Chromium。运行前启动服务。
