'use strict';
/**
 * Server-side AI client: OpenAI Responses API with Structured Outputs. Dependency-free (raw HTTPS via
 * fetch), like the rest of the backend. The API key never leaves the backend and is never logged.
 *
 * Configuration (environment):
 *   AI_PROVIDER      "openai" (the only supported provider; default)
 *   AI_API_KEY       required to enable AI extraction
 *   AI_BASE_URL      default https://api.openai.com
 *   AI_MODEL         default gpt-5.6-luna
 *   AI_TIMEOUT_MS    per-attempt timeout, default 120000
 *   AI_MAX_RETRIES   retries after the first attempt, default 2 (so at most 3 attempts)
 *
 * The response is never trusted: even with a strict json_schema format it must parse as JSON and pass
 * the caller's own validator, otherwise the attempt counts as failed (and is retried within
 * AI_MAX_RETRIES). Retries are bounded and only for transient failures; authentication, permission,
 * model-not-found, bad-request and quota/billing errors fail immediately.
 */

const DEFAULT_PROVIDER = 'openai';
const DEFAULT_BASE_URL = 'https://api.openai.com';
const DEFAULT_MODEL = 'gpt-5.6-luna';
const SUPPORTED_PROVIDERS = new Set(['openai']);

/** 429 codes that mean "out of money/quota", not "slow down": retrying cannot help. */
const QUOTA_CODES = new Set([
  'insufficient_quota', 'credit_balance_exhausted', 'organization_spend_limit_exceeded',
  'project_spend_limit_exceeded', 'organization_usage_limit_exceeded', 'billing_hard_limit_reached',
]);

class AiError extends Error {
  /**
   * @param {string} code timeout | rate_limited | unavailable | malformed_output | invalid_schema |
   *                      refusal | auth | permission | not_found | bad_request | quota_exceeded |
   *                      not_configured
   */
  constructor(code, message, { retryable = false, status = null, attempts = 0, requestId = null } = {}) {
    super(message);
    this.name = 'AiError';
    this.code = code;
    this.retryable = retryable;
    this.status = status;
    this.attempts = attempts;
    this.requestId = requestId;
  }
}

function intFromEnv(value, fallback) {
  const n = parseInt(value ?? '', 10);
  return Number.isFinite(n) && n >= 0 ? n : fallback;
}

function configFromEnv(env = process.env) {
  return {
    provider: (env.AI_PROVIDER || DEFAULT_PROVIDER).trim().toLowerCase(),
    apiKey: env.AI_API_KEY || null,
    baseUrl: env.AI_BASE_URL || DEFAULT_BASE_URL,
    model: env.AI_MODEL || DEFAULT_MODEL,
    timeoutMs: intFromEnv(env.AI_TIMEOUT_MS, 120000),
    maxRetries: Math.min(intFromEnv(env.AI_MAX_RETRIES, 2), 10),
  };
}

function responsesEndpoint(baseUrl) {
  const base = baseUrl.replace(/\/+$/, '');
  return /\/v1$/.test(base) ? `${base}/responses` : `${base}/v1/responses`;
}

