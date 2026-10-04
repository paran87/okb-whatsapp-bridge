'use strict';
/**
 * Server-side AI client (Claude Messages API over HTTPS, structured JSON output). Dependency-free,
 * like the rest of the backend. The API key never leaves the backend and is never logged.
 *
 * Configuration (environment):
 *   AI_API_KEY       required to enable AI extraction
 *   AI_BASE_URL      default https://api.anthropic.com
 *   AI_MODEL         default claude-opus-5-5
 *   AI_TIMEOUT_MS    per-attempt timeout, default 120000
 *   AI_MAX_RETRIES   retries after the first attempt, default 2 (so at most 3 attempts)
 *
 * The response is never trusted: it must parse as JSON and pass the caller's validator, otherwise the
 * attempt counts as failed (and is retried within AI_MAX_RETRIES). Retries are bounded; there is no
 * retry loop beyond that.
 */

const ANTHROPIC_VERSION = '2023-06-01';
const DEFAULT_BASE_URL = 'https://api.anthropic.com';
const DEFAULT_MODEL = 'claude-opus-5-5';
const FALLBACK_BETA = 'server-side-fallback-2026-07-01';
/** Models that accept server-side refusal fallbacks in "default" mode on the first-party API. */
const FALLBACK_MODELS = new Set(['claude-fable-5-1', 'claude-opus-5-5', 'claude-opus-5', 'claude-sonnet-5-5']);

class AiError extends Error {
  /**
   * @param {string} code timeout | rate_limited | unavailable | malformed_output | invalid_schema |
   *                      refusal | auth | bad_request | not_configured
   */
  constructor(code, message, { retryable = false, status = null, attempts = 0 } = {}) {
    super(message);
    this.name = 'AiError';
    this.code = code;
    this.retryable = retryable;
    this.status = status;
    this.attempts = attempts;
  }
}

function intFromEnv(value, fallback) {
  const n = parseInt(value ?? '', 10);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}

function configFromEnv(env = process.env) {
  return {
    apiKey: env.AI_API_KEY || null,
    baseUrl: env.AI_BASE_URL || DEFAULT_BASE_URL,
    model: env.AI_MODEL || DEFAULT_MODEL,
    timeoutMs: intFromEnv(env.AI_TIMEOUT_MS, 120000),
    maxRetries: Math.min(intFromEnv(env.AI_MAX_RETRIES, 2), 10),
  };
}

function messagesEndpoint(baseUrl) {
  const base = baseUrl.replace(/\/+$/, '');
  return /\/v1$/.test(base) ? `${base}/messages` : `${base}/v1/messages`;
}

function isFirstPartyApi(baseUrl) {
  try { return new URL(baseUrl).hostname === 'api.anthropic.com'; } catch { return false; }
}

/** Effort is set explicitly only on model families known to accept it; others use their default. */
function supportsEffort(model) {
  return /^claude-(opus|sonnet|fable|mythos)-5/.test(model);
}

const defaultSleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * @param {object} [config] see configFromEnv
 * @param {object} [deps] { fetchImpl, sleep, log, random }
 */
