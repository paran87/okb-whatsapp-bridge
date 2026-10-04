'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer } = require('../server');
const { createAiClient, AiError } = require('../lib/ai');
const F = require('./helpers/flood-fixtures');

const DEVICE = 'OKB-ANDROID-A82F19';
const auth = { Authorization: 'Bearer secret-token', 'Content-Type': 'application/json' };

async function start(opts = {}) {
  const dataDir = opts.dataDir || fs.mkdtempSync(path.join(os.tmpdir(), 'okb-reports-'));
  const server = createServer({ dataDir, tokens: ['secret-token'], log: () => {}, ...opts });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const api = async (method, p, body, headers = auth) => {
    const res = await fetch(`${base}${p}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    return { status: res.status, body: await res.json().catch(() => null) };
  };
  return { server, base, dataDir, api, reports: server.okb.reports, close: () => new Promise((r) => server.close(r)) };
}

let seq = 0;
function msg(overrides = {}) {
  seq++;
  return {
    deviceId: DEVICE,
    clientMessageId: `client-${seq}`,
    fingerprint: crypto.createHash('sha256').update(`fp-${seq}-${Math.random()}`).digest('hex'),
    groupName: 'DPWH Flood Monitoring',
    senderName: 'Engineer A',
    messageText: F.MONITORING_REPORT,
    timestamp: '2026-10-04T14:05:00+08:00',
    timestampMillis: 1791180300000,
    mediaType: 'TEXT',
    mediaStatus: 'NONE',
    sourcePackage: 'com.whatsapp',
    capturedAt: '2026-10-04T14:05:01+08:00',
    ...overrides,
  };
}

async function post(t, body) {
  const r = await t.api('POST', '/api/v1/messages', body);
  await t.reports.drain();
  return r;
}

async function reportFor(t, messageId) {
  const r = t.reports.forMessage(messageId);
  return r ? (await t.api('GET', `/api/v1/reports/${r.id}`)).body : null;
}

/** Phase 2 local-sink media flow for one photo linked to a message fingerprint. */
async function uploadPhoto(t, fingerprint, label, { r2 = false } = {}) {
  const bytes = Buffer.from(`jpeg-${label}-${'x'.repeat(500)}`);
  const sha256 = crypto.createHash('sha256').update(bytes).digest('hex');
  const meta = { deviceId: DEVICE, sha256, mediaType: 'IMAGE', mimeType: 'image/jpeg', fileSizeBytes: bytes.length, capturedAt: '2026-10-04T14:05:00+08:00', messageFingerprint: fingerprint };
  const intent = (await t.api('POST', '/api/v1/media/intent', meta)).body;
  if (intent.status === 'upload' && !r2) {
    const put = await fetch(intent.uploadUrl, { method: 'PUT', headers: { 'Content-Type': 'image/jpeg' }, body: bytes });
    assert.strictEqual(put.status, 200);
  }
  const complete = await t.api('POST', '/api/v1/media/complete', { ...meta, objectKey: intent.objectKey, etag: 'etag', senderName: 'Engineer A', groupName: 'DPWH Flood Monitoring' });
  assert.ok([200, 201].includes(complete.status), JSON.stringify(complete.body));
  return complete.body;
}

const routes = () => F.aiByText([
  ['Marcos Alvarez', F.multiLocationAnswer()],
  ['Flood subsided at 3:30 PM', F.partialAnswer()],
  ['FLOOD MONITORING REPORT', F.monitoringAnswer()],
]);

test('WhatsApp message → report: extracted, provenance kept, original preserved, logs written', async () => {
  const t = await start({ ai: routes() });
  const body = msg();
  const stored = await post(t, body);
  assert.strictEqual(stored.status, 201);
  const report = await reportFor(t, stored.body.id);
  assert.strictEqual(report.status, 'needs_review');
  assert.strictEqual(report.platform, 'whatsapp');
  assert.strictEqual(report.reportType, 'flood_monitoring');
  assert.strictEqual(report.extraction.locations[0].flood.currentFloodHeight.value, 0.2);
  // Original content and provenance are preserved verbatim next to the extraction.
  assert.strictEqual(report.source.messageText, body.messageText);
  assert.strictEqual(report.source.groupName, 'DPWH Flood Monitoring');
  assert.strictEqual(report.source.senderName, 'Engineer A');
  assert.strictEqual(report.source.messageId, stored.body.id);
  assert.strictEqual(report.source.deviceId, DEVICE);
  assert.strictEqual(report.source.messageTimestamp, body.timestamp);
  assert.strictEqual(report.source.senderId, null);
  const list = (await t.api('GET', '/api/v1/messages')).body;
  assert.strictEqual(list.messages[0].messageText, body.messageText, 'stored message untouched');
  const events = report.logs.map((l) => l.event);
  for (const e of ['received', 'processing_started', 'classification_completed', 'extraction_completed', 'validation_completed', 'report_saved', 'needs_review']) {
    assert.ok(events.includes(e), e);
  }
  assert.ok(!JSON.stringify(report.logs).includes('Daang Hari'), 'logs never contain message bodies');
  await t.close();
});

test('Viber group message goes through the same pipeline and keeps Viber provenance', async () => {
  const ai = routes();
  const t = await start({ ai });
  const wa = await post(t, msg());
  const vb = await post(t, msg({ platform: 'viber', sourcePackage: 'com.viber.voip', groupName: 'DPWH Flood Monitoring', senderName: 'Engineer A' }));
  const r = await reportFor(t, vb.body.id);
  assert.strictEqual(r.platform, 'viber');
  assert.strictEqual(r.source.platform, 'viber');
  assert.strictEqual(r.source.groupName, 'DPWH Flood Monitoring');
  assert.strictEqual(r.source.senderName, 'Engineer A');
  assert.strictEqual(r.source.sourcePackage, 'com.viber.voip');
  assert.strictEqual(ai.calls[0].system, ai.calls[1].system, 'same extraction prompt');
  assert.deepStrictEqual(r.extraction, (await reportFor(t, wa.body.id)).extraction);
  const viberOnly = (await t.api('GET', '/api/v1/reports?platform=viber')).body;
  assert.strictEqual(viberOnly.count, 1);
  await t.close();
});

test('Viber platform is derived from the package for clients that do not send "platform"', async () => {
  const t = await start({ ai: routes() });
  const r = await post(t, msg({ sourcePackage: 'com.viber.voip' }));
  assert.strictEqual((await reportFor(t, r.body.id)).platform, 'viber');
  await t.close();
});

test('duplicate WhatsApp and duplicate Viber messages never create a second report', async () => {
  const ai = routes();
  const t = await start({ ai });
  for (const extra of [{}, { platform: 'viber', sourcePackage: 'com.viber.voip' }]) {
    const body = msg(extra);
    const first = await post(t, body);
    const again = await post(t, body);
    assert.strictEqual(again.status, 200);
    assert.strictEqual(again.body.duplicate, true);
    assert.strictEqual(again.body.id, first.body.id);
  }
  assert.strictEqual((await t.api('GET', '/api/v1/reports')).body.count, 2);
  assert.strictEqual(ai.calls.length, 2);
  await t.close();
});

test('same text and fingerprint from WhatsApp and Viber are two different source messages', async () => {
  const t = await start({ ai: routes() });
  const body = msg();
  const wa = await post(t, body);
  const vb = await post(t, { ...body, platform: 'viber', sourcePackage: 'com.viber.voip' });
  assert.strictEqual(wa.status, 201);
  assert.strictEqual(vb.status, 201);
  assert.notStrictEqual(wa.body.id, vb.body.id);
  const platforms = (await t.api('GET', '/api/v1/reports')).body.reports.map((r) => r.platform).sort();
  assert.deepStrictEqual(platforms, ['viber', 'whatsapp']);
  await t.close();
});

test('unsupported / inconsistent platforms are rejected; unknown packages are kept but not processed', async () => {
  const t = await start({ ai: routes() });
  assert.strictEqual((await post(t, msg({ platform: 'telegram' }))).status, 400);
  assert.strictEqual((await post(t, msg({ platform: 'viber', sourcePackage: 'com.whatsapp' }))).status, 400);
  const unknown = await post(t, msg({ sourcePackage: 'org.example.chat' }));
  assert.strictEqual(unknown.status, 201);
  const r = await reportFor(t, unknown.body.id);
  assert.strictEqual(r.status, 'ignored');
  assert.strictEqual(r.extractionMeta.reason, 'UNSUPPORTED_PLATFORM');
  await t.close();
});

test('Phase 1 messages without platform/sourcePackage are WhatsApp, marked as a legacy default', async () => {
  const t = await start({ ai: routes() });
  const body = msg();
  delete body.sourcePackage;
  const r = await reportFor(t, (await post(t, body)).body.id);
  assert.strictEqual(r.platform, 'whatsapp');
  assert.strictEqual(r.source.platformBasis, 'legacy_default');
  await t.close();
});

test('multiple locations → exactly one report with two locations', async () => {
  const t = await start({ ai: routes() });
  const r = await reportFor(t, (await post(t, msg({ messageText: F.MULTI_LOCATION_REPORT }))).body.id);
  assert.strictEqual(r.extraction.locations.length, 2);
  assert.strictEqual((await t.api('GET', '/api/v1/reports')).body.count, 1);
  await t.close();
});

test('spec case end-to-end: no flood start → null + missing, report needs_review (not failed)', async () => {
  const t = await start({ ai: routes() });
  const r = await reportFor(t, (await post(t, msg({ messageText: F.PARTIAL_REPORT }))).body.id);
  const f = r.extraction.locations[0].flood;
  assert.strictEqual(f.currentFloodHeight.value, 0.2);
  assert.strictEqual(f.floodStartedAt.value, null);
  assert.strictEqual(f.floodSubsidedAt.value, '15:30');
  assert.ok(r.extractionMeta.missingFields.some((p) => p.endsWith('floodStartedAt')));
  assert.strictEqual(r.status, 'needs_review');
  await t.close();
});

test('report media reuses Phase 2 objects: multiple photos, captions, single-location association', async () => {
  const t = await start({ ai: routes() });
  const body = msg({ mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' });
  const stored = await post(t, body);
  await uploadPhoto(t, body.fingerprint, 'a');
  await uploadPhoto(t, body.fingerprint, 'b');
  const objectsBefore = fs.readFileSync(path.join(t.dataDir, 'media.jsonl'), 'utf8').trim().split('\n').length;
  const r = await reportFor(t, stored.body.id);
  assert.strictEqual(r.media.length, 2);
  for (const m of r.media) {
    assert.ok(m.remoteRef.startsWith('local://whatsapp/'));
    assert.strictEqual(m.sourceMessageId, stored.body.id);
    assert.strictEqual(m.association, 'source_message');
    assert.strictEqual(m.caption, body.messageText);
    assert.strictEqual(m.locationIndex, 0);
    assert.strictEqual(m.locationAssociationBasis, 'single_location_report');
  }
  // Retrying extraction never creates new media objects.
  await t.api('POST', `/api/v1/reports/${r.id}/retry`, {});
  assert.strictEqual(fs.readFileSync(path.join(t.dataDir, 'media.jsonl'), 'utf8').trim().split('\n').length, objectsBefore);
  await t.close();
});

test('media on a multi-location report stays on the report (location not guessed)', async () => {
  const t = await start({ ai: routes() });
  const body = msg({ messageText: F.MULTI_LOCATION_REPORT, mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' });
  const stored = await post(t, body);
  await uploadPhoto(t, body.fingerprint, 'multi');
  const r = await reportFor(t, stored.body.id);
  assert.strictEqual(r.media.length, 1);
  assert.strictEqual(r.media[0].locationIndex, null);
  await t.close();
});

test('R2-associated media: report references the existing r2:// object; a second message reuses it', async () => {
  const t = await start({ ai: routes(), r2: { accountId: 'acc', bucket: 'okb-media', accessKeyId: 'AK', secretAccessKey: 'SK' } });
  const first = msg({ mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' });
  const second = msg({ mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE', messageText: F.PARTIAL_REPORT });
  const s1 = await post(t, first);
  const s2 = await post(t, second);
  await uploadPhoto(t, first.fingerprint, 'same-bytes', { r2: true });
  await uploadPhoto(t, second.fingerprint, 'same-bytes', { r2: true }); // identical content → existing object
  const r1 = await reportFor(t, s1.body.id);
  const r2 = await reportFor(t, s2.body.id);
  assert.ok(r1.media[0].remoteRef.startsWith('r2://okb-media/whatsapp/'));
  assert.strictEqual(r1.media[0].storage, 'r2');
  assert.strictEqual(r2.media[0].objectKey, r1.media[0].objectKey, 'same R2 object, not re-uploaded');
  assert.strictEqual(fs.readFileSync(path.join(t.dataDir, 'media.jsonl'), 'utf8').trim().split('\n').length, 1);
  await t.close();
});

test('caption-less media: separate photo is a review item, suggested for the report, linked by a reviewer', async () => {
  const ai = routes();
  const t = await start({ ai });
  const text = await post(t, msg({ timestamp: '2026-10-04T14:05:00+08:00' }));
  const photoMsg = msg({ messageText: null, mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE', timestamp: '2026-10-04T14:06:00+08:00' });
  const photo = await post(t, photoMsg);
  await uploadPhoto(t, photoMsg.fingerprint, 'captionless');
  assert.strictEqual(ai.calls.length, 1, 'caption-less media never goes to the AI');

  const photoReport = await reportFor(t, photo.body.id);
  assert.strictEqual(photoReport.status, 'needs_review');
  assert.strictEqual(photoReport.extractionMeta.warnings[0].code, 'MEDIA_WITHOUT_TEXT');
  assert.strictEqual(photoReport.media.length, 1);
  assert.strictEqual(photoReport.media[0].caption, null);

  let report = await reportFor(t, text.body.id);
  assert.strictEqual(report.media.length, 0, 'never attached automatically');
  assert.deepStrictEqual(report.mediaSuggestions.map((s) => s.messageId), [photo.body.id]);

  const linked = await t.api('POST', `/api/v1/reports/${report.id}/review`, { action: 'link_media', messageIds: [photo.body.id], reviewer: 'ops' });
  assert.strictEqual(linked.status, 200);
  report = linked.body;
  assert.strictEqual(report.media.length, 1);
  assert.strictEqual(report.media[0].association, 'reviewer_linked');
  assert.strictEqual(report.mediaSuggestions.length, 0);

  const otherPlatform = await post(t, msg({ messageText: null, mediaType: 'IMAGE', platform: 'viber', sourcePackage: 'com.viber.voip' }));
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${report.id}/review`, { action: 'link_media', messageIds: [otherPlatform.body.id] })).status, 400);
  await t.close();
});

