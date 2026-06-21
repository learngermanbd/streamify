/**
 * Phase 8 · Step 8.20 — /api/playlists route.
 *
 * Full CRUD for user playlists. Auth model:
 *   GET /            — public
 *   GET /:id         — public
 *   POST /           — EDITOR+
 *   PUT /:id         — EDITOR+
 *   DELETE /:id      — SUPER_ADMIN
 */
const express = require('express');
const router = express.Router();
const ctrl = require('../controllers/playlistsController');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

// Public
router.get('/', ctrl.list);
router.get('/:id', ctrl.getById);

// Admin-only write operations
router.post('/', authRequired, requireRole('EDITOR'), ctrl.create);
router.put('/:id', authRequired, requireRole('EDITOR'), ctrl.update);
router.delete('/:id', authRequired, requireRole('SUPER_ADMIN'), ctrl.delete);

module.exports = router;
