/**
 * Phase 8 · Step 8.3 — SportStream admin backend entry point.
 *
 * Full REST API with JWT auth, RBAC, Zod validation, rate limiting,
 * and CRUD for all 10 Prisma models.
 *
 * Middleware order matters:
 *   helmet → cors → json → morgan → rateLimit → authOptional → routes → 404 → error handler
 */
const express = require('express');
const helmet = require('helmet');
const cors = require('cors');
const morgan = require('morgan');
require('dotenv').config();

const { authOptional, JWT_SECRET, JWT_REFRESH_SECRET } = require('./middleware/auth');
const { rateLimiter } = require('./middleware/rateLimit');

// Routes
const healthRoute = require('./routes/health');
const authRoute = require('./routes/auth');
const eventsRoute = require('./routes/events');
const channelsRoute = require('./routes/channels');
const highlightsRoute = require('./routes/highlights');
const categoriesRoute = require('./routes/categories');
const bannersRoute = require('./routes/banners');
const configRoute = require('./routes/config');
const notificationsRoute = require('./routes/notifications');
const analyticsRoute = require('./routes/analytics');
const adminUsersRoute = require('./routes/adminUsers');
const uploadRoute = require('./routes/upload');
const devicesRoute = require('./routes/devices');
const { startScheduler } = require('./services/scheduler');

// --- Production guard ---
// Refuse to boot in production if JWT_SECRET or JWT_REFRESH_SECRET are
// the dev defaults.
if (process.env.NODE_ENV === 'production') {
  if (JWT_SECRET === 'dev-not-secret-change-me') {
    throw new Error('JWT_SECRET must be set in production');
  }
  if (JWT_REFRESH_SECRET === (JWT_SECRET + '-refresh')) {
    console.warn('[streamify-backend] WARNING: JWT_REFRESH_SECRET is derived from JWT_SECRET. Set a separate JWT_REFRESH_SECRET in production.');
  }
}

const app = express();
const PORT = parseInt(process.env.PORT || '3000', 10);

// --- CORS origin ---
function resolveCorsOrigin() {
  const configured = [
    process.env.ADMIN_WEB_ORIGIN,
    process.env.ADMIN_ANDROID_ORIGIN
  ].filter(Boolean);
  if (configured.length > 0) return configured;
  if (process.env.NODE_ENV === 'development') return true;
  return false;
}

// --- Global middleware ---
app.use(helmet());
app.use(cors({
  origin: resolveCorsOrigin(),
  credentials: true
}));
app.use(express.json({ limit: '5mb' }));
app.use(morgan(process.env.NODE_ENV === 'production' ? 'combined' : 'dev'));
app.use(authOptional);

// --- Global rate limiter: 100 req / 15 min for all /api routes ---
app.use('/api', rateLimiter({ windowMs: 15 * 60 * 1000, max: 100 }));

// --- Auth rate limiter: 5 req / 15 min for login ---
app.use('/api/admin/auth/login', rateLimiter({
  windowMs: 15 * 60 * 1000,
  max: 5,
  message: 'Too many login attempts, please try again later.'
}));

// --- Routes (Phase 8.3: full REST API) ---

// Serve admin panel static files (Phase 8.4-8.9)
const path = require('path');
app.use('/admin', express.static(path.join(__dirname, '..', 'admin')));

app.use('/api/health', healthRoute);
app.use('/api/admin/auth', authRoute);
app.use('/api/admin/users', adminUsersRoute);
app.use('/api/events', eventsRoute);
app.use('/api/channels', channelsRoute);
app.use('/api/highlights', highlightsRoute);
app.use('/api/categories', categoriesRoute);
app.use('/api/banners', bannersRoute);
app.use('/api/config', configRoute);
app.use('/api/notifications', notificationsRoute);
app.use('/api/analytics', analyticsRoute);
app.use('/api', uploadRoute);
app.use('/api/devices', devicesRoute);

// --- Root sanity check ---
app.get('/', (_req, res) => {
  res.json({
    name: 'streamify-admin-backend',
    version: process.env.npm_package_version || '0.1.0',
    docs: '/api/health for liveness',
    phase: 'Phase 8.3 — backend REST API',
    endpoints: [
      'GET  /api/health',
      'POST /api/admin/auth/login',
      'POST /api/admin/auth/refresh',
      'GET  /api/admin/auth/me',
      'GET  /api/admin/users',
      'POST /api/admin/users',
      'GET  /api/events',
      'POST /api/events',
      'PUT  /api/events/:id',
      'DELETE /api/events/:id',
      'GET  /api/channels',
      'POST /api/channels',
      'PUT  /api/channels/:id',
      'DELETE /api/channels/:id',
      'GET  /api/highlights',
      'POST /api/highlights',
      'GET  /api/categories',
      'GET  /api/banners',
      'GET  /api/config',
      'PUT  /api/config',
      'GET  /api/notifications',
      'POST /api/notifications/send',
      'POST /api/devices/register',
      'POST /api/devices/unregister',
      'GET  /api/devices',
      'GET  /api/analytics/overview',
      'POST /api/analytics/events',
      'POST /api/upload'
    ]
  });
});

// --- 404 ---
app.use((req, res) => {
  res.status(404).json({ error: 'Not found', path: req.path, method: req.method });
});

// --- Error handler ---
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, _next) => {
  console.error('[error]', err);
  res.status(err.status || 500).json({
    error: err.message || 'Internal server error',
    code: err.code
  });
});

if (require.main === module) {
  if (process.env.NODE_ENV !== 'production') {
    if (!process.env.DB_HOST || !process.env.DB_USER || !process.env.DB_PASSWORD) {
      console.warn('[streamify-backend] DEV mode — set DB_HOST, DB_USER, DB_PASSWORD in .env (direct pg connection)');
    }
  }
  app.listen(PORT, () => {
    console.log(`[streamify-backend] listening on :${PORT}`);
    console.log(`[streamify-backend] health:  http://localhost:${PORT}/api/health`);
    console.log(`[streamify-backend] config: http://localhost:${PORT}/api/config`);

    // Step 8.18: Start notification scheduler
    startScheduler();
  });
}

module.exports = app;
