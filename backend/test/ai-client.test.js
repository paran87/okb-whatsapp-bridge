'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createAiClient, configFromEnv, messagesEndpoint } = require('../lib/ai');
const { AI_OUTPUT_SCHEMA, validateAiOutput } = require('../lib/flood/schema');
const F = require('./helpers/flood-fixtures');

const config = { apiKey: 'sk-test-secret', baseUrl: 'https://api.anthropic.com', model: 'claude-opus-5-5', timeoutMs: 200, maxRetries: 2 };

function response(status, body, headers = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return { ok: status >= 200 && status < 300, status, text: async () => text, headers: { get: (k) => headers[k.toLowerCase()] ?? null } };
}

function message(data, extra = {}) {
  return { id: 'msg_1', type: 'message', model: 'claude-opus-5-5', stop_reason: 'end_turn', content: [{ type: 'text', text: typeof data === 'string' ? data : JSON.stringify(data) }], usage: { input_tokens: 1, output_tokens: 1 }, ...extra };
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

const request = { system: 'sys', user: 'msg', schema: AI_OUTPUT_SCHEMA, validate: validateAiOutput };
const deps = (f) => ({ fetchImpl: f.impl, sleep: async () => {}, random: () => 0 });

test('reads AI_* configuration from the environment with safe defaults', () => {
  const c = configFromEnv({ AI_API_KEY: 'k', AI_TIMEOUT_MS: '5000', AI_MAX_RETRIES: '1' });
  assert.deepStrictEqual(c, { apiKey: 'k', baseUrl: 'https://api.anthropic.com', model: 'claude-opus-5-5', timeoutMs: 5000, maxRetries: 1 });
  assert.strictEqual(configFromEnv({}).apiKey, null);
  assert.strictEqual(messagesEndpoint('https://gw.example.org/v1'), 'https://gw.example.org/v1/messages');
});

test('sends a structured-output Messages API request and returns validated JSON', async () => {
  const f = fetchQueue(response(200, message(F.partialAnswer())));
  const ai = createAiClient(config, deps(f));
  const r = await ai.generateJson(request);
  assert.strictEqual(r.data.locations.length, 1);
  assert.strictEqual(r.attempts, 1);
  const { url, init } = f.requests[0];
  assert.strictEqual(url, 'https://api.anthropic.com/v1/messages');
  assert.strictEqual(init.headers['x-api-key'], 'sk-test-secret');
  assert.strictEqual(init.headers['anthropic-version'], '2023-06-01');
  const body = JSON.parse(init.body);
  assert.strictEqual(body.model, 'claude-opus-5-5');
  assert.strictEqual(body.output_config.format.type, 'json_schema');
  assert.deepStrictEqual(body.output_config.format.schema, AI_OUTPUT_SCHEMA);
  assert.strictEqual(body.system, 'sys');
  assert.deepStrictEqual(body.messages, [{ role: 'user', content: 'msg' }]);
});

test('not configured without AI_API_KEY (and never calls the network)', async () => {
  const f = fetchQueue();
  const ai = createAiClient({ ...config, apiKey: null }, deps(f));
  assert.strictEqual(ai.isConfigured, false);
  await assert.rejects(ai.generateJson(request), (e) => e.code === 'not_configured');
  assert.strictEqual(f.requests.length, 0);
});

test('AI timeout is retried, then fails after AI_MAX_RETRIES', async () => {
  const hang = (url, init) => new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  const f = fetchQueue(hang, hang, hang);
  const ai = createAiClient({ ...config, timeoutMs: 20 }, deps(f));
  await assert.rejects(ai.generateJson(request), (e) => e.code === 'timeout' && e.attempts === 3);
  assert.strictEqual(f.requests.length, 3);
});

test('AI retry: a rate limit then success', async () => {
  const waits = [];
  const f = fetchQueue(response(429, { error: { message: 'rate limited' } }, { 'retry-after': '2' }), response(200, message(F.partialAnswer())));
  const ai = createAiClient(config, { fetchImpl: f.impl, sleep: async (ms) => waits.push(ms), random: () => 0 });
  const r = await ai.generateJson(request);
  assert.strictEqual(r.attempts, 2);
  assert.deepStrictEqual(waits, [2000], 'honours retry-after');
});

test('AI malformed JSON is retried and fails cleanly when it persists', async () => {
  const f = fetchQueue(response(200, message('not json {')), response(200, message('{"half":')), response(200, message('still not json')));
  const ai = createAiClient(config, deps(f));
  await assert.rejects(ai.generateJson(request), (e) => e.code === 'malformed_output' && e.attempts === 3);
});

test('AI output that violates the schema is rejected (never returned to the caller)', async () => {
  const bad = { ...F.partialAnswer(), extraField: 'x' };
  const contradictory = F.partialAnswer();
  contradictory.reportTitle = { raw: 'invented', status: 'missing' };
  const f = fetchQueue(response(200, message(bad)), response(200, message(contradictory)), response(200, message({ classification: {} })));
  const ai = createAiClient(config, deps(f));
  await assert.rejects(ai.generateJson(request), (e) => e.code === 'invalid_schema');
  assert.strictEqual(f.requests.length, 3);
});

test('AI unavailable (5xx / network) retries; auth and bad requests do not', async () => {
  const f1 = fetchQueue(response(529, { error: { message: 'overloaded' } }), async () => { throw new Error('ECONNRESET'); }, response(200, message(F.partialAnswer())));
  assert.strictEqual((await createAiClient(config, deps(f1)).generateJson(request)).attempts, 3);

  const f2 = fetchQueue(response(401, { error: { message: 'invalid x-api-key' } }));
  await assert.rejects(createAiClient(config, deps(f2)).generateJson(request), (e) => e.code === 'auth' && !e.retryable);
  assert.strictEqual(f2.requests.length, 1);
});

test('refusal and truncated output are handled explicitly', async () => {
  const f1 = fetchQueue(response(200, { ...message('{}'), stop_reason: 'refusal', stop_details: { category: 'cyber' } }));
  await assert.rejects(createAiClient(config, deps(f1)).generateJson(request), (e) => e.code === 'refusal');
  const f2 = fetchQueue(...[1, 2, 3].map(() => response(200, { ...message('{"a":'), stop_reason: 'max_tokens' })));
  await assert.rejects(createAiClient(config, deps(f2)).generateJson(request), (e) => e.code === 'malformed_output');
});

test('the API key never appears in errors', async () => {
  const f = fetchQueue(response(400, { error: { message: 'bad' } }));
  await assert.rejects(createAiClient(config, deps(f)).generateJson(request), (e) => !e.message.includes('sk-test-secret'));
});