/** Removes anything key-like from text that may end up in logs (OpenAI echoes masked keys on 401). */
function redact(text, apiKey) {
  let out = String(text);
  if (apiKey) out = out.split(apiKey).join('[redacted]');
  return out.replace(/\b(sk|rk)-[A-Za-z0-9_*.-]{4,}/g, '[redacted]');
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
  const provider = config.provider || DEFAULT_PROVIDER;
  const supported = SUPPORTED_PROVIDERS.has(provider);
  const endpoint = responsesEndpoint(config.baseUrl || DEFAULT_BASE_URL);

  function backoffMs(attempt, err) {
    const ms = parseFloat(err.retryAfterMs || '');
    if (Number.isFinite(ms) && ms >= 0) return Math.min(ms, 60000);
    const s = parseFloat(err.retryAfter || '');
    if (Number.isFinite(s) && s >= 0) return Math.min(s * 1000, 60000);
    return Math.min(30000, 1000 * 2 ** attempt) + Math.floor(random() * 250);
  }

  function buildRequest({ system, user, schema, schemaName, maxTokens }) {
    const body = {
      model: config.model,
      instructions: system,
      input: [{ role: 'user', content: user }],
      text: { format: { type: 'json_schema', name: schemaName || 'structured_output', schema, strict: true } },
      // Reasoning models spend output tokens on reasoning too; leave room so the JSON is not cut off.
      max_output_tokens: maxTokens || 32000,
      // Do not keep flood reports on the provider side beyond what the request itself requires.
      store: false,
    };
    return {
      headers: { 'content-type': 'application/json', authorization: `Bearer ${config.apiKey}` },
      body: JSON.stringify(body),
    };
  }

  function httpError(status, text, headers) {
    let error = {};
    try { error = JSON.parse(text).error || {}; } catch { /* non-JSON error body */ }
    const requestId = headers.get('x-request-id');
    const detail = error.message ? `: ${redact(error.message, config.apiKey).slice(0, 200)}` : '';
    const msg = `AI HTTP ${status}${error.code ? ` (${error.code})` : ''}${detail}`;
    const opts = { status, requestId };
    if (status === 401) return new AiError('auth', msg, opts);
    if (status === 403) return new AiError('permission', msg, opts);
    if (status === 404) return new AiError('not_found', msg, opts);
    if (status === 429) {
      if (QUOTA_CODES.has(error.code) || QUOTA_CODES.has(error.type)) return new AiError('quota_exceeded', msg, opts);
      return Object.assign(new AiError('rate_limited', msg, { ...opts, retryable: true }), {
        retryAfter: headers.get('retry-after'), retryAfterMs: headers.get('retry-after-ms'),
      });
    }
    if (status === 408 || status === 409 || status >= 500) {
      return Object.assign(new AiError(status === 408 ? 'timeout' : 'unavailable', msg, { ...opts, retryable: true }), {
        retryAfter: headers.get('retry-after'), retryAfterMs: headers.get('retry-after-ms'),
      });
    }
    return new AiError('bad_request', msg, opts);
  }

  /** Pulls the JSON text out of a Responses API result, or throws a typed AiError. */
  function outputText(response, requestId) {
    const items = Array.isArray(response.output) ? response.output : [];
    const content = items.filter((i) => i && i.type === 'message').flatMap((i) => (Array.isArray(i.content) ? i.content : []));
    const refusal = content.find((c) => c && c.type === 'refusal');
    if (refusal) {
      throw new AiError('refusal', `AI declined the request: ${redact(refusal.refusal || '', config.apiKey).slice(0, 200)}`, { requestId });
    }
    if (response.status === 'incomplete') {
      const reason = response.incomplete_details && response.incomplete_details.reason;
      if (reason === 'content_filter') throw new AiError('refusal', 'AI output was blocked by a content filter', { requestId });
      throw new AiError('malformed_output', `AI output was incomplete (${reason || 'unknown reason'})`, { retryable: true, requestId });
    }
    if (response.status && response.status !== 'completed') {
      const err = response.error || {};
      throw new AiError('unavailable', `AI response status ${response.status}${err.code ? ` (${err.code})` : ''}`, { retryable: true, requestId });
    }
    const text = content.filter((c) => c && c.type === 'output_text').map((c) => c.text).join('');
    if (!text.trim()) throw new AiError('malformed_output', 'AI response contained no output text', { retryable: true, requestId });
    return text;
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
      throw new AiError('unavailable', `AI request failed: ${redact(err.message, config.apiKey)}`, { retryable: true });
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) throw httpError(res.status, text, res.headers);
    const requestId = res.headers.get('x-request-id');

    let response;
    try { response = JSON.parse(text); } catch {
      throw new AiError('malformed_output', 'AI response body is not JSON', { retryable: true, status: res.status, requestId });
    }
    let data;
    try { data = JSON.parse(outputText(response, requestId)); } catch (err) {
      if (err instanceof AiError) throw err;
      throw new AiError('malformed_output', 'AI output is not valid JSON', { retryable: true, requestId });
    }
    // Structured Outputs constrain the shape; our own validator still decides what is acceptable.
    const problems = validateFn ? validateFn(data) : [];
    if (problems.length) {
      throw new AiError('invalid_schema', `AI output failed validation: ${problems.slice(0, 3).join('; ')}`, { retryable: true, requestId });
    }
    return { data, servedModel: response.model || config.model, usage: response.usage || null, requestId };
  }

  /**
   * Requests a JSON object matching `schema`, validated by `validate(data) -> string[]`.
   * @returns {Promise<{ data, model, servedModel, usage, attempts, requestId }>}
   */
  async function generateJson({ system, user, schema, schemaName, validate, maxTokens }) {
    if (!supported) throw new AiError('not_configured', `AI_PROVIDER "${provider}" is not supported (use "openai")`);
    if (!config.apiKey) throw new AiError('not_configured', 'AI_API_KEY is not configured');
    const request = buildRequest({ system, user, schema, schemaName, maxTokens });
    const maxAttempts = config.maxRetries + 1;
    let lastError;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      try {
        const result = await attemptOnce(request, validate);
        return { ...result, model: config.model, attempts: attempt + 1 };
      } catch (err) {
        lastError = err instanceof AiError ? err : new AiError('unavailable', redact(err.message, config.apiKey), { retryable: true });
        lastError.attempts = attempt + 1;
        if (!lastError.retryable || attempt === maxAttempts - 1) break;
        const wait = backoffMs(attempt, lastError);
        log(`ai attempt ${attempt + 1}/${maxAttempts} failed (${lastError.code}${lastError.status ? ` HTTP ${lastError.status}` : ''}); retrying in ${wait} ms`);
        await sleep(wait);
      }
    }
    throw lastError;
  }

  return {
    get isConfigured() { return supported && Boolean(config.apiKey); },
    provider,
    model: config.model,
    endpoint,
    generateJson,
  };
}

module.exports = { createAiClient, configFromEnv, AiError, responsesEndpoint, redact, DEFAULT_MODEL, DEFAULT_PROVIDER };
