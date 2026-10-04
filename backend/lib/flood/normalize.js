'use strict';
/**
 * Deterministic normalization of AI-extracted source text.
 *
 * The AI only *locates* values (verbatim `raw` text + a status); everything that turns text into a
 * structured value happens here, in plain code, so it is testable and never "creative". When a value
 * cannot be interpreted with confidence the field becomes `value: null, status: "ambiguous"` and the raw
 * text is kept for the reviewer. Nothing here fills in missing information.
 */

/** Explicit "not applicable" markers. ("None" is deliberately NOT here: its meaning depends on the field.) */
const NOT_APPLICABLE = /^(n\s*\.?\s*\/?\s*a\s*\.?|not applicable|not available|-{1,3}|—|–)$/i;

/** Wording that marks a value as approximate ("around 2:00 PM", "approx. 0.20 m"). */
const APPROXIMATE = /\b(around|approx(?:\.|imately)?|about|more or less|estimated|est\.)|~|\+\/-|±/i;

function cleanText(raw) {
  return String(raw).replace(/\s+/g, ' ').trim();
}

function field(status, { value = null, raw = null, origin = 'source', ...extra } = {}) {
  return { value, status, raw, origin, ...extra };
}

/** An ambiguous result: no value, raw kept, reason recorded. */
function ambiguous(raw, reason, extra = {}) {
  return field('ambiguous', { raw, reason, ...extra });
}

// ---------------------------------------------------------------------------------------------------
// Flood heights → meters
// ---------------------------------------------------------------------------------------------------

