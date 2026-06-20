/**
 * Phase 8 · Step 8.3 — Channels controller.
 */
const { getPrisma } = require('../config/database');

const channelsController = {
  /** GET /api/channels — Public list with optional filters */
  async list(req, res) {
    try {
      const { categoryId, isActive, page = '1', limit = '50' } = req.query;
      const prisma = getPrisma();

      const where = {};
      if (categoryId) where.categoryId = categoryId;
      if (isActive !== undefined) where.isActive = isActive === 'true';

      const skip = (Math.max(1, parseInt(page, 10)) - 1) * Math.max(1, Math.min(100, parseInt(limit, 10)));
      const take = Math.max(1, Math.min(100, parseInt(limit, 10)));

      const [channels, total] = await Promise.all([
        prisma.channel.findMany({
          where,
          include: { category: { select: { id: true, name: true } } },
          orderBy: { sortOrder: 'asc' },
          skip,
          take
        }),
        prisma.channel.count({ where })
      ]);

      return res.json({
        channels,
        pagination: { page: Math.max(1, parseInt(page, 10)), limit: take, total, totalPages: Math.ceil(total / take) }
      });
    } catch (err) {
      console.error('[channels/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/channels/:id */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const channel = await prisma.channel.findUnique({
        where: { id: req.params.id },
        include: { category: true }
      });
      if (!channel) return res.status(404).json({ error: 'channel not found' });
      return res.json({ channel });
    } catch (err) {
      console.error('[channels/getById]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/channels (EDITOR+) */
  async create(req, res) {
    try {
      const prisma = getPrisma();
      const { name, logoUrl, streamUrl, categoryId, isActive, sortOrder } = req.body;

      if (!name || !streamUrl || !categoryId) {
        return res.status(400).json({ error: 'name, streamUrl, and categoryId are required' });
      }

      // Verify category exists
      const category = await prisma.category.findUnique({ where: { id: categoryId } });
      if (!category) {
        return res.status(400).json({ error: 'category not found' });
      }

      const channel = await prisma.channel.create({
        data: {
          name,
          logoUrl: logoUrl || null,
          streamUrl,
          categoryId,
          isActive: isActive !== undefined ? isActive : true,
          sortOrder: sortOrder || 0
        },
        include: { category: { select: { id: true, name: true } } }
      });
      return res.status(201).json({ channel });
    } catch (err) {
      console.error('[channels/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/channels/:id (EDITOR+) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.channel.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'channel not found' });

      const channel = await prisma.channel.update({
        where: { id: req.params.id },
        data: req.body,
        include: { category: { select: { id: true, name: true } } }
      });
      return res.json({ channel });
    } catch (err) {
      console.error('[channels/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/channels/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.channel.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'channel not found' });
      await prisma.channel.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[channels/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = channelsController;
