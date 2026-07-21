# 帧好 GenWow 视觉与设备验收

## 验收范围

- 设计基准：Figma `AteKnDANl4hjp5wBaJ2uNX`，版本 `2378301108976446819`，顶层画板 `412 × 917`。
- 常规设备：Android 35 `GenWow_API_35`，物理配置 `1080 × 2400`、density `420`。
- 小屏等效配置：`900 × 1600`、density `400`，约 `360 × 640dp`；验收后已恢复常规配置。
- 所有截图和 Figma 原始渲染仅保存在本机 `/tmp` 或被忽略的 `docs/screenshots/`，没有上传或加入版本控制。

## 已验证页面与状态

- 启动、登录、注册：常规尺寸与小屏均检查；输入区可滚动，键盘和系统栏不遮挡主要操作。
- 相机：真实 CameraX 预览、黑色上下控制区、模式入口、快门、切镜头和三目的地导航无裁切。
- 构图引导：平移、俯仰、偏航、滚转、错误方向、接近、对齐、人物目标和人物对齐均检查。
- 信息流：常规与小屏均可滚动，标签、帖子动作和底部入口不互相遮挡。
- 摄影师主页：常规与小屏均可滚动，统计与两排作品网格可访问。
- 权限、相机初始化失败和分析失败：品牌化状态页、重试/授权动作和系统栏对比度已检查。
- 拍摄反馈：白闪、MediaStore 保存和左下真实缩略图更新已在最终 APK 上验证。

## 最终本地证据

- 常规信息流：`/tmp/genwow-feed-final.png`
- 常规摄影师主页：`/tmp/genwow-profile-final.png`
- 小屏登录/相机/信息流/主页：`/tmp/genwow-small-login.png`、`/tmp/genwow-small-camera.png`、`/tmp/genwow-feed-small-final.png`、`/tmp/genwow-profile-small-final.png`
- 拍摄缩略图：`/tmp/genwow-camera-thumbnail-final.png`
- 构图代表状态：`/tmp/genwow-stage6-final-roll.png`、`/tmp/genwow-stage6-final-subject-23.png`、`/tmp/genwow-stage6-final-subject-24.png`、`/tmp/genwow-stage6-final-subject-29.png`

## 有意偏差与边界

- Figma 中的静态取景照片不用于生产相机页；视觉比较只覆盖控制区、比例、Insets 和 Overlay，运行时使用真实 CameraX Preview。
- Figma 的粗虚线和二维旋转符号已按产品要求优化为细渐隐轴、透视椭圆弧和几何方向箭头。
- 人物轮廓不是固定 Figma Path。生产接口接收 AI 每帧输出的归一化轮廓点集；Debug 仅生成明确标注的动态样本。
- Figma 指定的 Noto Sans CJK SC 字体文件未随素材提供，当前使用系统 Sans Serif；未擅自下载或嵌入未知授权字体。
- Feed/Profile 图片来自本地 Figma 渲染裁切资源；运行时不访问网络。
