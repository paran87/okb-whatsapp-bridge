'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer } = require('../server');
const { offlineAi } = require('./helpers/flood-fixtures');

async function start(opts = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'okb-backend-'));
  const server = createServer({ dataDir, tokens: ['secret-token'], log: () => {}, ai: offlineAi(), ...opts });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base, dataDir };
}

const auth = { Authorization: 'Bearer secret-token', 'Content-Type': 'application/json' };
const message = {
  deviceId: 'OKB-ANDROID-A82F19',
  clientMessageId: 'uuid-1',
  fingerprint: 'a'.repeat(64),
  groupName: 'OKB Monitoring',
  senderName: 'Juan Santos',
  messageText: 'Flooding observed at Barangay San Jose',
  timestamp: '2026-10-04T08:42:00+08:00',
  timestampMillis: 1791074520000,
  mediaType: 'TEXT',
  mediaStatus: 'NONE',
  sourcePackage: 'com.whatsapp',
  capturedAt: '2026-10-04T08:42:01+08:00',
};

test('health is public', async () => {
  const { server, base } = await start();
  const res = await fetch(`${base}/api/v1/health`);
  assert.strictEqual(res.status, 200);
  assert.strictEqual((await res.json()).status, 'ok');
  server.close();
});

test('messages require a valid token', async () => {
  const { server, base } = await start();
  const res = await fetch(`${base}/api/v1/messages`, { method: 'POST', body: JSON.stringify(message) });
  assert.strictEqual(res.status, 401);
  const wrong = await fetch(`${base}/api/v1/messages`, {
    method: 'POST', headers: { ...auth, Authorization: 'Bearer nope' }, body: JSON.stringify(message),
  });
  assert.strictEqual(wrong.status, 401);
  server.close();
});

test('message is stored once and duplicates return the same id', async () => {
  const { server, base, dataDir } = await start();
  const first = await fetch(`${base}/api/v1/messages`, { method: 'POST', headers: auth, body: JSON.stringify(message) });
  assert.strictEqual(first.status, 201);
  const { id } = await first.json();
  const again = await fetch(`${base}/api/v1/messages`, { method: 'POST', headers: auth, body: JSON.stringify(message) });
  assert.strictEqual(again.status, 200);
  const dup = await again.json();
  assert.strictEqual(dup.id, id);
  assert.strictEqual(dup.duplicate, true);
  const list = await (await fetch(`${base}/api/v1/messages`, { headers: auth })).json();
  assert.strictEqual(list.count, 1);
  assert.strictEqual(list.messages[0].messageText, message.messageText);
  server.close();

  // Restart: dedupe index is rebuilt from disk.
  const restarted = createServer({ dataDir, tokens: ['secret-token'], log: () => {}, ai: offlineAi() });
  await new Promise((r) => restarted.listen(0, '127.0.0.1', r));
  const res = await fetch(`http://127.0.0.1:${restarted.address().port}/api/v1/messages`, {
    method: 'POST', headers: auth, body: JSON.stringify(message),
  });
  assert.strictEqual(res.status, 200);
  restarted.close();
});

test('invalid payloads are rejected with 400', async () => {
  const { server, base } = await start();
  const res = await fetch(`${base}/api/v1/messages`, {
    method: 'POST', headers: auth, body: JSON.stringify({ ...message, mediaType: 'PHOTO', fingerprint: 'x' }),
  });
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /fingerprint, mediaType/);
  server.close();
});

test('device registration', async () => {
  const { server, base } = await start();
  const body = JSON.stringify({ deviceId: 'OKB-ANDROID-A82F19', deviceName: 'Ops phone', platform: 'android' });
  const first = await fetch(`${base}/api/v1/devices/register`, { method: 'POST', headers: auth, body });
  assert.strictEqual(first.status, 201);
  const again = await fetch(`${base}/api/v1/devices/register`, { method: 'POST', headers: auth, body });
  assert.strictEqual(again.status, 200);
  const bad = await fetch(`${base}/api/v1/devices/register`, { method: 'POST', headers: auth, body: '{"deviceId":"phone"}' });
  assert.strictEqual(bad.status, 400);
  server.close();
});

test('refuses to start without tokens unless explicitly allowed', () => {
  assert.throws(() => createServer({ dataDir: os.tmpdir(), tokens: [], allowNoAuth: false, log: () => {}, ai: offlineAi() }));
});