function createAiClient(config = configFromEnv(), deps = {}) {
  const fetchImpl = deps.fetchImpl || globalThis.fetch;
  const sleep = deps.sleep || defaultSleep;
  const log = deps.log || (() => {});
  const random = deps.random || Math.random;
  const endpoint = messagesEndpoint(config.baseUrl);
  const firstParty = isFirstPartyApi(config.baseUrl);

  function backoffMs(attempt, retryAfterHeader) {
    const retryAfter = parseFloat(retryAfterHeader || '');
    if (Number.isFinite(retryAfter) && retryAfter >= 0) return Math.min(retryAfter * 1000, 60000);
    return Math.min(30000, 1000 * 2 ** attempt) + Math.floor(random() * 250);
  }

  function buildRequest({ system, user, schema, maxTokens }) {
    const body = {
      model: config.model,
      max_tokens: maxTokens || 16000,
      system,
      messages: [{ role: 'user', content: user }],
      output_config: { format: { type: 'json_schema', schema } },
    };
    if (supportsEffort(config.model)) body.output_config.effort = 'high';
    const headers = {
      'content-type': 'application/json',
      'x-api-key': config.apiKey,
      'anthropic-version': ANTHROPIC_VERSION,
    };
    if (firstParty && FALLBACK_MODELS.has(config.model)) {
      body.fallbacks = 'default';
      headers['anthropic-beta'] = FALLBACK_BETA;
    }
    return { headers, body: JSON.stringify(body) };
  }

  /** One HTTP attempt. Resolves to the validated JSON, or throws AiError. */
  async function attemptOnce(request, validateFn) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.timeoutMs);
    let res;
    let text;
    try {
      res = await fetchImpl(endpoint, { method: 'POST', headers: request.headers, body: request.body, signal: controller.signal });
      text = await res.text();
    } catch (err) {
      if (controller.signal.aborted) throw new AiError('timeout', `AI request timed out after ${config.timeoutMs} ms`, { retryable: true });
      throw new AiError('unavailable', `AI request failed: ${err.message}`, { retryable: true });
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) {
      let detail = '';
      try { detail = (JSON.parse(text).error || {}).message || ''; } catch { /* non-JSON error body */ }
      const msg = `AI HTTP ${res.status}${detail ? `: ${detail.slice(0, 200)}` : ''}`;
      const s = res.status;
      if (s === 429) throw Object.assign(new AiError('rate_limited', msg, { retryable: true, status: s }), { retryAfter: res.headers.get('retry-after') });
      if (s === 408 || s === 409 || s >= 500) throw Object.assign(new AiError('unavailable', msg, { retryable: true, status: s }), { retryAfter: res.headers.get('retry-after') });
      if (s === 401 || s === 403) throw new AiError('auth', msg, { status: s });
      throw new AiError('bad_request', msg, { status: s });
    }

    let message;
    try { message = JSON.parse(text); } catch {
      throw new AiError('malformed_output', 'AI response body is not JSON', { retryable: true, status: res.status });
    }
    if (message.stop_reason === 'refusal') {
      const category = message.stop_details && message.stop_details.category;
      throw new AiError('refusal', `AI declined the request${category ? ` (${category})` : ''}`);
    }
    if (message.stop_reason === 'max_tokens') {
      throw new AiError('malformed_output', 'AI output was truncated (max_tokens)', { retryable: true });
    }
    const output = (message.content || []).filter((b) => b && b.type === 'text').map((b) => b.text).join('');
    let data;
    try { data = JSON.parse(output); } catch {
      throw new AiError('malformed_output', 'AI output is not valid JSON', { retryable: true });
    }
    const problems = validateFn ? validateFn(data) : [];
    if (problems.length) {
      throw new AiError('invalid_schema', `AI output failed validation: ${problems.slice(0, 3).join('; ')}`, { retryable: true });
    }
    return { data, servedModel: message.model || config.model, usage: message.usage || null };
  }

  /**
   * Requests a JSON object matching `schema`, validated by `validate(data) -> string[]`.
   * @returns {Promise<{ data, model, servedModel, usage, attempts }>}
   */
  async function generateJson({ system, user, schema, validate, maxTokens }) {
    if (!config.apiKey) throw new AiError('not_configured', 'AI_API_KEY is not configured');
    const request = buildRequest({ system, user, schema, maxTokens });
    const maxAttempts = config.maxRetries + 1;
    let lastError;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        const result = await attemptOnce(request, validate);
        return { ...result, model: config.model, attempts: attempt + 1 };
      } catch (err) {
        lastError = err instanceof AiError ? err : new AiError('unavailable', err.message, { retryable: true });
        lastError.attempts = attempt + 1;
        if (!lastError.retryable || attempt === maxAttempts - 1) break;
        const wait = backoffMs(attempt, lastError.retryAfter);
        log(`ai attempt ${attempt + 1}/${maxAttempts} failed (${lastError.code}); retrying in ${wait} ms`);
        await sleep(wait);
      }
    }
    throw lastError;
  }

  return {
    get isConfigured() { return Boolean(config.apiKey); },
    model: config.model,
    generateJson,
  };
}

module.exports = { createAiClient, configFromEnv, AiError, messagesEndpoint, DEFAULT_MODEL };
