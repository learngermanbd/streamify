/**
 * Phase 8 · Step 8.3 — RBAC middleware.
 *
 * Role hierarchy: SUPER_ADMIN > EDITOR > VIEWER
 *   - VIEWER  = read-only access
 *   - EDITOR  = CRUD content (events, channels, highlights, etc.)
 *   - SUPER_ADMIN = manage admin users + all else
 */

const ROLE_WEIGHT = {
  SUPER_ADMIN: 3,
  EDITOR: 2,
  VIEWER: 1
};

/**
 * Returns Express middleware that rejects requests where req.user.role
 * is below the minimum required role.
 *
 * @param {string} minRole - one of SUPER_ADMIN | EDITOR | VIEWER
 */
function requireRole(minRole) {
  return (req, res, next) => {
    if (!req.user) {
      return res.status(401).json({ error: 'authentication required' });
    }

    const userWeight = ROLE_WEIGHT[req.user.role] || 0;
    const requiredWeight = ROLE_WEIGHT[minRole] || 3;

    if (userWeight < requiredWeight) {
      return res.status(403).json({
        error: 'insufficient permissions',
        required: minRole,
        current: req.user.role
      });
    }

    next();
  };
}

module.exports = { requireRole, ROLE_WEIGHT };
