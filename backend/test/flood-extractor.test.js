'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { extractReport } = require('../lib/flood/extractor');
const { SYSTEM_PROMPT, buildUserMessage } = require('../lib/flood/prompt');
const { AI_OUTPUT_SCHEMA } = require('../lib/flood/schema');
const { AiError } = require('../lib/ai');
const F = require('./helpers/flood-fixtures');

function source(overrides = {}) {
  return {
    platform: 'whatsapp', platformBasis: 'source_package', messageId: 'm-1', fingerprint: 'a'.repeat(64),
    deviceId: 'OKB-ANDROID-A82F19', groupId: null, groupName: 'DPWH Flood Monitoring', senderId: null,
    senderName: 'Engineer A', messageText: F.MONITORING_REPORT, messageTimestamp: '2026-10-05T09:00:00+08:00',
    receivedAt: '2026-10-05T09:00:05+08:00', capturedAt: '2026-10-05T09:00:01+08:00', sourcePackage: 'com.whatsapp',
    mediaIndicator: { mediaType: 'TEXT', mediaStatus: 'NONE' }, media: [], ...overrides,
  };
}

const flood = (r, i = 0) => r.extraction.locations[i].flood;

test('normal WhatsApp flood monitoring report is fully extracted and normalized', async () => {
  const ai = F.fakeAi(() => F.monitoringAnswer());
  const r = await extractReport(source(), { ai });
  assert.strictEqual(r.reportType, 'flood_monitoring');
  assert.strictEqual(r.extraction.reporting.reportDate.value, '2026-10-04');
  assert.strictEqual(r.extraction.reporting.reportTime.value, '14:00');
  assert.strictEqual(r.extraction.administrative.districtEngineeringOffice.value, 'Cavite 1st District Engineering Office');
  assert.strictEqual(flood(r).currentFloodHeight.value, 0.2);
  assert.strictEqual(flood(r).maximumFloodHeight.value, 0.3);
  assert.strictEqual(flood(r).maximumFloodHeightTime.value, '13:15');
  assert.strictEqual(flood(r).floodStartedAt.value, '12:30');
  assert.strictEqual(flood(r).floodSubsidedAt.value, '15:30');
  const loc = r.extraction.locations[0];
  assert.strictEqual(loc.latitude.value, 14.4062);
  assert.strictEqual(loc.longitude.value, 120.9787);
  assert.strictEqual(loc.rainfall.rainfallIntensity.value, 'moderate');
  assert.strictEqual(loc.rainfall.rainfallStartedAt.value, '11:45');
  assert.strictEqual(loc.intervention.interventionText.value, 'Declogging of drainage inlets; warning signages installed');
  assert.deepStrictEqual(loc.intervention.interventionType.value, ['declogging', 'traffic_management']);
  assert.strictEqual(r.extraction.preparedBy.name.value, 'Engr. Juan Dela Cruz');
  assert.strictEqual(r.extraction.preparedBy.position.value, 'Engineer II');
  // Fields the report does not state are missing (not guessed) and route the report to review.
  assert.ok(r.metadata.missingFields.includes('locations[0].flood.floodHeightBefore'));
  assert.strictEqual(r.status, 'needs_review');
  assert.strictEqual(r.metadata.ambiguousFields.length, 0);
});

test('a complete report with nothing missing or flagged is "extracted" (approval is still a human step)', async () => {
  const ai = F.fakeAi(() => {
    const a = F.monitoringAnswer();
    const markNA = (o) => { for (const [k, v] of Object.entries(o)) if (v && v.status === 'missing') o[k] = F.NA('N/A'); };
    markNA(a); for (const g of ['reporting', 'administrative', 'preparedBy']) markNA(a[g]);
    const l = a.locations[0]; markNA(l); for (const g of ['flood', 'rainfall', 'intervention']) markNA(l[g]);
    return a;
  });
  const text = `${F.MONITORING_REPORT}\nOther fields: N/A`;
  const r = await extractReport(source({ messageText: text }), { ai });
  assert.deepStrictEqual(r.metadata.missingFields, []);
  assert.strictEqual(r.status, 'extracted');
});

