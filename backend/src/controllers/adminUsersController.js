/**
 * Phase 8 · Step 8.3 — Admin users controller (manage admin accounts).
 */
const { getPrisma } = require('../config/database');
const { hashPassword } = require('../middleware/auth');

const adminUsersController = {
  /** GET /api/admin/users — List all admins (SUPER_ADMIN only) */
  async list(req, res) {
    try {
      const prisma = getPrisma();
      const users = await prisma.admin.findMany({
        select: { id: true, email: true, name: true, role: true, avatarUrl: true, lastLoginAt: true, createdAt: true }
      });
      return res.json({ users });
    } catch (err) {
      console.error('[adminUsers/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/admin/users — Create admin (SUPER_ADMIN) */
  async create(req, res) {
    try {
      const { email, password, name, role } = req.body;
      if (!email || !password || !name) {
        return res.status(400).json({ error: 'email, password, and name are required' });
      }

      const prisma = getPrisma();

      const existing = await prisma.admin.findUnique({ where: { email } });
      if (existing) {
        return res.status(409).json({ error: 'email already in use' });
      }

      const passwordHash = await hashPassword(password);

      const user = await prisma.admin.create({
        data: { email, passwordHash, name, role: role || 'EDITOR' },
        select: { id: true, email: true, name: true, role: true, createdAt: true }
      });

      return res.status(201).json({ user });
    } catch (err) {
      console.error('[adminUsers/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/admin/users/:id — Update admin role/name (SUPER_ADMIN) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.admin.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'user not found' });

      // Cannot demote yourself
      if (req.user.sub === req.params.id && req.body.role && req.body.role !== existing.role) {
        return res.status(400).json({ error: 'cannot change your own role' });
      }

      const data = {};
      if (req.body.name) data.name = req.body.name;
      if (req.body.role) data.role = req.body.role;
      if (req.body.password) data.passwordHash = await hashPassword(req.body.password);
      else if (req.body.password === '') return res.status(400).json({ error: 'password cannot be empty' });

      const user = await prisma.admin.update({
        where: { id: req.params.id },
        data,
        select: { id: true, email: true, name: true, role: true, updatedAt: true }
      });

      return res.json({ user });
    } catch (err) {
      console.error('[adminUsers/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/admin/users/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      // Cannot delete yourself
      if (req.user.sub === req.params.id) {
        return res.status(400).json({ error: 'cannot delete your own account' });
      }

      const prisma = getPrisma();
      const existing = await prisma.admin.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'user not found' });

      await prisma.admin.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[adminUsers/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = adminUsersController;
