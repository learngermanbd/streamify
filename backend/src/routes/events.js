/**
 * Phase 8 · Step 8.3 — Events routes.
 */
const express = require('express');
const ctrl = require('../controllers/eventsController');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

const router = express.Router();

// Public
router.get('/', ctrl.list);
router.get('/:id', ctrl.getById);

// Admin only
router.post('/', authRequired, requireRole('EDITOR'), ctrl.create);
router.put('/:id', authRequired, requireRole('EDITOR'), ctrl.update);
router.delete('/:id', authRequired, requireRole('SUPER_ADMIN'), ctrl.delete);

module.exports = router;
