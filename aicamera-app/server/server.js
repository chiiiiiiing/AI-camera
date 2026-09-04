#!/usr/bin/env node
/**
 * 帧好 GenWow — 认证后端服务器（Node 零依赖，开箱即用）
 *
 * 运行：node server.js        （默认 0.0.0.0:8080，PORT 环境变量可改）
 * 数据：server/data.json      （原子写入；删除即重置全部账号）
 *
 * 接口契约见 README.md。MVP 说明：
 * - 无真实短信/邮件通道，devCode 直接随响应下发（App 开发模式展示给用户）；
 * - 密码 scrypt 加盐哈希存储；token = 32 字节随机 hex；
 * - 日志打码 PII（手机号中间 4 位、邮箱本地部分）。
 */
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8080);
const DATA_FILE = process.env.DATA_FILE
  ? path.resolve(process.env.DATA_FILE)
  : path.join(__dirname, 'data.json');
const CODE_TTL_MS = 5 * 60 * 1000;
const CODE_RESEND_COOLDOWN_MS = 60 * 1000;
const CODE_MAX_ATTEMPTS = 5;
const PHONE_RE = /^1\d{10}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const startedAt = Date.now();

// ── 持久化 ──────────────────────────────────────────────────

let db = { users: [], sessions: {}, codes: {} };

function loadDb() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    const parsed = JSON.parse(raw);
    if (parsed && Array.isArray(parsed.users) && typeof parsed.sessions === 'object') {
      db = { users: parsed.users, sessions: parsed.sessions || {}, codes: parsed.codes || {} };
      log('数据已加载', `用户数=${db.users.length}`);
    }
  } catch (e) {
    if (e.code !== 'ENOENT') log('data.json 损坏，从空库启动', e.message);
  }
}

let saveTimer = null;
function saveDb() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const tmp = DATA_FILE + '.tmp';
    try {
      fs.writeFileSync(tmp, JSON.stringify(db, null, 2), 'utf8');
      fs.renameSync(tmp, DATA_FILE);
    } catch (e) {
      log('保存失败', e.message);
    }
  }, 150);
}

// ── 工具 ────────────────────────────────────────────────────

function log(...args) {
  console.log(new Date().toISOString().slice(11, 19), ...args);
}

function maskTarget(target) {
  if (PHONE_RE.test(target)) return target.slice(0, 3) + '****' + target.slice(7);
  const at = target.indexOf('@');
  if (at > 0) return target.slice(0, 2) + '***' + target.slice(at);
  return '***';
}

function hashPassword(password, salt) {
  return crypto.scryptSync(password, salt, 64).toString('hex');
}

function issueToken(userId) {
  const token = crypto.randomBytes(32).toString('hex');
  db.sessions[token] = { userId, createdAt: Date.now() };
  saveDb();
  return token;
}

function findUserByAccount(account) {
  return db.users.find(u => u.username === account || u.phone === account || u.email === account);
}

function findUserByTarget(target) {
  return PHONE_RE.test(target)
    ? db.users.find(u => u.phone === target)
    : db.users.find(u => u.email === target);
}

function publicUser(u) {
  return { id: u.id, username: u.username, phone: u.phone || '', email: u.email || '' };
}

function isValidTarget(target) {
  return PHONE_RE.test(target) || EMAIL_RE.test(target);
}

// ── 验证码 ──────────────────────────────────────────────────

function createCode(target, scene) {
  const key = `${scene}|${target}`;
  const existing = db.codes[key];
  const now = Date.now();
  if (existing && now - existing.ts < CODE_RESEND_COOLDOWN_MS) {
    const retryAfterSec = Math.ceil((CODE_RESEND_COOLDOWN_MS - (now - existing.ts)) / 1000);
    return { error: '发送太频繁，请稍后再试', status: 429, retryAfterSec };
  }
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  db.codes[key] = { code, exp: now + CODE_TTL_MS, att: 0, ts: now };
  saveDb();
  log(`验证码 [${scene}] target=${maskTarget(target)} code=${code}`);
  return { code };
}

