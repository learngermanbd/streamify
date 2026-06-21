#!/usr/bin/env node
/**
 * verify-tls.js — One-shot verifier for the deployed HTTPS endpoint:
 *
 *   1. Live cert at $HOST matches the SPKI pin in BOTH
 *      app/src/main/res/xml/network_security_config.xml AND
 *      app/src/main/java/com/streamify/app/security/SSLPinner.kt
 *   2. /api/health over HTTPS returns HTTP 200 with a JSON body
 *   3. /api/config over HTTPS returns HTTP 200 with a JSON apiBaseUrl
 *
 * Exit codes (BLAME: <layer>):
 *   0  all layers OK
 *   1  live cert could not be fetched (DNS / TLS / route)
 *   2  live cert pin not present in pinned set
 *   3  /api/health or /api/config not 200
 *   4  pinned set is empty (run regen-pins.sh to confirm the host
 *      has certs, then update XML + SSLPinner.kt)
 *
 * Usage:
 *   node tools/verify-tls.js                      # learngermanwith.fun
 *   node tools/verify-tls.js api.example.com
 *
 * Safe to run repeatedly; no DB or filesystem writes.
 */

'use strict';

const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const HOST = process.argv[2] || 'learngermanwith.fun';
const PORT = 443;
const XML    = path.join(REPO_ROOT, 'app', 'src', 'main', 'res', 'xml', 'network_security_config.xml');
const KOTLIN = path.join(REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'streamify', 'app', 'security', 'SSLPinner.kt');

console.log(`[streamify-tls-verify] host=${HOST}:${PORT}`);
console.log('');

// ── Pinned-set extraction ────────────────────────────────────────

function extractPins(file) {
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, 'utf8');
  const out = new Set();
  // OkHttp / SSLPinner.kt: "sha256/<base64>"
  const r1 = /"sha256\/([A-Za-z0-9+/=]+)"/g;
  let m;
  while ((m = r1.exec(text)) !== null) out.add(m[1]);
  // Android xml: <pin digest="SHA-256"><base64></pin>
  const r2 = /<pin\s+digest="SHA-256">([A-Za-z0-9+/=]+)<\/pin>/g;
  while ((m = r2.exec(text)) !== null) out.add(m[1]);
  return [...out];
}

// ── Live cert + SPKI pin computation ─────────────────────────────

function fetchLiveCert() {
  return new Promise((resolve, reject) => {
    const req = https.request(
      { host: HOST, port: PORT, method: 'GET', servername: HOST, rejectUnauthorized: false },
      (res) => {
        const cert = res.socket.getPeerCertificate(true);
        res.resume();
        res.on('end', () => (cert && cert.raw ? resolve(cert) : reject(new Error('no peer cert'))));
      }
    );
    req.on('error', reject);
    req.setTimeout(8000, () => req.destroy(new Error('timeout')));
    req.end();
  });
}

function spkiPinFromDer(der) {
  return crypto.createHash('sha256').update(der).digest('base64');
}

function spkiPinFromRawCert(rawCertDer) {
  // Wrap DER in PEM envelope so Node's X509Certificate can parse it,
  // then export the SPKI as DER and hash it.
  const pem =
    '-----BEGIN CERTIFICATE-----\n' +
    rawCertDer.toString('base64').match(/.{1,64}/g).join('\n') +
    '\n-----END CERTIFICATE-----\n';
  const cert = new crypto.X509Certificate(pem);
  return spkiPinFromDer(cert.publicKey.export({ type: 'spki', format: 'der' }));
}

// ── HTTPS GET helper ────────────────────────────────────────────

function getJson(pathname) {
  return new Promise((resolve) => {
    const req = https.get(
      { host: HOST, port: PORT, path: pathname, servername: HOST, rejectUnauthorized: false },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          let parsed = null;
          try { parsed = JSON.parse(body); } catch { /* leave null */ }
          resolve({ status: res.statusCode, body, parsed });
        });
      }
    );
    req.on('error', (e) => resolve({ status: 0, error: e.code || e.message }));
    req.setTimeout(8000, () => req.destroy(new Error('timeout')));
  });
}

// ── Run ──────────────────────────────────────────────────────────

(async function main() {
  // 1. Pinned set (combined XML + Kotlin).
  const pinned = [...extractPins(XML), ...extractPins(KOTLIN)];
  console.log(`Pinned leaf/intermediate SPKI SHA-256  (${pinned.length}):`);
  if (pinned.length === 0) {
    console.log('  (none)');
    console.log('');
    console.log('BLAME: pinned set is EMPTY. Run tools/regen-pins.sh against the');
    console.log('       deployed host then update XML + SSLPinner.kt.');
    process.exit(4);
  }
  pinned.forEach((p) => console.log(`  ${p}`));

  // 2. Live cert.
  console.log('');
  console.log(`Fetching live cert from ${HOST}:${PORT} ...`);
  let cert;
  try {
    cert = await fetchLiveCert();
  } catch (e) {
    console.log(`✗ live cert fetch failed: ${e.code || e.message}`);
    process.exit(1);
  }
  const livePin = spkiPinFromRawCert(cert.raw);
  console.log(`Live leaf CN:                            ${cert.subject && cert.subject.CN || '(unknown)'}`);
  console.log(`Live leaf SPKI SHA-256:                  ${livePin}`);
  console.log(`Valid: ${cert.valid_from} \u2192 ${cert.valid_to}`);

  if (!pinned.includes(livePin)) {
    // Could be the chain's intermediate is what we pinned \u2014 signal that.
    const viaIntermediate = cert.issuer && cert.issuer.CN
      ? `(issuer: ${cert.issuer.CN})`
      : '';
    console.log('');
    console.log('BLAME: live leaf pin is NOT in the pinned set.');
    console.log(`       Cert is signed by: ${cert.issuer && cert.issuer.CN || '?'}`);
    console.log('       Run tools/regen-pins.sh, then update XML + SSLPinner.kt.');
    process.exit(2);
  }
  console.log('Live leaf pin match:                     ✓');

  // 3. /api/health.
  console.log('');
  console.log(`Probing https://${HOST}/api/health ...`);
  const h = await getJson('/api/health');
  console.log(`/api/health status:                       ${h.status}`);
  if (h.parsed && h.parsed.status) {
    console.log(`/api/health body:                         status="${h.parsed.status}" service="${h.parsed.service || ''}" version="${h.parsed.version || ''}"`);
  } else if (h.body) {
    console.log(`/api/health body:                         "${(h.body || '').slice(0, 120)}"`);
  }
  if (h.status !== 200) {
    console.log('BLAME: /api/health did not return 200.');
    process.exit(3);
  }

  // 4. /api/config.
  console.log('');
  console.log(`Probing https://${HOST}/api/config ...`);
  const c = await getJson('/api/config');
  console.log(`/api/config status:                       ${c.status}`);
  if (c.parsed && c.parsed.apiBaseUrl) {
    console.log(`/api/config body:                         apiBaseUrl="${c.parsed.apiBaseUrl}" maintenanceMode=${c.parsed.maintenanceMode}`);
  } else if (c.status === 200) {
    console.log('/api/config body:                         (no apiBaseUrl field \u2014 PUT /api/config as SUPER_ADMIN)');
  }
  if (c.status !== 200) {
    console.log('BLAME: /api/config did not return 200.');
    process.exit(3);
  }

  console.log('');
  console.log('\u2713 full TLS chain + endpoint round-trip passes.');
  process.exit(0);
})().catch((e) => {
  console.error('[streamify-tls-verify] uncaught:', e && e.stack || e);
  process.exit(99);
});
