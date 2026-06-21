#!/usr/bin/env node
/**
 * verify-apis.js — End-to-end probe of every public route exposed by
 *                  backend/src/server.js. Designed to run against
 *                  a live HTTPS deployment (default: learngermanwith.fun).
 *
 * Each row in the verdict table is grouped into one of three layers:
 *
 *   L0 (TLS)        — the connection itself succeeds
 *   L1 (liveness)   — root + /api/health (no DB)
 *   L2 (DB-backed)  — /api/config, /api/categories, /api/channels,
 *                      /api/events, /api/highlights, /api/banners,
 *                      /api/analytics/events (ingest), and a few
 *                      admin-gated checks (expect 401 without a JWT)
 *
 * Status column meanings:
 *   ✓    HTTP 200 (or 401 for an auth-gated row, which is expected)
 *   DB✗  HTTP 200 OR non-401, but the body carries {"error":"…"} that
 *        smells like a Postgres / controller failure \u2014 BLAME is almost
 *        certainly DATABASE_URL or the run of supabase_migration.sql
 *   \u2715    any other 4xx/5xx
 *
 * Exit codes:
 *   0  every row matches expectation
 *   1  one or more rows failed unexpectedly
 *   2  TLS layer dead (can't even reach the host)
 *
 * Usage:
 *   node tools/verify-apis.js                      # learngermanwith.fun
 *   node tools/verify-apis.js other.example.com
 */

'use strict';

const https = require('https');

const HOST = process.argv[2] || 'learngermanwith.fun';
const PORT = 443;
const TIMEOUT_MS = 8000;

// Each row: method + path + expected category + optional body for POST.
const ENDPOINTS = [
  // L0 \u2014 root level (sanity)
  { method: 'GET',  path: '/',                       layer: 'L0', label: 'Root sanity' },

  // L1 \u2014 liveness
  { method: 'GET',  path: '/api/health',             layer: 'L1', label: 'Health probe (no DB)' },

  // L2 \u2014 DB-backed reads
  { method: 'GET',  path: '/api/config',             layer: 'L2', label: 'AppConfig (DB AppConfig row)' },
  { method: 'GET',  path: '/api/categories',         layer: 'L2', label: 'Categories list' },
  { method: 'GET',  path: '/api/channels',           layer: 'L2', label: 'Channels list' },
  { method: 'GET',  path: '/api/events',             layer: 'L2', label: 'Events list' },
  { method: 'GET',  path: '/api/highlights',         layer: 'L2', label: 'Highlights list' },
  { method: 'GET',  path: '/api/banners',            layer: 'L2', label: 'Banners list' },

  // L2 \u2014 POST endpoints
  { method: 'POST', path: '/api/devices/register',   layer: 'L2', label: 'Device register (no auth, DB)',
    body: { token: 'verify-apis-' + Date.now(), platform: 'node-test' } },
  { method: 'POST', path: '/api/analytics/events',   layer: 'L2', label: 'Analytics ingest (no auth, DB)',
    body: { eventType: 'verify', eventName: 'verify-apis.js' } },

  // L2 \u2014 auth-gated (expect 401 without a JWT)
  { method: 'GET',  path: '/api/admin/users',        layer: 'L2', label: 'Admin users list',  expectWithoutJwt: 401 },
  { method: 'GET',  path: '/api/admin/auth/me',      layer: 'L2', label: 'Auth whoami',       expectWithoutJwt: 401 },
  { method: 'GET',  path: '/api/notifications',      layer: 'L2', label: 'Notifications list', expectWithoutJwt: 401 },
  { method: 'GET',  path: '/api/analytics/overview', layer: 'L2', label: 'Analytics overview',expectWithoutJwt: 401 },
  { method: 'GET',  path: '/api/devices',            layer: 'L2', label: 'Devices list',      expectWithoutJwt: 401 },
];

