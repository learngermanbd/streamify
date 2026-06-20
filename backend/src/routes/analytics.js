/**
 * Phase 8 · Step 8.3 — Analytics routes.
 */
const express = require('express');
const ctrl = require('../controllers/analyticsController');
const { authRequired } = require('../middleware/auth');

const router = express.Router();

router.get('/overview', authRequired, ctrl.overview);
router.get('/events', authRequired, ctrl.events);

// Ingest from user app — no auth (device sends anonymous events)
router.post('/events', ctrl.ingest);

module.exports = router;
