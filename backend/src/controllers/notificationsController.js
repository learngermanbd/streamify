/**
 * Phase 8 · Step 8.3 + 8.18 — Notifications controller.
 *
 * Step 8.18 wires real Firebase Cloud Messaging (FCM) sending
 * via the firebase-admin SDK. Immediate sends go out inline;
 * scheduled notifications are processed by src/services/scheduler.js.
 */
const { getPrisma } = require('../config/database');
const fcm = require('../services/fcm');

const notificationsController = {
  /** GET /api/notifications — List notifications with optional filters */
  async list(req, res) {
    try {
      const { status, page = '1', limit = '50' } = req.query;
      const prisma = getPrisma();

      const where = {};
      if (status) where.status = status.toUpperCase();

      const skip = (Math.max(1, parseInt(page, 10)) - 1) * Math.max(1, Math.min(100, parseInt(limit, 10)));
      const take = Math.max(1, Math.min(100, parseInt(limit, 10)));

      const [notifications, total] = await Promise.all([
        prisma.notification.findMany({
          where,
          include: { sentBy: { select: { id: true, name: true } } },
          orderBy: { createdAt: 'desc' },
          skip,
          take
        }),
        prisma.notification.count({ where })
      ]);

      return res.json({
        notifications,
        pagination: { page: Math.max(1, parseInt(page, 10)), limit: take, total, totalPages: Math.ceil(total / take) }
      });
    } catch (err) {
      console.error('[notifications/list]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/notifications/send — Send push notification (EDITOR+) */
  async send(req, res) {
    try {
      const { title, body, targetTopic, targetToken, deepLink, scheduledAt } = req.body;

      if (!title || !body) {
        return res.status(400).json({ error: 'title and body are required' });
      }

      if (!targetTopic && !targetToken) {
        return res.status(400).json({ error: 'targetTopic or targetToken is required' });
      }

      const prisma = getPrisma();

      const notification = await prisma.notification.create({
        data: {
          title,
          body,
          targetTopic: targetTopic || null,
          targetToken: targetToken || null,
          deepLink: deepLink || null,
          scheduledAt: scheduledAt ? new Date(scheduledAt) : null,
          status: scheduledAt ? 'SCHEDULED' : 'PENDING',
          sentById: req.user.sub
        }
      });

      // If immediate (no schedule), send now via FCM
      if (!scheduledAt) {
        let result;
        if (targetTopic) {
          result = await fcm.sendToTopic({ title, body, topic: targetTopic, deepLink });
        } else if (targetToken) {
          result = await fcm.sendToToken({ title, body, token: targetToken, deepLink });
        }

        if (result && result.success) {
          await prisma.notification.update({
            where: { id: notification.id },
            data: { status: 'SENT', sentAt: new Date() }
          });
          notification.status = 'SENT';
          notification.sentAt = new Date();
        } else {
          await prisma.notification.update({
            where: { id: notification.id },
            data: { status: 'FAILED' }
          });
          notification.status = 'FAILED';
        }
      } else {
        console.log(`[notifications] Scheduled FCM to ${targetTopic || targetToken} for ${scheduledAt}: "${title}"`);
      }

      return res.status(201).json({ notification });
    } catch (err) {
      console.error('[notifications/send]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** DELETE /api/notifications/:id (SUPER_ADMIN) */
  async delete(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.notification.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'notification not found' });
      await prisma.notification.delete({ where: { id: req.params.id } });
      return res.json({ ok: true });
    } catch (err) {
      console.error('[notifications/delete]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  },

  /** POST /api/notifications/:id/cancel — Cancel a scheduled notification */
  async cancel(req, res) {
    try {
      const prisma = getPrisma();
      const existing = await prisma.notification.findUnique({ where: { id: req.params.id } });
      if (!existing) return res.status(404).json({ error: 'notification not found' });
      if (existing.status !== 'SCHEDULED') {
        return res.status(400).json({ error: 'only scheduled notifications can be cancelled' });
      }
      const notification = await prisma.notification.update({
        where: { id: req.params.id },
        data: { status: 'CANCELLED' }
      });
      return res.json({ notification });
    } catch (err) {
      console.error('[notifications/cancel]', err);
      return res.status(500).json({ error: 'internal server error' });
    }
  }
};

module.exports = notificationsController;