function request(method, pathname, body) {
  return new Promise((resolve) => {
    const req = https.request(
      {
        host: HOST, port: PORT, path: pathname, method,
        servername: HOST, rejectUnauthorized: false,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
          'User-Agent': 'streamify-verify-apis/1.0',
        },
      },
      (res) => {
        let data = '';
        res.on('data', (c) => (data += c));
        res.on('end', () => {
          let parsed = null;
          try { parsed = JSON.parse(data); } catch { /* not JSON, fall through */ }
          resolve({ status: res.statusCode, body: data, parsed });
        });
      }
    );
    req.on('error', (e) => resolve({ status: 0, error: e.code || e.message }));
    req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error('timeout')));
    if (body) req.write(JSON.stringify(body));
    req.end();
  });
}

function classify(r, ep) {
  if (r.status === 0)         return { verdict: '\u2715', note: `connect: ${r.error || 'unknown'}` };
  if (r.status === 401)       return { verdict: '\u2713', note: '401 (auth required, as expected without JWT)' };
  if (r.status === 200 || r.status === 201) {
    // 200 with an error-shaped body = controller-level fail.
    const errLike = r.parsed && (r.parsed.error || /internal server error/i.test(r.body || ''));
    if (errLike) return { verdict: 'DB\u2717', note: `200 but error-shaped body: ${(r.parsed && r.parsed.error || '').slice(0, 80)}` };
    return { verdict: '\u2713', note: '200' };
  }
  if (r.status === 404)       return { verdict: '\u2715', note: '404 (route not mounted / wrong base URL)' };
  if (r.status === 429)       return { verdict: '\u2715', note: '429 (rate-limit hit during this run)' };
  if (r.status >= 500)        return { verdict: 'DB\u2717', note: `${r.status} (likely DB / handler error)` };
  return { verdict: '\u2715', note: `${r.status} ${(r.body || '').slice(0, 80)}` };
}

(async function main() {
  console.log(`[streamify-api-verify] host=${HOST}:443  (TLS=${process.env.SKIP_TLS ? 'skip' : 'live'})`);
  console.log('');

  let l0Ok = false, l1Ok = false;
  let pass = 0, softFail = 0, hardFail = 0;
  const rows = [];

  for (const ep of ENDPOINTS) {
    const r = await request(ep.method, ep.path, ep.body);
    const c = classify(r, ep);

    if (ep.layer === 'L0' && c.verdict === '\u2713') l0Ok = true;
    if (ep.layer === 'L1' && c.verdict === '\u2713') l1Ok = true;

    if (c.verdict === '\u2713') pass++;
    else if (c.verdict === 'DB\u2717') softFail++;
    else hardFail++;

    rows.push({ ...ep, status: r.status, body: r.body, parsed: r.parsed, verdict: c.verdict, note: c.note });
  }

  // ── Render verdict table ───────────────────────────────────────
  const fmt = (s, n) => (s || '').padEnd(n);
  console.log('LAYER | METHOD | PATH                                       | HTTP | VERDICT | NOTE');
  console.log('------+--------+--------------------------------------------+------+---------+--------------------------------------------------');
  for (const row of rows) {
    const line = [
      fmt(row.layer, 5),
      fmt(row.method, 6),
      fmt(row.path, 44),
      String(row.status).padEnd(4),
      fmt(row.verdict, 7),
      row.note,
    ].join(' | ');
    console.log(line);
  }

  // ── Summary + blame ────────────────────────────────────────────
  console.log('');
  const total = pass + softFail + hardFail;
  console.log(`SUMMARY: ${pass}/${total} PASS, ${softFail} DB-shaped fails, ${hardFail} hard fails`);
  if (!l0Ok) console.log('  BLAME: TLS layer down \u2014 cannot reach host');
  if (!l1Ok && l0Ok) console.log('  BLAME: /api/health not 200 \u2014 backend crashed or route removed');
  if (softFail > 0 && l0Ok && l1Ok) {
    console.log('  BLAME: HTTPS + health OK but DB-backed endpoints return errors.');
    console.log('         Run backend/tools/check_db_pg.js to isolate the DB layer.');
  }

  // ── Exit ───────────────────────────────────────────────────────
  if (!l0Ok) process.exit(2);
  if (hardFail > 0 || !l1Ok) process.exit(1);
  process.exit(0);
})().catch((e) => {
  console.error('[streamify-api-verify] uncaught:', e && e.stack || e);
  process.exit(99);
});
