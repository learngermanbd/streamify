/**
 * Phase 8 · Step 8.3 — Categories controller.
 */
const { getPrisma } = require('../config/database');

const categoriesController = {
  /** GET /api/categories — Public list, only visible */
  async list(req, res) {
    try {
      const prisma = getPrisma();
      const { includeHidden } = req.query;
      const where = includeHidden === 'true' ? {} : { isVisible: true };

      const categories = await prisma.category.findMany({
        where,
        orderBy: { sortOrder: 'asc' },
        include: { _count: { select: { channels: true, events: true } } }
      });

      return res.json({ categories });
    } catch (err) {
      console.error('[categories/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/categories/:id */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const category = await prisma.category.findUnique({
        where: { id: req.params.id },
        include: { _count: { select: { channels: true, events: true } } }
      });
      if (!category) return res.status(404).json({ error: 'category not found' });
      return res.json({ category });
    } catch (err) {
      console.error('[categories/getById]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/categories (EDITOR+) */
  async create(req, res) {
    try {
      const { name, iconUrl, sortOrder, isVisible } = req.body;
      if (!name) {
        return res.status(400).json({ error: 'name is required' });
      }
      const prisma = getPrisma();
      const category = await prisma.category.create({
        data: {
          name,
          iconUrl: iconUrl || null,
          sortOrder: sortOrder || 0,
          isVisible: isVisible !== undefined ? isVisible : true
        }
      });
      return res.status(201).json({ category });
    } catch (err) {
      console.error('[categories/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/categories/:id (EDITOR+) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.category.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'category not found' });
      const category = await prisma.category.update({ where: { id: req.params.id }, data: req.body });
      return res.json({ category });
    } catch (err) {
      console.error('[categories/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/categories/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.category.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'category not found' });

      // Check for dependent channels/events
      const [chCount, evCount] = await Promise.all([
        prisma.channel.count({ where: { categoryId: req.params.id } }),
        prisma.event.count({ where: { categoryId: req.params.id } })
      ]);
      if (chCount > 0 || evCount > 0) {
        return res.status(400).json({
          error: 'category has dependent items',
          channels: chCount,
          events: evCount
        });
      }

      await prisma.category.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[categories/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = categoriesController;
