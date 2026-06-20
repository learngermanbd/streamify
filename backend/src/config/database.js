/**
 * Phase 9 · pg database adapter.
 *
 * Uses a direct PostgreSQL connection pool (via node-postgres) instead of
 * the Supabase REST API.  By-passing the API layer means we never deal
 * with expired/revoked service_role keys.
 *
 * Exposes the same Prisma-alike API that all 12 controllers call:
 *   findMany  findUnique  findFirst  create  update  delete
 *   upsert    count       deleteMany createMany
 */

const { Client } = require('pg');

// ── Per-query Client (avoids pg-pool SNI issues with Supabase PgBouncer) ─
//    tools/check_db_pg.js proved that new Client({...}) with the object
//    config works; pg-pool's internal Client construction can behave
//    differently with TLS/SNI on some versions.

let client = null;

function getClient() {
  if (client) return client;

  const host     = process.env.DB_HOST;
  const port     = process.env.DB_PORT     ? parseInt(process.env.DB_PORT, 10) : 6543;
  const user     = process.env.DB_USER;
  const password = process.env.DB_PASSWORD;
  const database = process.env.DB_NAME || 'postgres';

  if (!host || !user || !password) {
    throw new Error('[pg] Missing DB_HOST/DB_USER/DB_PASSWORD env vars. Set them in .env');
  }

  client = new Client({
    host, port, user, password, database,
    ssl: { rejectUnauthorized: false },
    connectionTimeoutMillis: 10000,
    keepAlive: true,
  });

  client.on('error', (err) => {
    console.error('[pg] Client error:', err.message);
  });

  return client;
}

let connected = false;

async function ensureConnected() {
  const cl = getClient();
  if (connected) return;
  await cl.connect();
  connected = true;
  console.log('[pg] Connected →', cl.host + ':' + cl.port);
}