test('normal Viber report runs through the same engine, same prompt, with Viber provenance', async () => {
  const ai = F.fakeAi(() => F.monitoringAnswer());
  const wa = await extractReport(source(), { ai });
  const vb = await extractReport(source({ platform: 'viber', sourcePackage: 'com.viber.voip', groupName: 'DPWH Flood Monitoring', senderName: 'Engineer A' }), { ai });
  assert.strictEqual(ai.calls[0].system, ai.calls[1].system, 'one unified prompt');
  assert.ok(ai.calls[1].user.includes('"platform":"viber"'));
  assert.deepStrictEqual(vb.extraction, wa.extraction, 'platform does not change extraction');
  assert.strictEqual(vb.status, wa.status);
});

test('flood-prone and non-flood-prone assessments keep their type; explicit N/A is not_applicable', async () => {
  const ai = F.aiByText([
    ['AREAS NOT INCLUDED', F.aiOutput({
      reportType: 'non_flood_prone_area_assessment',
      report: { reportTitle: F.P('INITIAL ASSESSMENT REPORT ON AREAS NOT INCLUDED IN FLOOD-PRONE AREAS'), reporting: { inspectionDate: F.P('October 4, 2026') } },
      locations: [F.location({
        rawLocationText: F.P("Governor's Drive, Km 35+200, Dasmariñas City"),
        flood: { currentFloodHeight: F.NA('N/A'), maximumFloodHeight: F.NA('N/A') },
        rainfall: { rainfallIntensity: F.P('Heavy') },
      })],
    })],
    ['FLOOD-PRONE AREAS', F.aiOutput({
      reportType: 'flood_prone_area_assessment',
      report: { reportTitle: F.P('INITIAL ASSESSMENT REPORT ON FLOOD-PRONE AREAS') },
      locations: [F.location({ flood: { currentFloodHeight: F.P('0.15 m') }, rainfall: { rainfallIntensity: F.P('Light') } })],
    })],
  ]);
  const prone = await extractReport(source({ messageText: F.FLOOD_PRONE_REPORT }), { ai });
  assert.strictEqual(prone.reportType, 'flood_prone_area_assessment');
  assert.strictEqual(flood(prone).currentFloodHeight.value, 0.15);
  assert.strictEqual(prone.extraction.locations[0].rainfall.rainfallIntensity.value, 'light');

  const notProne = await extractReport(source({ messageText: F.NON_FLOOD_PRONE_REPORT }), { ai });
  assert.strictEqual(notProne.reportType, 'non_flood_prone_area_assessment');
  const max = flood(notProne).maximumFloodHeight;
  assert.deepStrictEqual([max.value, max.status, max.raw], [null, 'not_applicable', 'N/A']);
  assert.ok(notProne.metadata.notApplicableFields.includes('locations[0].flood.maximumFloodHeight'));
  assert.ok(!notProne.metadata.missingFields.includes('locations[0].flood.maximumFloodHeight'));
  assert.strictEqual(notProne.extraction.locations[0].rainfall.rainfallIntensity.value, 'heavy');
  assert.strictEqual(notProne.extraction.reporting.inspectionDate.value, '2026-10-04');
});

test('other flood report: descriptive depth stays text, never becomes a number', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    reportType: 'other_flood_report', confidence: 'medium',
    locations: [F.location({ rawLocationText: F.P('tulay sa Molino'), flood: { currentFloodHeight: F.P('hanggang tuhod') } })],
  }));
  const r = await extractReport(source({ messageText: F.OTHER_FLOOD_REPORT }), { ai });
  assert.strictEqual(r.reportType, 'other_flood_report');
  assert.strictEqual(flood(r).currentFloodHeight.value, null);
  assert.strictEqual(flood(r).currentFloodHeight.status, 'ambiguous');
  assert.strictEqual(flood(r).currentFloodHeight.raw, 'hanggang tuhod');
  assert.strictEqual(r.status, 'needs_review');
});

