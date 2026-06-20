/**
 * Phase 8 · Step 8.3 — Highlights controller.
 */
const { getPrisma } = require('../config/database');

const highlightsController = {
  /** GET /api/highlights — Public list */
  async list(req, res) {
    try {
      const { page = '1', limit = '50' } = req.query;
      const prisma = getPrisma();

      const skip = (Math.max(1, parseInt(page, 10)) - 1) * Math.max(1, Math.min(100, parseInt(limit, 10)));
      const take = Math.max(1, Math.min(100, parseInt(limit, 10)));

      const [highlights, total] = await Promise.all([
        prisma.highlight.findMany({ orderBy: { date: 'desc' }, skip, take }),
        prisma.highlight.count()
      ]);

      return res.json({
        highlights,
        pagination: { page: Math.max(1, parseInt(page, 10)), limit: take, total, totalPages: Math.ceil(total / take) }
      });
    } catch (err) {
      console.error('[highlights/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/highlights/:id */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const highlight = await prisma.highlight.findUnique({ where: { id: req.params.id } });
      if (!highlight) return res.status(404).json({ error: 'highlight not found' });
      return res.json({ highlight });
    } catch (err) {
      console.error('[highlights/getById]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/highlights (EDITOR+) */
  async create(req, res) {
    try {
      const { title, thumbnailUrl, videoUrl, date, duration, views } = req.body;
      if (!title || !thumbnailUrl || !videoUrl) {
        return res.status(400).json({ error: 'title, thumbnailUrl, and videoUrl are required' });
      }
      const prisma = getPrisma();
      const highlight = await prisma.highlight.create({
        data: {
          title,
          thumbnailUrl,
          videoUrl,
          date: date || Date.now(),
          duration: duration || 0,
          views: views || 0
        }
      });
      return res.status(201).json({ highlight });
    } catch (err) {
      console.error('[highlights/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/highlights/:id (EDITOR+) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.highlight.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'highlight not found' });
      const highlight = await prisma.highlight.update({ where: { id: req.params.id }, data: req.body });
      return res.json({ highlight });
    } catch (err) {
      console.error('[highlights/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/highlights/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.highlight.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'highlight not found' });
      await prisma.highlight.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[highlights/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = highlightsController;
