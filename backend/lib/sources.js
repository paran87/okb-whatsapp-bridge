'use strict';
/**
 * Message-source layer: turns a stored incoming message (from any supported messaging platform) into
 * one normalized `SourceMessage`. The flood-report extraction engine only ever sees `SourceMessage`s,
 * so it never branches on the platform.
 *
 * Adding a platform = one entry in PLATFORMS (plus the Android capture adapter). Nothing downstream
 * changes.
 */

/** Supported messaging platforms and the Android packages that identify them. */
const PLATFORMS = Object.freeze({
  whatsapp: { id: 'whatsapp', displayName: 'WhatsApp', packages: ['com.whatsapp', 'com.whatsapp.w4b'] },
  viber: { id: 'viber', displayName: 'Viber', packages: ['com.viber.voip'] },
});

const PLATFORM_BY_PACKAGE = new Map();
for (const p of Object.values(PLATFORMS)) for (const pkg of p.packages) PLATFORM_BY_PACKAGE.set(pkg, p.id);

function isSupportedPlatform(id) {
  return typeof id === 'string' && Object.prototype.hasOwnProperty.call(PLATFORMS, id);
}

function platformForPackage(sourcePackage) {
  return (typeof sourcePackage === 'string' && PLATFORM_BY_PACKAGE.get(sourcePackage)) || null;
}

/**
 * Resolves the platform of an incoming message body.
 *
 * - explicit `platform` (Phase 3 clients) must be supported and agree with `sourcePackage` when both are known;
 * - otherwise it is derived from `sourcePackage`;
 * - a Phase 1/2 record with neither is WhatsApp (the only platform that existed before Phase 3) — recorded
 *   as `platformBasis: "legacy_default"` so it is never mistaken for a source-provided value.
 *
 * @returns {{ platform: string|null, platformBasis: string, error?: string }}
 */
function resolvePlatform({ platform, sourcePackage }) {
  const fromPackage = platformForPackage(sourcePackage);
  if (platform !== undefined && platform !== null) {
    if (!isSupportedPlatform(platform)) return { platform: null, platformBasis: 'invalid', error: 'unsupported platform' };
    if (fromPackage && fromPackage !== platform) {
      return { platform: null, platformBasis: 'invalid', error: 'platform does not match sourcePackage' };
    }
    return { platform, platformBasis: 'client' };
  }
  if (fromPackage) return { platform: fromPackage, platformBasis: 'source_package' };
  if (sourcePackage === null || sourcePackage === undefined) return { platform: 'whatsapp', platformBasis: 'legacy_default' };
  // An unknown package: keep the message (Phase 1 behaviour) but never treat it as a known platform.
  return { platform: null, platformBasis: 'unknown_package' };
}

/** Dedupe key for incoming messages. The platform is part of provenance: never collapse across platforms. */
function messageDedupeKey(platform, fingerprint) {
  return `${platform || 'unknown'}:${fingerprint}`;
}

/** Platform of a stored message record, including records written before Phase 3. */
function platformOfRecord(record) {
  if (record.platform) return record.platform;
  return resolvePlatform({ sourcePackage: record.sourcePackage ?? null }).platform;
}

/**
 * Normalized source message consumed by the report pipeline. Metadata the platform does not expose is
 * `null` (e.g. Android notifications expose no stable group/sender ids) — never invented.
 *
 * @param {object} record stored message (messages.jsonl)
 * @param {object[]} media completed media objects linked to this message (may be empty)
 */
function toSourceMessage(record, media = []) {
  return {
    platform: platformOfRecord(record),
    platformBasis: record.platformBasis || (record.platform ? 'client' : resolvePlatform({ sourcePackage: record.sourcePackage ?? null }).platformBasis),
    messageId: record.id,
    clientMessageId: record.clientMessageId ?? null,
    fingerprint: record.fingerprint,
    deviceId: record.deviceId,
    groupId: record.groupId ?? null,
    groupName: record.groupName ?? null,
    senderId: record.senderId ?? null,
    senderName: record.senderName ?? null,
    messageText: record.messageText ?? null,
    /** Message time as reported by the messaging app (notification). */
    messageTimestamp: record.timestamp ?? null,
    /** When the backend received the upload. */
    receivedAt: record.receivedAt ?? null,
    capturedAt: record.capturedAt ?? null,
    sourcePackage: record.sourcePackage ?? null,
    /** What the notification said about media, even when no file could be acquired. */
    mediaIndicator: { mediaType: record.mediaType ?? null, mediaStatus: record.mediaStatus ?? null },
    media,
  };
}

module.exports = {
  PLATFORMS,
  isSupportedPlatform,
  platformForPackage,
  resolvePlatform,
  messageDedupeKey,
  platformOfRecord,
  toSourceMessage,
};
