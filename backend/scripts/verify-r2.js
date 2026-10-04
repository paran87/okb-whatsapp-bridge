'use strict';
/**
 * Verifies real Cloudflare R2 connectivity end-to-end, the exact way the Android device does it.
 *
 * Two modes:
 *   1) Direct (default): presign a PUT with lib/r2 and upload a tiny object straight to R2.
 *      Proves the SigV4 presigner + your R2 credentials + bucket write permission all work.
 *        node scripts/verify-r2.js
 *      (Reads R2_ACCOUNT_ID / R2_BUCKET_NAME / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY from env.
 *       On Node >= 20 you can load a file: node --env-file=.env scripts/verify-r2.js)
 *
 *   2) Via backend: drive the real intent -> PUT -> complete flow against a running server.
 *        node scripts/verify-r2.js --via-backend http://localhost:8080 [--token YOUR_TOKEN]
 *
 * Flags: --dry (construct the presigned URL but do not upload), --keep (don't note cleanup).
 */
const crypto = require('node:crypto');
const { createR2Client } = require('../lib/r2');

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}
const has = (name) => process.argv.includes(name);

async function direct() {
  const cfg = {
    accountId: process.env.R2_ACCOUNT_ID,
    bucket: process.env.R2_BUCKET_NAME,
    accessKeyId: process.env.R2_ACCESS_KEY_ID,
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY,
  };
  const missing = Object.entries(cfg).filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) {
    console.error('Missing env: ' + missing.join(', '));
    console.error('Set the R2_* variables (or use: node --env-file=.env scripts/verify-r2.js)');
    process.exit(2);
  }
  const r2 = createR2Client(cfg);

  const body = Buffer.from(`okb r2 verify ${new Date().toISOString()}\n`);
  const sha = crypto.createHash('sha256').update(body).digest('hex');
  const d = new Date();
  const key = `whatsapp/OKB-ANDROID-TEST00/${d.getUTCFullYear()}/${String(d.getUTCMonth() + 1).padStart(2, '0')}/${String(d.getUTCDate()).padStart(2, '0')}/${sha}.txt`;
  const contentType = 'text/plain';

  const presigned = r2.presignPut(key, { contentType });
  console.log('Bucket :', cfg.bucket, '@', `${cfg.accountId}.r2.cloudflarestorage.com`);
  console.log('Object :', key);
  console.log('URL    :', presigned.url.slice(0, 96) + '…  (expires ' + presigned.expiresAt + ')');

  if (has('--dry')) {
    console.log('\n[--dry] Skipping upload. Presigned URL constructed successfully.');
    return;
  }

  const res = await fetch(presigned.url, { method: 'PUT', headers: presigned.headers, body });
  const etag = res.headers.get('etag');
  if (res.ok) {
    console.log(`\n✅ R2 upload OK — HTTP ${res.status}${etag ? `, ETag ${etag}` : ''}`);
    console.log(`   remoteRef: ${r2.remoteRef(key)}`);
    console.log('   The media pipeline will upload to R2 whenever the app acquires a media file.');
    if (!has('--keep')) console.log('   (You can delete this test object from the bucket.)');
  } else {
    const text = await res.text().catch(() => '');
    console.error(`\n❌ R2 upload FAILED — HTTP ${res.status}`);
    console.error('   Response:', text.slice(0, 400).replace(/\n/g, ' '));
    console.error('   Common causes: wrong Account ID/bucket, token lacks Object Read & Write,');
    console.error('   token not scoped to this bucket, or a clock-skew issue on this machine.');
    process.exit(1);
  }
}

async function viaBackend(base) {
  const token = arg('--token');
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const health = await (await fetch(`${base}/api/v1/health`)).json();
  console.log('Backend media mode:', health.media);
  if (health.media !== 'r2') {
    console.error('❌ Backend is not in R2 mode. Restart it with the R2_* env vars set.');
    process.exit(1);
  }

  const body = Buffer.from(`okb r2 verify via backend ${Date.now()}\n`);
  const sha = crypto.createHash('sha256').update(body).digest('hex');
  const intentReq = {
    deviceId: 'OKB-ANDROID-TEST00', sha256: sha, mediaType: 'DOCUMENT', mimeType: 'text/plain',
    fileSizeBytes: body.length, originalFileName: 'verify.txt', groupName: 'verify',
    capturedAt: new Date().toISOString(), messageFingerprint: null,
  };
  const intent = await (await fetch(`${base}/api/v1/media/intent`, { method: 'POST', headers, body: JSON.stringify(intentReq) })).json();
  if (intent.status !== 'upload') { console.error('❌ intent did not return an upload URL:', intent); process.exit(1); }
  console.log('Object :', intent.objectKey);

  const put = await fetch(intent.uploadUrl, { method: 'PUT', headers: intent.headers || {}, body });
  if (!put.ok) { console.error(`❌ PUT to R2 failed — HTTP ${put.status}:`, (await put.text()).slice(0, 300)); process.exit(1); }
  const etag = (put.headers.get('etag') || '').replace(/"/g, '');

  const complete = await fetch(`${base}/api/v1/media/complete`, {
    method: 'POST', headers,
    body: JSON.stringify({ ...intentReq, objectKey: intent.objectKey, etag, senderName: 'verify' }),
  });
  const done = await complete.json();
  if (complete.ok) {
    console.log(`\n✅ End-to-end R2 flow OK — stored ${done.id}, remoteRef ${done.remoteRef}`);
  } else {
    console.error('❌ complete failed:', done); process.exit(1);
  }
}

const base = arg('--via-backend');
(base ? viaBackend(base) : direct()).catch((e) => { console.error('Error:', e.message); process.exit(1); });
