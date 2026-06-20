/**
 * Phase 8 · Step 8.18 — Device routes.
 */
const { Router } = require('express');
const ctrl = require('../controllers/devicesController');

const router = Router();

// Public: register/unregister tokens from the mobile app
router.post('/register', ctrl.register);
router.post('/unregister', ctrl.unregister);

// Admin-only: list and count devices
router.get('/', ctrl.list);
router.get('/count', ctrl.count);

module.exports = router;
