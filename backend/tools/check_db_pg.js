#!/usr/bin/env node
/**
 * check_db_pg.js — verify Postgres connectivity and the runtime
 * AppConfig ↔ secrets.properties chain for the Streamify admin
 * backend.
 *
 * Run:   `node tools/check_db_pg.js`
 *        `node tools/check_db_pg.js --no-db`     (skip pg call, static compare only)
 *
 * Prints `BLAME: <layer>` if any step fails:
 *   1. DATABASE_URL is set (.env / process env)
 *   2. pg connection succeeds (TLS + timeout)
 *   3. AppConfig table exists in `public`
 *   4. AppConfig row exists with non-empty apiBaseUrl
 *   5. AppConfig.apiBaseUrl == secrets.properties API_BASE_URL
 *   6. Static fallback: migration-seed apiBaseUrl ↔ secrets.properties
 *
 * Exit codes:
 *   0  chain is consistent end-to-end
 *   1  no DATABASE_URL
 *   2  pg connect failed
 *   3  AppConfig table/column missing
 *   4  AppConfig row missing apiBaseUrl
 *   5  chain mismatch
 */

'use strict';

const { Client } = require('pg');
const fs = require('fs');
const path = require('path');

const ROOT_BACKEND = path.resolve(__dirname, '..');
const REPO_ROOT    = path.resolve(ROOT_BACKEND, '..');
const ENV_FILE     = path.join(ROOT_BACKEND, '.env');
const SECRETS_FILE = path.join(REPO_ROOT,  'secrets.properties');
const MIGRATION    = path.join(ROOT_BACKEND, 'prisma', 'supabase_migration.sql');

const SKIP_DB = process.argv.includes('--no-db');

// ── Helpers ────────────────────────────────────────────────────────

function readEnvFile() {
  if (!fs.existsSync(ENV_FILE)) return null;
  return fs.readFileSync(ENV_FILE, 'utf8');
}

function extractKey(content, key) {
  if (!content) return null;
  // Strip optional surrounding quotes, trim whitespace, allow CRLF.
  const rx = new RegExp(`^${key}\\s*=\\s*[\"']([^\"']+)[\"']?\\s*$`, 'mi');
  const m  = content.match(rx);
  if (m) return m[1].trim();
  // Bare/formatted fallback.
  const rx2 = new RegExp(`^${key}\\s*=\\s*(.+)$`, 'mi');
  const m2  = content.match(rx2);
  return m2 ? m2[1].trim().replace(/^["']|["']$/g, '') : null;
}

function maskUrl(u) {
  // Hide password segment in log output.
  return u ? u.replace(/(:[^:@/]+)@/, ':***@') : u;
}

function normalize(u) {
  // Strip trailing slashes + optional /api suffix for a fair compare.
  if (!u) return u;
  return u.replace(/\/+$/, '').replace(/\/api$/, '');
}

async function connectDb(url) {
  const c = new Client({
    connectionString: url,
    ssl: { rejectUnauthorized: false },
    connectionTimeoutMillis: 6000,
    statement_timeout: 8000,
  });
  c.on('error', (e) => { /* swallow — surfaced via await */ });
  await c.connect();
  return c;
}

// ── Static compare (works without DB) ──────────────────────────────

function staticCompareInline() {
  const secretsUrl = extractKey(fs.existsSync(SECRETS_FILE) ? fs.readFileSync(SECRETS_FILE, 'utf8') : '', 'API_BASE_URL');
  let seedUrl = null;
  if (fs.existsSync(MIGRATION)) {
    const m = fs.readFileSync(MIGRATION, 'utf8');
    // First INSERT into AppConfig — `INSERT INTO "AppConfig" ("id", "apiBaseUrl") VALUES (..., '…')`
    const match = m.match(/INSERT INTO "AppConfig"[^)]*\)\s*VALUES\s*\([^)]*['"]([^'"]+)['"]/);
    if (match) seedUrl = match[1];
  }
  console.log(`secrets.properties:        ${secretsUrl || '(not set)'}`);
  console.log(`migration seed apiBaseUrl: ${seedUrl || '(not parsed)'}`);
  const match = secretsUrl && seedUrl && normalize(secretsUrl) === normalize(seedUrl);
  console.log(`seed ↔ secrets match:      ${match ? '✓ identical' : '✗ MISMATCH'}`);
  return { secretsUrl, seedUrl, matched: !!match };
}

// ── Main ───────────────────────────────────────────────────────────