test('unrelated message classified not_flood_report is ignored, without extraction', async () => {
  const ai = F.fakeAi(() => F.aiOutput({ reportType: 'not_flood_report', confidence: 'high' }));
  const r = await extractReport(source({ messageText: 'Reminder: submit your timesheets by Friday.' }), { ai });
  assert.strictEqual(r.status, 'ignored');
  assert.strictEqual(r.reportType, 'not_flood_report');
  assert.strictEqual(r.extraction, null);
});

test('low-confidence "not a flood report" goes to review instead of being dropped', async () => {
  const ai = F.fakeAi(() => F.aiOutput({ reportType: 'not_flood_report', confidence: 'low' }));
  const r = await extractReport(source({ messageText: 'Tubig sa kanto ng Molino, sir' }), { ai });
  assert.strictEqual(r.status, 'needs_review');
});

test('system notifications and greetings are decided without calling the AI', async () => {
  const ai = F.fakeAi(() => assert.fail('AI must not be called'));
  for (const [text, platform] of [['WhatsApp Web is currently active', 'whatsapp'], ['Missed Viber call', 'viber'], ['Good morning po!', 'viber']]) {
    const r = await extractReport(source({ messageText: text, platform }), { ai });
    assert.strictEqual(r.status, 'ignored', text);
    assert.strictEqual(r.classification.basis, 'deterministic');
  }
  assert.strictEqual(ai.calls.length, 0);
});

test('group name alone never makes a message a flood report', async () => {
  const ai = F.fakeAi(() => assert.fail('AI must not be called'));
  const r = await extractReport(source({ groupName: 'OKB Flood Monitoring', messageText: 'Good morning everyone' }), { ai });
  assert.strictEqual(r.reportType, 'not_flood_report');
});

test('multiple locations in one message → ONE report with two locations', async () => {
  const ai = F.fakeAi(() => F.multiLocationAnswer());
  const r = await extractReport(source({ messageText: F.MULTI_LOCATION_REPORT }), { ai });
  assert.strictEqual(r.extraction.locations.length, 2);
  assert.strictEqual(r.extraction.locations[0].roadName.value, 'Daang Hari Road');
  assert.strictEqual(flood(r, 0).currentFloodHeight.value, 0.2);
  assert.strictEqual(r.extraction.locations[1].roadName.value, 'Marcos Alvarez Road');
  assert.strictEqual(flood(r, 1).currentFloodHeight.value, 0.1);
  assert.strictEqual(r.extraction.locations[0].rainfall.rainfallIntensity.value, 'light_to_moderate');
  assert.strictEqual(r.extraction.locations[1].rainfall.rainfallIntensity.value, 'heavy');
});

test('spec case: current height + subsidence only → flood start stays null and missing', async () => {
  const ai = F.fakeAi(() => F.partialAnswer());
  const r = await extractReport(source({ messageText: F.PARTIAL_REPORT, messageTimestamp: '2026-10-04T14:00:00+08:00' }), { ai });
  assert.strictEqual(flood(r).currentFloodHeight.value, 0.2);
  assert.strictEqual(flood(r).floodStartedAt.value, null);
  assert.strictEqual(flood(r).floodStartedAt.status, 'missing');
  assert.strictEqual(flood(r).floodSubsidedAt.value, '15:30');
  assert.ok(r.metadata.missingFields.includes('locations[0].flood.floodStartedAt'));
  assert.strictEqual(r.status, 'needs_review');
});

test('missing coordinates, subsidence, maximum height, report date, DEO and prepared-by all stay null', async () => {
  const ai = F.fakeAi(() => F.partialAnswer());
  const r = await extractReport(source({ messageText: F.PARTIAL_REPORT }), { ai });
  const loc = r.extraction.locations[0];
  assert.strictEqual(loc.latitude.value, null);
  assert.strictEqual(loc.longitude.value, null);
  assert.strictEqual(loc.flood.maximumFloodHeight.value, null);
  assert.strictEqual(r.extraction.reporting.reportDate.value, null);
  assert.strictEqual(r.extraction.administrative.districtEngineeringOffice.value, null);
  assert.strictEqual(r.extraction.preparedBy.name.value, null);
  assert.strictEqual(r.extraction.preparedBy.position.value, null);
  for (const p of ['locations[0].latitude', 'locations[0].longitude', 'locations[0].flood.maximumFloodHeight',
    'reporting.reportDate', 'administrative.districtEngineeringOffice', 'preparedBy.name']) {
    assert.ok(r.metadata.missingFields.includes(p), p);
  }
});

