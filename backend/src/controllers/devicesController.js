/**
 * Phase 8 · Step 8.18 — Device token controller.
 *
 * Allows the mobile app to register/unregister FCM device tokens.
 * Tokens are stored so admins can target specific devices or topics.
 */
const { getPrisma } = require('../config/database');
const fcm = require('../services/fcm');

const devicesController = {
  /**
   * POST /api/devices/register
   * Register or update a device FCM token.
   * Body: { token, platform?, topics? }
   */
  async register(req, res) {
    try {
      const { token, platform, topics } = req.body;

      if (!token || typeof token !== 'string' || token.length < 20) {
        return res.status(400).json({ error: 'valid FCM token is required' });
      }

      const prisma = getPrisma();

      // Upsert: create if new, update if token changed for same device
      const device = await prisma.deviceToken.upsert({
        where: { token },
        update: {
          platform: platform || null,
          lastSeenAt: new Date()
        },
        create: {
          token,
          platform: platform || null
        }
      });

      // Subscribe to topics if provided
      if (topics && Array.isArray(topics) && topics.length > 0) {
        for (const topic of topics) {
          if (typeof topic === 'string' && topic.length > 0) {
            await fcm.subscribeToTopic([token], topic);
          }
        }
      }

      return res.status(200).json({ device: { id: device.id, token: device.token.slice(0, 12) + '...', platform: device.platform } });
    } catch (err) {
      console.error('[devices/register]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * POST /api/devices/unregister
   * Remove a device token (e.g. on logout or app uninstall).
   * Body: { token }
   */
  async unregister(req, res) {
    try {
      const { token } = req.body;

      if (!token || typeof token !== 'string') {
        return res.status(400).json({ error: 'token is required' });
      }

      const prisma = getPrisma();

      await prisma.deviceToken.deleteMany({ where: { token } });

      return res.json({ ok: true });
    } catch (err) {
      console.error('[devices/unregister]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * GET /api/devices — List registered devices (SUPER_ADMIN only)
   */
  async list(req, res) {
    try {
      const prisma = getPrisma();
      const devices = await prisma.deviceToken.findMany({
        orderBy: { lastSeenAt: 'desc' },
        take: 200
      });

      return res.json({
        devices: devices.map(d => ({
          id: d.id,
          token: d.token.slice(0, 12) + '...',
          platform: d.platform,
          lastSeenAt: d.lastSeenAt,
          createdAt: d.createdAt
        })),
        total: devices.length
      });
    } catch (err) {
      console.error('[devices/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /**
   * GET /api/devices/count — Device count by platform (analytics)
   */
  async count(req, res) {
    try {
      const prisma = getPrisma();
      const total = await prisma.deviceToken.count();
      const android = await prisma.deviceToken.count({ where: { platform: 'android' } });
      const ios = await prisma.deviceToken.count({ where: { platform: 'ios' } });

      return res.json({ total, android, ios });
    } catch (err) {
      console.error('[devices/count]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = devicesController;
