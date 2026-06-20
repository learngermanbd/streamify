/**
 * Phase 8 · Step 8.3 — Auth routes (real auth using Prisma + bcrypt).
 *
 * POST /api/admin/auth/login    — verify credentials, return tokens
 * POST /api/admin/auth/refresh  — rotate access token via refresh token
 * POST /api/admin/auth/logout   — client-side token discard
 * GET  /api/admin/auth/me       — return current user info
 */

const express = require('express');
const { z } = require('zod');
const {
  signAccessToken,
  signRefreshToken,
  verifyRefreshToken,
  hashPassword,
  verifyPassword,
  authRequired
} = require('../middleware/auth');
const { getPrisma } = require('../config/database');

const router = express.Router();

// ---- Validation schemas ----

const loginSchema = z.object({
  email: z.string().email('invalid email format'),
  password: z.string().min(1, 'password is required')
});

const refreshSchema = z.object({
  refreshToken: z.string().min(1, 'refreshToken is required')
});

// ---- POST /login ----

router.post('/login', async (req, res) => {
  try {
    const { email, password } = loginSchema.parse(req.body);

    const prisma = getPrisma();
    const admin = await prisma.admin.findUnique({ where: { email } });

    if (!admin) {
      return res.status(401).json({ error: 'invalid email or password' });
    }

    const valid = await verifyPassword(password, admin.passwordHash);
    if (!valid) {
      return res.status(401).json({ error: 'invalid email or password' });
    }

    // Update last login timestamp
    await prisma.admin.update({
      where: { id: admin.id },
      data: { lastLoginAt: new Date() }
    });

    const payload = {
      sub: admin.id,
      email: admin.email,
      role: admin.role,
      name: admin.name
    };

    const accessToken = signAccessToken(payload);
    const refreshToken = signRefreshToken({ sub: admin.id });

    return res.json({
      accessToken,
      refreshToken,
      user: {
        id: admin.id,
        email: admin.email,
        name: admin.name,
        role: admin.role,
        avatarUrl: admin.avatarUrl
      },
      expiresIn: process.env.JWT_ACCESS_EXP || '15m'
    });
  } catch (err) {
    if (err instanceof z.ZodError) {
      return res.status(400).json({
        error: 'validation failed',
        fields: err.errors.map(e => ({ path: e.path.join('.'), message: e.message }))
      });
    }
    console.error('[auth/login]', err);
    return res.status(500).json({ error: 'internal server error' });
  }
});

// ---- POST /refresh ----

router.post('/refresh', async (req, res) => {
  try {
    const { refreshToken } = refreshSchema.parse(req.body);

    let decoded;
    try {
      decoded = verifyRefreshToken(refreshToken);
    } catch (_e) {
      return res.status(401).json({ error: 'invalid or expired refresh token' });
    }

    const prisma = getPrisma();
    const admin = await prisma.admin.findUnique({ where: { id: decoded.sub } });

    if (!admin) {
      return res.status(401).json({ error: 'user not found' });
    }

    const payload = {
      sub: admin.id,
      email: admin.email,
      role: admin.role,
      name: admin.name
    };

    const newAccessToken = signAccessToken(payload);
    const newRefreshToken = signRefreshToken({ sub: admin.id });

    return res.json({
      accessToken: newAccessToken,
      refreshToken: newRefreshToken,
      user: {
        id: admin.id,
        email: admin.email,
        name: admin.name,
        role: admin.role,
        avatarUrl: admin.avatarUrl
      },
      expiresIn: process.env.JWT_ACCESS_EXP || '15m'
    });
  } catch (err) {
    if (err instanceof z.ZodError) {
      return res.status(400).json({
        error: 'validation failed',
        fields: err.errors.map(e => ({ path: e.path.join('.'), message: e.message }))
      });
    }
    console.error('[auth/refresh]', err);
    return res.status(500).json({ error: 'internal server error' });
  }
});

// ---- POST /logout ----

router.post('/logout', (_req, res) => {
  // Stateless JWT — client discards tokens.
  // In production, add refresh token to a blocklist in Redis.
  res.json({ ok: true });
});

// ---- GET /me ----

router.get('/me', authRequired, async (req, res) => {
  try {
    const prisma = getPrisma();
    const admin = await prisma.admin.findUnique({
      where: { id: req.user.sub },
      select: { id: true, email: true, name: true, role: true, avatarUrl: true, lastLoginAt: true }
    });

    if (!admin) {
      return res.status(404).json({ error: 'user not found' });
    }

    return res.json({ user: admin });
  } catch (err) {
    console.error('[auth/me]', err);
    return res.status(500).json({ error: 'internal server error' });
  }
});

module.exports = router;
