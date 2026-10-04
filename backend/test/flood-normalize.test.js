'use strict';
const test = require('node:test');
const assert = require('node:assert');
const {
  parseHeight, parseTime, parseDate, parseCoordinate, parseIntensity, normalizeField, deriveInterventionType,
  squash, countOccurrences,
} = require('../lib/flood/normalize');
const { prefilter } = require('../lib/flood/prefilter');

test('flood heights normalize to meters', () => {
  assert.strictEqual(parseHeight('0.20 m').value, 0.2);
  assert.strictEqual(parseHeight('20 cm').value, 0.2);
  assert.strictEqual(parseHeight('200 mm').value, 0.2);
  assert.strictEqual(parseHeight('0.20m').value, 0.2);
  assert.strictEqual(parseHeight('0.5 meters').value, 0.5);
  assert.strictEqual(parseHeight('0 m').value, 0);
  assert.strictEqual(parseHeight('0.20 m').raw, '0.20 m');
});

test('".010 m" is kept raw and flagged, never silently reinterpreted', () => {
  const f = parseHeight('.010 m');
  assert.strictEqual(f.value, null);
  assert.strictEqual(f.status, 'ambiguous');
  assert.strictEqual(f.raw, '.010 m');
  assert.strictEqual(f.reason, 'SUSPICIOUS_MEASUREMENT');
});

test('heights that cannot be interpreted confidently are ambiguous with raw preserved', () => {
  for (const [raw, reason] of [['0.20', 'UNIT_MISSING'], ['knee-deep', 'NOT_NUMERIC'], ['0.20-0.30 m', 'MULTIPLE_VALUES'], ['15 m', 'SUSPICIOUS_MEASUREMENT']]) {
    const f = parseHeight(raw);
    assert.deepStrictEqual([f.value, f.status, f.raw, f.reason], [null, 'ambiguous', raw, reason], raw);
  }
});

test('times normalize to HH:mm and ambiguous times are not guessed', () => {
  assert.strictEqual(parseTime('2:00 PM').value, '14:00');
  assert.strictEqual(parseTime('8:30 PM').value, '20:30');
  assert.strictEqual(parseTime('3:30 p.m.').value, '15:30');
  assert.strictEqual(parseTime('1400H').value, '14:00');
  assert.strictEqual(parseTime('14:00').value, '14:00');
  assert.strictEqual(parseTime('12:00 NN').value, '12:00');
  assert.strictEqual(parseTime('12:00 MN').value, '00:00');
  const noMeridiem = parseTime('2:00');
  assert.deepStrictEqual([noMeridiem.value, noMeridiem.status, noMeridiem.reason], [null, 'ambiguous', 'AM_PM_UNSPECIFIED']);
  assert.strictEqual(parseTime('2:00 PM - 4:00 PM').status, 'ambiguous');
  assert.strictEqual(parseTime('not yet subsided').status, 'ambiguous');
});

test('a time keeps its date only when the same text states one', () => {
  const withDate = parseTime('October 4, 2026 2:00 PM');
  assert.strictEqual(withDate.value, '14:00');
  assert.strictEqual(withDate.date, '2026-10-04');
  assert.strictEqual(parseTime('2:00 PM').date, undefined);
});

test('dates normalize only when explicit and unambiguous', () => {
  assert.strictEqual(parseDate('October 4, 2026').value, '2026-10-04');
  assert.strictEqual(parseDate('Oct. 4, 2026').value, '2026-10-04');
  assert.strictEqual(parseDate('4 October 2026').value, '2026-10-04');
  assert.strictEqual(parseDate('2026-10-04').value, '2026-10-04');
  assert.strictEqual(parseDate('13/04/2026').value, '2026-04-13');
  assert.strictEqual(parseDate('10/04/2026').reason, 'DATE_ORDER_AMBIGUOUS');
  assert.strictEqual(parseDate('October 4').reason, 'YEAR_MISSING');
  assert.strictEqual(parseDate('February 30, 2026').status, 'ambiguous');
});

test('coordinates parse from decimal, pairs and DMS; out-of-range is ambiguous', () => {
  assert.strictEqual(parseCoordinate('14.4062', 'latitude').value, 14.4062);
  assert.strictEqual(parseCoordinate('14.4062, 120.9787', 'longitude').value, 120.9787);
  assert.strictEqual(parseCoordinate('14.4062, 120.9787', 'latitude').value, 14.4062);
  assert.strictEqual(parseCoordinate('14°25\'46.6"N', 'latitude').value, 14.429611);
  const swapped = parseCoordinate('120.9787', 'latitude');
  assert.deepStrictEqual([swapped.value, swapped.status, swapped.reason], [null, 'ambiguous', 'COORDINATE_OUT_OF_RANGE']);
});

