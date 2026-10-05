'use strict';
/**
 * Shared Phase 3 test fixtures: representative DPWH report texts and a fake AI client.
 *
 * The fake AI stands in for the model, so these tests verify everything the backend does with an AI
 * answer (validation, normalization, anti-fabrication rules, statuses, persistence) deterministically.
 * It validates its canned answers with the same validator the real client uses, so an invalid fixture
 * fails loudly instead of slipping through.
 */
const { REPORT_FIELDS, REPORT_GROUPS, LOCATION_FIELDS, LOCATION_GROUPS } = require('../../lib/flood/schema');
const { AiError, createAiClient } = require('../../lib/ai');

/**
 * An AI client with no key: tests that do not exercise AI get it by default, so `npm test` can never
 * reach a real provider even if AI_API_KEY happens to be exported in the shell.
 */
const offlineAi = () => createAiClient({ provider: 'openai', apiKey: null, baseUrl: 'https://api.openai.com', model: 'offline', timeoutMs: 1, maxRetries: 0 });

const M = () => ({ raw: null, status: 'missing' });
const P = (raw) => ({ raw, status: 'provided' });
const NA = (raw = 'N/A') => ({ raw, status: 'not_applicable' });
const AMB = (raw) => ({ raw, status: 'ambiguous' });

function allMissing(spec) {
  const out = {};
  for (const name of Object.keys(spec)) out[name] = M();
  return out;
}

/** A location with every field missing, overridden by `o` (group keys merge). */
function location(o = {}) {
  const loc = allMissing(LOCATION_FIELDS);
  for (const [group, spec] of Object.entries(LOCATION_GROUPS)) loc[group] = { ...allMissing(spec), ...(o[group] || {}) };
  for (const [k, v] of Object.entries(o)) if (!LOCATION_GROUPS[k]) loc[k] = v;
  return loc;
}

/** A full AI answer with every field missing, overridden by the given parts. */
function aiOutput({ reportType = 'flood_monitoring', confidence = 'high', overall = 'high', report = {}, locations = [], notes = [] } = {}) {
  const out = {
    classification: { reportType, confidence, rationale: 'fixture' },
    overallConfidence: overall,
    ...allMissing(REPORT_FIELDS),
    locations,
    notes,
  };
  for (const [group, spec] of Object.entries(REPORT_GROUPS)) out[group] = { ...allMissing(spec), ...(report[group] || {}) };
  for (const [k, v] of Object.entries(report)) if (!REPORT_GROUPS[k]) out[k] = v;
  return out;
}

/**
 * Fake AI client. `responder(request)` returns an AI answer object (or throws an AiError).
 * Records every request in `calls`.
 */
function fakeAi(responder) {
  const calls = [];
  return {
    isConfigured: true,
    model: 'fake-model',
    calls,
    async generateJson(request) {
      calls.push(request);
      const data = await responder(request);
      const problems = request.validate(data);
      if (problems.length) throw new AiError('invalid_schema', `fixture invalid: ${problems.join('; ')}`, { retryable: true, attempts: 1 });
      return { data, model: 'fake-model', servedModel: 'fake-model', usage: null, attempts: 1 };
    },
  };
}

/** Routes by a substring of the message text in the user turn. */
function aiByText(routes, fallback = () => aiOutput({ reportType: 'not_flood_report', confidence: 'high' })) {
  return fakeAi((req) => {
    for (const [needle, answer] of routes) if (req.user.includes(needle)) return typeof answer === 'function' ? answer(req) : answer;
    return fallback(req);
  });
}

// ---------------------------------------------------------------------------------------------------
// Report texts
// ---------------------------------------------------------------------------------------------------

const MONITORING_REPORT = `FLOOD MONITORING REPORT
Date: October 4, 2026
Time: 2:00 PM
Cavite 1st District Engineering Office
DPWH Region IV-A

Location: Daang Hari Road, Km 23+100, near SM Molino, Brgy. Molino IV, Bacoor City, Cavite
Coordinates: 14.4062, 120.9787
Current flood height: 0.20 m
Maximum flood height: 0.30 m at 1:15 PM
Flood started: 12:30 PM
Flood subsided: 3:30 PM
Rainfall: Moderate, started 11:45 AM, ended 1:40 PM
Intervention: Declogging of drainage inlets; warning signages installed
Remarks: Passable to all types of vehicles

Prepared by: Engr. Juan Dela Cruz
Engineer II`;