test('missing flood subsidence stays null (not taken from rainfall end)', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    locations: [F.location({ flood: { currentFloodHeight: F.P('0.20 m'), floodStartedAt: F.P('2:00 PM') }, rainfall: { rainfallEndedAt: F.P('4:00 PM') } })],
  }));
  const r = await extractReport(source({ messageText: 'Flood height 0.20 m. Flood started 2:00 PM. Rain stopped 4:00 PM.' }), { ai });
  assert.strictEqual(flood(r).floodSubsidedAt.value, null);
  assert.strictEqual(flood(r).floodSubsidedAt.status, 'missing');
  assert.strictEqual(r.extraction.locations[0].rainfall.rainfallEndedAt.value, '16:00');
});

test('ANTI-FABRICATION: one source time used for both rainfall start and flood start is discarded for review', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    locations: [F.location({
      flood: { currentFloodHeight: F.P('0.20 m'), floodStartedAt: F.P('2:00 PM') },
      rainfall: { rainfallStartedAt: F.P('2:00 PM') },
    })],
  }));
  const r = await extractReport(source({ messageText: 'Rain started 2:00 PM. Current flood height: 0.20 m' }), { ai });
  assert.strictEqual(flood(r).floodStartedAt.value, null);
  assert.strictEqual(flood(r).floodStartedAt.reason, 'SOURCE_TEXT_REUSED');
  assert.ok(r.metadata.ambiguousFields.includes('locations[0].flood.floodStartedAt'));
  assert.strictEqual(r.status, 'needs_review');
});

test('ANTI-FABRICATION: maximum height copied from current height is discarded', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    locations: [F.location({ flood: { currentFloodHeight: F.P('0.20 m'), maximumFloodHeight: F.P('0.20 m') } })],
  }));
  const r = await extractReport(source({ messageText: 'Current flood height: 0.20 m' }), { ai });
  assert.strictEqual(flood(r).maximumFloodHeight.value, null);
  assert.strictEqual(flood(r).maximumFloodHeight.status, 'ambiguous');
});

test('the same height legitimately stated twice is kept', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    locations: [F.location({ flood: { currentFloodHeight: F.P('0.20 m'), maximumFloodHeight: F.P('0.20 m') } })],
  }));
  const r = await extractReport(source({ messageText: 'Current flood height: 0.20 m\nMaximum flood height: 0.20 m' }), { ai });
  assert.strictEqual(flood(r).currentFloodHeight.value, 0.2);
  assert.strictEqual(flood(r).maximumFloodHeight.value, 0.2);
});

test('ANTI-FABRICATION: a value not present in the message (e.g. a date from the message timestamp) is discarded', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    report: { reporting: { reportDate: F.P('October 5, 2026') } },
    locations: [F.location({ latitude: F.P('14.4062'), flood: { currentFloodHeight: F.P('0.20 m') } })],
  }));
  const r = await extractReport(source({ messageText: 'Daang Hari Road flood height 0.20 m' }), { ai });
  const d = r.extraction.reporting.reportDate;
  assert.deepStrictEqual([d.value, d.status, d.reason], [null, 'ambiguous', 'VALUE_NOT_IN_SOURCE']);
  assert.strictEqual(r.extraction.locations[0].latitude.value, null, 'coordinates not in text are discarded');
  assert.ok(r.metadata.warnings.some((w) => w.code === 'VALUE_NOT_IN_SOURCE'));
});

test('the AI never receives the message received time or timestamp (nothing to infer dates from)', async () => {
  const s = source({ messageTimestamp: '2026-10-05T09:00:00+08:00', receivedAt: '2026-10-05T09:00:05+08:00' });
  const user = buildUserMessage(s, s.messageText);
  assert.ok(!user.includes('2026-10-05'));
  assert.ok(!user.includes('09:00'));
});

