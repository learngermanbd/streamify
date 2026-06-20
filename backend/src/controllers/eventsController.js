/**
 * Phase 8 · Step 8.3 — Events controller.
 *
 * Full CRUD for sports events. Public read (no auth), admin write (auth + RBAC).
 */
const { getPrisma } = require('../config/database');

const eventsController = {
  /** GET /api/events — Public list with optional filters */
  async list(req, res) {
    try {
      const {
        status,
        categoryId,
        isLive,
        page = '1',
        limit = '50',
        sort = 'scheduledFor',
        order = 'asc'
      } = req.query;

      const prisma = getPrisma();
      const where = {};

      if (status) where.status = status.toUpperCase();
      if (categoryId) where.categoryId = categoryId;
      if (isLive !== undefined) where.isLive = isLive === 'true';

      const skip = (Math.max(1, parseInt(page, 10)) - 1) * Math.max(1, Math.min(100, parseInt(limit, 10)));
      const take = Math.max(1, Math.min(100, parseInt(limit, 10)));

      const [events, total] = await Promise.all([
        prisma.event.findMany({
          where,
          include: { streams: true, category: { select: { id: true, name: true } } },
          orderBy: { [sort === 'title' ? 'title' : sort === 'createdAt' ? 'createdAt' : 'scheduledFor']: order === 'desc' ? 'desc' : 'asc' },
          skip,
          take
        }),
        prisma.event.count({ where })
      ]);

      return res.json({
        events,
        pagination: {
          page: Math.max(1, parseInt(page, 10)),
          limit: take,
          total,
          totalPages: Math.ceil(total / take)
        }
      });
    } catch (err) {
      console.error('[events/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/events/:id — Single event with streams */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const event = await prisma.event.findUnique({
        where: { id: req.params.id },
        include: { streams: true, category: true }
      });

      if (!event) {
        return res.status(404).json({ error: 'event not found' });
      }

      return res.json({ event });
    } catch (err) {
      console.error('[events/getById]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/events — Create new event (EDITOR+) */
  async create(req, res) {
    try {
      const prisma = getPrisma();
      const { streams, categoryId, ...eventData } = req.body;

      // Verify category exists
      if (categoryId) {
        const category = await prisma.category.findUnique({ where: { id: categoryId } });
        if (!category) {
          return res.status(400).json({ error: 'category not found' });
        }
      }

      const event = await prisma.event.create({
        data: {
          ...eventData,
          categoryId,
          streams: streams && streams.length > 0
            ? { create: streams.map(s => ({ name: s.name, url: s.url, quality: s.quality || 'AUTO', sortOrder: s.sortOrder || 0 })) }
            : undefined
        },
        include: { streams: true, category: { select: { id: true, name: true } } }
      });

      return res.status(201).json({ event });
    } catch (err) {
      console.error('[events/create]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/events/:id — Update event (EDITOR+) */
  async update(req, res) {
    try {
      const prisma = getPrisma();

      const existing = await prisma.event.findUnique({ where: { id: req.params.id } });
      if (!existing) {
        return res.status(404).json({ error: 'event not found' });
      }

      const { streams, ...eventData } = req.body;

      // If streams provided, replace all stream links (delete old, create new)
      if (streams !== undefined) {
        await prisma.streamLink.deleteMany({ where: { eventId: req.params.id } });
        if (streams.length > 0) {
          await prisma.streamLink.createMany({
            data: streams.map(s => ({
              eventId: req.params.id,
              name: s.name,
              url: s.url,
              quality: s.quality || 'AUTO',
              sortOrder: s.sortOrder || 0
            }))
          });
        }
      }

      const event = await prisma.event.update({
        where: { id: req.params.id },
        data: eventData,
        include: { streams: true, category: { select: { id: true, name: true } } }
      });

      return res.json({ event });
    } catch (err) {
      console.error('[events/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/events/:id — Delete event (SUPER_ADMIN only) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();

      const existing = await prisma.event.findUnique({ where: { id: req.params.id } });
      if (!existing) {
        return res.status(404).json({ error: 'event not found' });
      }

      // StreamLinks cascade-delete via onDelete: Cascade in schema
      await prisma.event.delete({ where: { id: req.params.id } });

      return res.json({ ok: true });
    } catch (err) {
      console.error('[events/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = eventsController;
