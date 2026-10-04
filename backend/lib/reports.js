'use strict';
/**
 * Flood-report persistence, processing and review.
 *
 * One report record per source message (WhatsApp or Viber) — never one per location and never two for
 * the same message. Records live in DATA_DIR/reports.jsonl (append-only; the last line for an id wins,
 * like the rest of this backend's JSON/JSONL storage) and every processing step is written to
 * DATA_DIR/processing-logs.jsonl.
 *
 * The original message is never modified: it stays in messages.jsonl, and the report keeps an immutable
 * snapshot of it (`source`). AI output is a derived representation stored next to it (`extraction`).
 * Media is not copied or re-uploaded: report media is resolved from the Phase 2 media records (R2
 * objects) linked to the source message.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { toSourceMessage, messageDedupeKey } = require('./sources');
const { extractReport } = require('./flood/extractor');
const { REPORT_STATUS } = require('./flood/schema');
const { MEDIA_PLACEHOLDER } = require('./flood/prefilter');

const HISTORY_LIMIT = 20;
const LOGS_PER_REPORT_LIMIT = 200;
const MEDIA_SUGGESTION_WINDOW_MS = 15 * 60 * 1000;
const REVIEWABLE = new Set([REPORT_STATUS.EXTRACTED, REPORT_STATUS.NEEDS_REVIEW]);
const FLOOD_REPORT_TYPES = new Set(['flood_monitoring', 'flood_prone_area_assessment', 'non_flood_prone_area_assessment', 'other_flood_report']);

function httpError(status, message) {
  return Object.assign(new Error(message), { status });
}

/** Append-only JSONL file whose last record per id wins. */
function createJsonlStore(file) {
  return {
    load() {
      const rows = [];
      if (!fs.existsSync(file)) return rows;
      for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
        if (!line.trim()) continue;
        try { rows.push(JSON.parse(line)); } catch { /* skip corrupt line */ }
      }
      return rows;
    },
    append(record) {
      fs.appendFileSync(file, JSON.stringify(record) + '\n');
    },
  };
}

/**
 * @param {object} opts
 * @param {string} opts.dataDir
 * @param {object|null} opts.ai AI client (lib/ai.js) — may be unconfigured
 * @param {(id: string) => object|undefined} opts.getMessage stored message by id
 * @param {() => object[]} opts.allMessages every stored message
 * @param {(record: object) => object[]} opts.mediaForMessage completed media linked to a stored message
 * @param {boolean} [opts.autoProcess] process new messages automatically (default true)
 * @param {number} [opts.concurrency] parallel extractions (default 2)
 */
