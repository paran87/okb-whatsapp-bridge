'use strict';
/**
 * Deterministic pre-classification, run before any AI call.
 *
 * It only decides the cases that need no judgement — system notifications, empty messages, bare
 * greetings/acknowledgements, and media without any text — so AI cost is never spent on them.
 * Anything that might be a report goes to the AI. Group names are never used here: a message is not a
 * flood report because of the group it was posted in, and is not rejected because the group is unfamiliar.
 */

/** Media placeholder text that messaging apps put in notifications instead of a caption. */
const MEDIA_PLACEHOLDER =
  /^(?:[📷📸🖼🎥📹🎬👾🎤🎙🎵🎶📄📃📑📎📍🗺👤📊]️?\s*)?(photo|image|picture|video|gif|audio|voice message(?:\s*\(\d{1,2}:\d{2}\))?|document|sticker|location|live location|file)?$/iu;

/** System / service notification texts (WhatsApp and Viber). Matched against the whole message text. */
const SYSTEM_PATTERNS = [
  /^whatsapp( business)?$/i,
  /^viber$/i,
  /^whatsapp web is (currently )?active.*$/i,
  /^checking for (new )?messages.*$/i,
  /^you may have new messages.*$/i,
  /^\d+ (new )?messages?( from \d+ chats?)?\.?$/i,
  /^(this message was deleted|you deleted this message|message deleted|this message has been deleted)\.?$/i,
  /^waiting for this message.*$/i,
  /^(missed|incoming|ongoing) (viber |whatsapp )?(voice |video |group )?(voice |video )?call.*$/i,
  /^(backup|chat backup) (completed|in progress|failed|paused).*$/i,
  /^(backing up|restoring|syncing|preparing) (messages|media|backup|chats|history).*$/i,
  /^a new device was linked.*$/i,
  // Membership events. Kept narrow on purpose: "Signage was added" or "passable on the left" are report text.
  /^.{1,60} (joined using this group's invite link|joined the (group|community|chat)|left the (group|community|chat))\.?$/i,
  /^.{1,60} changed (the|this) (group|community)('s)? (description|icon|name|subject|photo|settings).*$/i,
  /^.{1,60} (pinned|unpinned) a message\.?$/i,
  /^messages and calls are end-to-end encrypted.*$/i,
  /^.{1,60} is typing.*$/i,
];

/** Greeting / acknowledgement-only messages (English and Filipino), checked only when short. */
const SMALL_TALK =
  /^(good\s+(morning|afternoon|evening|day|noon|night)|morning|hello|hi|hey|thanks?(\s+you)?|thank\s+you|ty|ok(ay)?|noted|copy|copy that|roger|received|god bless|ingat|salamat|maraming salamat|magandang\s+(umaga|hapon|gabi|tanghali)|amen|yes|no)(\s+(po|all|everyone|sir|ma'?am|team|din|rin|po sir|po ma'?am))*[\s!.,🙏👍😊🙂❤️]*$/iu;

const FLOOD_HINT = /\b(flood|baha|binaha|water level|rain|ulan|inundat|submerg|kneel?.?deep|gutter.?deep|impassable|passable)\b/i;

/**
 * @param {object} source normalized SourceMessage
 * @returns {{ decision: 'ignore'|'media_only'|'needs_ai', reason: string, text: string|null }}
 */
function prefilter(source) {
  const rawText = typeof source.messageText === 'string' ? source.messageText.trim() : '';
  const hasMedia = (source.media && source.media.length > 0) ||
    (source.mediaIndicator && source.mediaIndicator.mediaType && !['TEXT', 'LOCATION'].includes(source.mediaIndicator.mediaType));
  const placeholderOnly = rawText !== '' && MEDIA_PLACEHOLDER.test(rawText);
  const text = rawText === '' || placeholderOnly ? null : rawText;

  if (text === null) {
    return hasMedia
      ? { decision: 'media_only', reason: 'MEDIA_WITHOUT_TEXT', text: null }
      : { decision: 'ignore', reason: 'EMPTY_MESSAGE', text: null };
  }
  if (SYSTEM_PATTERNS.some((re) => re.test(text))) return { decision: 'ignore', reason: 'SYSTEM_NOTIFICATION', text };
  if (text.length <= 80 && SMALL_TALK.test(text) && !FLOOD_HINT.test(text)) {
    return { decision: 'ignore', reason: 'SMALL_TALK', text };
  }
  return { decision: 'needs_ai', reason: 'CONTENT_REQUIRES_CLASSIFICATION', text };
}

module.exports = { prefilter, MEDIA_PLACEHOLDER };