function verifyCode(target, scene, input) {
  const key = `${scene}|${target}`;
  const entry = db.codes[key];
  if (!entry) return { error: '请先获取验证码', status: 400 };
  if (Date.now() > entry.exp) { delete db.codes[key]; saveDb(); return { error: '验证码已过期', status: 410 }; }
  if (entry.att >= CODE_MAX_ATTEMPTS) return { error: '尝试次数过多，请重新获取', status: 429 };
  if (entry.code !== input) {
    entry.att += 1;
    saveDb();
    return { error: '验证码错误', status: 400 };
  }
  delete db.codes[key];
  saveDb();
  return { ok: true };
}

// ── 路由 ────────────────────────────────────────────────────

const routes = {
  'GET /api/health': () => ({
    body: { ok: true, users: db.users.length, uptimeSec: Math.round((Date.now() - startedAt) / 1000) },
  }),

  'POST /api/auth/code/send': (body) => {
    const { target, scene } = body;
    if (!target || !isValidTarget(target)) return err(400, '目标格式不正确');
    if (!['register', 'login', 'reset'].includes(scene)) return err(400, 'scene 不合法');
    const r = createCode(target, scene);
    if (r.error) return err(r.status, r.error, { retryAfterSec: r.retryAfterSec });
    return { body: { ok: true, expiresInSec: CODE_TTL_MS / 1000, devCode: r.code } };
  },

  'POST /api/auth/code/verify': (body) => {
    const { target, scene, code } = body;
    if (!target || !code) return err(400, '参数不完整');
    const r = verifyCode(target, scene, code);
    if (r.error) return err(r.status, r.error);
    return { body: { ok: true } };
  },

  'POST /api/auth/register': (body) => {
    const { username, password, phone, email } = body;
    if (!username || username.length < 2 || username.length > 20) return err(400, '用户名需 2-20 个字符');
    if (!password || password.length < 6) return err(400, '密码至少 6 位');
    if (!phone && !email) return err(400, '需要手机号或邮箱');
    if (phone && !PHONE_RE.test(phone)) return err(400, '手机号格式不正确');
    if (email && !EMAIL_RE.test(email)) return err(400, '邮箱格式不正确');
    if (phone && db.users.some(u => u.phone === phone)) return err(409, '该手机号已注册');
    if (email && db.users.some(u => u.email === email)) return err(409, '该邮箱已注册');
    if (db.users.some(u => u.username === username)) return err(409, '用户名已被占用');

    const salt = crypto.randomBytes(16).toString('hex');
    const user = {
      id: crypto.randomUUID(),
      username,
      passwordHash: hashPassword(password, salt),
      salt,
      phone: phone || '',
      email: email || '',
      avatarUri: '',
      bio: '',
      createdAt: Date.now(),
    };
    db.users.push(user);
    saveDb();
    log('注册成功', `username=${username} target=${maskTarget(phone || email)} 用户数=${db.users.length}`);
    return { body: { ok: true, user: publicUser(user) } };
  },

  'POST /api/auth/login': (body) => {
    const { account, password } = body;
    if (!account || !password) return err(400, '参数不完整');
    const user = findUserByAccount(account);
    if (!user) return err(404, '账号不存在，请先注册');
    if (!user.passwordHash || hashPassword(password, user.salt) !== user.passwordHash) {
      return err(401, '密码错误');
    }
    const token = issueToken(user.id);
    log('登录成功', `username=${user.username}`);
    return { body: { ok: true, token, user: publicUser(user) } };
  },

  'POST /api/auth/login-code': (body) => {
    const { target, code } = body;
    if (!target || !isValidTarget(target)) return err(400, '目标格式不正确');
    if (!code) return err(400, '请输入验证码');
    const v = verifyCode(target, 'login', code);
    if (v.error) return err(v.status, v.error);

    let user = findUserByTarget(target);
    let isNewUser = false;
    if (!user) {
      // 自动开户
      const base = PHONE_RE.test(target) ? '用户' + target.slice(-4) : '用户' + target.split('@')[0].slice(0, 6);
      let username = base;
      while (db.users.some(u => u.username === username)) {
        username = base + crypto.randomInt(10, 99);
      }
      user = {
        id: crypto.randomUUID(),
        username,
        passwordHash: '',
        salt: crypto.randomBytes(16).toString('hex'),
        phone: PHONE_RE.test(target) ? target : '',
        email: PHONE_RE.test(target) ? '' : target,
        avatarUri: '',
        bio: '',
        createdAt: Date.now(),
      };
      db.users.push(user);
      isNewUser = true;
      log('验证码登录自动开户', `username=${username} target=${maskTarget(target)}`);
    }
    const token = issueToken(user.id);
    saveDb();
    log('验证码登录成功', `username=${user.username} 新用户=${isNewUser}`);
    return { body: { ok: true, token, user: publicUser(user), isNewUser } };
  },

  'POST /api/auth/reset-password': (body) => {
    const { target, code, newPassword } = body;
    if (!target || !code) return err(400, '参数不完整');
    if (!newPassword || newPassword.length < 6) return err(400, '密码至少 6 位');
    const user = findUserByTarget(target);
    if (!user) return err(404, '账号不存在，请先注册');
    const v = verifyCode(target, 'reset', code);
    if (v.error) return err(v.status, v.error);
    user.salt = crypto.randomBytes(16).toString('hex');
    user.passwordHash = hashPassword(newPassword, user.salt);
    saveDb();
    log('密码已重置', `username=${user.username}`);
    return { body: { ok: true } };
  },

  'GET /api/user/me': (body, headers) => {
    const user = authFromHeaders(headers);
    if (!user) return err(401, '未登录');
    return { body: { ok: true, user: publicUser(user) } };
  },

  'POST /api/user/profile': (body, headers) => {
    const user = authFromHeaders(headers);
    if (!user) return err(401, '未登录');
    if (typeof body.bio === 'string') user.bio = body.bio.slice(0, 200);
    if (typeof body.avatarUri === 'string') user.avatarUri = body.avatarUri.slice(0, 500);
    if (typeof body.username === 'string' && body.username.length >= 2 && body.username.length <= 20) {
      if (!db.users.some(u => u.username === body.username && u.id !== user.id)) {
        user.username = body.username;
      }
    }
    saveDb();
    return { body: { ok: true, user: publicUser(user) } };
  },
};