test('explicit N/A, ambiguous value and ".010 m" suspicious measurement', async () => {
  const ai = F.fakeAi(() => F.aiOutput({
    locations: [F.location({
      flood: { currentFloodHeight: F.P('.010 m'), maximumFloodHeight: F.NA('N/A'), floodHeightAfter: F.AMB('0.15') },
    })],
    notes: ['Current height ".010 m" looks like a typo.'],
  }));
  const r = await extractReport(source({ messageText: 'Flood height: .010 m\nMax: N/A\nAfter declogging 0.15' }), { ai });
  const f = flood(r);
  assert.deepStrictEqual([f.currentFloodHeight.value, f.currentFloodHeight.status, f.currentFloodHeight.raw], [null, 'ambiguous', '.010 m']);
  assert.strictEqual(f.currentFloodHeight.reason, 'SUSPICIOUS_MEASUREMENT');
  assert.deepStrictEqual([f.maximumFloodHeight.value, f.maximumFloodHeight.status], [null, 'not_applicable']);
  assert.deepStrictEqual([f.floodHeightAfter.value, f.floodHeightAfter.status, f.floodHeightAfter.raw], [null, 'ambiguous', '0.15']);
  assert.ok(r.metadata.warnings.some((w) => w.code === 'AI_NOTE'));
  assert.strictEqual(r.status, 'needs_review');
});

test('low confidence is a review signal, not permission to keep a guess', async () => {
  const ai = F.fakeAi(() => ({ ...F.partialAnswer(), overallConfidence: 'low' }));
  const r = await extractReport(source({ messageText: F.PARTIAL_REPORT }), { ai });
  assert.ok(r.metadata.warnings.some((w) => w.code === 'LOW_CONFIDENCE'));
  assert.strictEqual(r.status, 'needs_review');
});

test('caption-less media: no AI call, needs_review with an explanation', async () => {
  const ai = F.fakeAi(() => assert.fail('AI must not be called'));
  const r = await extractReport(source({ messageText: null, mediaIndicator: { mediaType: 'IMAGE', mediaStatus: 'UNAVAILABLE' } }), { ai });
  assert.strictEqual(r.status, 'needs_review');
  assert.strictEqual(r.reportType, null);
  assert.strictEqual(r.metadata.warnings[0].code, 'MEDIA_WITHOUT_TEXT');
});

test('Viber message with incomplete metadata (no sender, no group) still extracts; metadata stays null', async () => {
  const ai = F.fakeAi(() => F.partialAnswer());
  const r = await extractReport(source({ platform: 'viber', senderName: null, groupName: null, sourcePackage: 'com.viber.voip' }), { ai });
  assert.strictEqual(flood(r).currentFloodHeight.value, 0.2);
  assert.ok(ai.calls[0].user.includes('"senderName":null'));
  assert.ok(ai.calls[0].user.includes('"groupName":null'));
});

test('unsupported platform is ignored without AI', async () => {
  const ai = F.fakeAi(() => assert.fail('AI must not be called'));
  const r = await extractReport(source({ platform: null, sourcePackage: 'org.telegram.messenger' }), { ai });
  assert.strictEqual(r.status, 'ignored');
  assert.strictEqual(r.metadata.reason, 'UNSUPPORTED_PLATFORM');
});

test('AI failures propagate as AiError (the caller records "failed")', async () => {
  const ai = F.fakeAi(() => { throw new AiError('timeout', 'timed out', { retryable: true, attempts: 3 }); });
  await assert.rejects(extractReport(source(), { ai }), (e) => e.code === 'timeout');
});

test('prompt states the no-guessing rules and the schema forbids extra properties', () => {
  for (const rule of ['Never fill a field from a different field', 'flood start is not rainfall start', 'Never infer coordinates',
    '"missing"', '"not_applicable"', '"ambiguous"', 'One message is one report', 'group name is context only']) {
    assert.ok(SYSTEM_PROMPT.includes(rule), rule);
  }
  assert.strictEqual(AI_OUTPUT_SCHEMA.additionalProperties, false);
  assert.ok(AI_OUTPUT_SCHEMA.required.includes('locations'));
});