/** The AI answer for MONITORING_REPORT: every value copied verbatim. */
function monitoringAnswer() {
  return aiOutput({
    report: {
      reportTitle: P('FLOOD MONITORING REPORT'),
      reporting: { reportDate: P('October 4, 2026'), reportTime: P('2:00 PM') },
      administrative: { region: P('DPWH Region IV-A'), districtEngineeringOffice: P('Cavite 1st District Engineering Office') },
      preparedBy: { name: P('Engr. Juan Dela Cruz'), position: P('Engineer II') },
    },
    locations: [location({
      rawLocationText: P('Daang Hari Road, Km 23+100, near SM Molino, Brgy. Molino IV, Bacoor City, Cavite'),
      roadName: P('Daang Hari Road'),
      kilometerReference: P('Km 23+100'),
      landmark: P('SM Molino'),
      barangay: P('Brgy. Molino IV'),
      municipality: P('Bacoor City'),
      province: P('Cavite'),
      latitude: P('14.4062'),
      longitude: P('120.9787'),
      remarks: P('Passable to all types of vehicles'),
      flood: {
        currentFloodHeight: P('0.20 m'),
        maximumFloodHeight: P('0.30 m'),
        maximumFloodHeightTime: P('1:15 PM'),
        floodStartedAt: P('12:30 PM'),
        floodSubsidedAt: P('3:30 PM'),
      },
      rainfall: { rainfallStartedAt: P('11:45 AM'), rainfallEndedAt: P('1:40 PM'), rainfallIntensity: P('Moderate') },
      intervention: { interventionText: P('Declogging of drainage inlets; warning signages installed') },
    })],
  });
}

const MULTI_LOCATION_REPORT = `FLOOD MONITORING REPORT
October 4, 2026

1. Daang Hari Road, Bacoor City
   Flood height: 0.20 m
   Rainfall: Light to Moderate

2. Marcos Alvarez Road, Las Piñas City
   Flood height: 0.10 m
   Rainfall: Heavy`;

function multiLocationAnswer() {
  return aiOutput({
    report: { reportTitle: P('FLOOD MONITORING REPORT'), reporting: { reportDate: P('October 4, 2026') } },
    locations: [
      location({
        rawLocationText: P('Daang Hari Road, Bacoor City'), roadName: P('Daang Hari Road'), municipality: P('Bacoor City'),
        flood: { currentFloodHeight: P('0.20 m') }, rainfall: { rainfallIntensity: P('Light to Moderate') },
      }),
      location({
        rawLocationText: P('Marcos Alvarez Road, Las Piñas City'), roadName: P('Marcos Alvarez Road'), municipality: P('Las Piñas City'),
        flood: { currentFloodHeight: P('0.10 m') }, rainfall: { rainfallIntensity: P('Heavy') },
      }),
    ],
  });
}

/** Spec test 42: current height and subsidence only — no flood start. */
const PARTIAL_REPORT = 'Current flood height: 0.20 m\nFlood subsided at 3:30 PM.';

function partialAnswer() {
  return aiOutput({
    locations: [location({ flood: { currentFloodHeight: P('0.20 m'), floodSubsidedAt: P('3:30 PM') } })],
  });
}

const FLOOD_PRONE_REPORT = `INITIAL ASSESSMENT REPORT ON FLOOD-PRONE AREAS
Date of Inspection: October 4, 2026
Location: Aguinaldo Highway, Km 21+500, Imus City
Flood height: 0.15 m
Rainfall Intensity: Light
Remarks: Passable`;

const NON_FLOOD_PRONE_REPORT = `INITIAL ASSESSMENT REPORT ON AREAS NOT INCLUDED IN FLOOD-PRONE AREAS
Date of Inspection: October 4, 2026
Location: Governor's Drive, Km 35+200, Dasmariñas City
Flood height: N/A
Maximum flood height: N/A
Rainfall Intensity: Heavy
Remarks: No flooding observed`;

const OTHER_FLOOD_REPORT = 'Update po: may baha na sa may tulay sa Molino, mga hanggang tuhod. Ingat po sa mga dadaan.';

module.exports = {
  offlineAi, M, P, NA, AMB, location, aiOutput, fakeAi, aiByText,
  MONITORING_REPORT, monitoringAnswer, MULTI_LOCATION_REPORT, multiLocationAnswer,
  PARTIAL_REPORT, partialAnswer, FLOOD_PRONE_REPORT, NON_FLOOD_PRONE_REPORT, OTHER_FLOOD_REPORT,
};
