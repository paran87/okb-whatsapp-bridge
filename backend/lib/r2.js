'use strict';
/**
 * Minimal, dependency-free Cloudflare R2 (S3-compatible) presigner.
 *
 * Generates AWS Signature V4 presigned PUT URLs so an authenticated device can upload one object
 * directly to R2 without ever holding the R2 Access Key / Secret. Credentials stay on the backend.
 *
 * R2 endpoint: https://<accountId>.r2.cloudflarestorage.com/<bucket>/<key>, region "auto", service "s3".
 */
const crypto = require('node:crypto');

const ALGORITHM = 'AWS4-HMAC-SHA256';
const SERVICE = 's3';
const REGION = 'auto';

/** RFC3986 encoding as required by SigV4 (encodeURIComponent plus !*'() ). */
function uriEncode(str, encodeSlash = true) {
  let out = '';
  for (const ch of Buffer.from(str, 'utf8').toString('binary')) {
    if (/[A-Za-z0-9\-._~]/.test(ch)) {
      out += ch;
    } else if (ch === '/') {
      out += encodeSlash ? '%2F' : '/';
    } else {
      out += '%' + ch.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0');
    }
  }
  return out;
}

function hmac(key, data) {
  return crypto.createHmac('sha256', key).update(data, 'utf8').digest();
}

function sha256Hex(data) {
  return crypto.createHash('sha256').update(data, 'utf8').digest('hex');
}

function amzDates(now = new Date()) {
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, '');
  return { amzDate, dateStamp: amzDate.slice(0, 8) };
}

function signingKey(secret, dateStamp) {
  const kDate = hmac('AWS4' + secret, dateStamp);
  const kRegion = hmac(kDate, REGION);
  const kService = hmac(kRegion, SERVICE);
  return hmac(kService, 'aws4_request');
}

function createR2Client(config) {
  const { accountId, bucket, accessKeyId, secretAccessKey } = config;
  if (!accountId || !bucket || !accessKeyId || !secretAccessKey) {
    throw new Error('R2 requires accountId, bucket, accessKeyId and secretAccessKey');
  }
  const host = `${accountId}.r2.cloudflarestorage.com`;
  const endpoint = `https://${host}`;

  /**
   * Presigned PUT URL scoped to one object key (and, when given, one Content-Type).
   * @returns {{ url, method, headers, expiresAt, objectKey }}
   */
  function presignPut(objectKey, { contentType, expiresSeconds = 900, now = new Date() } = {}) {
    const { amzDate, dateStamp } = amzDates(now);
    const canonicalUri = '/' + uriEncode(bucket, false) + '/' + uriEncode(objectKey, false);
    const credentialScope = `${dateStamp}/${REGION}/${SERVICE}/aws4_request`;

    const signedHeadersList = contentType ? ['content-type', 'host'] : ['host'];
    const signedHeaders = signedHeadersList.join(';');

    const query = {
      'X-Amz-Algorithm': ALGORITHM,
      'X-Amz-Credential': `${accessKeyId}/${credentialScope}`,
      'X-Amz-Date': amzDate,
      'X-Amz-Expires': String(expiresSeconds),
      'X-Amz-SignedHeaders': signedHeaders,
    };
    const canonicalQuery = Object.keys(query)
      .sort()
      .map((k) => `${uriEncode(k)}=${uriEncode(query[k])}`)
      .join('&');

    const canonicalHeaders =
      (contentType ? `content-type:${contentType}\n` : '') + `host:${host}\n`;
    const payloadHash = 'UNSIGNED-PAYLOAD';
    const canonicalRequest = [
      'PUT',
      canonicalUri,
      canonicalQuery,
      canonicalHeaders,
      signedHeaders,
      payloadHash,
    ].join('\n');

    const stringToSign = [ALGORITHM, amzDate, credentialScope, sha256Hex(canonicalRequest)].join('\n');
    const signature = crypto
      .createHmac('sha256', signingKey(secretAccessKey, dateStamp))
      .update(stringToSign, 'utf8')
      .digest('hex');

    const url = `${endpoint}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`;
    const headers = contentType ? { 'Content-Type': contentType } : {};
    return {
      url,
      method: 'PUT',
      headers,
      expiresAt: new Date(now.getTime() + expiresSeconds * 1000).toISOString(),
      objectKey,
    };
  }

  function remoteRef(objectKey) {
    return `r2://${bucket}/${objectKey}`;
  }

  return { presignPut, remoteRef, host, bucket };
}

module.exports = { createR2Client, uriEncode, sha256Hex };
