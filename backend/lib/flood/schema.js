'use strict';
/**
 * Flood-report extraction schema — the single source of truth for:
 *   - the JSON schema the AI must answer with (structured output),
 *   - local validation of that answer (the AI response is never trusted directly),
 *   - the field lists used by normalization and missing/ambiguous bookkeeping.
 *
 * Every extracted field has the same shape, so "provided" / "missing" / "not_applicable" / "ambiguous"
 * is explicit everywhere:
 *
 *   AI answer:        { raw: string|null, status }
 *   Normalized field: { value, status, raw, origin: "source"|"system_derived", ... }
 */

const SCHEMA_VERSION = 1;

const REPORT_TYPES = Object.freeze([
  'flood_monitoring',
  'flood_prone_area_assessment',
  'non_flood_prone_area_assessment',
  'other_flood_report',
  'not_flood_report',
]);

const FIELD_STATUS = Object.freeze(['provided', 'missing', 'not_applicable', 'ambiguous']);
const CONFIDENCE = Object.freeze(['low', 'medium', 'high']);

/** Processing / review states of a report record (one per source message). */
const REPORT_STATUS = Object.freeze({
  RECEIVED: 'received', // stored, waiting for processing
  PROCESSING: 'processing',
  EXTRACTED: 'extracted', // processed; nothing missing/ambiguous/flagged (still needs approval to be "approved")
  NEEDS_REVIEW: 'needs_review', // processed successfully, but a human must look at it
  APPROVED: 'approved', // human-approved
  REJECTED: 'rejected', // human-rejected
  IGNORED: 'ignored', // not a flood report / system notification / unsupported source
  FAILED: 'failed', // processing itself failed (AI unavailable after retries, storage error, ...)
});

/**
 * Field kinds drive normalization:
 *   text      → trimmed verbatim text
 *   date      → YYYY-MM-DD (only when explicit and unambiguous)
 *   time      → HH:mm (plus a date when the same text states one)
 *   height    → meters (only when the unit and number are clear)
 *   latitude / longitude → decimal degrees
 *   intensity → rainfall-intensity enum, source wording kept in `raw`
 */
const REPORT_FIELDS = Object.freeze({
  reportTitle: 'text',
  remarks: 'text',
});

const REPORT_GROUPS = Object.freeze({
  reporting: { reportDate: 'date', reportTime: 'time', inspectionDate: 'date', inspectionTime: 'time' },
  administrative: {
    region: 'text', districtEngineeringOffice: 'text', province: 'text', municipality: 'text', barangay: 'text',
  },
  preparedBy: { name: 'text', position: 'text', office: 'text' },
});

const LOCATION_FIELDS = Object.freeze({
  rawLocationText: 'text',
  roadName: 'text',
  kilometerReference: 'text',
  landmark: 'text',
  barangay: 'text',
  municipality: 'text',
  province: 'text',
  latitude: 'latitude',
  longitude: 'longitude',
  roadStatus: 'text',
  remarks: 'text',
});

const LOCATION_GROUPS = Object.freeze({
  flood: {
    currentFloodHeight: 'height',
    floodHeightBefore: 'height',
    floodHeightAfter: 'height',
    maximumFloodHeight: 'height',
    maximumFloodHeightTime: 'time',
    floodStartedAt: 'time',
    floodSubsidedAt: 'time',
  },
  rainfall: { rainfallStartedAt: 'time', rainfallEndedAt: 'time', rainfallIntensity: 'intensity' },
  intervention: { interventionText: 'text' },
});

// ---------------------------------------------------------------------------------------------------
// JSON schema for the AI answer, in the subset OpenAI Structured Outputs accepts in strict mode: every
// object closed with additionalProperties:false, every property required, nullable values written as a
// type array (no anyOf), no numeric/string-length constraints.
// ---------------------------------------------------------------------------------------------------

const nullableString = { type: ['string', 'null'] };

const AI_FIELD = {
  type: 'object',
  properties: { raw: nullableString, status: { type: 'string', enum: [...FIELD_STATUS] } },
  required: ['raw', 'status'],
  additionalProperties: false,
};

function closedObject(properties) {
  return { type: 'object', properties, required: Object.keys(properties), additionalProperties: false };
}

function fieldsObject(spec) {
  const props = {};
  for (const name of Object.keys(spec)) props[name] = AI_FIELD;
  return props;
}

function groupsObject(groups) {
  const props = {};
  for (const [group, spec] of Object.entries(groups)) props[group] = closedObject(fieldsObject(spec));
  return props;
}