function authFromHeaders(headers) {
  const auth = headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  const session = token && db.sessions[token];
  return session ? db.users.find(u => u.id === session.userId) : null;
}

function err(status, message, extra = {}) {
  return { status, body: { error: message, ...extra } };
}

// ── HTTP 服务 ───────────────────────────────────────────────

const server = http.createServer((req, res) => {
  // CORS（局域网调试页面用）
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  const routeKey = `${req.method} ${req.url.split('?')[0]}`;
  const handler = routes[routeKey];

  const respond = (status, obj) => {
    const text = JSON.stringify(obj);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(text);
  };

  if (!handler) {
    respond(404, { error: '接口不存在' });
    return;
  }

  let raw = '';
  let tooBig = false;
  req.on('data', chunk => {
    raw += chunk;
    if (raw.length > 64 * 1024) { tooBig = true; req.destroy(); }
  });
  req.on('end', () => {
    if (tooBig) return;
    try {
      const body = raw ? JSON.parse(raw) : {};
      const result = handler(body, req.headers) || { body: { ok: true } };
      respond(result.status || 200, result.body);
    } catch (e) {
      if (e instanceof SyntaxError) {
        respond(400, { error: 'JSON 格式错误' });
      } else {
        log('处理异常', e.message);
        respond(500, { error: '服务器内部错误' });
      }
    }
  });
  req.on('error', () => {});
});

loadDb();
server.listen(PORT, '0.0.0.0', () => {
  log(`认证服务器已启动 → http://0.0.0.0:${PORT}`);
  log(`健康检查: http://localhost:${PORT}/api/health`);
});

module.exports = { server, db };
