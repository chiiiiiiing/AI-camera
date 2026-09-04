#!/usr/bin/env node
/**
 * 认证服务器冒烟测试（零依赖）：
 *   node smoke-test.js
 * 覆盖：health → 发码 → 错误码失败 → 注册 → 重复注册 409 → 错误密码 401 →
 *       登录 → me → 重置密码 → 验证码登录自动开户 → 带 token 再查 me。
 * 任一步失败即非零退出。
 */
'use strict';

const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const tmpData = path.join(os.tmpdir(), `genwow-auth-test-${Date.now()}.json`);
process.env.PORT = '0';
process.env.DATA_FILE = tmpData;

const { server } = require('./server.js');

let passed = 0;
let failed = 0;

function step(name, fn) {
  return fn()
    .then(() => { passed++; console.log(`  PASS  ${name}`); })
    .catch((e) => { failed++; console.error(`  FAIL  ${name}: ${e.message}`); });
}

async function api(port, method, url, body, token) {
  const resp = await fetch(`http://127.0.0.1:${port}${url}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const json = await resp.json().catch(() => ({}));
  return { status: resp.status, json };
}

server.on('listening', async () => {
  const port = server.address().port;
  console.log(`smoke test on :${port}\n`);

  let devCode = null;
  let token = null;
  const phone = '13800001111';
  const email = 'test@genwow.dev';

  await step('health 返回用户数', async () => {
    const r = await api(port, 'GET', '/api/health');
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.equal(r.json.users, 0);
  });

  await step('发送验证码（手机号，register）', async () => {
    const r = await api(port, 'POST', '/api/auth/code/send', { target: phone, scene: 'register' });
    assert.equal(r.status, 200);
    assert.match(r.json.devCode, /^\d{6}$/);
    devCode = r.json.devCode;
  });

  await step('60s 内重发被冷却限制（429）', async () => {
    const r = await api(port, 'POST', '/api/auth/code/send', { target: phone, scene: 'register' });
    assert.equal(r.status, 429);
    assert.ok(r.json.retryAfterSec > 0);
  });

  await step('错误验证码被拒绝', async () => {
    const wrong = devCode === '000000' ? '000001' : '000000';
    const r = await api(port, 'POST', '/api/auth/code/verify', { target: phone, scene: 'register', code: wrong });
    assert.equal(r.status, 400);
  });

  await step('正确验证码通过', async () => {
    const r = await api(port, 'POST', '/api/auth/code/verify', { target: phone, scene: 'register', code: devCode });
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
  });

  await step('手机号注册', async () => {
    const r = await api(port, 'POST', '/api/auth/register', { username: '测试用户', password: 'secret123', phone });
    assert.equal(r.status, 200);
    assert.equal(r.json.user.username, '测试用户');
    assert.equal(r.json.user.phone, phone);
    assert.ok(!r.json.user.passwordHash, '响应不得携带密码哈希');
  });

  await step('同手机号重复注册 409', async () => {
    const r = await api(port, 'POST', '/api/auth/register', { username: '另一个人', password: 'secret123', phone });
    assert.equal(r.status, 409);
  });

  await step('错误密码 401', async () => {
    const r = await api(port, 'POST', '/api/auth/login', { account: phone, password: 'wrong-pass' });
    assert.equal(r.status, 401);
  });

  await step('手机号 + 密码登录成功', async () => {
    const r = await api(port, 'POST', '/api/auth/login', { account: phone, password: 'secret123' });
    assert.equal(r.status, 200);
    assert.equal(r.json.user.username, '测试用户');
    assert.ok(r.json.token.length >= 32);
    token = r.json.token;
  });

  await step('用户名也能登录', async () => {
    const r = await api(port, 'POST', '/api/auth/login', { account: '测试用户', password: 'secret123' });
    assert.equal(r.status, 200);
  });

  await step('Bearer token 查 me', async () => {
    const r = await api(port, 'GET', '/api/user/me', null, token);
    assert.equal(r.status, 200);
    assert.equal(r.json.user.phone, phone);
  });

  await step('无 token 查 me 返回 401', async () => {
    const r = await api(port, 'GET', '/api/user/me');
    assert.equal(r.status, 401);
  });

  await step('重置密码全流程', async () => {
    const send = await api(port, 'POST', '/api/auth/code/send', { target: phone, scene: 'reset' });
    assert.equal(send.status, 200);
    const reset = await api(port, 'POST', '/api/auth/reset-password', { target: phone, code: send.json.devCode, newPassword: 'newpass456' });
    assert.equal(reset.status, 200);
    const oldLogin = await api(port, 'POST', '/api/auth/login', { account: phone, password: 'secret123' });
    assert.equal(oldLogin.status, 401, '旧密码应已失效');
    const newLogin = await api(port, 'POST', '/api/auth/login', { account: phone, password: 'newpass456' });
    assert.equal(newLogin.status, 200);
  });

  await step('验证码登录：未注册邮箱自动开户', async () => {
    const send = await api(port, 'POST', '/api/auth/code/send', { target: email, scene: 'login' });
    assert.equal(send.status, 200);
    const login = await api(port, 'POST', '/api/auth/login-code', { target: email, code: send.json.devCode });
    assert.equal(login.status, 200);
    assert.equal(login.json.isNewUser, true);
    assert.equal(login.json.user.email, email);
    token = login.json.token;
    const me = await api(port, 'GET', '/api/user/me', null, token);
    assert.equal(me.json.user.email, email);
  });

  await step('同邮箱再次验证码登录不是新用户', async () => {
    const send = await api(port, 'POST', '/api/auth/code/send', { target: email, scene: 'login' });
    const login = await api(port, 'POST', '/api/auth/login-code', { target: email, code: send.json.devCode });
    assert.equal(login.json.isNewUser, false);
  });

  await step('坏 JSON 返回 400', async () => {
    const resp = await fetch(`http://127.0.0.1:${port}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{broken',
    });
    assert.equal(resp.status, 400);
  });

  await step('数据持久化到磁盘', async () => {
    await new Promise(r => setTimeout(r, 300)); // 等 debounce 落盘
    const raw = JSON.parse(fs.readFileSync(tmpData, 'utf8'));
    assert.equal(raw.users.length, 2);
    assert.ok(raw.users.every(u => u.passwordHash === undefined || typeof u.passwordHash === 'string'));
  });

  console.log(`\n${passed} passed, ${failed} failed`);
  server.close();
  try { fs.unlinkSync(tmpData); } catch {}
  process.exit(failed > 0 ? 1 : 0);
});
