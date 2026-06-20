/**
 * Phase 8 · Step 8.3 — Admin users routes.
 */
const express = require('express');
const ctrl = require('../controllers/adminUsersController');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

const router = express.Router();

// All admin user management requires SUPER_ADMIN
router.use(authRequired, requireRole('SUPER_ADMIN'));

router.get('/', ctrl.list);
router.post('/', ctrl.create);
router.put('/:id', ctrl.update);
router.delete('/:id', ctrl.delete);

module.exports = router;