test('media the phone could not acquire is still visible as an indicator', async () => {
  const t = await start({ ai: routes() });
  const r = await reportFor(t, (await post(t, msg({ mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' }))).body.id);
  assert.deepStrictEqual(r.mediaIndicators[0], { messageId: r.messageId, mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' });
  assert.strictEqual(r.media.length, 0);
  await t.close();
});

test('system notifications (WhatsApp and Viber) become ignored records without AI cost', async () => {
  const ai = routes();
  const t = await start({ ai });
  await post(t, msg({ messageText: 'WhatsApp Web is currently active' }));
  await post(t, msg({ messageText: 'Missed Viber call', platform: 'viber', sourcePackage: 'com.viber.voip' }));
  assert.strictEqual(ai.calls.length, 0);
  assert.strictEqual((await t.api('GET', '/api/v1/reports')).body.count, 0, 'hidden from the default list');
  assert.strictEqual((await t.api('GET', '/api/v1/reports?status=ignored')).body.count, 2);
  await t.close();
});

test('AI failure → failed (with reason); retry later succeeds on the SAME report', async () => {
  let down = true;
  const ai = F.fakeAi(() => {
    if (down) throw new AiError('unavailable', 'AI HTTP 529: overloaded', { retryable: true, attempts: 3 });
    return F.monitoringAnswer();
  });
  const t = await start({ ai });
  const stored = await post(t, msg());
  let r = await reportFor(t, stored.body.id);
  assert.strictEqual(r.status, 'failed');
  assert.strictEqual(r.processing.lastError.code, 'unavailable');
  assert.ok(r.logs.some((l) => l.event === 'failed'));

  down = false;
  const retried = await t.api('POST', `/api/v1/reports/${r.id}/retry`, {});
  assert.strictEqual(retried.status, 200);
  assert.strictEqual(retried.body.status, 'needs_review');
  assert.strictEqual(retried.body.id, r.id);
  assert.strictEqual(retried.body.processing.attempts, 2);
  assert.strictEqual(retried.body.processing.lastError, null);
  assert.ok(retried.body.logs.some((l) => l.event === 'retry_requested'));
  assert.strictEqual((await t.api('GET', '/api/v1/reports?includeIgnored=1')).body.count, 1);
  r = await reportFor(t, stored.body.id);
  assert.strictEqual(r.status, 'needs_review');
  await t.close();
});

test('AI malformed/invalid output ends in "failed", never in a corrupted report', async () => {
  const ai = F.fakeAi(() => ({ classification: { reportType: 'flood_monitoring' } }));
  const t = await start({ ai });
  const r = await reportFor(t, (await post(t, msg())).body.id);
  assert.strictEqual(r.status, 'failed');
  assert.strictEqual(r.processing.lastError.code, 'invalid_schema');
  assert.strictEqual(r.extraction, null);
  await t.close();
});

test('duplicate processing requests are idempotent; concurrent retry is refused', async () => {
  let release;
  const gate = new Promise((r) => { release = r; });
  let gated = false;
  const ai = F.fakeAi(async () => {
    if (gated) await gate;
    return F.monitoringAnswer();
  });
  const t = await start({ ai, autoProcessReports: false });
  const stored = await post(t, msg());
  const first = await t.api('POST', '/api/v1/reports/process', { messageId: stored.body.id });
  assert.strictEqual(first.body.alreadyProcessed, false);
  const again = await t.api('POST', '/api/v1/reports/process', { messageId: stored.body.id });
  assert.strictEqual(again.body.alreadyProcessed, true);
  assert.strictEqual(again.body.id, first.body.id);
  assert.strictEqual(ai.calls.length, 1);

  gated = true;
  const running = t.api('POST', `/api/v1/reports/${first.body.id}/retry`, {});
  await new Promise((r) => setTimeout(r, 30));
  const concurrent = await t.api('POST', `/api/v1/reports/${first.body.id}/retry`, {});
  assert.strictEqual(concurrent.status, 409);
  release();
  assert.strictEqual((await running).status, 200);
  assert.strictEqual((await t.api('POST', '/api/v1/reports/process', { messageId: 'nope' })).status, 404);
  await t.close();
});

test('database failure while saving → report reported as failed (storage_error), recoverable by retry', async () => {
  const t = await start({ ai: routes() });
  const stored = await post(t, msg());
  const r = await reportFor(t, stored.body.id);
  const store = t.reports._store;
  const realAppend = store.append;
  let writes = 0;
  store.append = (rec) => { writes++; if (writes > 1) throw new Error('disk full'); return realAppend(rec); };
  const res = await t.api('POST', `/api/v1/reports/${r.id}/retry`, {});
  assert.strictEqual(res.status, 200);
  assert.strictEqual(res.body.status, 'failed');
  assert.strictEqual(res.body.processing.lastError.code, 'storage_error');
  store.append = realAppend;
  const ok = await t.api('POST', `/api/v1/reports/${r.id}/retry`, {});
  assert.strictEqual(ok.body.status, 'needs_review');
  await t.close();
});

test('review: approve, reject, reopen; reviewed reports are only re-extracted with force', async () => {
  const t = await start({ ai: routes() });
  const r = await reportFor(t, (await post(t, msg())).body.id);
  const approved = await t.api('POST', `/api/v1/reports/${r.id}/review`, { action: 'approve', reviewer: 'Engr. Reviewer', notes: 'checked against photo' });
  assert.strictEqual(approved.body.status, 'approved');
  assert.strictEqual(approved.body.review.history[0].reviewer, 'Engr. Reviewer');
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${r.id}/review`, { action: 'approve' })).status, 409);
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${r.id}/retry`, {})).status, 409);
  const forced = await t.api('POST', `/api/v1/reports/${r.id}/retry`, { force: true });
  assert.strictEqual(forced.body.status, 'needs_review', 're-extraction is never auto-approved');
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${r.id}/review`, { action: 'reject' })).body.status, 'rejected');
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${r.id}/review`, { action: 'reopen' })).body.status, 'needs_review');
  assert.strictEqual((await t.api('POST', `/api/v1/reports/${r.id}/review`, { action: 'delete' })).status, 400);
  await t.close();
});

test('without AI configured, messages are stored, deterministic cases resolve, AI cases wait in "received"', async () => {
  const t = await start({ ai: createAiClient({ apiKey: null, baseUrl: 'https://api.anthropic.com', model: 'm', timeoutMs: 1, maxRetries: 0 }) });
  assert.strictEqual((await t.api('GET', '/api/v1/health')).body.reports.ai, 'not_configured');
  const report = await reportFor(t, (await post(t, msg())).body.id);
  assert.strictEqual(report.status, 'received');
  assert.ok(report.logs.some((l) => l.event === 'ai_not_configured'));
  const sys = await reportFor(t, (await post(t, msg({ messageText: 'Checking for new messages' }))).body.id);
  assert.strictEqual(sys.status, 'ignored');
  await t.close();
});

test('reports persist across restart; an interrupted run is recovered, not stuck in "processing"', async () => {
  const t = await start({ ai: routes() });
  const body = msg();
  const stored = await post(t, body);
  const id = t.reports.forMessage(stored.body.id).id;
  fs.appendFileSync(path.join(t.dataDir, 'reports.jsonl'), JSON.stringify({ ...t.reports.forMessage(stored.body.id), status: 'processing' }) + '\n');
  await t.close();

  const t2 = await start({ ai: routes(), dataDir: t.dataDir });
  await t2.reports.drain();
  const r = (await t2.api('GET', `/api/v1/reports/${id}`)).body;
  assert.strictEqual(r.status, 'needs_review');
  assert.ok(r.logs.some((l) => l.event === 'recovered_after_restart'));
  // Dedupe survives the restart too.
  assert.strictEqual((await t2.api('POST', '/api/v1/messages', body)).body.duplicate, true);
  await t2.close();
});

test('report endpoints accept only admin tokens when OKB_ADMIN_TOKENS is configured', async () => {
  const t = await start({ ai: routes(), adminTokens: ['admin-token'] });
  assert.strictEqual((await t.api('GET', '/api/v1/reports')).status, 401);
  const admin = { Authorization: 'Bearer admin-token', 'Content-Type': 'application/json' };
  assert.strictEqual((await t.api('GET', '/api/v1/reports', undefined, admin)).status, 200);
  assert.strictEqual((await t.api('POST', '/api/v1/messages', msg())).status, 201, 'devices unaffected');
  assert.strictEqual((await t.api('GET', '/api/v1/reports', undefined, { Authorization: 'Bearer nope' })).status, 401);
  await t.close();
});
