/**
 * Phase 8 · Step 8.3 — Analytics controller.
 */
const { getPrisma } = require('../config/database');

const analyticsController = {
  /** GET /api/analytics/overview — Dashboard summary stats */
  async overview(req, res) {
    try {
      const prisma = getPrisma();
      const [totalEvents, liveEvents, totalChannels, totalHighlights, totalUsers, recentAnalytics] = await Promise.all([
        prisma.event.count(),
        prisma.event.count({ where: { isLive: true } }),
        prisma.channel.count(),
        prisma.highlight.count(),
        prisma.admin.count(),
        prisma.analyticsEvent.count()
      ]);

      return res.json({
        overview: {
          totalEvents,
          liveEvents,
          totalChannels,
          totalHighlights,
          totalUsers,
          totalAnalyticsEvents: recentAnalytics
        }
      });
    } catch (err) {
      console.error('[analytics/overview]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** GET /api/analytics/events — App event analytics with date filtering */
  async events(req, res) {
    try {
      const { eventType, from, to, limit = '100' } = req.query;
      const prisma = getPrisma();

      const where = {};
      if (eventType) where.eventType = eventType;
      if (from || to) {
        where.occurredAt = {};
        if (from) where.occurredAt.gte = new Date(from);
        if (to) where.occurredAt.lte = new Date(to);
      }

      const events = await prisma.analyticsEvent.findMany({
        where,
        orderBy: { occurredAt: 'desc' },
        take: Math.max(1, Math.min(500, parseInt(limit, 10)))
      });

      return res.json({ events });
    } catch (err) {
      console.error('[analytics/events]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/analytics/events — Ingest an analytics event (from user app) */
  async ingest(req, res) {
    try {
      const { eventType, eventName, properties, deviceId, platform, appVersion, country } = req.body;
      if (!eventType || !eventName) {
        return res.status(400).json({ error: 'eventType and eventName are required' });
      }

      const prisma = getPrisma();
      await prisma.analyticsEvent.create({
        data: {
          eventType,
          eventName,
          properties: properties || {},
          deviceId: deviceId || null,
          platform: platform || null,
          appVersion: appVersion || null,
          country: country || null
        }
      });

      return res.status(201).json({ ok: true });
    } catch (err) {
      console.error('[analytics/ingest]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = analyticsController;
