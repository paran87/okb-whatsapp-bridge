'use strict';
/**
 * One controlled, real AI extraction call — to check the configured provider end-to-end.
 * (Deliberately not named *-test.js: `node --test` must never pick it up and make a real API call.)
 *
 *   cd backend
 *   npm run smoke:ai             # sends ONE fictional sample report
 *   npm run smoke:ai -- --dry    # checks configuration only, no network call
 *
 * Reads AI_* from the environment, or from backend/.env when they are not set. Sends exactly one
 * request containing a FICTIONAL sample report (no real DPWH data, no message history), runs the
 * answer through the normal pipeline (schema validation → normalization → anti-fabrication rules) and
 * checks the result. The API key is never printed. Exit code 0 = passed, 1 = failed, 2 = not configured.
 */
const fs = require('node:fs');
const path = require('node:path');

/** Minimal .env reader: KEY=VALUE lines, # comments, optional quotes. Never echoes values. */
function loadDotEnv(file) {
  if (!fs.existsSync(file)) return false;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m || line.trim().startsWith('#')) continue;
    const value = m[2].replace(/^(['"])(.*)\1$/, '$2');
    if (process.env[m[1]] === undefined && value !== '') process.env[m[1]] = value;
  }
  return true;
}

const envFile = path.join(__dirname, '..', '.env');
const usedEnvFile = loadDotEnv(envFile);

const { createAiClient, configFromEnv } = require('../lib/ai');
const { extractReport } = require('../lib/flood/extractor');
const { validateAiOutput } = require('../lib/flood/schema');

/** Fictional report: names and places are made up. Deliberately omits some fields and includes a suspicious value. */
const SAMPLE = `FLOOD MONITORING REPORT (TEST SAMPLE - NOT A REAL REPORT)
Date: January 15, 2026

Location 1: Sample Road, Km 10+200, Barangay Uno, Sample City
Current flood height: 0.20 m
Flood subsided at 3:30 PM.
Rainfall: Light to Moderate

Location 2: Test Avenue, Barangay Dos, Sample City
Current flood height: .010 m
Remarks: N/A

Prepared by: Engr. Test User`;

const source = {
  platform: 'whatsapp', platformBasis: 'smoke_test', messageId: 'smoke-test', fingerprint: '0'.repeat(64),
  deviceId: 'OKB-ANDROID-000000', groupId: null, groupName: 'Smoke Test Group', senderId: null, senderName: 'Smoke Test',
  messageText: SAMPLE, messageTimestamp: '2026-10-05T09:00:00+08:00', receivedAt: null, capturedAt: null,
  sourcePackage: 'com.whatsapp', mediaIndicator: { mediaType: 'TEXT', mediaStatus: 'NONE' }, media: [],
};

async function main() {
  const config = configFromEnv();
  const ai = createAiClient(config);
  console.log('AI smoke test');
  console.log(`  provider:  ${ai.provider}`);
  console.log(`  model:     ${ai.model}`);
  console.log(`  endpoint:  ${ai.endpoint}`);
  console.log(`  api key:   ${config.apiKey ? 'set' : 'NOT SET'}${usedEnvFile ? ' (backend/.env read)' : ''}`);
  console.log(`  timeout:   ${config.timeoutMs} ms, retries: ${config.maxRetries}`);
  if (!ai.isConfigured) {
    console.log('\nNot configured: set AI_API_KEY (and AI_PROVIDER=openai) in backend/.env.');
    return 2;
  }
  if (process.argv.includes('--dry')) {
    console.log('\n--dry: configuration looks complete; no request sent.');
    return 0;
  }

  let captured = null;
  let meta = null;
  const recording = {
    isConfigured: true,
    async generateJson(req) {
      const r = await ai.generateJson(req);
      captured = r.data;
      meta = r;
      return r;
    },
  };

  console.log('\nSending ONE fictional sample report...');
  const started = Date.now();
  let result;
  try {
    result = await extractReport(source, { ai: recording });
  } catch (err) {
    console.log(`\nFAILED: ${err.code || 'error'}${err.status ? ` (HTTP ${err.status})` : ''}: ${err.message}`);
    if (err.requestId) console.log(`  request id: ${err.requestId}`);
    if (err.code === 'auth') console.log('  → check AI_API_KEY.');
    if (err.code === 'not_found') console.log('  → check AI_MODEL is available to this API key/project.');
    if (err.code === 'quota_exceeded') console.log('  → the account or project is out of quota/credit.');
    return 1;
  }
  console.log(`  answered in ${Date.now() - started} ms by ${meta.servedModel} (attempts: ${meta.attempts}${meta.requestId ? `, request id ${meta.requestId}` : ''})`);

  const loc = (i) => (result.extraction && result.extraction.locations[i]) || null;
  const checks = [];
  const check = (name, ok, hard = true) => checks.push({ name, ok: Boolean(ok), hard });

  // Connectivity / parsing / validation.
  check('authentication, model and structured output accepted', captured !== null);
  check('answer passes the local schema validator', captured && validateAiOutput(captured).length === 0);
  const tampered = captured && JSON.parse(JSON.stringify(captured));
  if (tampered) tampered.reportTitle = { raw: 'invented', status: 'missing' };
  check('validator still rejects a contradictory answer', tampered && validateAiOutput(tampered).length > 0);

  // Structure.
  check('classified as a flood report', result.reportType && result.reportType !== 'not_flood_report');
  check('one report with two locations', result.extraction && result.extraction.locations.length === 2);

  // Anti-fabrication: values the sample never states must stay null.
  const l0 = loc(0);
  const l1 = loc(1);
  check('flood start not stated → null', l0 && l0.flood.floodStartedAt.value === null);
  check('maximum flood height not stated → null', l0 && l0.flood.maximumFloodHeight.value === null);
  check('coordinates not stated → null', l0 && l0.latitude.value === null && l0.longitude.value === null);
  check('report time not stated → null', result.extraction && result.extraction.reporting.reportTime.value === null);
  check('".010 m" not silently normalized', l1 && l1.flood.currentFloodHeight.value === null);
  check('missing fields route to needs_review', result.status === 'needs_review');

  // Values the sample does state (model-dependent: reported, but they do not fail the test).
  check('current height 0.20 m → 0.2', l0 && l0.flood.currentFloodHeight.value === 0.2, false);
  check('subsided 3:30 PM → 15:30', l0 && l0.flood.floodSubsidedAt.value === '15:30', false);
  check('report date → 2026-01-15', result.extraction && result.extraction.reporting.reportDate.value === '2026-01-15', false);
  check('rainfall → light_to_moderate', l0 && l0.rainfall.rainfallIntensity.value === 'light_to_moderate', false);

  console.log('\nChecks:');
  for (const c of checks) console.log(`  ${c.ok ? 'PASS' : c.hard ? 'FAIL' : 'WARN'}  ${c.name}`);
  console.log(`\nResult: status=${result.status}, reportType=${result.reportType}, ` +
    `missing=${result.metadata.missingFields.length}, ambiguous=${result.metadata.ambiguousFields.length}, warnings=${result.metadata.warnings.length}`);

  const failed = checks.filter((c) => c.hard && !c.ok);
  console.log(failed.length ? `\nSMOKE TEST FAILED (${failed.length} check(s))` : '\nSMOKE TEST PASSED');
  return failed.length ? 1 : 0;
}

main().then((code) => { process.exitCode = code; }, (err) => {
  console.error(`Unexpected error: ${err.message}`);
  process.exitCode = 1;
});