(async function main() {
  console.log('[streamify-db-check]');
  console.log('');

  // 1. DATABASE_URL presence
  const envContent = readEnvFile();
  let dbUrl = extractKey(envContent, 'DATABASE_URL');
  if (!dbUrl && process.env.DATABASE_URL) dbUrl = process.env.DATABASE_URL;
  if (!dbUrl) {
    console.log('BLAME: DATABASE_URL not set anywhere (process env, .env).');
    console.log('  \u2192 cp backend/.env.example backend/.env then fill DATABASE_URL, or');
    console.log('  \u2192 run with the URL inline: DATABASE_URL=postgres://... node tools/check_db_pg.js');
    console.log('');
    console.log('--- static fallback (no DB required) ---');
    staticCompareInline();
    if (SKIP_DB) process.exit(0);
    process.exit(1);
  }

  console.log(`DATABASE_URL:              ${maskUrl(dbUrl)}`);
  if (SKIP_DB) {
    console.log('');
    console.log('--- --no-db mode: skipping pg connect ---');
    staticCompareInline();
    process.exit(0);
  }

  // 2. pg connect
  let client;
  try {
    client = await connectDb(dbUrl);
  } catch (e) {
    console.log(`pg connect:                \u2717 ${e.code || e.message}`);
    console.log('');
    console.log('BLAME: Postgres unreachable.');
    console.log('  \u2192 Confirm host/port reachable from this machine.');
    console.log('  \u2192 For Supabase, use the pooler URL (port 6543) not direct (5432).');
    console.log('  \u2192 Confirm password / project-ref characters are URL-encoded.');
    console.log('');
    console.log('--- static fallback ---');
    staticCompareInline();
    process.exit(2);
  }
  console.log('pg connect:                \u2713 connected');

  // 3. AppConfig table presence
  let appCfgUrl = null;
  try {
    const tabs = await client.query(
      `SELECT table_name FROM information_schema.tables
         WHERE table_schema='public' AND table_name='AppConfig' LIMIT 1`
    );
    if (tabs.rowCount === 0) {
      console.log('AppConfig table:           \u2717 not in public schema');
      console.log('');
      console.log('BLAME: AppConfig table missing \u2014 migration not applied.');
      console.log('  \u2192 Run backend/prisma/supabase_migration.sql in your Postgres.');
      await client.end();
      process.exit(3);
    }
    console.log('AppConfig table:           \u2713 present');

    // 4. Row + apiBaseUrl
    const r = await client.query(
      `SELECT "apiBaseUrl", "updatedAt" FROM "AppConfig" ORDER BY "updatedAt" DESC NULLS LAST LIMIT 1`
    );
    if (r.rowCount === 0) {
      console.log('AppConfig row:             \u2717 no rows');
      console.log('BLAME: no AppConfig rows; server returns safe default with empty apiBaseUrl.');
      console.log('  \u2192 PUT /api/config as SUPER_ADMIN to bootstrap the row, or seed it.');
      await client.end();
      process.exit(4);
    }
    appCfgUrl = r.rows[0].apiBaseUrl || '';
    console.log(`AppConfig row:             ${appCfgUrl ? '\u2713' : '\u2717 empty'}  apiBaseUrl=${appCfgUrl}`);
    if (!appCfgUrl) {
      console.log('BLAME: AppConfig row has empty apiBaseUrl.');
      console.log('  \u2192 PUT /api/config as SUPER_ADMIN, or fix the seed in supabase_migration.sql.');
      await client.end();
      process.exit(4);
    }
  } catch (e) {
    console.log(`AppConfig query:           \u2717 ${e.code || e.message}`);
    await client.end();
    process.exit(3);
  }

  // 5. secrets.properties ↔ AppConfig
  const secretsUrl = extractKey(fs.existsSync(SECRETS_FILE) ? fs.readFileSync(SECRETS_FILE, 'utf8') : '', 'API_BASE_URL');
  console.log('');
  console.log('--- runtime chain compare ---');
  console.log(`secrets.properties API_BASE_URL:  ${secretsUrl || '(not set)'}`);
  console.log(`AppConfig.apiBaseUrl (in DB):    ${appCfgUrl}`);
  const matched = secretsUrl && normalize(secretsUrl) === normalize(appCfgUrl);
  console.log(`match:                            ${matched ? '\u2713 identical (after /api suffix + slash normalization)' : '\u2717 MISMATCH'}`);

  // Static compare also surfaces the migration seed for sanity.
  console.log('');
  console.log('--- static fallback (migration seed vs secrets) ---');
  staticCompareInline();

  await client.end();
  process.exit(matched ? 0 : 5);
})().catch((e) => {
  console.error('[streamify-db-check] uncaught:', e);
  process.exit(99);
});
