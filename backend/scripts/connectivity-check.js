#!/usr/bin/env node
/**
 * connectivity-check.js — end-to-end diagnostic for the Streamify backend.
 *
 * Run: `node backend/scripts/connectivity-check.js`
 *
 * Probes three layers in order and prints "BLAME: <layer>" if any of them
 * are broken:
 *   1. backend/.env + required variables present
 *   2. backend HTTP server reachable on PORT (tries 4000 / 3000 if unset)
 *   3. /api/config route responds with a valid config payload
 *
 * Exits 0 on a clean run; non-zero on first failed layer.
 *
 * Safe to run repeatedly; doesn't mutate the database.
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

// ── Helpers ────────────────────────────────────────────────────────

function readEnvFile() {
  const envPath = path.resolve(__dirname, '..', '.env');
  if (!fs.existsSync(envPath)) return null;
  return fs.readFileSync(envPath, 'utf8');
}

function envHas(content, key) {
  if (!content) return false;
  return new RegExp(`^${key}=.+`, 'm').test(content);
}

function probePort(host, port, route = '/api/health', timeoutMs = 3000) {
  return new Promise((resolve) => {
    const started = Date.now();
    const req = http.request(
      { host, port, path: route, method: 'GET', timeout: timeoutMs },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () =>
          resolve({
            ok: res.statusCode >= 200 && res.statusCode < 400,
            status: res.statusCode,
            ms: Date.now() - started,
            preview: body.slice(0, 160).replace(/\s+/g, ' '),
          })
        );
      }
    );
    req.on('error', (e) =>
      resolve({ ok: false, error: e.code || e.message, ms: Date.now() - started })
    );
    req.on('timeout', () => {
      req.destroy();
      resolve({ ok: false, error: 'TIMEOUT', ms: Date.now() - started });
    });
    req.end();
  });
}

// ── Run ────────────────────────────────────────────────────────────

(async function main() {
  let exitCode = 0;
  console.log('[streamify-connectivity-check]');
  console.log('');

  // ── Layer 1: .env presence ──────────────────────────────────────
  const envContent = readEnvFile();
  console.log(`backend/.env:        ${envContent ? '\u2713 present' : '\u2717 MISSING'}`);
  console.log(`DATABASE_URL:        ${envHas(envContent, 'DATABASE_URL') ? '\u2713' : '\u2717 MISSING \u2014 backend will throw on first DB query'}`);
  console.log(`JWT_SECRET:          ${envHas(envContent, 'JWT_SECRET') ? '\u2713' : '\u26A0 using dev default \u2014 fine locally, NOT for prod'}`);
  console.log(`PORT (in .env):      ${envHas(envContent, 'PORT') ? '\u2713' : '\u26A0 not set \u2014 probing 4000 then 3000'}`);
  if (!envContent) exitCode = 1;

  // ── Layer 2: backend reachable ──────────────────────────────────
  const portsToTry = [];
  if (envContent) {
    const m = envContent.match(/^PORT=(\d+)/m);
    if (m) portsToTry.push(parseInt(m[1], 10));
  }
  portsToTry.push(4000, 3000);

  let reached = null;
  console.log('');
  console.log('--- backend reachability ---');
  for (const port of [...new Set(portsToTry)]) {
    const r = await probePort('127.0.0.1', port);
    if (r.ok) {
      console.log(`  [${port}] \u2713 ${r.status} (${r.ms}ms)  "${r.preview}"`);
      reached = { host: '127.0.0.1', port };
    } else {
      console.log(`  [${port}] \u2717 ${r.error || r.status} (${r.ms}ms)`);
    }
  }
  if (!reached) {
    console.log('');
    console.log('BLAME: backend is not reachable on any probed port.');
    console.log('  \u2192 Start it: cd backend && npm run dev');
    process.exit(2);
  }

  // ── Layer 3: /api/config route ──────────────────────────────────
  console.log('');
  console.log('--- /api/config probe (used by RemoteConfigHelper on app start) ---');
  const cfg = await probePort(reached.host, reached.port, '/api/config');
  if (cfg.ok) {
    try {
      const parsed = JSON.parse(cfg.preview);
      const url = parsed.apiBaseUrl || '(empty!)';
      console.log(`  \u2713 ${cfg.status} apiBaseUrl=${url}`);
      if (!url || url === '') {
        console.log('  \u26A0 AppConfig row missing apiBaseUrl \u2014 PUT /api/config from admin UI');
      }
    } catch {
      console.log(`  \u2713 ${cfg.status} (non-JSON body: "${cfg.preview}")`);
    }
  } else {
    console.log(`  \u2717 ${cfg.error || cfg.status}`);
    console.log('');
    console.log('BLAME: backend reachable but /api/config failing.');
    console.log('  \u2192 Most likely DATABASE_URL missing or invalid (see Layer 1).');
    process.exit(3);
  }

  console.log('');
  console.log('All layers OK. App should connect on next launch.');
  process.exit(exitCode);
})();