function createReportService(opts) {
  const {
    dataDir, ai, getMessage, allMessages, mediaForMessage,
    autoProcess = true, concurrency = 2, log = () => {}, now = () => new Date(),
  } = opts;

  const store = createJsonlStore(path.join(dataDir, 'reports.jsonl'));
  const logStore = createJsonlStore(path.join(dataDir, 'processing-logs.jsonl'));

  const reports = new Map();
  const byMessageId = new Map();
  for (const r of store.load()) { reports.set(r.id, r); byMessageId.set(r.messageId, r.id); }
  const logsByReport = new Map();
  for (const entry of logStore.load()) pushLog(entry);

  const inFlight = new Set();
  const queued = new Set();
  const queue = [];
  let running = 0;
  let idleWaiters = [];

  function pushLog(entry) {
    if (!logsByReport.has(entry.reportId)) logsByReport.set(entry.reportId, []);
    const list = logsByReport.get(entry.reportId);
    list.push(entry);
    if (list.length > LOGS_PER_REPORT_LIMIT) list.shift();
  }

  /** Processing log. Never contains message bodies or credentials; never throws. */
  function logEvent(report, event, detail = null) {
    const entry = {
      id: crypto.randomUUID(), at: now().toISOString(), reportId: report.id, messageId: report.messageId,
      platform: report.platform, event, ...(detail ? { detail } : {}),
    };
    pushLog(entry);
    try { logStore.append(entry); } catch (err) { log(`processing log write failed: ${err.message}`); }
  }

  /** Writes first, then updates memory, so a failed write never leaves memory claiming it was saved. */
  function persist(record) {
    const next = { ...record, updatedAt: now().toISOString() };
    store.append(next);
    reports.set(next.id, next);
    byMessageId.set(next.messageId, next.id);
    return next;
  }

  /** Memory-only update used when storage is failing, so the API still reports the truth. */
  function remember(record) {
    reports.set(record.id, record);
    return record;
  }

  function ensureReport(messageRecord) {
    const existingId = byMessageId.get(messageRecord.id);
    if (existingId) return reports.get(existingId);
    const source = toSourceMessage(messageRecord);
    delete source.media; // media is resolved live from the Phase 2 media index
    const at = now().toISOString();
    const report = persist({
      id: crypto.randomUUID(),
      messageId: messageRecord.id,
      platform: source.platform,
      sourceKey: messageDedupeKey(source.platform, source.fingerprint),
      deviceId: source.deviceId,
      source,
      status: REPORT_STATUS.RECEIVED,
      reportType: null,
      classification: null,
      extraction: null,
      extractionMeta: null,
      processing: { attempts: 0, lastAttemptAt: null, lastError: null, history: [] },
      review: { linkedMessageIds: [], history: [] },
      createdAt: at,
    });
    logEvent(report, 'received');
    return report;
  }

  function withHistory(processing, entry) {
    return { ...processing, history: [...processing.history, entry].slice(-HISTORY_LIMIT) };
  }

  async function runProcessing(id, trigger) {
    const report = reports.get(id);
    if (!report) throw httpError(404, 'report not found');
    if (inFlight.has(id)) throw httpError(409, 'report is already being processed');
    inFlight.add(id);
    try {
      const startedAt = now();
      let working;
      try {
        working = persist({
          ...report,
          status: REPORT_STATUS.PROCESSING,
          processing: { ...report.processing, attempts: report.processing.attempts + 1, lastAttemptAt: startedAt.toISOString() },
        });
      } catch (err) {
        logEvent(report, 'failed', { code: 'storage_error', message: err.message, trigger });
        throw httpError(500, 'storage error');
      }
      logEvent(working, 'processing_started', { trigger, attempt: working.processing.attempts });

      const messageRecord = getMessage(working.messageId);
      if (!messageRecord) return fail(working, 'source_missing', 'source message not found', trigger);
      const source = toSourceMessage(messageRecord, mediaForMessage(messageRecord));

      let result;
      try {
        result = await extractReport(source, { ai });
      } catch (err) {
        if (err.code === 'not_configured') {
          logEvent(working, 'ai_not_configured');
          return save(working, { ...working, status: REPORT_STATUS.RECEIVED });
        }
        const code = err.code || 'internal_error';
        logEvent(working, 'ai_failed', { code, attempts: err.attempts || null });
        return fail(working, code, err.code ? err.message : 'unexpected processing error', trigger);
      }

      logEvent(working, 'classification_completed', {
        reportType: result.reportType, basis: result.classification.basis, confidence: result.classification.confidence,
      });
      if (result.extraction) {
        logEvent(working, 'extraction_completed', {
          locations: result.extraction.locations.length, aiAttempts: result.metadata.aiAttempts,
        });
        logEvent(working, 'validation_completed', {
          missing: result.metadata.missingFields.length,
          ambiguous: result.metadata.ambiguousFields.length,
          warnings: result.metadata.warnings.length,
        });
      }
      const finishedAt = now();
      const final = {
        ...working,
        status: result.status,
        reportType: result.reportType,
        classification: result.classification,
        extraction: result.extraction,
        extractionMeta: { ...result.metadata, extractedAt: finishedAt.toISOString(), durationMs: finishedAt - startedAt },
        processing: withHistory({ ...working.processing, lastError: null }, {
          at: finishedAt.toISOString(), trigger, status: result.status, model: result.metadata.model,
        }),
      };
      const saved = save(working, final);
      if (saved.status === result.status) logEvent(saved, 'report_saved', { status: saved.status });
      if (saved.status === REPORT_STATUS.NEEDS_REVIEW) logEvent(saved, 'needs_review', { warnings: result.metadata.warnings.map((w) => w.code).slice(0, 20) });
      return saved;
    } finally {
      inFlight.delete(id);
    }
  }

  /** Saves a processing outcome; a storage failure turns it into a (memory-only) failed state. */
  function save(working, next) {
    try {
      return persist(next);
    } catch (err) {
      log(`report ${working.id} could not be saved: ${err.message}`);
      logEvent(working, 'failed', { code: 'storage_error', message: err.message });
      return remember({
        ...working,
        status: REPORT_STATUS.FAILED,
        processing: { ...working.processing, lastError: { code: 'storage_error', message: 'report could not be saved' } },
      });
    }
  }

  function fail(working, code, message, trigger) {
    logEvent(working, 'failed', { code, message: String(message).slice(0, 300), trigger });
    return save(working, {
      ...working,
      status: REPORT_STATUS.FAILED,
      processing: withHistory({ ...working.processing, lastError: { code, message: String(message).slice(0, 300) } }, {
        at: now().toISOString(), trigger, status: REPORT_STATUS.FAILED, error: code,
      }),
    });
  }

  // ---- background queue (bounded concurrency; one in-flight run per report) ----

  function enqueue(id) {
    if (queued.has(id) || inFlight.has(id)) return;
    queued.add(id);
    queue.push(id);
    pump();
  }

  function pump() {
    while (running < concurrency && queue.length) {
      const id = queue.shift();
      queued.delete(id);
      running++;
      runProcessing(id, 'auto')
        .catch((err) => log(`report ${id} processing error: ${err.message}`))
        .finally(() => {
          running--;
          pump();
          if (running === 0 && queue.length === 0) {
            const waiters = idleWaiters;
            idleWaiters = [];
            waiters.forEach((w) => w());
          }
        });
    }
  }

  /** Resolves when the background queue is empty (used by tests and graceful shutdown). */
  function drain() {
    if (running === 0 && queue.length === 0) return Promise.resolve();
    return new Promise((resolve) => idleWaiters.push(resolve));
  }

  // Startup recovery: a run interrupted by a restart is re-queued, never left "processing".
  for (const r of [...reports.values()]) {
    if (r.status === REPORT_STATUS.PROCESSING) {
      try {
        const reset = persist({ ...r, status: REPORT_STATUS.RECEIVED });
        logEvent(reset, 'recovered_after_restart');
      } catch (err) { log(`could not recover report ${r.id}: ${err.message}`); }
    }
  }
  if (autoProcess) for (const r of reports.values()) if (r.status === REPORT_STATUS.RECEIVED) enqueue(r.id);

  // ---- public operations ----

  /** Called after a new message is stored. Never throws into the ingest path. */
  function onMessageStored(messageRecord) {
    try {
      const report = ensureReport(messageRecord);
      if (autoProcess) enqueue(report.id);
    } catch (err) {
      log(`could not create report for message ${messageRecord.id}: ${err.message}`);
    }
  }

  /** Processes the report for a message, creating it if needed. Idempotent for processed reports. */
  async function processMessage(messageId) {
    const record = getMessage(messageId);
    if (!record) throw httpError(404, 'message not found');
    const report = ensureReport(record);
    if (report.status !== REPORT_STATUS.RECEIVED && report.status !== REPORT_STATUS.FAILED) {
      return { report, alreadyProcessed: true };
    }
    return { report: await runProcessing(report.id, 'manual'), alreadyProcessed: false };
  }

  async function retry(id, { force = false } = {}) {
    const report = reports.get(id);
    if (!report) throw httpError(404, 'report not found');
    if (inFlight.has(id) || report.status === REPORT_STATUS.PROCESSING) throw httpError(409, 'report is already being processed');
    if ((report.status === REPORT_STATUS.APPROVED || report.status === REPORT_STATUS.REJECTED) && !force) {
      throw httpError(409, `report is ${report.status}; pass force=true to re-extract it`);
    }
    logEvent(report, 'retry_requested', { previousStatus: report.status, force: Boolean(force) });
    return runProcessing(id, 'retry');
  }

  function review(id, { action, reviewer = null, notes = null, messageIds = [] } = {}) {
    const report = reports.get(id);
    if (!report) throw httpError(404, 'report not found');
    if (inFlight.has(id) || report.status === REPORT_STATUS.PROCESSING) throw httpError(409, 'report is being processed');
    const entry = {
      action, at: now().toISOString(),
      reviewer: typeof reviewer === 'string' ? reviewer.slice(0, 100) : null,
      notes: typeof notes === 'string' ? notes.slice(0, 1000) : null,
    };
    let next;
    switch (action) {
      case 'approve':
        if (!REVIEWABLE.has(report.status)) throw httpError(409, `cannot approve a report in status ${report.status}`);
        if (!report.extraction) throw httpError(409, 'nothing extracted to approve');
        next = { ...report, status: REPORT_STATUS.APPROVED };
        break;
      case 'reject':
        next = { ...report, status: REPORT_STATUS.REJECTED };
        break;
      case 'reopen':
        if (report.status !== REPORT_STATUS.APPROVED && report.status !== REPORT_STATUS.REJECTED) {
          throw httpError(409, 'only approved or rejected reports can be reopened');
        }
        next = { ...report, status: REPORT_STATUS.NEEDS_REVIEW };
        break;
      case 'link_media': {
        if (!Array.isArray(messageIds) || messageIds.length === 0 || messageIds.length > 50) {
          throw httpError(400, 'messageIds must be a non-empty array (max 50)');
        }
        const linked = new Set(report.review.linkedMessageIds);
        for (const mid of messageIds) {
          const m = typeof mid === 'string' ? getMessage(mid) : null;
          if (!m) throw httpError(400, `unknown message ${mid}`);
          if (m.id === report.messageId) throw httpError(400, 'a report cannot link its own message');
          const s = toSourceMessage(m);
          // Only messages from the same platform conversation can belong to the same report.
          if (s.platform !== report.platform || s.groupName !== report.source.groupName) {
            throw httpError(400, `message ${mid} is from a different platform or group`);
          }
          linked.add(m.id);
        }
        entry.messageIds = [...linked];
        next = { ...report, review: { ...report.review, linkedMessageIds: [...linked] } };
        break;
      }
      default:
        throw httpError(400, 'action must be approve, reject, reopen or link_media');
    }
    next = { ...next, review: { ...next.review, history: [...next.review.history, entry].slice(-HISTORY_LIMIT) } };
    const saved = persist(next);
    logEvent(saved, 'reviewed', { action, status: saved.status });
    return saved;
  }

  // ---- read models ----

  function captionOf(message) {
    const text = typeof message.messageText === 'string' ? message.messageText.trim() : '';
    return text && !MEDIA_PLACEHOLDER.test(text) ? text : null;
  }

  function mediaView(report) {
    const out = [];
    const indicators = [];
    const singleLocation = report.extraction && report.extraction.locations.length === 1;
    const add = (message, association) => {
      indicators.push({ messageId: message.id, mediaType: message.mediaType ?? null, mediaStatus: message.mediaStatus ?? null });
      for (const m of mediaForMessage(message)) {
        out.push({
          mediaId: m.id,
          objectKey: m.objectKey,
          remoteRef: m.remoteRef,
          storage: m.storage,
          mediaType: m.mediaType,
          mimeType: m.mimeType,
          fileSizeBytes: m.fileSizeBytes,
          sha256: m.sha256,
          originalFileName: m.originalFileName,
          capturedAt: m.capturedAt,
          sourceMessageId: message.id,
          caption: captionOf(message),
          association,
          // A photo is tied to a location only when the report has exactly one; otherwise it stays on the report.
          locationIndex: singleLocation ? 0 : null,
          locationAssociationBasis: singleLocation ? 'single_location_report' : null,
        });
      }
    };
    const own = getMessage(report.messageId);
    if (own) add(own, 'source_message');
    for (const mid of report.review.linkedMessageIds) {
      const m = getMessage(mid);
      if (m) add(m, 'reviewer_linked');
    }
    return { media: out, mediaIndicators: indicators };
  }

  /**
   * Caption-less media posted separately by the same sender in the same conversation shortly before or
   * after the report. These are suggestions for the reviewer only — never attached automatically.
   */
  function mediaSuggestions(report) {
    if (report.reportType && !FLOOD_REPORT_TYPES.has(report.reportType)) return [];
    const t0 = Date.parse(report.source.messageTimestamp);
    if (Number.isNaN(t0)) return [];
    const linked = new Set(report.review.linkedMessageIds);
    const out = [];
    for (const m of allMessages()) {
      if (m.id === report.messageId || linked.has(m.id)) continue;
      const s = toSourceMessage(m);
      if (s.platform !== report.platform || s.deviceId !== report.deviceId) continue;
      if (s.groupName !== report.source.groupName || s.senderName !== report.source.senderName) continue;
      if (!m.mediaType || m.mediaType === 'TEXT' || m.mediaType === 'LOCATION' || captionOf(m)) continue;
      const t = Date.parse(m.timestamp);
      if (Number.isNaN(t) || Math.abs(t - t0) > MEDIA_SUGGESTION_WINDOW_MS) continue;
      out.push({ messageId: m.id, timestamp: m.timestamp, mediaType: m.mediaType, mediaStatus: m.mediaStatus ?? null, basis: 'same_sender_within_15_minutes' });
    }
    return out;
  }

  function get(id) {
    const report = reports.get(id);
    if (!report) return null;
    return {
      ...report,
      ...mediaView(report),
      mediaSuggestions: mediaSuggestions(report),
      logs: logsByReport.get(id) || [],
    };
  }

  function summary(r) {
    const meta = r.extractionMeta || {};
    return {
      id: r.id, messageId: r.messageId, platform: r.platform, status: r.status, reportType: r.reportType,
      groupName: r.source.groupName, senderName: r.source.senderName, messageTimestamp: r.source.messageTimestamp,
      locations: r.extraction ? r.extraction.locations.length : 0,
      missingFields: (meta.missingFields || []).length,
      ambiguousFields: (meta.ambiguousFields || []).length,
      warnings: (meta.warnings || []).length,
      createdAt: r.createdAt, updatedAt: r.updatedAt,
    };
  }

  function list({ status, platform, reportType, includeIgnored = false, limit = 50 } = {}) {
    let rows = [...reports.values()];
    if (status) rows = rows.filter((r) => r.status === status);
    else if (!includeIgnored) rows = rows.filter((r) => r.status !== REPORT_STATUS.IGNORED);
    if (platform) rows = rows.filter((r) => r.platform === platform);
    if (reportType) rows = rows.filter((r) => r.reportType === reportType);
    rows.sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0));
    return { count: rows.length, reports: rows.slice(0, limit).map(summary) };
  }

  return {
    onMessageStored, processMessage, retry, review, get, list, drain,
    forMessage: (messageId) => reports.get(byMessageId.get(messageId)) || null,
    /** Test hooks. */
    _store: store,
  };
}

module.exports = { createReportService };
