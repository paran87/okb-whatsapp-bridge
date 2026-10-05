'use strict';
/**
 * OpenAI Responses API client tests. Every HTTP exchange is mocked: the suite never needs a real key.
 */
const test = require('node:test');
const assert = require('node:assert');
const { createAiClient, configFromEnv, responsesEndpoint, redact } = require('../lib/ai');
const { AI_OUTPUT_SCHEMA, validateAiOutput } = require('../lib/flood/schema');
const { extractReport } = require('../lib/flood/extractor');
const F = require('./helpers/flood-fixtures');

const KEY = 'sk-proj-test-secret-0123456789';
const config = { provider: 'openai', apiKey: KEY, baseUrl: 'https://api.openai.com', model: 'gpt-5.6-luna', timeoutMs: 200, maxRetries: 2 };

function headersOf(h = {}) {
  const lower = Object.fromEntries(Object.entries(h).map(([k, v]) => [k.toLowerCase(), v]));
  return { get: (k) => lower[k.toLowerCase()] ?? null };
}

function response(status, body, headers = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return { ok: status >= 200 && status < 300, status, text: async () => text, headers: headersOf({ 'x-request-id': 'req_123', ...headers }) };
}

/** A completed Responses API result whose message carries `data` as output_text. */
function completed(data, extra = {}) {
  return {
    id: 'resp_1', object: 'response', status: 'completed', model: 'gpt-5.6-luna-2026-09-01', error: null, incomplete_details: null,
    output: [
      { type: 'reasoning', id: 'rs_1', summary: [] },
      { type: 'message', id: 'msg_1', role: 'assistant', status: 'completed', content: [{ type: 'output_text', text: typeof data === 'string' ? data : JSON.stringify(data), annotations: [] }] },
    ],
    usage: { input_tokens: 10, output_tokens: 20, total_tokens: 30 },
    ...extra,
  };
}

function openAiError(status, message, code = null, type = 'invalid_request_error') {
  return response(status, { error: { message, type, param: null, code } });
}

/** fetch stub returning queued responses (a function entry is called with the request). */
function fetchQueue(...queue) {
  const requests = [];
  const impl = async (url, init) => {
    requests.push({ url, init });
    const next = queue.shift();
    if (!next) throw new Error('no more responses');
    return typeof next === 'function' ? next(url, init) : next;
  };
  return { impl, requests };
}

const request = { system: 'sys', user: 'msg', schema: AI_OUTPUT_SCHEMA, schemaName: 'flood_report_extraction', validate: validateAiOutput };
const deps = (f, extra = {}) => ({ fetchImpl: f.impl, sleep: async () => {}, random: () => 0, ...extra });
const client = (f, overrides = {}, extra = {}) => createAiClient({ ...config, ...overrides }, deps(f, extra));

test('reads AI_* configuration with OpenAI defaults', () => {
  const c = configFromEnv({ AI_API_KEY: 'k', AI_TIMEOUT_MS: '5000', AI_MAX_RETRIES: '1' });
  assert.deepStrictEqual(c, { provider: 'openai', apiKey: 'k', baseUrl: 'https://api.openai.com', model: 'gpt-5.6-luna', timeoutMs: 5000, maxRetries: 1 });
  assert.strictEqual(configFromEnv({ AI_PROVIDER: ' OpenAI ' }).provider, 'openai');
  assert.strictEqual(configFromEnv({}).apiKey, null);
  assert.strictEqual(responsesEndpoint('https://api.openai.com'), 'https://api.openai.com/v1/responses');
  assert.strictEqual(responsesEndpoint('https://gw.example.org/v1/'), 'https://gw.example.org/v1/responses');
});

test('1. successful response: Responses API request with strict json_schema output', async () => {
  const f = fetchQueue(response(200, completed(F.partialAnswer())));
  const r = await client(f).generateJson(request);
  assert.strictEqual(r.attempts, 1);
  assert.strictEqual(r.servedModel, 'gpt-5.6-luna-2026-09-01');
  assert.strictEqual(r.requestId, 'req_123');
  const { url, init } = f.requests[0];
  assert.strictEqual(url, 'https://api.openai.com/v1/responses');
  assert.strictEqual(init.method, 'POST');
  assert.strictEqual(init.headers.authorization, `Bearer ${KEY}`);
  assert.strictEqual(init.headers['content-type'], 'application/json');
  const body = JSON.parse(init.body);
  assert.strictEqual(body.model, 'gpt-5.6-luna');
  assert.strictEqual(body.instructions, 'sys');
  assert.deepStrictEqual(body.input, [{ role: 'user', content: 'msg' }]);
  assert.deepStrictEqual(body.text.format, { type: 'json_schema', name: 'flood_report_extraction', schema: AI_OUTPUT_SCHEMA, strict: true });
  assert.strictEqual(body.store, false);
  assert.ok(body.max_output_tokens > 0);
});

