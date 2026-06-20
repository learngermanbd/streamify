/**
 * Phase 8 · Step 8.3 — AppConfig controller (replaces Firebase Remote Config).
 *
 * GET  /api/config          — Public, returns the current app config
 * PUT  /api/config          — Admin update (SUPER_ADMIN)
 * POST /api/config          — Create initial config row (SUPER_ADMIN)
 */
const { getPrisma } = require('../config/database');

const configController = {
  /** GET /api/config — Public, no auth required. The user Android app polls this. */
  async get(req, res) {
    try {
      const prisma = getPrisma();
      // Always return the most recently updated config row
      const config = await prisma.appConfig.findFirst({
        orderBy: { updatedAt: 'desc' }
      });

      if (!config) {
        // No config row yet — return safe defaults
        return res.json({
          apiBaseUrl: process.env.API_BASE_URL || '',
          updateUrl: null,
          telegramLink: null,
          noticeText: null,
          maintenanceMode: false,
          minAppVersion: null,
          featureFlags: {}
        });
      }

      return res.json({
        apiBaseUrl: config.apiBaseUrl,
        updateUrl: config.updateUrl,
        telegramLink: config.telegramLink,
        noticeText: config.noticeText,
        maintenanceMode: config.maintenanceMode,
        minAppVersion: config.minAppVersion,
        featureFlags: config.featureFlags || {}
      });
    } catch (err) {
      console.error('[config/get]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** PUT /api/config — Upsert current config (SUPER_ADMIN) */
  async update(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.appConfig.findFirst({ orderBy: { updatedAt: 'desc' } });

      let config;
      if (existing) {
        config = await prisma.appConfig.update({
          where: { id: existing.id },
          data: req.body
        });
      } else {
        config = await prisma.appConfig.create({
          data: {
            apiBaseUrl: req.body.apiBaseUrl || '',
            updateUrl: req.body.updateUrl,
            telegramLink: req.body.telegramLink,
            noticeText: req.body.noticeText,
            maintenanceMode: req.body.maintenanceMode || false,
            minAppVersion: req.body.minAppVersion,
            featureFlags: req.body.featureFlags || {}
          }
        });
      }

      return res.json({ config });
    } catch (err) {
      console.error('[config/update]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = configController;
