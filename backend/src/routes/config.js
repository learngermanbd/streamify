/**
 * Phase 8 · Step 8.3 — Config routes (replaces Firebase Remote Config).
 */
const express = require('express');
const ctrl = require('../controllers/configController');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

const router = express.Router();

// Public — the user Android app reads this on launch
router.get('/', ctrl.get);

// Admin only
router.put('/', authRequired, requireRole('SUPER_ADMIN'), ctrl.update);

module.exports = router;