test('2. structured extraction through the real client feeds the unchanged extraction engine', async () => {
  const f = fetchQueue(response(200, completed(F.monitoringAnswer())));
  const source = {
    platform: 'viber', messageId: 'm1', fingerprint: 'a'.repeat(64), deviceId: 'OKB-ANDROID-A82F19', groupId: null,
    groupName: 'DPWH Flood Monitoring', senderId: null, senderName: 'Engineer A', messageText: F.MONITORING_REPORT,
    messageTimestamp: '2026-10-05T09:00:00+08:00', mediaIndicator: { mediaType: 'TEXT', mediaStatus: 'NONE' }, media: [],
  };
  const r = await extractReport(source, { ai: client(f) });
  assert.strictEqual(r.reportType, 'flood_monitoring');
  assert.strictEqual(r.extraction.locations[0].flood.currentFloodHeight.value, 0.2);
  assert.strictEqual(r.extraction.locations[0].flood.floodSubsidedAt.value, '15:30');
  assert.strictEqual(r.metadata.servedModel, 'gpt-5.6-luna-2026-09-01');
  assert.strictEqual(r.metadata.aiRequestId, 'req_123');
  assert.strictEqual(r.status, 'needs_review', 'fields the report omits are still missing');
});

test('3. a response that passes the provider schema but not our validator is rejected', async () => {
  const contradictory = F.partialAnswer();
  contradictory.reportTitle = { raw: 'invented title', status: 'missing' }; // "missing" must not carry text
  const extra = { ...F.partialAnswer(), extraField: 'x' };
  const f = fetchQueue(response(200, completed(contradictory)), response(200, completed(extra)), response(200, completed({ classification: {} })));
  await assert.rejects(client(f).generateJson(request), (e) => e.code === 'invalid_schema' && e.attempts === 3);
});

test('4. 401: no retry, key never appears in the error', async () => {
  const f = fetchQueue(openAiError(401, `Incorrect API key provided: ${KEY.slice(0, 8)}****6789. You can find your API key at https://platform.openai.com/account/api-keys.`, 'invalid_api_key'));
  await assert.rejects(client(f).generateJson(request), (e) => {
    assert.strictEqual(e.code, 'auth');
    assert.strictEqual(e.retryable, false);
    assert.strictEqual(e.status, 401);
    assert.ok(!e.message.includes('sk-proj'), e.message);
    assert.ok(!e.message.includes(KEY));
    return true;
  });
  assert.strictEqual(f.requests.length, 1);
});

test('400, 403 and 404 are permanent and not retried', async () => {
  for (const [status, code] of [[400, 'bad_request'], [403, 'permission'], [404, 'not_found']]) {
    const f = fetchQueue(openAiError(status, 'nope', status === 404 ? 'model_not_found' : null));
    await assert.rejects(client(f).generateJson(request), (e) => e.code === code && !e.retryable && e.status === status);
    assert.strictEqual(f.requests.length, 1, String(status));
  }
});

test('5. 429 rate limit is retried honouring retry-after; quota exhaustion is not retried', async () => {
  const waits = [];
  const limited = { ...openAiError(429, 'Rate limit reached for requests', 'rate_limit_exceeded'), headers: headersOf({ 'retry-after': '2' }) };
  const f = fetchQueue(limited, response(200, completed(F.partialAnswer())));
  const r = await client(f, {}, { sleep: async (ms) => waits.push(ms) }).generateJson(request);
  assert.strictEqual(r.attempts, 2);
  assert.deepStrictEqual(waits, [2000]);

  const quota = fetchQueue(openAiError(429, 'You exceeded your current quota', 'insufficient_quota', 'insufficient_quota'));
  await assert.rejects(client(quota).generateJson(request), (e) => e.code === 'quota_exceeded' && !e.retryable);
  assert.strictEqual(quota.requests.length, 1);
});

test('6. 500, 502 and 503 are retried; 408 and 409 too', async () => {
  const f = fetchQueue(openAiError(500, 'server error', null, 'server_error'), openAiError(503, 'overloaded', 'server_is_overloaded'), response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(f).generateJson(request)).attempts, 3);
  const g = fetchQueue(openAiError(502, 'bad gateway'), openAiError(408, 'timeout'), response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(g).generateJson(request)).attempts, 3);
  const h = fetchQueue(openAiError(409, 'conflict'), response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(h).generateJson(request)).attempts, 2);
});

test('7. timeout aborts the request and is retried', async () => {
  const hang = (url, init) => new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  const f = fetchQueue(hang, response(200, completed(F.partialAnswer())));
  const r = await client(f, { timeoutMs: 20 }).generateJson(request);
  assert.strictEqual(r.attempts, 2);
});

