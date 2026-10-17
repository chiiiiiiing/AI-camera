# GenWow 旋转引导 Icon（独立 SVG）

基于用户提供的「02 / 旋转引导」六宫格视觉稿手工矢量重绘。

## 文件
- `svg/roll_clockwise.svg` — ROLL 顺时针
- `svg/roll_counterclockwise.svg` — ROLL 逆时针
- `svg/pitch_up.svg` — PITCH 抬头
- `svg/pitch_down.svg` — PITCH 低头
- `svg/yaw_right.svg` — YAW 向右
- `svg/yaw_left.svg` — YAW 向左
- `preview/rotation_six_icons_board.png` — 六宫格预览

## Figma 导入
直接将每个 SVG 拖入 Figma。每个文件包含 `target`（灰色目标轮廓）、`current`（粉橘当前轮廓）、`correction-arrow`（纠错箭头）三个独立分组，可分别调整。

SVG 背景透明、无文字。画板统一为 200×200 viewBox。

## 注意
这是按照视觉稿人工重绘的可编辑矢量近似稿，不是逐像素自动描摹，细节可能存在少量差异。
图中旋转方向与手机姿态仅为视觉设计，正式接入需与设备坐标系和纠错方向约定核对。
