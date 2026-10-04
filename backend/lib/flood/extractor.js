'use strict';
/**
 * Flood-report extraction engine.
 *
 *   SourceMessage (any platform)
 *     → deterministic prefilter (system notifications, small talk, media without text)
 *     → AI classification + extraction (structured JSON)
 *     → schema validation (inside the AI client; malformed output never gets here)
 *     → deterministic normalization (units, times, dates, coordinates, intensity)
 *     → business rules (value must appear in the source; one source span cannot fill two fields)
 *     → missing / ambiguous / not-applicable bookkeeping → status
 *
 * The engine never looks at the platform to decide how to extract; the platform is provenance only.
 * Missing information never fails a report: it becomes `null` + "missing" and sends it to review.
 */

const {
  SCHEMA_VERSION, REPORT_STATUS, REPORT_FIELDS, REPORT_GROUPS, LOCATION_FIELDS, LOCATION_GROUPS,
  AI_OUTPUT_SCHEMA, validateAiOutput,
} = require('./schema');
const { normalizeField, deriveInterventionType, squash, countOccurrences } = require('./normalize');
const { prefilter } = require('./prefilter');
const { PROMPT_VERSION, SYSTEM_PROMPT, buildUserMessage } = require('./prompt');

const FLOOD_TYPES = new Set(['flood_monitoring', 'flood_prone_area_assessment', 'non_flood_prone_area_assessment', 'other_flood_report']);

/** Field kinds where re-using one piece of source text for two fields signals an inference, not a reading. */
const SPAN_GUARDED_KINDS = new Set(['height', 'time', 'date']);

function warning(code, message, field) {
  return field ? { code, field, message } : { code, message };
}

const AMBIGUITY_MESSAGES = {
  SUSPICIOUS_MEASUREMENT: 'Measurement looks suspicious; raw value kept, not normalized.',
  UNIT_MISSING: 'Measurement has no unit; not normalized.',
  UNKNOWN_UNIT: 'Measurement unit not recognized.',
  NOT_NUMERIC: 'Value is descriptive, not a measurement.',
  MULTIPLE_VALUES: 'More than one value found for a single field.',
  AM_PM_UNSPECIFIED: 'Time could be AM or PM.',
  DATE_ORDER_AMBIGUOUS: 'Numeric date could be MM/DD or DD/MM.',
  YEAR_MISSING: 'Date has no year.',
  YEAR_INCOMPLETE: 'Date has a two-digit year.',
  COORDINATE_OUT_OF_RANGE: 'Coordinate outside the Philippines; possibly swapped or mistyped.',
  UNRECOGNIZED_INTENSITY: 'Rainfall intensity wording not recognized.',
  NOT_A_TIME: 'Value is not a clock time.',
  NOT_A_DATE: 'Value is not a date.',
  NOT_A_COORDINATE: 'Value is not a coordinate.',
  INVALID_TIME: 'Time is not valid.',
  INVALID_DATE: 'Date is not valid.',
  AI_FLAGGED_AMBIGUOUS: 'Extraction marked this value as ambiguous.',
  VALUE_NOT_IN_SOURCE: 'Extracted text does not appear in the original message; value discarded.',
  SOURCE_TEXT_REUSED: 'The same source text was used for several fields; values discarded for review.',
};

/** Normalizes a validated AI answer against the original text. */
function normalizeAiOutput(aiOutput, sourceText) {
  const fields = []; // { path, kind, field }
  const norm = (kind, aiField, path) => {
    const f = normalizeField(kind, aiField);
    fields.push({ path, kind, field: f });
    return f;
  };
  const normGroup = (spec, aiGroup, prefix) => {
    const out = {};
    for (const [name, kind] of Object.entries(spec)) out[name] = norm(kind, aiGroup[name], `${prefix}${name}`);
    return out;
  };

  const extraction = {};
  for (const [name, kind] of Object.entries(REPORT_FIELDS)) extraction[name] = norm(kind, aiOutput[name], name);
  for (const [group, spec] of Object.entries(REPORT_GROUPS)) extraction[group] = normGroup(spec, aiOutput[group], `${group}.`);
  extraction.locations = aiOutput.locations.map((loc, i) => {
    const prefix = `locations[${i}].`;
    const out = { index: i, ...normGroup(LOCATION_FIELDS, loc, prefix) };
    for (const [group, spec] of Object.entries(LOCATION_GROUPS)) out[group] = normGroup(spec, loc[group], `${prefix}${group}.`);
    // Derived, and labelled as such: never presented as source-provided.
    out.intervention.interventionType = deriveInterventionType(out.intervention.interventionText);
    return out;
  });

  // Rule 1: a value must exist in the original message (catches invented values).
  const src = squash(sourceText);
  for (const { field, kind } of fields) {
    if (field.status === 'missing' || !field.raw) continue;
    const needle = squash(field.raw);
    // Numbers/times must match on their own ("2:00 PM" is not found inside "12:00 PM").
    const found = kind === 'text' ? src.includes(needle) : countOccurrences(src, needle) > 0;
    if (!found) {
      Object.assign(field, { value: null, status: 'ambiguous', reason: 'VALUE_NOT_IN_SOURCE', rawVerified: false });
    }
  }

  // Rule 2: one piece of source text cannot fill more fields than it appears in the message
  // (e.g. "2:00 PM" given once as rainfall start must not also become the flood start).
  const claims = new Map();
  for (const entry of fields) {
    const { field, kind } = entry;
    if (!SPAN_GUARDED_KINDS.has(kind) || field.status !== 'provided' || !field.raw) continue;
    const key = squash(field.raw);
    if (!claims.has(key)) claims.set(key, []);
    claims.get(key).push(entry);
  }
  for (const [key, entries] of claims) {
    if (entries.length > 1 && entries.length > countOccurrences(src, key)) {
      for (const { field } of entries) Object.assign(field, { value: null, status: 'ambiguous', reason: 'SOURCE_TEXT_REUSED' });
    }
  }

  return { extraction, fields };
}