// ── Quote an identifier (table or column name) ────────────────────
function qi(name) {
  return '"' + name.replace(/"/g, '""') + '"';
}

// ── Build WHERE clause from a filter object ───────────────────────
//   { status: 'LIVE', scheduledAt: { lte: Date } }  →  "status"=$1 AND "scheduledAt"<=$2
function buildWhere(where, values, startIdx) {
  if (!where || Object.keys(where).length === 0) return '';
  const clauses = [];
  let i = startIdx;
  for (const [key, val] of Object.entries(where)) {
    if (val === undefined || val === null) continue;
    if (typeof val === 'object' && !Array.isArray(val) && !(val instanceof Date)) {
      // Range filter  { column: { lte: ..., gte: ... } }
      for (const [op, opVal] of Object.entries(val)) {
        const sqlOp = { lte: '<=', gte: '>=', lt: '<', gt: '>', not: '<>' }[op] || '=';
        clauses.push(`${qi(key)}${sqlOp}$${i}`);
        values.push(opVal instanceof Date ? opVal.toISOString() : opVal);
        i++;
      }
    } else {
      clauses.push(`${qi(key)}=$${i}`);
      values.push(val);
      i++;
    }
  }
  return ' WHERE ' + clauses.join(' AND ');
}

// ── Build ORDER BY ────────────────────────────────────────────────
function buildOrderBy(orderBy) {
  if (!orderBy) return '';
  const entries = Array.isArray(orderBy) ? orderBy : [orderBy];
  const parts = [];
  for (const entry of entries) {
    for (const [field, dir] of Object.entries(entry)) {
      parts.push(qi(field) + (dir === 'desc' ? ' DESC' : ' ASC'));
    }
  }
  return ' ORDER BY ' + parts.join(', ');
}

// ── Map relation names to foreign-key info ────────────────────────
const RELATIONS = {
  streams:    { table: 'StreamLink',  fk: 'eventId',   many: true },
  category:   { table: 'Category',    fk: null,         many: false, reverseFk: 'id', localFk: 'categoryId' },
  sentBy:     { table: 'Admin',       fk: null,         many: false, reverseFk: 'id', localFk: 'sentById' },
  channels:   { table: 'Channel',     fk: 'categoryId', many: true },
  events:     { table: 'Event',       fk: 'categoryId', many: true },
};

// ── Model class (one per table) ───────────────────────────────────
class PgModel {
  constructor(tableName) {
    this.table = tableName;
  }

  // ── Helpers ─────────────────────────────────────────────────────
  async _query(text, params = []) {
    await ensureConnected();
    const res = await client.query(text, params);
    return res.rows;
  }

  async _queryOne(text, params = []) {
    const rows = await this._query(text, params);
    return rows[0] || null;
  }

  /** Fetch related rows for a set of parent records. */
  async _loadRelations(rows, include) {
    if (!include || !rows || rows.length === 0) return;
    const idField = 'id';

    for (const [key, spec] of Object.entries(include)) {
      if (key === '_count') continue;
      const rel = RELATIONS[key] || { table: key, fk: this.table.toLowerCase() + 'Id', many: true };

      if (rel.many) {
        // One-to-many: child.fk IN parent.ids
        const ids = rows.map(r => r[idField]);
        const childRows = await this._query(
          `SELECT * FROM ${qi(rel.table)} WHERE ${qi(rel.fk)} = ANY($1::text[])`,
          [ids]
        );
        // Group children by parent id
        for (const row of rows) {
          row[key] = childRows.filter(c => c[rel.fk] === row[idField]);
        }
      } else {
        // Many-to-one or one-to-one: parent.localFk = child.reverseFk
        const fkField = rel.localFk || (this.table.toLowerCase() + 'Id');
        const refField = rel.reverseFk || 'id';
        const ids = [...new Set(rows.map(r => r[fkField]).filter(Boolean))];
        if (ids.length === 0) {
          for (const row of rows) row[key] = null;
          continue;
        }
        const childRows = await this._query(
          `SELECT * FROM ${qi(rel.table)} WHERE ${qi(refField)} = ANY($1::text[])`,
          [ids]
        );
        const map = {};
        for (const c of childRows) map[c[refField]] = c;

        // If spec.select limits which columns we return, filter them
        if (spec.select) {
          const cols = Object.keys(spec.select);
          for (const row of rows) {
            const full = map[row[fkField]] || null;
            if (full) {
              const trimmed = {};
              for (const col of cols) trimmed[col] = full[col];
              row[key] = trimmed;
            } else {
              row[key] = null;
            }
          }
        } else {
          for (const row of rows) row[key] = map[row[fkField]] || null;
        }
      }
    }

    // Handle _count includes
    if (include._count) {
      for (const [childKey] of Object.entries(include._count.select || {})) {
        const childRel = RELATIONS[childKey] || { table: childKey, fk: this.table.toLowerCase() + 'Id' };
        for (const row of rows) {
          const childRows = await this._query(
            `SELECT COUNT(*)::int as cnt FROM ${qi(childRel.table)} WHERE ${qi(childRel.fk)} = $1`,
            [row[idField]]
          );
          if (!row._count) row._count = {};
          row._count[childKey] = childRows[0]?.cnt || 0;
        }
      }
    }
  }

  // ── CRUD ────────────────────────────────────────────────────────
  async findMany({ where, include, orderBy, skip, take, select: selectFields } = {}) {
    const values = [];
    const selectClause = selectFields
      ? (Array.isArray(selectFields) ? selectFields : selectFields.split(',').map(s => s.trim())).map(qi).join(', ')
      : '*';

    let sql = `SELECT ${selectClause} FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    sql += buildOrderBy(orderBy);

    if (skip !== undefined && take !== undefined) {
      sql += ` OFFSET ${skip} LIMIT ${take}`;
    } else if (take !== undefined) {
      sql += ` LIMIT ${take}`;
    }

    const rows = await this._query(sql, values);

    if (include) await this._loadRelations(rows, include);
    return rows;
  }

  async findUnique({ where, include, select: selectFields } = {}) {
    const values = [];
    const selectClause = selectFields
      ? (Array.isArray(selectFields) ? selectFields : selectFields.split(',').map(s => s.trim())).map(qi).join(', ')
      : '*';

    let sql = `SELECT ${selectClause} FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    sql += ' LIMIT 1';

    const row = await this._queryOne(sql, values);
    if (row && include) await this._loadRelations([row], include);
    return row;
  }

  async findFirst({ where, include, orderBy, select: selectFields } = {}) {
    const values = [];
    const selectClause = selectFields
      ? (Array.isArray(selectFields) ? selectFields : selectFields.split(',').map(s => s.trim())).map(qi).join(', ')
      : '*';

    let sql = `SELECT ${selectClause} FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    sql += buildOrderBy(orderBy);
    sql += ' LIMIT 1';

    const row = await this._queryOne(sql, values);
    if (row && include) await this._loadRelations([row], include);
    return row;
  }

  async create({ data, include, select: selectFields }) {
    // Extract nested data
    const flat = {}, nested = [];
    for (const [key, value] of Object.entries(data)) {
      if (value && typeof value === 'object' && !Array.isArray(value) && value.create) {
        nested.push({ key, records: Array.isArray(value.create) ? value.create : [value.create] });
      } else if (key === 'streams' && Array.isArray(value)) {
        nested.push({ key, records: value });
      } else {
        flat[key] = value;
      }
    }

    const columns = Object.keys(flat);
    const values = Object.values(flat).map(v => v instanceof Date ? v.toISOString() : v);
    const placeholders = values.map((_, i) => '$' + (i + 1));

    const selectClause = selectFields
      ? (Array.isArray(selectFields) ? selectFields : selectFields.split(',').map(s => s.trim())).map(qi).join(', ')
      : '*';

    const sql = `INSERT INTO ${qi(this.table)} (${columns.map(qi).join(', ')}) VALUES (${placeholders.join(', ')}) RETURNING ${selectClause}`;

    const rows = await this._query(sql, values);
    const record = rows[0];

    // Handle nested creates
    if (record && nested.length > 0) {
      for (const n of nested) {
        const rel = RELATIONS[n.key] || { table: n.key, fk: this.table.toLowerCase() + 'Id' };
        for (const childData of n.records) {
          const childCols = Object.keys(childData);
          const childVals = Object.values(childData);
          childCols.push(rel.fk);
          childVals.push(record.id);
          const childPh = childVals.map((_, i) => '$' + (i + 1));
          await this._query(
            `INSERT INTO ${qi(rel.table)} (${childCols.map(qi).join(', ')}) VALUES (${childPh.join(', ')})`,
            childVals
          );
        }
      }
      // Re-fetch with includes
      if (include) {
        return this.findUnique({ where: { id: record.id }, include });
      }
    }

    if (record && include) {
      return this.findUnique({ where: { id: record.id }, include });
    }
    return record;
  }

  async update({ where, data, include, select: selectFields }) {
    const setValues = [];
    const setClauses = [];
    const whereValues = [];

    for (const [key, value] of Object.entries(data)) {
      setClauses.push(`${qi(key)}=$${setValues.length + 1}`);
      setValues.push(value instanceof Date ? value.toISOString() : value);
    }
    if (setClauses.length === 0) {
      // Nothing to update — just return existing
      return this.findUnique({ where, include });
    }

    let idxOffset = setValues.length;
    let whereClause = '';
    if (where) {
      whereClause = buildWhere(where, whereValues, idxOffset + 1);
    }

    const allValues = [...setValues, ...whereValues];
    const selectClause = selectFields
      ? (Array.isArray(selectFields) ? selectFields : selectFields.split(',').map(s => s.trim())).map(qi).join(', ')
      : '*';

    const sql = `UPDATE ${qi(this.table)} SET ${setClauses.join(', ')}${whereClause} RETURNING ${selectClause}`;
    const rows = await this._query(sql, allValues);
    const record = rows[0] || null;
    if (record && include) await this._loadRelations([record], include);
    return record;
  }

  async delete({ where } = {}) {
    const values = [];
    let sql = `DELETE FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    await this._query(sql, values);
  }

  async upsert({ where: _where, update, create }) {
    // For DeviceToken: upsert on token column
    const data = update || create || {};
    const onConflictCol = _where ? Object.keys(_where)[0] : 'token';

    const columns = Object.keys(data);
    const values = Object.values(data);
    const ph = values.map((_, i) => '$' + (i + 1));

    const setClauses = columns.map(c => `${qi(c)} = EXCLUDED.${qi(c)}`).join(', ');

    const sql = `INSERT INTO ${qi(this.table)} (${columns.map(qi).join(', ')}) VALUES (${ph.join(', ')}) ON CONFLICT (${qi(onConflictCol)}) DO UPDATE SET ${setClauses} RETURNING *`;

    const rows = await this._query(sql, values);
    return rows[0] || null;
  }

  async count({ where } = {}) {
    const values = [];
    let sql = `SELECT COUNT(*)::int AS count FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    const rows = await this._query(sql, values);
    return rows[0]?.count || 0;
  }

  async deleteMany({ where } = {}) {
    const values = [];
    let sql = `DELETE FROM ${qi(this.table)}`;
    sql += buildWhere(where, values, 1);
    await this._query(sql, values);
  }

  async createMany({ data }) {
    if (!data || data.length === 0) return;
    const columns = Object.keys(data[0]);
    let idx = 1;
    const valueRows = [];
    const allValues = [];
    for (const row of data) {
      const ph = columns.map(() => '$' + (idx++));
      valueRows.push('(' + ph.join(', ') + ')');
      for (const col of columns) allValues.push(row[col]);
    }
    const sql = `INSERT INTO ${qi(this.table)} (${columns.map(qi).join(', ')}) VALUES ${valueRows.join(', ')}`;
    await this._query(sql, allValues);
  }
}

// ── Table map (same shape Prisma expects) ─────────────────────────
const TABLE_MAP = {
  event:           'Event',
  channel:         'Channel',
  category:        'Category',
  highlight:       'Highlight',
  banner:          'Banner',
  appConfig:       'AppConfig',
  notification:    'Notification',
  deviceToken:     'DeviceToken',
  analyticsEvent:  'AnalyticsEvent',
  admin:           'Admin',
  streamLink:      'StreamLink',
};

class PgPrisma {
  constructor() {
    for (const [prismaName, tableName] of Object.entries(TABLE_MAP)) {
      const model = new PgModel(tableName);
      model.table = tableName; // keep for debugging
      this[prismaName] = model;
    }
  }

  async $transaction(fn) {
    return fn(this);
  }
}

let pgPrisma = null;

function getPrisma() {
  if (pgPrisma) return pgPrisma;
  pgPrisma = new PgPrisma();
  console.log('[pg-prisma] Models ready:', Object.keys(TABLE_MAP).length, 'tables');
  return pgPrisma;
}

module.exports = { getPrisma };