const UNIT_TO_METERS = [
  [/^(m|mtrs?|meters?|metres?|mts?)$/, 1],
  [/^(cm|cms|centimeters?|centimetres?)$/, 0.01],
  [/^(mm|millimeters?|millimetres?)$/, 0.001],
  [/^(in|inch|inches|")$/, 0.0254],
  [/^(ft|feet|foot|')$/, 0.3048],
];

/** Above this a road flood height is not plausible enough to normalize without a human look. */
const MAX_PLAUSIBLE_METERS = 10;

function parseHeight(raw) {
  const text = cleanText(raw).toLowerCase();
  const numbers = text.match(/\d*\.?\d+/g) || [];
  if (numbers.length === 0) return ambiguous(raw, 'NOT_NUMERIC');
  if (numbers.length > 1) return ambiguous(raw, 'MULTIPLE_VALUES');

  const m = /(\d*\.?\d+)\s*([a-z"']+\.?)?/.exec(text);
  const numText = m[1];
  const unitText = (m[2] || '').replace(/\.$/, '');
  const number = Number(numText);
  if (!Number.isFinite(number)) return ambiguous(raw, 'NOT_NUMERIC');

  if (number === 0) return field('provided', { value: 0, raw, unit: unitText || null });

  // ".010 m": a leading-dot value with 3+ decimals is a common typing slip (".10"? "0.010"? "1.0"?).
  // Its intended magnitude is unclear, so keep the raw text and let a human decide.
  if (/^\.\d{3,}$/.test(numText)) return ambiguous(raw, 'SUSPICIOUS_MEASUREMENT');

  if (!unitText) return ambiguous(raw, 'UNIT_MISSING');
  const unit = UNIT_TO_METERS.find(([re]) => re.test(unitText));
  if (!unit) return ambiguous(raw, 'UNKNOWN_UNIT');

  const meters = Math.round(number * unit[1] * 10000) / 10000;
  if (meters > MAX_PLAUSIBLE_METERS) return ambiguous(raw, 'SUSPICIOUS_MEASUREMENT');
  return field('provided', { value: meters, raw, unit: unitText, ...(APPROXIMATE.test(text) ? { approximate: true } : {}) });
}

// ---------------------------------------------------------------------------------------------------
// Dates → YYYY-MM-DD
// ---------------------------------------------------------------------------------------------------

const MONTHS = {
  jan: 1, january: 1, feb: 2, february: 2, mar: 3, march: 3, apr: 4, april: 4, may: 5, jun: 6, june: 6,
  jul: 7, july: 7, aug: 8, august: 8, sep: 9, sept: 9, september: 9, oct: 10, october: 10, nov: 11,
  november: 11, dec: 12, december: 12,
};

function iso(y, m, d) {
  const date = new Date(Date.UTC(y, m - 1, d));
  if (date.getUTCFullYear() !== y || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null;
  return `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

const MONTH_NAME = '(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)';
const DATE_PATTERNS = [
  { re: /\b(\d{4})-(\d{1,2})-(\d{1,2})\b/g, read: (m) => ({ y: +m[1], mo: +m[2], d: +m[3] }) },
  { re: new RegExp(`\\b${MONTH_NAME}\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:\\s*,\\s*|\\s+)(\\d{4})\\b`, 'gi'), read: (m) => ({ y: +m[3], mo: MONTHS[m[1].toLowerCase()], d: +m[2] }) },
  { re: new RegExp(`\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?${MONTH_NAME}\\.?,?\\s+(\\d{4})\\b`, 'gi'), read: (m) => ({ y: +m[3], mo: MONTHS[m[2].toLowerCase()], d: +m[1] }) },
  {
    re: /\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})\b/g,
    read: (m) => {
      if (m[3].length !== 4) return { reason: 'YEAR_INCOMPLETE' };
      const a = +m[1];
      const b = +m[2];
      // MM/DD vs DD/MM cannot be told apart when both parts could be a month.
      if (a <= 12 && b <= 12 && a !== b) return { reason: 'DATE_ORDER_AMBIGUOUS' };
      return a > 12 ? { y: +m[3], mo: b, d: a } : { y: +m[3], mo: a, d: b };
    },
  },
];

/** Finds every explicit date in a text. Returns { dates: [{iso, index, length}], problems: [reason] }. */
function findDates(text) {
  const dates = [];
  const problems = [];
  const taken = [];
  for (const { re, read } of DATE_PATTERNS) {
    re.lastIndex = 0;
    let m;
    while ((m = re.exec(text))) {
      const start = m.index;
      const end = start + m[0].length;
      if (taken.some(([s, e]) => start < e && end > s)) continue;
      taken.push([start, end]);
      const parts = read(m);
      if (parts.reason) { problems.push(parts.reason); continue; }
      const value = iso(parts.y, parts.mo, parts.d);
      if (!value) { problems.push('INVALID_DATE'); continue; }
      dates.push({ iso: value, index: start, length: m[0].length });
    }
  }
  return { dates, problems };
}

function parseDate(raw) {
  const text = cleanText(raw);
  const { dates, problems } = findDates(text);
  if (problems.length) return ambiguous(raw, problems[0]);
  if (dates.length === 0) {
    const yearless = new RegExp(`\\b${MONTH_NAME}\\.?\\s+\\d{1,2}\\b|\\b\\d{1,2}\\s+${MONTH_NAME}\\b`, 'i');
    return ambiguous(raw, yearless.test(text) ? 'YEAR_MISSING' : 'NOT_A_DATE');
  }
  const distinct = [...new Set(dates.map((d) => d.iso))];
  if (distinct.length > 1) return ambiguous(raw, 'MULTIPLE_VALUES');
  return field('provided', { value: distinct[0], raw });
}

// ---------------------------------------------------------------------------------------------------
// Times → HH:mm
// ---------------------------------------------------------------------------------------------------

const TIME_RE = /\b(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?|nn|noon|mn|midnight|h(?:rs?|ours?)?\.?)?(?![\d])/gi;
const MILITARY_RE = /\b([01]\d|2[0-3])([0-5]\d)\s*(h(?:rs?|ours?)?\.?)(?![a-z])/gi;

function hhmm(h, min) {
  return `${String(h).padStart(2, '0')}:${String(min).padStart(2, '0')}`;
}

/**
 * Parses one time out of `text` (any date inside the text has already been removed).
 * Returns { value } or { reason }.
 */
function readTime(text) {
  const found = [];
  MILITARY_RE.lastIndex = 0;
  let m;
  const military = [];
  while ((m = MILITARY_RE.exec(text))) military.push(m);
  for (const mm of military) found.push({ value: hhmm(+mm[1], +mm[2]) });
  const rest = military.length ? text.replace(MILITARY_RE, ' ') : text;

  TIME_RE.lastIndex = 0;
  while ((m = TIME_RE.exec(rest))) {
    const hour = +m[1];
    const hasMinutes = m[2] !== undefined;
    const minute = hasMinutes ? +m[2] : 0;
    const suffix = (m[3] || '').toLowerCase().replace(/[.\s]/g, '');
    if (!hasMinutes && !suffix) continue; // a bare number is not a time
    if (minute > 59) { found.push({ reason: 'INVALID_TIME' }); continue; }
    if (suffix === 'am' || suffix === 'pm') {
      if (hour < 1 || hour > 12) { found.push({ reason: 'INVALID_TIME' }); continue; }
      found.push({ value: hhmm((hour % 12) + (suffix === 'pm' ? 12 : 0), minute) });
    } else if (suffix === 'nn' || suffix === 'noon') {
      found.push(hour === 12 && minute === 0 ? { value: '12:00' } : { reason: 'INVALID_TIME' });
    } else if (suffix === 'mn' || suffix === 'midnight') {
      found.push(hour === 12 && minute === 0 ? { value: '00:00' } : { reason: 'INVALID_TIME' });
    } else if (suffix.startsWith('h')) {
      found.push(hour <= 23 ? { value: hhmm(hour, minute) } : { reason: 'INVALID_TIME' });
    } else if (hour === 0 || (hour >= 13 && hour <= 23)) {
      found.push({ value: hhmm(hour, minute) }); // unambiguous 24-hour clock
    } else if (hour <= 12) {
      found.push({ reason: 'AM_PM_UNSPECIFIED' }); // "2:00" could be 02:00 or 14:00
    } else {
      found.push({ reason: 'INVALID_TIME' });
    }
  }
  if (/^\s*(12\s*)?(noon|midnight)\s*$/i.test(rest) && found.length === 0) {
    found.push({ value: /noon/i.test(rest) ? '12:00' : '00:00' });
  }
  if (found.length === 0) return { reason: 'NOT_A_TIME' };
  const values = [...new Set(found.filter((f) => f.value).map((f) => f.value))];
  const reasons = found.filter((f) => f.reason).map((f) => f.reason);
  if (values.length + reasons.length > 1) return { reason: 'MULTIPLE_VALUES' };
  return reasons.length ? { reason: reasons[0] } : { value: values[0] };
}

function parseTime(raw) {
  const text = cleanText(raw);
  // A date written in the same text is kept with the time (never assumed when absent).
  const { dates, problems } = findDates(text);
  let withoutDates = text;
  for (const d of [...dates].sort((a, b) => b.index - a.index)) {
    withoutDates = withoutDates.slice(0, d.index) + ' ' + withoutDates.slice(d.index + d.length);
  }
  const t = readTime(withoutDates);
  if (t.reason) return ambiguous(raw, t.reason);
  const distinctDates = [...new Set(dates.map((d) => d.iso))];
  const extra = {};
  if (distinctDates.length === 1 && problems.length === 0) extra.date = distinctDates[0];
  else if (distinctDates.length > 1 || problems.length) extra.dateNote = 'date present but not interpretable';
  if (APPROXIMATE.test(text)) extra.approximate = true;
  return field('provided', { value: t.value, raw, ...extra });
}

// ---------------------------------------------------------------------------------------------------
// Coordinates → decimal degrees
// ---------------------------------------------------------------------------------------------------

/** Philippine bounding box (generous). Outside it, swapped or mistyped coordinates are likely. */
const PH_LAT = [4, 22];
const PH_LON = [116, 127.5];

function parseCoordinateNumbers(text) {
  const out = [];
  // DMS: 14°25'46.6"N
  const dms = /(-?\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*(?:(\d{1,2}(?:\.\d+)?)\s*["″])?\s*([NSEW])?/gi;
  let m;
  let consumed = text;
  while ((m = dms.exec(text))) {
    let v = Math.abs(+m[1]) + (+m[2]) / 60 + (m[3] ? +m[3] / 3600 : 0);
    const hemi = (m[4] || '').toUpperCase();
    if (+m[1] < 0 || hemi === 'S' || hemi === 'W') v = -v;
    out.push({ value: Math.round(v * 1e6) / 1e6, hemi: hemi || null });
    consumed = consumed.replace(m[0], ' ');
  }
  const dec = /([NSEW])?\s*(-?\d{1,3}\.\d+)\s*°?\s*([NSEW])?/gi;
  while ((m = dec.exec(consumed))) {
    const hemi = (m[1] || m[3] || '').toUpperCase();
    let v = +m[2];
    if (hemi === 'S' || hemi === 'W') v = -Math.abs(v);
    out.push({ value: v, hemi: hemi || null });
  }
  return out;
}

function parseCoordinate(raw, axis) {
  const text = cleanText(raw);
  const nums = parseCoordinateNumbers(text);
  if (nums.length === 0) return ambiguous(raw, 'NOT_A_COORDINATE');
  const [lo, hi] = axis === 'latitude' ? PH_LAT : PH_LON;
  const axisHemis = axis === 'latitude' ? ['N', 'S'] : ['E', 'W'];
  // "14.4296, 120.9367" given for one axis: the Philippine latitude and longitude ranges do not overlap,
  // so the matching number is unambiguous. Anything else is left to a human.
  const candidates = nums.filter((n) => (n.hemi ? axisHemis.includes(n.hemi) : true) && n.value >= lo && n.value <= hi);
  if (candidates.length === 1) return field('provided', { value: candidates[0].value, raw });
  if (candidates.length > 1) return ambiguous(raw, 'MULTIPLE_VALUES');
  return ambiguous(raw, 'COORDINATE_OUT_OF_RANGE');
}

// ---------------------------------------------------------------------------------------------------
// Rainfall intensity
// ---------------------------------------------------------------------------------------------------

const INTENSITIES = {
  none: 'none', 'no rain': 'none', 'no rainfall': 'none', nil: 'none',
  light: 'light',
  'light to moderate': 'light_to_moderate',
  moderate: 'moderate',
  'moderate to heavy': 'moderate_to_heavy',
  heavy: 'heavy',
  'heavy to intense': 'heavy_to_intense',
  intense: 'intense',
  torrential: 'torrential',
};

function parseIntensity(raw) {
  const key = cleanText(raw).toLowerCase()
    .replace(/[()]/g, ' ')
    .replace(/\b(rains?|rainfall|showers?|precipitation|intensity)\b/g, ' ')
    .replace(/\s*(?:-|\/|–|—)\s*/g, ' to ')
    .replace(/\s+/g, ' ')
    .trim();
  const value = INTENSITIES[key];
  if (!value) return ambiguous(raw, 'UNRECOGNIZED_INTENSITY');
  return field('provided', { value, raw });
}

// ---------------------------------------------------------------------------------------------------
// Intervention type (system-derived, clearly labelled as such)
// ---------------------------------------------------------------------------------------------------

const INTERVENTION_TYPES = [
  ['declogging', /\b(de-?clog\w*|unclog\w*|desilt\w*|de-silt\w*)/i],
  ['pumping', /\bpump\w*/i],
  ['traffic_management', /\b(signages?|warning signs?|barricad\w*|re-?rout\w*|traffic (management|advisory|enforcer)|cones)\b/i],
  ['debris_clearing', /\b(debris|obstruction|clearing)\b/i],
  ['monitoring', /\bmonitor\w*/i],
];

function deriveInterventionType(interventionField) {
  const base = { origin: 'system_derived', basis: 'keyword_match' };
  if (interventionField.status !== 'provided' || !interventionField.value) {
    return { value: null, status: interventionField.status === 'provided' ? 'missing' : interventionField.status, raw: null, ...base };
  }
  const types = INTERVENTION_TYPES.filter(([, re]) => re.test(interventionField.value)).map(([t]) => t);
  return types.length
    ? { value: types, status: 'provided', raw: null, ...base }
    : { value: null, status: 'ambiguous', raw: null, reason: 'NO_KNOWN_INTERVENTION_KEYWORD', ...base };
}

// ---------------------------------------------------------------------------------------------------

/**
 * Normalizes one AI field ({ raw, status }) according to its kind.
 */
function normalizeField(kind, aiField) {
  const raw = typeof aiField.raw === 'string' && aiField.raw.trim() !== '' ? cleanText(aiField.raw) : null;
  if (aiField.status === 'missing' || raw === null) return field('missing');
  if (aiField.status === 'not_applicable') return field('not_applicable', { raw });
  if (aiField.status === 'ambiguous') return ambiguous(raw, 'AI_FLAGGED_AMBIGUOUS');
  if (NOT_APPLICABLE.test(raw)) return field('not_applicable', { raw });

  switch (kind) {
    case 'text': return field('provided', { value: raw, raw });
    case 'date': return parseDate(raw);
    case 'time': return parseTime(raw);
    case 'height': return parseHeight(raw);
    case 'latitude': return parseCoordinate(raw, 'latitude');
    case 'longitude': return parseCoordinate(raw, 'longitude');
    case 'intensity': return parseIntensity(raw);
    default: throw new Error(`unknown field kind ${kind}`);
  }
}

// ---------------------------------------------------------------------------------------------------
// Verbatim-source checks (anti-fabrication)
// ---------------------------------------------------------------------------------------------------

/** Comparison form: case-folded, typographic variants unified, all whitespace removed. */
function squash(text) {
  return String(text)
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[’‘`]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[–—]/g, '-')
    .replace(/[\s​]+/g, '');
}

/**
 * Number of places `needle` occurs in `haystack` (both squashed), not counting matches that are part of a
 * longer number ("2:00pm" inside "12:00pm", "0.20" inside "10.20").
 */
function countOccurrences(haystack, needle) {
  if (!needle) return 0;
  let count = 0;
  let from = 0;
  const startsNumeric = /^[\d.]/.test(needle);
  const endsNumeric = /\d$/.test(needle);
  for (;;) {
    const i = haystack.indexOf(needle, from);
    if (i === -1) return count;
    const before = haystack[i - 1];
    const after = haystack[i + needle.length];
    const okBefore = !startsNumeric || before === undefined || !/[\d.]/.test(before);
    const okAfter = !endsNumeric || after === undefined || !/\d/.test(after);
    if (okBefore && okAfter) count++;
    from = i + 1;
  }
}

module.exports = {
  normalizeField,
  parseHeight,
  parseDate,
  parseTime,
  parseCoordinate,
  parseIntensity,
  deriveInterventionType,
  squash,
  countOccurrences,
  NOT_APPLICABLE,
};
