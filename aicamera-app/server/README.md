# 帧好 GenWow — 认证后端服务器

Node.js 零依赖单文件服务器（无需 `npm install`），为 App 提供注册 / 登录 / 验证码服务。
用户数据持久化在 `server/data.json` —— **注册过的账号重启服务器、重装 App 都还在**。

## 快速开始

```bash
cd aicamera-app/server
node server.js          # 默认 0.0.0.0:8080
```

- 改端口：`set PORT=9000 && node server.js`（Windows）/ `PORT=9000 node server.js`（macOS/Linux）
- 首次连接时 Windows 防火墙弹窗请选 **专用网络允许**（手机才能访问到电脑）

## 手机连电脑的服务器（同一 Wi-Fi）

1. 电脑查局域网 IP：`ipconfig` → WLAN 的 IPv4 地址（例如 `192.168.0.100`）
2. 手机与电脑连 **同一个 Wi-Fi**
3. App 构建时指向该地址：
   ```bash
   ./gradlew :app:assembleDebug -PAUTH_BASE_URL=http://192.168.0.100:8080/
   ```
   （默认已内置 `http://192.168.0.100:8080/`，IP 变了才需要这个参数）
4. 服务器不在线时 App 自动回退 **本地账号模式**，所有功能（含验证码）仍可用

## 验证码（MVP 开发模式）

没有接真实短信/邮件通道：验证码由服务器随机生成（6 位、5 分钟有效、
5 次尝试上限、60 秒重发冷却），通过响应里的 `devCode` 字段下发，
App 点「获取验证码」后直接把验证码显示在输入框下方。
服务器控制台也会打印（手机号打码）：`验证码 [register] target=138****1111 code=592538`

## 接口一览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查 `{ ok, users, uptimeSec }` |
| POST | `/api/auth/code/send` | 发验证码 `{ target, scene: register/login/reset }` → `{ ok, expiresInSec, devCode }` |
| POST | `/api/auth/code/verify` | 单独校验验证码 `{ target, scene, code }` |
| POST | `/api/auth/register` | 注册 `{ username, password, phone?/email? }` |
| POST | `/api/auth/login` | 密码登录 `{ account(用户名/手机/邮箱), password }` → `{ ok, token, user }` |
| POST | `/api/auth/login-code` | 验证码免密登录 `{ target, code }`（未注册自动开户） |
| POST | `/api/auth/reset-password` | 重置密码 `{ target, code, newPassword }` |
| GET | `/api/user/me` | `Authorization: Bearer <token>` 查当前用户 |
| POST | `/api/user/profile` | 更新资料 `{ bio?, avatarUri?, username? }`（Bearer） |

## 冒烟测试

```bash
node smoke-test.js    # 17 步全链路自测，全部 PASS 为准
```

## 手工 curl 验证

```bash
curl http://localhost:8080/api/health
curl -X POST http://localhost:8080/api/auth/code/send -H "Content-Type: application/json" -d "{\"target\":\"13800001111\",\"scene\":\"register\"}"
curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" -d "{\"username\":\"demo\",\"password\":\"secret123\",\"phone\":\"13800001111\"}"
curl -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"account\":\"13800001111\",\"password\":\"secret123\"}"
curl http://localhost:8080/api/user/me -H "Authorization: Bearer <上一步的token>"
```

## 重置全部数据

```bash
del data.json     # Windows
rm data.json      # macOS/Linux
```
