/**
 * Phase 8 · Step 8.3 — Notifications routes.
 */
const express = require('express');
const ctrl = require('../controllers/notificationsController');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

const router = express.Router();

router.get('/', authRequired, ctrl.list);
router.post('/send', authRequired, requireRole('EDITOR'), ctrl.send);
router.post('/:id/cancel', authRequired, requireRole('EDITOR'), ctrl.cancel);
router.delete('/:id', authRequired, requireRole('SUPER_ADMIN'), ctrl.delete);

module.exports = router;
