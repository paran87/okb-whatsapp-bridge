'use strict';
/**
 * Minimal reference backend for the OKB WhatsApp Bridge.
 *
 *   GET  /api/v1/health
 *   POST /api/v1/devices/register
 *   POST /api/v1/messages
 *   GET  /api/v1/messages?limit=50     (operator verification)
 *
 * Dependency-free (Node >= 18). Stores data as JSON files in DATA_DIR. Intended for development
 * and acceptance testing; put it behind HTTPS (reverse proxy) for anything beyond a test LAN.
 *
 * Configuration (environment):
 *   PORT                default 8080
 *   HOST                default 0.0.0.0
 *   DATA_DIR            default ./data
 *   OKB_DEVICE_TOKENS   comma-separated bearer tokens accepted from devices (required)
 *   OKB_ALLOW_NO_AUTH   set to "1" to accept requests without a token (local testing only)
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const VERSION = '1.0.0';
const MAX_BODY_BYTES = 64 * 1024;
const MEDIA_TYPES = new Set(['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'LOCATION', 'STICKER', 'UNKNOWN']);
const DEVICE_ID = /^OKB-ANDROID-[0-9A-F]{6}$/;

function createServer(options = {}) {
  const dataDir = options.dataDir || process.env.DATA_DIR || path.join(__dirname, 'data');
  const tokens = (options.tokens ?? (process.env.OKB_DEVICE_TOKENS || '').split(','))
    .map((t) => t.trim())
    .filter(Boolean);
  const allowNoAuth = options.allowNoAuth ?? process.env.OKB_ALLOW_NO_AUTH === '1';
  const log = options.log || ((...args) => console.log(new Date().toISOString(), ...args));

  if (tokens.length === 0 && !allowNoAuth) {
    throw new Error('Configure OKB_DEVICE_TOKENS (or OKB_ALLOW_NO_AUTH=1 for local testing only).');
  }

  fs.mkdirSync(dataDir, { recursive: true });
  const messagesFile = path.join(dataDir, 'messages.jsonl');
  const devicesFile = path.join(dataDir, 'devices.json');

  // Fingerprint → stored id, rebuilt from disk so duplicates are rejected across restarts.
  const byFingerprint = new Map();
  const messages = [];
  if (fs.existsSync(messagesFile)) {
    for (const line of fs.readFileSync(messagesFile, 'utf8').split('\n')) {
      if (!line.trim()) continue;
      try {
        const m = JSON.parse(line);
        messages.push(m);
        byFingerprint.set(m.fingerprint, m.id);
      } catch {
        /* skip corrupt line */
      }
    }
  }
  let devices = {};
  if (fs.existsSync(devicesFile)) {
    try { devices = JSON.parse(fs.readFileSync(devicesFile, 'utf8')); } catch { devices = {}; }
  }

  function authorized(req) {
    if (allowNoAuth && tokens.length === 0) return true;
    const header = req.headers.authorization || '';
    const match = /^Bearer (.+)$/.exec(header);
    if (!match) return false;
    const given = Buffer.from(match[1]);
    return tokens.some((t) => {
      const expected = Buffer.from(t);
      return expected.length === given.length && crypto.timingSafeEqual(expected, given);
    });
  }

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

  async function handle(req, res) {
    const url = new URL(req.url, 'http://localhost');

    if (req.method === 'GET' && url.pathname === '/api/v1/health') {
      return send(res, 200, { status: 'ok', version: VERSION, time: new Date().toISOString() });
    }
    if (!url.pathname.startsWith('/api/v1/')) return send(res, 404, { error: 'not found' });
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
      if (!isString(b.fingerprint) || !/^[0-9a-f]{64}$/.test(b.fingerprint)) problems.push('fingerprint');
      if (!isString(b.timestamp) || Number.isNaN(Date.parse(b.timestamp))) problems.push('timestamp');
      if (!isString(b.mediaType) || !MEDIA_TYPES.has(b.mediaType)) problems.push('mediaType');
      for (const f of ['groupName', 'senderName', 'messageText', 'clientMessageId', 'mediaStatus', 'sourcePackage', 'capturedAt']) {
        if (!optionalString(b[f])) problems.push(f);
      }
      if (problems.length) return send(res, 400, { error: `invalid fields: ${problems.join(', ')}` });

      const existingId = byFingerprint.get(b.fingerprint);
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
      };
      // Persist before acknowledging: the device deletes nothing until it sees this 201.
      fs.appendFileSync(messagesFile, JSON.stringify(record) + '\n');
      messages.push(record);
      byFingerprint.set(record.fingerprint, record.id);
      if (devices[b.deviceId]) devices[b.deviceId].lastSeenAt = record.receivedAt;
      log(`message stored ${record.id} from ${record.deviceId} (${record.mediaType})`);
      return send(res, 201, { id: record.id, status: 'stored' });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/messages') {
      const limit = Math.min(Math.max(parseInt(url.searchParams.get('limit') || '50', 10) || 50, 1), 500);
      return send(res, 200, { count: messages.length, messages: messages.slice(-limit).reverse() });
    }

    return send(res, 404, { error: 'not found' });
  }

  return http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      const status = err.status || 500;
      if (status === 500) log('internal error', err.message);
      if (!res.headersSent) send(res, status, { error: status === 500 ? 'internal error' : err.message });
    });
  });
}

if (require.main === module) {
  const port = parseInt(process.env.PORT || '8080', 10);
  const host = process.env.HOST || '0.0.0.0';
  const server = createServer();
  server.listen(port, host, () => console.log(`OKB bridge reference backend listening on http://${host}:${port}`));
}

module.exports = { createServer };
