/**
 * Phase 8 · Step 8.19 — /api/live route.
 *
 * Returns the subset of events where isLive = true. Delegates to the
 * existing eventsController.list() with the isLive query parameter
 * pre-set, so pagination, sorting, and all other query-string options
 * remain available.
 *
 * Mounted in server.js as app.use('/api/live', liveRoute).
 */
const express = require('express');
const router = express.Router();
const ctrl = require('../controllers/eventsController');

// GET /api/live — same shape as GET /api/events?isLive=true
router.get('/', (req, res, next) => {
  req.query.isLive = 'true';
  ctrl.list(req, res, next);
});

module.exports = router;
