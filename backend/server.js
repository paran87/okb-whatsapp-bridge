'use strict';
/**
 * Reference backend for the OKB WhatsApp Bridge.
 *
 *   GET  /api/v1/health
 *   POST /api/v1/devices/register
 *   POST /api/v1/messages
 *   GET  /api/v1/messages?limit=50          (operator verification)
 *   POST /api/v1/media/intent               (Phase 2: get an upload URL, or a duplicate verdict)
 *   PUT  /api/v1/media/blob?key=..&exp=..&sig=..   (Phase 2: dev local-sink, only when R2 is NOT configured)
 *   POST /api/v1/media/complete             (Phase 2: confirm an uploaded object)
 *   GET  /api/v1/media/:id                   (Phase 2: media metadata)
 *   GET  /api/v1/media?limit=50              (Phase 2: operator verification)
 *   GET  /api/v1/reports                     (Phase 3: flood reports; ?status=&platform=&reportType=&includeIgnored=1)
 *   GET  /api/v1/reports/:id                 (Phase 3: extraction, original source, media, processing logs)
 *   POST /api/v1/reports/process             (Phase 3: {messageId} — process one message now; idempotent)
 *   POST /api/v1/reports/:id/retry           (Phase 3: re-extract; {force:true} for reviewed reports)
 *   POST /api/v1/reports/:id/review          (Phase 3: {action: approve|reject|reopen|link_media})
 *
 * Dependency-free (Node >= 18). Stores data as JSON/JSONL files in DATA_DIR. Intended for development
 * and acceptance testing; put it behind HTTPS (reverse proxy) for anything beyond a test LAN.
 *
 * Media architecture: the device never holds R2 credentials. `media/intent` returns a short-lived,
 * key-scoped upload URL — a presigned R2 PUT when R2 is configured, otherwise a signed local-sink URL
 * on this backend (DEV ONLY) so the whole pipeline is testable without R2. The Android contract is the
 * same either way: intent -> PUT the bytes -> complete.
 *
 * Configuration (environment):
 *   PORT                default 8080
 *   HOST                default 0.0.0.0
 *   DATA_DIR            default ./data
 *   OKB_DEVICE_TOKENS   comma-separated bearer tokens accepted from devices (required)
 *   OKB_ALLOW_NO_AUTH   "1" to accept requests without a token (DEVELOPMENT ONLY)
 *   OKB_MAX_MEDIA_BYTES local-sink blob size cap (default 2147483648 = 2 GiB)
 *   OKB_ADMIN_TOKENS    comma-separated bearer tokens for the /reports endpoints. When unset, device tokens
 *                       are accepted there too (same as the existing operator-verification endpoints).
 *   OKB_REPORT_AUTO_PROCESS  "0" to disable automatic report processing of new messages (default on)
 *   AI_PROVIDER, AI_API_KEY, AI_BASE_URL, AI_MODEL, AI_TIMEOUT_MS, AI_MAX_RETRIES
 *                       server-side AI (OpenAI Responses API) used for flood-report extraction (see lib/ai.js). Without AI_API_KEY,
 *                       messages are still stored and deterministic filtering still runs; AI-dependent
 *                       reports wait in status "received".
 *   R2_ACCOUNT_ID, R2_BUCKET_NAME, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY
 *                       when ALL are set, media uploads are presigned directly to Cloudflare R2.
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createR2Client } = require('./lib/r2');
const { createAiClient, configFromEnv } = require('./lib/ai');
const { createReportService } = require('./lib/reports');
const { resolvePlatform, messageDedupeKey, platformOfRecord } = require('./lib/sources');

const VERSION = '3.0.0';
const MAX_BODY_BYTES = 64 * 1024;
const MEDIA_TYPES = new Set(['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'LOCATION', 'STICKER', 'UNKNOWN']);
const UPLOADABLE_MEDIA = new Set(['IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'STICKER', 'UNKNOWN']);
const DEVICE_ID = /^OKB-ANDROID-[0-9A-F]{6}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const OBJECT_KEY = /^whatsapp\/OKB-ANDROID-[0-9A-F]{6}\/\d{4}\/\d{2}\/\d{2}\/[0-9a-f]{64}\.[a-z0-9]{1,5}$/;

const EXT_BY_MIME = {
  'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif',
  'image/heic': 'heic', 'video/mp4': 'mp4', 'video/3gpp': '3gp', 'video/webm': 'webm', 'video/quicktime': 'mov',
  'audio/ogg': 'ogg', 'audio/opus': 'opus', 'audio/mpeg': 'mp3', 'audio/amr': 'amr', 'audio/mp4': 'm4a',
  'application/pdf': 'pdf', 'text/vcard': 'vcf', 'application/zip': 'zip',
};
const EXT_BY_TYPE = { IMAGE: 'jpg', VIDEO: 'mp4', AUDIO: 'ogg', STICKER: 'webp', DOCUMENT: 'bin' };

function extensionFor(mimeType, mediaType, originalFileName) {
  const mime = typeof mimeType === 'string' ? mimeType.split(';')[0].trim().toLowerCase() : null;
  if (mime && EXT_BY_MIME[mime]) return EXT_BY_MIME[mime];
  if (typeof originalFileName === 'string') {
    const m = /\.([A-Za-z0-9]{1,5})$/.exec(originalFileName);
    if (m) return m[1].toLowerCase();
  }
  return EXT_BY_TYPE[mediaType] || 'bin';
}

function buildObjectKey(deviceId, sha256, ext, capturedAtIso) {
  const d = new Date(capturedAtIso);
  const when = Number.isNaN(d.getTime()) ? new Date() : d;
  const yyyy = when.getUTCFullYear();
  const mm = String(when.getUTCMonth() + 1).padStart(2, '0');
  const dd = String(when.getUTCDate()).padStart(2, '0');
  return `whatsapp/${deviceId}/${yyyy}/${mm}/${dd}/${sha256}.${ext}`;
}

function createServer(options = {}) {
  const dataDir = options.dataDir || process.env.DATA_DIR || path.join(__dirname, 'data');
  const tokens = (options.tokens ?? (process.env.OKB_DEVICE_TOKENS || '').split(','))
    .map((t) => t.trim())
    .filter(Boolean);
  const allowNoAuth = options.allowNoAuth ?? process.env.OKB_ALLOW_NO_AUTH === '1';
  const maxMediaBytes = options.maxMediaBytes ?? parseInt(process.env.OKB_MAX_MEDIA_BYTES || '2147483648', 10);
  const log = options.log || ((...args) => console.log(new Date().toISOString(), ...args));

  const adminTokens = (options.adminTokens ?? (process.env.OKB_ADMIN_TOKENS || '').split(','))
    .map((t) => t.trim())
    .filter(Boolean);

  if (tokens.length === 0 && !allowNoAuth) {
    throw new Error('Configure OKB_DEVICE_TOKENS (or OKB_ALLOW_NO_AUTH=1 for local testing only).');
  }

  // R2 is active only when every credential is present; otherwise the dev local-sink is used.
  const r2Config = options.r2 ?? {
    accountId: process.env.R2_ACCOUNT_ID,
    bucket: process.env.R2_BUCKET_NAME,
    accessKeyId: process.env.R2_ACCESS_KEY_ID,
    secretAccessKey: process.env.R2_SECRET_ACCESS_KEY,
  };
  const r2 = r2Config && r2Config.accountId && r2Config.bucket && r2Config.accessKeyId && r2Config.secretAccessKey
    ? createR2Client(r2Config)
    : null;

  fs.mkdirSync(dataDir, { recursive: true });
  const mediaBlobDir = path.join(dataDir, 'media');
  if (!r2) fs.mkdirSync(mediaBlobDir, { recursive: true });
  const messagesFile = path.join(dataDir, 'messages.jsonl');
  const devicesFile = path.join(dataDir, 'devices.json');
  const mediaFile = path.join(dataDir, 'media.jsonl');
  // Phase 3: extra message <-> media links (e.g. the same bytes attached to a second message).
  const mediaLinksFile = path.join(dataDir, 'media-links.jsonl');

  // Secret for signing dev local-sink upload URLs (never the device token). Persisted across restarts.
  const secretFile = path.join(dataDir, '.media-upload-secret');
  let mediaSecret;
  if (fs.existsSync(secretFile)) {
    mediaSecret = fs.readFileSync(secretFile, 'utf8').trim();
  } else {
    mediaSecret = crypto.randomBytes(32).toString('hex');
    fs.writeFileSync(secretFile, mediaSecret, { mode: 0o600 });
  }

  function loadJsonl(file) {
    const rows = [];
    if (fs.existsSync(file)) {
      for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
        if (!line.trim()) continue;
        try { rows.push(JSON.parse(line)); } catch { /* skip corrupt line */ }
      }
    }
    return rows;
  }

  // Dedupe is per platform: identical text from WhatsApp and Viber are different source messages.
  const byDedupeKey = new Map();
  const messagesById = new Map();
  const messages = loadJsonl(messagesFile);
  for (const m of messages) {
    byDedupeKey.set(messageDedupeKey(platformOfRecord(m), m.fingerprint), m.id);
    messagesById.set(m.id, m);
  }

  const mediaObjects = loadJsonl(mediaFile);
  const mediaByKey = new Map();
  const mediaById = new Map();
  for (const m of mediaObjects) { mediaByKey.set(m.objectKey, m); mediaById.set(m.id, m); }

  // (deviceId, messageFingerprint) -> media ids. Built from media records and explicit links.
  const mediaIdsByMessage = new Map();
  const linkKey = (deviceId, fingerprint) => `${deviceId}|${fingerprint}`;
  function indexMediaLink(deviceId, fingerprint, mediaId) {
    if (!deviceId || !fingerprint || !mediaId) return false;
    const k = linkKey(deviceId, fingerprint);
    if (!mediaIdsByMessage.has(k)) mediaIdsByMessage.set(k, new Set());
    const set = mediaIdsByMessage.get(k);
    if (set.has(mediaId)) return false;
    set.add(mediaId);
    return true;
  }
  for (const m of mediaObjects) indexMediaLink(m.deviceId, m.messageFingerprint, m.id);
  for (const l of loadJsonl(mediaLinksFile)) indexMediaLink(l.deviceId, l.messageFingerprint, l.mediaId);

  /** Records that an existing media object also belongs to another message (no new R2 object). */
  function linkMedia(record, deviceId, messageFingerprint) {
    if (!SHA256.test(messageFingerprint || '')) return;
    if (indexMediaLink(deviceId, messageFingerprint, record.id)) {
      fs.appendFileSync(mediaLinksFile, JSON.stringify({
        mediaId: record.id, objectKey: record.objectKey, deviceId, messageFingerprint, linkedAt: new Date().toISOString(),
      }) + '\n');
    }
  }

  function mediaForMessage(message) {
    const ids = mediaIdsByMessage.get(linkKey(message.deviceId, message.fingerprint));
    return ids ? [...ids].map((id) => mediaById.get(id)).filter(Boolean) : [];
  }

  let devices = {};
  if (fs.existsSync(devicesFile)) {
    try { devices = JSON.parse(fs.readFileSync(devicesFile, 'utf8')); } catch { devices = {}; }
  }

  function bearerMatches(req, accepted) {
    const match = /^Bearer (.+)$/.exec(req.headers.authorization || '');
    if (!match) return false;
    const given = Buffer.from(match[1]);
    return accepted.some((t) => {
      const expected = Buffer.from(t);
      return expected.length === given.length && crypto.timingSafeEqual(expected, given);
    });
  }

  function authorized(req) {
    if (allowNoAuth && tokens.length === 0) return true;
    return bearerMatches(req, tokens);
  }

  /** Report review/processing: admin tokens when configured, otherwise the device tokens. */
  function authorizedAdmin(req) {
    if (adminTokens.length > 0) return bearerMatches(req, adminTokens);
    return authorized(req);
  }

  // --- Phase 3: flood-report extraction ---
  const ai = options.ai !== undefined ? options.ai : createAiClient(undefined, { log });
  const reports = createReportService({
    dataDir,
    ai,
    log,
    getMessage: (id) => messagesById.get(id),
    allMessages: () => messages,
    mediaForMessage,
    autoProcess: options.autoProcessReports ?? process.env.OKB_REPORT_AUTO_PROCESS !== '0',
    concurrency: options.reportConcurrency ?? 2,
  });

  function send(res, status, body) {
    const json = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(json) });
    res.end(json);
  }

  function readJson(req) {
    return new Promise((resolve, reject) => {
      let size = 0;
      const chunks = [];
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_BODY_BYTES) {
          reject(Object.assign(new Error('Payload too large'), { status: 413 }));
          req.destroy();
          return;
        }
        chunks.push(c);
      });
      req.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')); } catch {
          reject(Object.assign(new Error('Invalid JSON'), { status: 400 }));
        }
      });
      req.on('error', reject);
    });
  }

  const isString = (v) => typeof v === 'string';
  const optionalString = (v) => v === null || v === undefined || isString(v);

  function signUpload(objectKey, exp) {
    return crypto.createHmac('sha256', mediaSecret).update(`${objectKey}|${exp}`).digest('hex');
  }

  function localSinkUrl(req, objectKey) {
    const exp = Date.now() + 15 * 60 * 1000;
    const sig = signUpload(objectKey, exp);
    const host = req.headers.host || 'localhost';
    const q = `key=${encodeURIComponent(objectKey)}&exp=${exp}&sig=${sig}`;
    return { url: `http://${host}/api/v1/media/blob?${q}`, expiresAt: new Date(exp).toISOString() };
  }

  // --- dev local-sink blob upload (only reachable when R2 is not configured) ---
  function handleBlobPut(req, res, url) {
    if (r2) return send(res, 404, { error: 'not found' });
    const objectKey = url.searchParams.get('key') || '';
    const exp = parseInt(url.searchParams.get('exp') || '0', 10);
    const sig = url.searchParams.get('sig') || '';
    if (!OBJECT_KEY.test(objectKey)) return send(res, 400, { error: 'invalid object key' });
    if (!exp || exp < Date.now()) return send(res, 403, { error: 'upload url expired' });
    const expected = signUpload(objectKey, exp);
    if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) {
      return send(res, 403, { error: 'invalid upload signature' });
    }
    const target = path.join(mediaBlobDir, objectKey);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    const tmp = `${target}.part`;
    const hash = crypto.createHash('md5');
    let size = 0;
    let aborted = false;
    const out = fs.createWriteStream(tmp);
    req.on('data', (c) => {
      size += c.length;
      if (size > maxMediaBytes) {
        aborted = true;
        out.destroy();
        req.destroy();
        fs.rm(tmp, { force: true }, () => {});
        if (!res.headersSent) send(res, 413, { error: 'media too large' });
        return;
      }
      hash.update(c);
      out.write(c);
    });
    req.on('end', () => {
      if (aborted) return;
      out.end(() => {
        fs.renameSync(tmp, target);
        const etag = hash.digest('hex');
        log(`media blob stored ${objectKey} (${size} bytes)`);
        res.writeHead(200, { ETag: `"${etag}"`, 'Content-Length': 0 });
        res.end();
      });
    });
    req.on('error', () => { if (!res.headersSent) send(res, 400, { error: 'upload failed' }); });
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');

    if (req.method === 'GET' && url.pathname === '/api/v1/health') {
      return send(res, 200, {
        status: 'ok', version: VERSION, time: new Date().toISOString(), media: r2 ? 'r2' : 'local-sink',
        reports: { ai: ai && ai.isConfigured ? 'configured' : 'not_configured' },
      });
    }
    // Blob PUT carries its own signed URL auth (not the device bearer token).
    if (req.method === 'PUT' && url.pathname === '/api/v1/media/blob') return handleBlobPut(req, res, url);

    if (!url.pathname.startsWith('/api/v1/')) return send(res, 404, { error: 'not found' });
    if (url.pathname === '/api/v1/reports' || url.pathname.startsWith('/api/v1/reports/')) {
      if (!authorizedAdmin(req)) return send(res, 401, { error: 'invalid or missing token' });
      return handleReports(req, res, url);
    }
    if (!authorized(req)) return send(res, 401, { error: 'invalid or missing device token' });

    if (req.method === 'POST' && url.pathname === '/api/v1/devices/register') {
      const body = await readJson(req);
      if (!isString(body.deviceId) || !DEVICE_ID.test(body.deviceId)) return send(res, 400, { error: 'invalid deviceId' });
      const existing = devices[body.deviceId];
      devices[body.deviceId] = {
        deviceId: body.deviceId,
        deviceName: isString(body.deviceName) ? body.deviceName.slice(0, 100) : null,
        platform: isString(body.platform) ? body.platform : null,
        appVersion: isString(body.appVersion) ? body.appVersion : null,
        osVersion: isString(body.osVersion) ? body.osVersion : null,
        manufacturer: isString(body.manufacturer) ? body.manufacturer : null,
        model: isString(body.model) ? body.model : null,
        registeredAt: existing ? existing.registeredAt : new Date().toISOString(),
        lastSeenAt: new Date().toISOString(),
      };
      fs.writeFileSync(devicesFile, JSON.stringify(devices, null, 2));
      log(`device registered ${body.deviceId}`);
      return send(res, existing ? 200 : 201, { deviceId: body.deviceId, registered: true });
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/messages') {
      const b = await readJson(req);
      const problems = [];
      if (!isString(b.deviceId) || !DEVICE_ID.test(b.deviceId)) problems.push('deviceId');
      if (!isString(b.fingerprint) || !SHA256.test(b.fingerprint)) problems.push('fingerprint');
      if (!isString(b.timestamp) || Number.isNaN(Date.parse(b.timestamp))) problems.push('timestamp');
      if (!isString(b.mediaType) || !MEDIA_TYPES.has(b.mediaType)) problems.push('mediaType');
      for (const f of ['groupName', 'senderName', 'messageText', 'clientMessageId', 'mediaStatus', 'sourcePackage', 'capturedAt', 'platform', 'groupId', 'senderId']) {
        if (!optionalString(b[f])) problems.push(f);
      }
      if (problems.length) return send(res, 400, { error: `invalid fields: ${problems.join(', ')}` });
      const resolved = resolvePlatform({ platform: b.platform, sourcePackage: b.sourcePackage });
      if (resolved.error) return send(res, 400, { error: resolved.error });

      const dedupeKey = messageDedupeKey(resolved.platform, b.fingerprint);
      const existingId = byDedupeKey.get(dedupeKey);
      if (existingId) return send(res, 200, { id: existingId, status: 'duplicate', duplicate: true });

      const record = {
        id: crypto.randomUUID(),
        receivedAt: new Date().toISOString(),
        deviceId: b.deviceId,
        clientMessageId: b.clientMessageId ?? null,
        fingerprint: b.fingerprint,
        groupName: b.groupName ?? null,
        senderName: b.senderName ?? null,
        messageText: b.messageText ?? null,
        timestamp: b.timestamp,
        timestampMillis: typeof b.timestampMillis === 'number' ? b.timestampMillis : null,
        mediaType: b.mediaType,
        mediaStatus: b.mediaStatus ?? null,
        sourcePackage: b.sourcePackage ?? null,
        capturedAt: b.capturedAt ?? null,
        // Phase 3 provenance. Ids are null when the platform does not expose them (Android notifications don't).
        platform: resolved.platform,
        platformBasis: resolved.platformBasis,
        groupId: b.groupId ?? null,
        senderId: b.senderId ?? null,
      };
      fs.appendFileSync(messagesFile, JSON.stringify(record) + '\n');
      messages.push(record);
      messagesById.set(record.id, record);
      byDedupeKey.set(dedupeKey, record.id);
      if (devices[b.deviceId]) devices[b.deviceId].lastSeenAt = record.receivedAt;
      log(`message stored ${record.id} from ${record.deviceId} (${record.platform || 'unknown platform'}, ${record.mediaType})`);
      send(res, 201, { id: record.id, status: 'stored' });
      reports.onMessageStored(record);
      return undefined;
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/media/intent') {
      const b = await readJson(req);
      const problems = [];
      if (!isString(b.deviceId) || !DEVICE_ID.test(b.deviceId)) problems.push('deviceId');
      if (!isString(b.sha256) || !SHA256.test(b.sha256)) problems.push('sha256');
      if (!isString(b.mediaType) || !UPLOADABLE_MEDIA.has(b.mediaType)) problems.push('mediaType');
      if (typeof b.fileSizeBytes !== 'number' || b.fileSizeBytes <= 0) problems.push('fileSizeBytes');
      if (!isString(b.capturedAt) || Number.isNaN(Date.parse(b.capturedAt))) problems.push('capturedAt');
      for (const f of ['mimeType', 'originalFileName', 'groupName', 'messageFingerprint']) {
        if (!optionalString(b[f])) problems.push(f);
      }
      if (problems.length) return send(res, 400, { error: `invalid fields: ${problems.join(', ')}` });
      if (b.fileSizeBytes > maxMediaBytes) return send(res, 413, { error: 'media too large' });

      const ext = extensionFor(b.mimeType, b.mediaType, b.originalFileName);
      const objectKey = buildObjectKey(b.deviceId, b.sha256, ext, b.capturedAt);

      const existing = mediaByKey.get(objectKey);
      if (existing && existing.completed) {
        linkMedia(existing, b.deviceId, b.messageFingerprint);
        return send(res, 200, {
          status: 'duplicate', objectKey, remoteRef: existing.remoteRef, etag: existing.etag || null,
        });
      }

      if (r2) {
        const presigned = r2.presignPut(objectKey, { contentType: b.mimeType || undefined });
        return send(res, 200, {
          status: 'upload', objectKey, uploadUrl: presigned.url, method: 'PUT',
          headers: presigned.headers, expiresAt: presigned.expiresAt, remoteRef: r2.remoteRef(objectKey),
        });
      }
      const sink = localSinkUrl(req, objectKey);
      const headers = b.mimeType ? { 'Content-Type': b.mimeType } : {};
      return send(res, 200, {
        status: 'upload', objectKey, uploadUrl: sink.url, method: 'PUT',
        headers, expiresAt: sink.expiresAt, remoteRef: `local://${objectKey}`,
      });
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/media/complete') {
      const b = await readJson(req);
      const problems = [];
      if (!isString(b.deviceId) || !DEVICE_ID.test(b.deviceId)) problems.push('deviceId');
      if (!isString(b.sha256) || !SHA256.test(b.sha256)) problems.push('sha256');
      if (!isString(b.objectKey) || !OBJECT_KEY.test(b.objectKey)) problems.push('objectKey');
      if (typeof b.fileSizeBytes !== 'number' || b.fileSizeBytes <= 0) problems.push('fileSizeBytes');
      if (!isString(b.mediaType) || !UPLOADABLE_MEDIA.has(b.mediaType)) problems.push('mediaType');
      for (const f of ['etag', 'mimeType', 'originalFileName', 'groupName', 'senderName', 'messageFingerprint', 'capturedAt']) {
        if (!optionalString(b[f])) problems.push(f);
      }
      if (problems.length) return send(res, 400, { error: `invalid fields: ${problems.join(', ')}` });
      // The object key is content-addressed; it must match the device + hash that claims it.
      if (!b.objectKey.includes(`/${b.deviceId}/`) || !b.objectKey.includes(`/${b.sha256}.`)) {
        return send(res, 400, { error: 'objectKey does not match deviceId/sha256' });
      }
      // In local-sink mode the bytes must actually be on disk before we record the object.
      if (!r2 && !fs.existsSync(path.join(mediaBlobDir, b.objectKey))) {
        return send(res, 409, { error: 'object not uploaded' });
      }

      const existing = mediaByKey.get(b.objectKey);
      if (existing && existing.completed) {
        linkMedia(existing, b.deviceId, b.messageFingerprint);
        return send(res, 200, { id: existing.id, objectKey: existing.objectKey, etag: existing.etag, remoteRef: existing.remoteRef, status: 'duplicate' });
      }

      const remoteRef = r2 ? r2.remoteRef(b.objectKey) : `local://${b.objectKey}`;
      const record = {
        id: crypto.randomUUID(),
        completed: true,
        receivedAt: new Date().toISOString(),
        deviceId: b.deviceId,
        objectKey: b.objectKey,
        sha256: b.sha256,
        etag: b.etag ?? null,
        fileSizeBytes: b.fileSizeBytes,
        mediaType: b.mediaType,
        mimeType: b.mimeType ?? null,
        originalFileName: b.originalFileName ?? null,
        groupName: b.groupName ?? null,
        senderName: b.senderName ?? null,
        messageFingerprint: b.messageFingerprint ?? null,
        capturedAt: b.capturedAt ?? null,
        storage: r2 ? 'r2' : 'local',
        remoteRef,
      };
      fs.appendFileSync(mediaFile, JSON.stringify(record) + '\n');
      mediaObjects.push(record);
      mediaByKey.set(record.objectKey, record);
      mediaById.set(record.id, record);
      indexMediaLink(record.deviceId, record.messageFingerprint, record.id);
      if (devices[b.deviceId]) devices[b.deviceId].lastSeenAt = record.receivedAt;
      log(`media completed ${record.id} ${record.objectKey} (${record.fileSizeBytes} bytes, ${record.storage})`);
      return send(res, 201, { id: record.id, objectKey: record.objectKey, etag: record.etag, remoteRef, status: 'stored' });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/messages') {
      const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 500);
      return send(res, 200, { count: messages.length, messages: messages.slice(-limit).reverse() });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/media') {
      const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 500);
      return send(res, 200, { count: mediaObjects.length, media: mediaObjects.slice(-limit).reverse() });
    }

    if (req.method === 'GET' && url.pathname.startsWith('/api/v1/media/')) {
      const id = url.pathname.slice('/api/v1/media/'.length);
      const record = mediaById.get(id);
      if (!record) return send(res, 404, { error: 'not found' });
      return send(res, 200, record);
    }

    return send(res, 404, { error: 'not found' });
  }

  async function handleReports(req, res, url) {
    const parts = url.pathname.split('/').filter(Boolean); // ['api','v1','reports', id?, action?]
    const id = parts[3];
    const action = parts[4];

    if (req.method === 'GET' && parts.length === 3) {
      const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 500);
      return send(res, 200, reports.list({
        status: url.searchParams.get('status') || undefined,
        platform: url.searchParams.get('platform') || undefined,
        reportType: url.searchParams.get('reportType') || undefined,
        includeIgnored: url.searchParams.get('includeIgnored') === '1',
        limit,
      }));
    }
    if (req.method === 'POST' && id === 'process' && parts.length === 4) {
      const b = await readJson(req);
      if (!isString(b.messageId)) return send(res, 400, { error: 'messageId is required' });
      const { report, alreadyProcessed } = await reports.processMessage(b.messageId);
      return send(res, 200, { ...reports.get(report.id), alreadyProcessed });
    }
    if (req.method === 'GET' && id && parts.length === 4) {
      const report = reports.get(id);
      return report ? send(res, 200, report) : send(res, 404, { error: 'not found' });
    }
    if (req.method === 'POST' && id && action === 'retry' && parts.length === 5) {
      const b = await readJson(req);
      const report = await reports.retry(id, { force: b.force === true });
      return send(res, 200, reports.get(report.id));
    }
    if (req.method === 'POST' && id && action === 'review' && parts.length === 5) {
      const b = await readJson(req);
      const report = reports.review(id, { action: b.action, reviewer: b.reviewer, notes: b.notes, messageIds: b.messageIds });
      return send(res, 200, reports.get(report.id));
    }
    return send(res, 404, { error: 'not found' });
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      const status = err.status || 500;
      if (status === 500) log('internal error', err.message);
      if (!res.headersSent) send(res, status, { error: status === 500 ? 'internal error' : err.message });
    });
  });
  /** In-process hooks for tests and embedding (not exposed over HTTP). */
  server.okb = { reports };
  return server;
}

if (require.main === module) {
  const port = parseInt(process.env.PORT || '8080', 10);
  const host = process.env.HOST || '0.0.0.0';
  const server = createServer();
  const mode = process.env.R2_ACCOUNT_ID ? 'R2' : 'local-sink (DEV)';
  const aiConfig = configFromEnv();
  const ai = aiConfig.apiKey ? `AI ${aiConfig.provider} ${aiConfig.model}` : 'AI not configured (reports wait in "received")';
  server.listen(port, host, () => console.log(`OKB bridge reference backend listening on http://${host}:${port} — media: ${mode} — reports: ${ai}`));
  if (!process.env.OKB_ADMIN_TOKENS) {
    console.log('note: OKB_ADMIN_TOKENS is not set, so device tokens can also read and review flood reports.');
  }
}

module.exports = { createServer };
