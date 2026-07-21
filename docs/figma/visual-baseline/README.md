# Figma 视觉基线说明

阶段 1 已通过 Figma REST 读取并渲染 11 个顶层画板。为保护未上线产品素材并避免把完整设计稿加入仓库，PNG 当前只保存在本机临时目录：

```text
/tmp/genwow-figma-frames/
```

这些文件的用途仅限：

- 核对页面结构、视觉层级和状态差异。
- 与后续 Android 模拟器截图进行本地比较。
- 识别需要单独导出的 Logo、图标和内容图片节点。

限制：

- 不把整页 PNG 放入 `app/src/main/res`。
- 不把相机静态照片用于伪装 CameraX Preview。
- 不上传截图或视觉差异图。
- 临时目录被系统清理后，通过 `docs/figma/frame-inventory.md` 中的 nodeId 重新渲染。

最终视觉校准记录将写入 `docs/figma/visual-validation.md`；模拟器截图目录 `docs/screenshots/` 已由 `.gitignore` 排除。
