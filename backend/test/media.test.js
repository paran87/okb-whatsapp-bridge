'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer } = require('../server');

async function start(opts = {}) {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'okb-media-'));
  const server = createServer({ dataDir, tokens: ['secret-token'], log: () => {}, ...opts });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { server, base: `http://127.0.0.1:${server.address().port}`, dataDir };
}

const auth = { Authorization: 'Bearer secret-token', 'Content-Type': 'application/json' };
const bytes = Buffer.from('fake-jpeg-bytes-for-testing-' + 'x'.repeat(2000));
const sha = crypto.createHash('sha256').update(bytes).digest('hex');

function intentBody(overrides = {}) {
  return JSON.stringify({
    deviceId: 'OKB-ANDROID-A82F19', sha256: sha, mediaType: 'IMAGE', mimeType: 'image/jpeg',
    fileSizeBytes: bytes.length, originalFileName: 'IMG-0001.jpg', groupName: 'OKB Monitoring',
    capturedAt: '2026-10-04T08:42:00+08:00', messageFingerprint: 'f'.repeat(64), ...overrides,
  });
}

test('health reports media mode', async () => {
  const { server, base } = await start();
  const h = await (await fetch(`${base}/api/v1/health`)).json();
  assert.strictEqual(h.media, 'local-sink');
  server.close();
});

test('full local-sink media flow: intent -> PUT -> complete -> fetch', async () => {
  const { server, base } = await start();
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  assert.strictEqual(intent.status, 'upload');
  assert.match(intent.objectKey, /^whatsapp\/OKB-ANDROID-A82F19\/2026\/10\/04\/[0-9a-f]{64}\.jpg$/);
  assert.ok(intent.uploadUrl.includes('/api/v1/media/blob?key='));

  const put = await fetch(intent.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: bytes });
  assert.strictEqual(put.status, 200);
  const etag = put.headers.get('etag');
  assert.ok(etag);

  const complete = await fetch(`${base}/api/v1/media/complete`, {
    method: 'POST', headers: auth,
    body: JSON.stringify({
      deviceId: 'OKB-ANDROID-A82F19', sha256: sha, objectKey: intent.objectKey, etag: etag.replace(/"/g, ''),
      fileSizeBytes: bytes.length, mediaType: 'IMAGE', mimeType: 'image/jpeg', originalFileName: 'IMG-0001.jpg',
      groupName: 'OKB Monitoring', senderName: 'Juan', messageFingerprint: 'f'.repeat(64), capturedAt: '2026-10-04T08:42:00+08:00',
    }),
  });
  assert.strictEqual(complete.status, 201);
  const body = await complete.json();
  assert.strictEqual(body.status, 'stored');
  assert.strictEqual(body.remoteRef, `local://${intent.objectKey}`);

  const fetched = await (await fetch(`${base}/api/v1/media/${body.id}`, { headers: auth })).json();
  assert.strictEqual(fetched.objectKey, intent.objectKey);
  const list = await (await fetch(`${base}/api/v1/media`, { headers: auth })).json();
  assert.strictEqual(list.count, 1);
  server.close();
});

test('duplicate content is detected after completion (no re-upload)', async () => {
  const { server, base } = await start();
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  await fetch(intent.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: bytes });
  const completeBody = JSON.stringify({
    deviceId: 'OKB-ANDROID-A82F19', sha256: sha, objectKey: intent.objectKey, etag: null, fileSizeBytes: bytes.length,
    mediaType: 'IMAGE', mimeType: 'image/jpeg', originalFileName: null, groupName: null, senderName: null,
    messageFingerprint: null, capturedAt: '2026-10-04T08:42:00+08:00',
  });
  await fetch(`${base}/api/v1/media/complete`, { method: 'POST', headers: auth, body: completeBody });

  // Re-intent for the same content now reports duplicate.
  const second = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  assert.strictEqual(second.status, 'duplicate');
  // Re-complete is idempotent.
  const dup = await fetch(`${base}/api/v1/media/complete`, { method: 'POST', headers: auth, body: completeBody });
  assert.strictEqual((await dup.json()).status, 'duplicate');
  const list = await (await fetch(`${base}/api/v1/media`, { headers: auth })).json();
  assert.strictEqual(list.count, 1);
  server.close();
});

test('complete fails when the bytes were never uploaded (local-sink)', async () => {
  const { server, base } = await start();
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  const res = await fetch(`${base}/api/v1/media/complete`, {
    method: 'POST', headers: auth,
    body: JSON.stringify({
      deviceId: 'OKB-ANDROID-A82F19', sha256: sha, objectKey: intent.objectKey, etag: null, fileSizeBytes: bytes.length,
      mediaType: 'IMAGE', mimeType: 'image/jpeg', originalFileName: null, groupName: null, senderName: null,
      messageFingerprint: null, capturedAt: '2026-10-04T08:42:00+08:00',
    }),
  });
  assert.strictEqual(res.status, 409);
  server.close();
});

test('media endpoints require auth and validate input', async () => {
  const { server, base } = await start();
  const noAuth = await fetch(`${base}/api/v1/media/intent`, { method: 'POST', body: intentBody() });
  assert.strictEqual(noAuth.status, 401);
  const bad = await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody({ sha256: 'xyz', mediaType: 'TEXT' }) });
  assert.strictEqual(bad.status, 400);
  assert.match((await bad.json()).error, /sha256, mediaType/);
  server.close();
});

test('blob upload rejects a tampered signature', async () => {
  const { server, base } = await start();
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  const tampered = intent.uploadUrl.replace(/sig=[0-9a-f]+/, 'sig=' + '0'.repeat(64));
  const put = await fetch(tampered, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: bytes });
  assert.strictEqual(put.status, 403);
  server.close();
});

test('R2 mode presigns directly to Cloudflare (no local blob endpoint)', async () => {
  const { server, base } = await start({
    r2: { accountId: 'acc123', bucket: 'okb-media', accessKeyId: 'AKIA', secretAccessKey: 'SECRET' },
  });
  const h = await (await fetch(`${base}/api/v1/health`)).json();
  assert.strictEqual(h.media, 'r2');
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers: auth, body: intentBody() })).json();
  assert.strictEqual(intent.status, 'upload');
  assert.ok(intent.uploadUrl.startsWith('https://acc123.r2.cloudflarestorage.com/okb-media/'));
  assert.ok(intent.uploadUrl.includes('X-Amz-Signature='));
  assert.strictEqual(intent.remoteRef, `r2://okb-media/${intent.objectKey}`);
  // The dev blob endpoint is disabled in R2 mode.
  const blob = await fetch(`${base}/api/v1/media/blob?key=${encodeURIComponent(intent.objectKey)}&exp=9999999999999&sig=x`, { method: 'PUT', body: bytes });
  assert.strictEqual(blob.status, 404);
  server.close();
});
