/**
 * Phase 8 · Step 8.3 — Banners controller.
 */
const { getPrisma } = require('../config/database');

const bannersController = {
  /** GET /api/banners — Public list, only active by default */
  async list(req, res) {
    try {
      const prisma = getPrisma();
      const { includeInactive } = req.query;
      const where = includeInactive === 'true' ? {} : { isActive: true };
      const banners = await prisma.banner.findMany({ where, orderBy: { sortOrder: 'asc' } });
      return res.json({ banners });
    } catch (err) {
      console.error('[banners/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/banners/:id */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const banner = await prisma.banner.findUnique({ where: { id: req.params.id } });
      if (!banner) return res.status(404).json({ error: 'banner not found' });
      return res.json({ banner });
    } catch (err) {
      console.error('[banners/getById]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/banners (EDITOR+) */
  async create(req, res) {
    try {
      const { title, imageUrl, linkUrl, isActive, sortOrder } = req.body;
      if (!title || !imageUrl) {
        return res.status(400).json({ error: 'title and imageUrl are required' });
      }
      const prisma = getPrisma();
      const banner = await prisma.banner.create({
        data: {
          title,
          imageUrl,
          linkUrl: linkUrl || null,
          isActive: isActive !== undefined ? isActive : true,
          sortOrder: sortOrder || 0
        }
      });
      return res.status(201).json({ banner });
    } catch (err) {
      console.error('[banners/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/banners/:id (EDITOR+) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.banner.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'banner not found' });
      const banner = await prisma.banner.update({ where: { id: req.params.id }, data: req.body });
      return res.json({ banner });
    } catch (err) {
      console.error('[banners/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/banners/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.banner.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'banner not found' });
      await prisma.banner.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[banners/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = bannersController;
