# GenWow Figma 画板清单

## 文件信息

- File key：`AteKnDANl4hjp5wBaJ2uNX`
- 文件名：`Untitled`
- 根节点：`0:1`
- 文件版本：`2378301108976446819`
- 最后修改：`2026-07-21T03:37:26Z`
- 设计基准：Android Compact，`412 × 917`
- 核对日期：`2026-07-21`

## 顶层画板

| Node ID | 名称 | 用途 | Ready for Dev | 本地临时渲染 |
| --- | --- | --- | --- | --- |
| `1:2` | Android Compact - 1 | 启动页 | 是 | `/tmp/genwow-figma-frames/1-2.png` |
| `1:3` | Android Compact - 2 | 登录页 | 是 | `/tmp/genwow-figma-frames/1-3.png` |
| `1:5` | Android Compact - 3 | 注册页 | 是 | `/tmp/genwow-figma-frames/1-5.png` |
| `1:7` | Android Compact - 4 | 梨子调整态 | 是 | `/tmp/genwow-figma-frames/1-7.png` |
| `1:9` | Android Compact - 5 | 梨子对齐态 | 是 | `/tmp/genwow-figma-frames/1-9.png` |
| `1:11` | Android Compact - 6 | 海滩青色旋转指引 | 是 | `/tmp/genwow-figma-frames/1-11.png` |
| `1:13` | Android Compact - 7 | 海滩橙色错误方向 | 否 | `/tmp/genwow-figma-frames/1-13.png` |
| `1:15` | Android Compact - 8 | 人物目标轮廓 | 否 | `/tmp/genwow-figma-frames/1-15.png` |
| `1:17` | Android Compact - 9 | 人物轮廓对齐 | 否 | `/tmp/genwow-figma-frames/1-17.png` |
| `1:19` | Android Compact - 10 | 推荐信息流 | 否 | `/tmp/genwow-figma-frames/1-19.png` |
| `1:21` | Android Compact - 11 | 摄影师主页 | 否 | `/tmp/genwow-figma-frames/1-21.png` |

Ready for Dev 状态来自首次完整文件读取时的节点数据。节点级再次批量读取遇到 Figma REST 速率限制，因此阶段 1 没有重复消耗接口额度；画板结构、尺寸和渲染图均已重新核对。

## 关键节点

- 正式 Logo 容器：`1:28`
- `GenWow` 英文品牌文字：`1:27`
- 启动页主要内容容器：`1:23`
- Figma Logo 可见渐变：浅粉到紫色。

## 实现规则

- 页面品牌、结构、控制区比例和主要入口以这些画板为依据。
- 相机静态照片不打包为生产预览；CameraX 继续提供真实 Preview。
- 粗虚轴、二维旋转符号和人物粗轮廓允许按设计审查进行轻量化优化。
- 需要使用的 Logo、图标和内容图片在对应开发阶段按节点定向导出，不导出整份文件或无关页面资源。