test('rainfall intensity: light, moderate, heavy, light-to-moderate, and source wording kept', () => {
  assert.strictEqual(parseIntensity('Light').value, 'light');
  assert.strictEqual(parseIntensity('Moderate').value, 'moderate');
  assert.strictEqual(parseIntensity('Heavy').value, 'heavy');
  assert.strictEqual(parseIntensity('Light to Moderate').value, 'light_to_moderate');
  assert.strictEqual(parseIntensity('light-moderate rains').value, 'light_to_moderate');
  assert.strictEqual(parseIntensity('Moderate to Heavy Rains').value, 'moderate_to_heavy');
  assert.strictEqual(parseIntensity('None').value, 'none');
  assert.strictEqual(parseIntensity('Light to Moderate').raw, 'Light to Moderate');
  assert.strictEqual(parseIntensity('on and off').status, 'ambiguous');
});

test('field statuses: provided, missing, explicit N/A, ambiguous', () => {
  assert.deepStrictEqual(normalizeField('time', { raw: '2:00 PM', status: 'provided' }).value, '14:00');
  const missing = normalizeField('time', { raw: null, status: 'missing' });
  assert.deepStrictEqual([missing.value, missing.status, missing.raw], [null, 'missing', null]);
  const na = normalizeField('height', { raw: 'N/A', status: 'not_applicable' });
  assert.deepStrictEqual([na.value, na.status, na.raw], [null, 'not_applicable', 'N/A']);
  // An explicit "N/A" is not-applicable even if the extractor called it "provided".
  assert.strictEqual(normalizeField('height', { raw: 'n/a', status: 'provided' }).status, 'not_applicable');
  const amb = normalizeField('height', { raw: '0.20', status: 'ambiguous' });
  assert.deepStrictEqual([amb.value, amb.status, amb.raw], [null, 'ambiguous', '0.20']);
});

test('intervention type is system-derived and labelled as such', () => {
  const text = normalizeField('text', { raw: 'Declogging of drainage inlets; warning signages installed', status: 'provided' });
  const t = deriveInterventionType(text);
  assert.deepStrictEqual(t.value, ['declogging', 'traffic_management']);
  assert.strictEqual(t.origin, 'system_derived');
  assert.strictEqual(deriveInterventionType(normalizeField('text', { raw: null, status: 'missing' })).status, 'missing');
});

test('verbatim matching ignores case/spacing but not number boundaries', () => {
  const src = squash('Rain started 12:00 PM; height 10.20 m');
  assert.strictEqual(countOccurrences(src, squash('12:00 pm')), 1);
  assert.strictEqual(countOccurrences(src, squash('2:00 PM')), 0);
  assert.strictEqual(countOccurrences(src, squash('0.20 m')), 0);
});

test('prefilter: system notifications (WhatsApp and Viber) never reach the AI', () => {
  const base = { platform: 'whatsapp', media: [], mediaIndicator: { mediaType: 'TEXT' } };
  for (const text of ['WhatsApp Web is currently active', 'Checking for new messages', '5 new messages', 'This message was deleted',
    'Missed voice call', 'Incoming Viber call', 'Missed Viber call', 'Viber', 'Juan joined using this group\'s invite link']) {
    assert.deepStrictEqual(prefilter({ ...base, messageText: text }).reason, 'SYSTEM_NOTIFICATION', text);
  }
});

test('prefilter: greetings are not flood reports; flood words always go to classification', () => {
  const base = { platform: 'viber', media: [], mediaIndicator: { mediaType: 'TEXT' } };
  assert.strictEqual(prefilter({ ...base, messageText: 'Good morning po sir!' }).reason, 'SMALL_TALK');
  assert.strictEqual(prefilter({ ...base, messageText: 'Noted, salamat po' }).decision, 'needs_ai');
  assert.strictEqual(prefilter({ ...base, messageText: 'Noted' }).reason, 'SMALL_TALK');
  assert.strictEqual(prefilter({ ...base, messageText: 'Signage was added at Km 23' }).decision, 'needs_ai');
  assert.strictEqual(prefilter({ ...base, messageText: 'One lane passable on the left' }).decision, 'needs_ai');
});

test('prefilter: caption-less media is recognised (empty text or placeholder text)', () => {
  const media = { platform: 'whatsapp', media: [], mediaIndicator: { mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' } };
  assert.strictEqual(prefilter({ ...media, messageText: null }).decision, 'media_only');
  assert.strictEqual(prefilter({ ...media, messageText: '📷 Photo' }).decision, 'media_only');
  assert.strictEqual(prefilter({ ...media, messageText: '📷 Flooding at Molino bridge' }).decision, 'needs_ai');
  assert.strictEqual(prefilter({ platform: 'whatsapp', media: [], mediaIndicator: { mediaType: 'TEXT' }, messageText: '  ' }).reason, 'EMPTY_MESSAGE');
});
