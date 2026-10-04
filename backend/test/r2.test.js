'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createR2Client } = require('../lib/r2');

const client = createR2Client({ accountId: 'acc123', bucket: 'okb-media', accessKeyId: 'AKIAEXAMPLE', secretAccessKey: 'SECRETKEY' });
const key = `whatsapp/OKB-ANDROID-A82F19/2026/10/04/${'a'.repeat(64)}.jpg`;

test('presigned PUT URL has the required SigV4 query parameters', () => {
  const r = client.presignPut(key, { contentType: 'image/jpeg', now: new Date('2026-10-04T08:00:00Z') });
  assert.ok(r.url.startsWith('https://acc123.r2.cloudflarestorage.com/okb-media/whatsapp/'));
  for (const p of ['X-Amz-Algorithm=AWS4-HMAC-SHA256', 'X-Amz-Credential=', 'X-Amz-Date=', 'X-Amz-Expires=900', 'X-Amz-SignedHeaders=', 'X-Amz-Signature=']) {
    assert.ok(r.url.includes(p), `missing ${p}`);
  }
  assert.match(r.url.split('X-Amz-Signature=')[1], /^[0-9a-f]{64}$/);
  assert.strictEqual(r.headers['Content-Type'], 'image/jpeg');
  assert.strictEqual(r.method, 'PUT');
});

test('signing is deterministic and key-specific', () => {
  const at = new Date('2026-10-04T08:00:00Z');
  const a = client.presignPut(key, { contentType: 'image/jpeg', now: at });
  const b = client.presignPut(key, { contentType: 'image/jpeg', now: at });
  assert.strictEqual(a.url, b.url);
  const other = client.presignPut(key.replace('.jpg', '.png'), { contentType: 'image/png', now: at });
  assert.notStrictEqual(a.url.split('X-Amz-Signature=')[1], other.url.split('X-Amz-Signature=')[1]);
});

test('content-type is included in signed headers only when provided', () => {
  const at = new Date('2026-10-04T08:00:00Z');
  const withCt = client.presignPut(key, { contentType: 'image/jpeg', now: at });
  const noCt = client.presignPut(key, { now: at });
  assert.ok(decodeURIComponent(withCt.url.match(/X-Amz-SignedHeaders=([^&]+)/)[1]).includes('content-type'));
  assert.strictEqual(decodeURIComponent(noCt.url.match(/X-Amz-SignedHeaders=([^&]+)/)[1]), 'host');
});

test('requires all credentials', () => {
  assert.throws(() => createR2Client({ accountId: 'a', bucket: 'b', accessKeyId: 'k' }));
});