const AI_LOCATION = closedObject({ ...fieldsObject(LOCATION_FIELDS), ...groupsObject(LOCATION_GROUPS) });

const AI_OUTPUT_SCHEMA = closedObject({
  classification: closedObject({
    reportType: { type: 'string', enum: [...REPORT_TYPES] },
    confidence: { type: 'string', enum: [...CONFIDENCE] },
    rationale: { type: 'string' },
  }),
  overallConfidence: { type: 'string', enum: [...CONFIDENCE] },
  ...fieldsObject(REPORT_FIELDS),
  ...groupsObject(REPORT_GROUPS),
  locations: { type: 'array', items: AI_LOCATION },
  notes: { type: 'array', items: { type: 'string' } },
});

// ---------------------------------------------------------------------------------------------------
// Minimal validator for the schema subset above (type or type array, enum, properties, required,
// additionalProperties:false, items, anyOf). Dependency-free, like the rest of the backend. It runs on
// every AI answer even though the provider enforces the same schema: provider-side enforcement is not
// a substitute for our own check.
// ---------------------------------------------------------------------------------------------------

function typeOf(v) {
  if (v === null) return 'null';
  if (Array.isArray(v)) return 'array';
  if (Number.isInteger(v)) return 'integer';
  return typeof v;
}

function validate(schema, value, path = '$', errors = []) {
  if (errors.length >= 20) return errors;
  if (schema.anyOf) {
    if (!schema.anyOf.some((s) => validate(s, value, path, []).length === 0)) errors.push(`${path}: no anyOf branch matched`);
    return errors;
  }
  const t = typeOf(value);
  const types = schema.type === undefined ? null : [].concat(schema.type);
  if (types && !types.some((e) => t === e || (e === 'number' && t === 'integer'))) {
    errors.push(`${path}: expected ${types.join('|')}, got ${t}`);
    return errors;
  }
  const expected = types && types.includes(t) ? t : types && types[0];
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${path}: value not in enum`);
  if (expected === 'object' && t === 'object') {
    for (const key of schema.required || []) {
      if (!Object.prototype.hasOwnProperty.call(value, key)) errors.push(`${path}.${key}: required`);
    }
    for (const [key, v] of Object.entries(value)) {
      const sub = schema.properties && schema.properties[key];
      if (!sub) {
        if (schema.additionalProperties === false) errors.push(`${path}.${key}: unexpected property`);
        continue;
      }
      validate(sub, v, `${path}.${key}`, errors);
    }
  }
  if (expected === 'array' && t === 'array' && schema.items) value.forEach((item, i) => validate(schema.items, item, `${path}[${i}]`, errors));
  return errors;
}

/**
 * Structural + semantic validation of an AI answer. Returns a list of problems (empty = valid).
 * Semantic rule: `provided` / `ambiguous` / `not_applicable` must carry the source text in `raw`;
 * `missing` must not carry any (a "missing" field with a value is a contradiction, not data).
 */
function validateAiOutput(output) {
  const errors = validate(AI_OUTPUT_SCHEMA, output);
  if (errors.length) return errors;
  const check = (field, path) => {
    const hasRaw = typeof field.raw === 'string' && field.raw.trim() !== '';
    if (field.status === 'missing' && hasRaw) errors.push(`${path}: status missing but raw present`);
    if (field.status !== 'missing' && !hasRaw) errors.push(`${path}: status ${field.status} requires raw source text`);
  };
  forEachAiField(output, check);
  return errors;
}

/** Visits every field object of an AI answer with its path. */
function forEachAiField(output, fn) {
  for (const name of Object.keys(REPORT_FIELDS)) fn(output[name], name);
  for (const [group, spec] of Object.entries(REPORT_GROUPS)) {
    for (const name of Object.keys(spec)) fn(output[group][name], `${group}.${name}`);
  }
  (output.locations || []).forEach((loc, i) => {
    for (const name of Object.keys(LOCATION_FIELDS)) fn(loc[name], `locations[${i}].${name}`);
    for (const [group, spec] of Object.entries(LOCATION_GROUPS)) {
      for (const name of Object.keys(spec)) fn(loc[group][name], `locations[${i}].${group}.${name}`);
    }
  });
}

module.exports = {
  SCHEMA_VERSION,
  REPORT_TYPES,
  FIELD_STATUS,
  CONFIDENCE,
  REPORT_STATUS,
  REPORT_FIELDS,
  REPORT_GROUPS,
  LOCATION_FIELDS,
  LOCATION_GROUPS,
  AI_OUTPUT_SCHEMA,
  validate,
  validateAiOutput,
  forEachAiField,
};
