/**
 * Phase 8 · Step 8.3 — Auth middleware (real JWT + bcrypt).
 *
 * Provides:
 *   - authOptional: decode JWT if present, never reject
 *   - authRequired: reject 401 if no valid token
 *   - signAccessToken / signRefreshToken / verifyRefreshToken
 *   - hashPassword / verifyPassword (bcrypt)
 */

const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');

const JWT_SECRET = process.env.JWT_SECRET || 'dev-not-secret-change-me';
const JWT_REFRESH_SECRET = process.env.JWT_REFRESH_SECRET || JWT_SECRET + '-refresh';
const JWT_ACCESS_EXP = process.env.JWT_ACCESS_EXP || '15m';
const JWT_REFRESH_EXP = process.env.JWT_REFRESH_EXP || '7d';
const BCRYPT_ROUNDS = 10;

// ---- Password hashing ----

async function hashPassword(plain) {
  return bcrypt.hash(plain, BCRYPT_ROUNDS);
}

async function verifyPassword(plain, hash) {
  return bcrypt.compare(plain, hash);
}

// ---- JWT sign / verify ----

function signAccessToken(payload) {
  return jwt.sign(payload, JWT_SECRET, { expiresIn: JWT_ACCESS_EXP });
}

function signRefreshToken(payload) {
  return jwt.sign(payload, JWT_REFRESH_SECRET, { expiresIn: JWT_REFRESH_EXP });
}

function verifyRefreshToken(token) {
  return jwt.verify(token, JWT_REFRESH_SECRET);
}

// ---- Express middleware ----

/** Best-effort JWT decode; never rejects (just leaves req.user null). */
function authOptional(req, _res, next) {
  const header = req.header('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (token) {
    try {
      req.user = jwt.verify(token, JWT_SECRET);
    } catch (_e) {
      req.user = null;
    }
  } else {
    req.user = null;
  }
  next();
}

/** Reject with 401 if no verified user. */
function authRequired(req, res, next) {
  if (!req.user) {
    return res.status(401).json({ error: 'authentication required' });
  }
  next();
}

module.exports = {
  authOptional,
  authRequired,
  signAccessToken,
  signRefreshToken,
  verifyRefreshToken,
  hashPassword,
  verifyPassword,
  JWT_SECRET,
  JWT_REFRESH_SECRET,
  JWT_ACCESS_EXP,
  JWT_REFRESH_EXP
};
