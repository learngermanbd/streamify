/**
 * Phase 8 · Step 8.20 — Playlists controller.
 *
 * Full CRUD for user playlists. Mirrors the eventsController pattern:
 *   - list / getById: public (any client)
 *   - create / update / delete: admin-only (EDITOR+)
 *
 * Playlist items are self-contained StreamLink clones (name, url, quality)
 * matching the Android data.models.Playlist shape exactly.
 */
const { getPrisma } = require('../config/database');

const playlistsController = {
  /**
   * GET /api/playlists[?ownerId=X]
   * Returns all playlists (optionally filtered by ownerId), each with items.
   */
  async list(req, res) {
    try {
      const prisma = getPrisma();
      const { ownerId, skip, take, sort } = req.query;

      const where = {};
      if (ownerId) where.ownerId = ownerId;

      const orderBy = sort === 'oldest'
        ? [{ createdAt: 'asc' }]
        : [{ createdAt: 'desc' }];

      const playlists = await prisma.playlist.findMany({
        where,
        include: { items: true },
        orderBy,
        skip: skip ? parseInt(skip, 10) : undefined,
        take: take ? parseInt(take, 10) : undefined,
      });

      const totalCount = await prisma.playlist.count({ where });

      res.json(playlists.map(formatPlaylist));
    } catch (err) {
      console.error('[playlists] list error:', err.message);
      res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * GET /api/playlists/:id
   */
  async getById(req, res) {
    try {
      const prisma = getPrisma();
      const playlist = await prisma.playlist.findUnique({
        where: { id: req.params.id },
        include: { items: true },
      });
      if (!playlist) {
        return res.status(404).json({ error: 'playlist not found' });
      }
      res.json({ playlist: formatPlaylist(playlist) });
    } catch (err) {
      console.error('[playlists] getById error:', err.message);
      res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * POST /api/playlists
   * Body: { name, ownerId, items: [{ name, url, quality?, sortOrder? }] }
   */
  async create(req, res) {
    try {
      const prisma = getPrisma();
      const { name, ownerId, items } = req.body;

      if (!name || !ownerId) {
        return res.status(400).json({ error: 'name and ownerId are required' });
      }

      // Build nested-create payload matching PgPrisma create() nested logic.
      // When items is an array, PgPrisma reads it as a nested create.
      const data = {
        name,
        ownerId,
        ...(items && items.length > 0
          ? {
              items: {
                create: items.map((s) => ({
                  name: s.name,
                  url: s.url,
                  quality: s.quality || 'AUTO',
                  sortOrder: s.sortOrder || 0,
                })),
              },
            }
          : {}),
      };

      const playlist = await prisma.playlist.create({
        data,
        include: { items: true },
      });

      res.status(201).json({ playlist: formatPlaylist(playlist) });
    } catch (err) {
      console.error('[playlists] create error:', err.message);
      res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * PUT /api/playlists/:id
   * Body: { name?, items?: [{ name, url, quality?, sortOrder? }] }
   *
   * Item replacement strategy: delete all existing items for this playlist,
   * then create new ones from the payload.  Same pattern as eventsController
   * update() → stream replacement.
   */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const { id } = req.params;
      const { name, items } = req.body;

      const existing = await prisma.playlist.findUnique({ where: { id } });
      if (!existing) {
        return res.status(404).json({ error: 'playlist not found' });
      }

      // Replace items: delete old, insert new
      if (items !== undefined) {
        await prisma.playlistItem.deleteMany({ where: { playlistId: id } });

        if (items.length > 0) {
          await prisma.playlistItem.createMany({
            data: items.map((s) => ({
              playlistId: id,
              name: s.name,
              url: s.url,
              quality: s.quality || 'AUTO',
              sortOrder: s.sortOrder || 0,
            })),
          });
        }
      }

      // Update playlist name
      if (name !== undefined) {
        await prisma.playlist.update({ where: { id }, data: { name } });
      }

      const updated = await prisma.playlist.findUnique({
        where: { id },
        include: { items: true },
      });

      res.json({ playlist: formatPlaylist(updated) });
    } catch (err) {
      console.error('[playlists] update error:', err.message);
      res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * DELETE /api/playlists/:id
   * PlaylistItems cascade-delete via ON DELETE CASCADE in the DB schema.
   */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const { id } = req.params;

      const existing = await prisma.playlist.findUnique({ where: { id } });
      if (!existing) {
        return res.status(404).json({ error: 'playlist not found' });
      }

      await prisma.playlist.delete({ where: { id } });
      res.json({ success: true });
    } catch (err) {
      console.error('[playlists] delete error:', err.message);
      res.status(500).json({ error: 'internal server error' });
    }
  },
};

/**
 * Format a playlist row for the JSON response.
 * The PgPrisma returns raw DB rows; the items relation is already loaded
 * as an array.  We normalise camelCase field names for the client.
 */
function formatPlaylist(p) {
  return {
    id: p.id,
    name: p.name,
    ownerId: p.ownerId,
    items: (p.items || [])
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((item) => ({
      name: item.name,
      url: item.url,
      quality: item.quality || 'AUTO',
    })),
    createdAt: p.createdAt,
    updatedAt: p.updatedAt,
  };
}

module.exports = playlistsController;