test('8. retry then success after a network failure', async () => {
  const f = fetchQueue(async () => { throw new Error('ECONNRESET'); }, response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(f).generateJson(request)).attempts, 2);
});

test('9. retry exhaustion: bounded by AI_MAX_RETRIES, then fails', async () => {
  const f = fetchQueue(...[1, 2, 3, 4].map(() => openAiError(503, 'overloaded', 'server_is_overloaded')));
  await assert.rejects(client(f).generateJson(request), (e) => e.code === 'unavailable' && e.attempts === 3 && e.status === 503);
  assert.strictEqual(f.requests.length, 3);
  const none = fetchQueue(openAiError(503, 'overloaded'), openAiError(503, 'overloaded'));
  await assert.rejects(client(none, { maxRetries: 0 }).generateJson(request), (e) => e.attempts === 1);
  assert.strictEqual(none.requests.length, 1);
});

test('10. malformed JSON (body or output text) is retried and fails cleanly', async () => {
  const f = fetchQueue(response(200, 'not json'), response(200, completed('{"half":')), response(200, completed('still not json')));
  await assert.rejects(client(f).generateJson(request), (e) => e.code === 'malformed_output' && e.attempts === 3);
});

test('11. refusal, content filter, truncation and empty output are handled explicitly', async () => {
  const refusal = completed('{}');
  refusal.output[1].content = [{ type: 'refusal', refusal: 'I cannot help with that.' }];
  const f1 = fetchQueue(response(200, refusal));
  await assert.rejects(client(f1).generateJson(request), (e) => e.code === 'refusal' && !e.retryable);
  assert.strictEqual(f1.requests.length, 1);

  const filtered = completed('{"a":', { status: 'incomplete', incomplete_details: { reason: 'content_filter' } });
  await assert.rejects(client(fetchQueue(response(200, filtered))).generateJson(request), (e) => e.code === 'refusal');

  const truncated = completed('{"a":', { status: 'incomplete', incomplete_details: { reason: 'max_output_tokens' } });
  const f3 = fetchQueue(response(200, truncated), response(200, truncated), response(200, truncated));
  await assert.rejects(client(f3).generateJson(request), (e) => e.code === 'malformed_output' && e.attempts === 3);

  const empty = completed('');
  empty.output = [{ type: 'reasoning', id: 'rs', summary: [] }];
  const f4 = fetchQueue(response(200, empty), response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(f4).generateJson(request)).attempts, 2, 'empty output is retried');

  const failed = completed('', { status: 'failed', error: { code: 'server_error', message: 'x' } });
  const f5 = fetchQueue(response(200, failed), response(200, completed(F.partialAnswer())));
  assert.strictEqual((await client(f5).generateJson(request)).attempts, 2);
});

test('not configured: missing key or unsupported provider never touches the network', async () => {
  const f = fetchQueue();
  assert.strictEqual(client(f, { apiKey: null }).isConfigured, false);
  await assert.rejects(client(f, { apiKey: null }).generateJson(request), (e) => e.code === 'not_configured');
  assert.strictEqual(client(f, { provider: 'other' }).isConfigured, false);
  await assert.rejects(client(f, { provider: 'other' }).generateJson(request), (e) => e.code === 'not_configured');
  assert.strictEqual(f.requests.length, 0);
});

test('redaction removes keys and key-like strings from any message', () => {
  assert.strictEqual(redact(`bad key ${KEY}`, KEY), 'bad key [redacted]');
  assert.strictEqual(redact('Incorrect API key provided: sk-proj-abcd****wxyz.', null), 'Incorrect API key provided: [redacted]');
});

test('the extraction schema is valid for OpenAI strict mode (closed objects, all required, no anyOf)', () => {
  const walk = (s, path) => {
    assert.ok(!s.anyOf && !s.oneOf, `${path} uses anyOf/oneOf`);
    if (s.type === 'object') {
      assert.strictEqual(s.additionalProperties, false, path);
      assert.deepStrictEqual([...s.required].sort(), Object.keys(s.properties).sort(), path);
      for (const [k, v] of Object.entries(s.properties)) walk(v, `${path}.${k}`);
    }
    if (s.type === 'array') walk(s.items, `${path}[]`);
  };
  walk(AI_OUTPUT_SCHEMA, '$');
  assert.deepStrictEqual(validateAiOutput(F.monitoringAnswer()), []);
  const nullRaw = F.partialAnswer();
  nullRaw.reportTitle = { raw: 5, status: 'provided' };
  assert.ok(validateAiOutput(nullRaw).length > 0, 'wrong types are still rejected');
});
