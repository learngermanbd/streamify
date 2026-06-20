/**
 * Phase 8 · Step 8.18 — Notification scheduler.
 *
 * Runs every 30 seconds via node-cron. Queries for SCHEDULED notifications
 * whose scheduledAt time has passed, sends them via FCM, and marks them SENT.
 */
const cron = require('node-cron');
const { getPrisma } = require('../config/database');
const fcm = require('./fcm');

let schedulerStarted = false;
let isProcessing = false; // overlap guard

/**
 * Start the notification scheduler.
 * Safe to call multiple times (idempotent).
 */
function startScheduler() {
  if (schedulerStarted) return;
  schedulerStarted = true;

  console.log('[scheduler] Starting notification scheduler (every 30s)');

  // Run every 30 seconds
  cron.schedule('*/30 * * * * *', async () => {
    await processScheduledNotifications();
  });
}

/**
 * Find and send all due scheduled notifications.
 */
async function processScheduledNotifications() {
  // Overlap guard: if a previous run is still processing, skip this tick.
  if (isProcessing) return;
  isProcessing = true;
  try {
    const prisma = getPrisma();
    const now = new Date();

    // Find all SCHEDULED notifications whose time has come
    const due = await prisma.notification.findMany({
      where: {
        status: 'SCHEDULED',
        scheduledAt: { lte: now }
      }
    });

    if (due.length === 0) return;

    console.log(`[scheduler] Processing ${due.length} due notification(s)`);

    for (const notif of due) {
      let result;

      if (notif.targetTopic) {
        result = await fcm.sendToTopic({
          title: notif.title,
          body: notif.body,
          topic: notif.targetTopic,
          deepLink: notif.deepLink
        });
      } else if (notif.targetToken) {
        result = await fcm.sendToToken({
          title: notif.title,
          body: notif.body,
          token: notif.targetToken,
          deepLink: notif.deepLink
        });
      } else {
        console.warn(`[scheduler] Notification ${notif.id} has no target — skipping`);
        await prisma.notification.update({
          where: { id: notif.id },
          data: { status: 'FAILED' }
        });
        continue;
      }

      if (result.success) {
        await prisma.notification.update({
          where: { id: notif.id },
          data: { status: 'SENT', sentAt: new Date() }
        });
        console.log(`[scheduler] Sent notification ${notif.id}: "${notif.title}"`);
      } else if (result.reason === 'fcm-not-initialized') {
        // Leave as SCHEDULED for retry once credentials are configured
        console.warn(`[scheduler] FCM not initialized — skipping notification ${notif.id}`);
      } else {
        console.error(`[scheduler] Failed notification ${notif.id}: ${result.reason || result.error}`);
        await prisma.notification.update({
          where: { id: notif.id },
          data: { status: 'FAILED' }
        });
      }
    }
  } catch (err) {
    console.error('[scheduler] Error processing scheduled notifications:', err);
  } finally {
    isProcessing = false;
  }
}

module.exports = { startScheduler, processScheduledNotifications };