function bookkeeping(fields) {
  const missingFields = [];
  const ambiguousFields = [];
  const notApplicableFields = [];
  const warnings = [];
  for (const { path, field } of fields) {
    if (field.status === 'missing') missingFields.push(path);
    else if (field.status === 'not_applicable') notApplicableFields.push(path);
    else if (field.status === 'ambiguous') {
      ambiguousFields.push(path);
      warnings.push(warning(field.reason || 'AMBIGUOUS', AMBIGUITY_MESSAGES[field.reason] || 'Value could not be interpreted.', path));
    }
  }
  return { missingFields, ambiguousFields, notApplicableFields, warnings };
}

function deterministicResult(status, reportType, reason, extraWarnings = []) {
  return {
    status,
    reportType,
    classification: { reportType, confidence: reportType ? 'high' : null, basis: 'deterministic', rationale: reason },
    extraction: null,
    metadata: {
      confidence: null, missingFields: [], ambiguousFields: [], notApplicableFields: [], warnings: extraWarnings,
      reason, model: null, promptVersion: null, schemaVersion: SCHEMA_VERSION, aiAttempts: 0,
    },
  };
}

/**
 * Runs the pipeline for one source message.
 * Resolves with a result for every non-system outcome; throws AiError when the AI step fails (the caller
 * records that as a processing failure).
 *
 * @param {object} source normalized SourceMessage
 * @param {{ ai: object }} deps AI client (see lib/ai.js)
 */
async function extractReport(source, { ai }) {
  if (!source.platform) return deterministicResult(REPORT_STATUS.IGNORED, null, 'UNSUPPORTED_PLATFORM');

  const pre = prefilter(source);
  if (pre.decision === 'ignore') return deterministicResult(REPORT_STATUS.IGNORED, 'not_flood_report', pre.reason);
  if (pre.decision === 'media_only') {
    return deterministicResult(REPORT_STATUS.NEEDS_REVIEW, null, pre.reason, [
      warning('MEDIA_WITHOUT_TEXT', 'Media arrived without a caption or text, so it cannot be classified. Link it to the report it belongs to, or reject it.'),
    ]);
  }

  const response = await ai.generateJson({
    system: SYSTEM_PROMPT,
    user: buildUserMessage(source, pre.text),
    schema: AI_OUTPUT_SCHEMA,
    validate: validateAiOutput,
  });
  const out = response.data;
  const classification = {
    reportType: out.classification.reportType,
    confidence: out.classification.confidence,
    basis: 'ai',
    rationale: out.classification.rationale,
  };
  const meta = {
    confidence: out.overallConfidence,
    model: response.model,
    servedModel: response.servedModel,
    promptVersion: PROMPT_VERSION,
    schemaVersion: SCHEMA_VERSION,
    aiAttempts: response.attempts,
  };

  if (classification.reportType === 'not_flood_report') {
    const uncertain = classification.confidence === 'low';
    return {
      status: uncertain ? REPORT_STATUS.NEEDS_REVIEW : REPORT_STATUS.IGNORED,
      reportType: 'not_flood_report',
      classification,
      extraction: null,
      metadata: {
        ...meta, missingFields: [], ambiguousFields: [], notApplicableFields: [],
        warnings: uncertain ? [warning('CLASSIFICATION_UNCERTAIN', 'Classified as not a flood report with low confidence.')] : [],
      },
    };
  }

  const { extraction, fields } = normalizeAiOutput(out, pre.text);
  const book = bookkeeping(fields);
  const warnings = [...book.warnings];
  if (FLOOD_TYPES.has(classification.reportType) && classification.reportType !== 'other_flood_report' && extraction.locations.length === 0) {
    warnings.push(warning('NO_LOCATIONS', 'Report type normally lists locations, but none were found.'));
  }
  if (classification.confidence === 'low') warnings.push(warning('CLASSIFICATION_UNCERTAIN', 'Report type was classified with low confidence.'));
  if (out.overallConfidence === 'low') warnings.push(warning('LOW_CONFIDENCE', 'Extraction confidence is low.'));
  for (const note of out.notes) {
    if (typeof note === 'string' && note.trim()) warnings.push(warning('AI_NOTE', note.trim().slice(0, 300)));
  }

  const needsReview = book.missingFields.length > 0 || book.ambiguousFields.length > 0 || warnings.length > 0;
  return {
    status: needsReview ? REPORT_STATUS.NEEDS_REVIEW : REPORT_STATUS.EXTRACTED,
    reportType: classification.reportType,
    classification,
    extraction,
    metadata: { ...meta, ...book, warnings },
  };
}

module.exports = { extractReport, normalizeAiOutput };
