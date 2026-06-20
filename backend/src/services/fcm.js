/**
 * Phase 8 · Step 8.18 — FCM (Firebase Cloud Messaging) service.
 *
 * Sends push notifications via Firebase Admin SDK. Supports:
 *  - Topic-based: send to all devices subscribed to a topic
 *  - Token-based: send to a single device token
 *  - Multicast: send to up to 500 device tokens at once
 *
 * Gracefully degrades when GOOGLE_APPLICATION_CREDENTIALS is not set
 * (logs a warning, returns { success: false } so the caller can handle).
 */
const admin = require('firebase-admin');

let fcmInitialized = false;

function initFCM() {
  if (fcmInitialized) return;

  // Check for explicit Firebase project config in env
  const projectId = process.env.FIREBASE_PROJECT_ID;
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL;
  const privateKey = process.env.FIREBASE_PRIVATE_KEY;

  if (projectId && clientEmail && privateKey) {
    admin.initializeApp({
      credential: admin.credential.cert({
        projectId,
        clientEmail,
        privateKey: privateKey.replace(/\\n/g, '\n')
      })
    });
    fcmInitialized = true;
    console.log('[fcm] Initialized with env credentials for project:', projectId);
  } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    // ADC (Application Default Credentials) — works on GCP / Firebase Cloud Functions
    admin.initializeApp();
    fcmInitialized = true;
    console.log('[fcm] Initialized with ADC');
  } else {
    console.warn(
      '[fcm] WARNING: No Firebase credentials found. Set FIREBASE_PROJECT_ID + FIREBASE_CLIENT_EMAIL + FIREBASE_PRIVATE_KEY, ' +
      'or GOOGLE_APPLICATION_CREDENTIALS. FCM will be disabled.'
    );
  }
}

/**
 * Send a push notification to a topic.
 * All devices subscribed to `topic` will receive it.
 */
async function sendToTopic({ title, body, topic, deepLink, imageUrl }) {
  initFCM();
  if (!fcmInitialized) return { success: false, reason: 'fcm-not-initialized' };

  const message = {
    notification: { title, body },
    android: {
      priority: 'high',
      notification: {
        channelId: 'streamify_events',
        clickAction: 'FLUTTER_NOTIFICATION_CLICK',
        ...(imageUrl && { imageUrl })
      }
    },
    apns: {
      payload: {
        aps: {
          sound: 'default',
          badge: 1,
          ...(imageUrl && { 'mutable-content': 1 })
        }
      },
      ...(imageUrl && { fcmOptions: { imageUrl } })
    },
    topic,
    data: deepLink ? { deepLink } : {}
  };

  try {
    const response = await admin.messaging().send(message);
    console.log(`[fcm] Sent to topic "${topic}": ${response}`);
    return { success: true, messageId: response };
  } catch (err) {
    console.error(`[fcm] Failed to send to topic "${topic}":`, err.message);
    return { success: false, reason: err.code || 'unknown', error: err.message };
  }
}

/**
 * Send a push notification to a single device token.
 */
async function sendToToken({ title, body, token, deepLink, imageUrl }) {
  initFCM();
  if (!fcmInitialized) return { success: false, reason: 'fcm-not-initialized' };

  const message = {
    notification: { title, body },
    android: {
      priority: 'high',
      notification: {
        channelId: 'streamify_events',
        clickAction: 'FLUTTER_NOTIFICATION_CLICK',
        ...(imageUrl && { imageUrl })
      }
    },
    apns: {
      payload: {
        aps: {
          sound: 'default',
          badge: 1,
          ...(imageUrl && { 'mutable-content': 1 })
        }
      },
      ...(imageUrl && { fcmOptions: { imageUrl } })
    },
    token,
    data: deepLink ? { deepLink } : {}
  };

  try {
    const response = await admin.messaging().send(message);
    console.log(`[fcm] Sent to token "${token.slice(0, 12)}...": ${response}`);
    return { success: true, messageId: response };
  } catch (err) {
    if (err.code === 'messaging/registration-token-not-registered') {
      console.warn(`[fcm] Token not registered: ${token.slice(0, 12)}...`);
      return { success: false, reason: 'token-not-registered', error: err.message };
    }
    console.error(`[fcm] Failed to send to token:`, err.message);
    return { success: false, reason: err.code || 'unknown', error: err.message };
  }
}

/**
 * Send a push notification to multiple device tokens (multicast, max 500).
 * Returns per-token results so the caller can clean up invalid tokens.
 */
async function sendMulticast({ title, body, tokens, deepLink, imageUrl }) {
  initFCM();
  if (!fcmInitialized) return { success: false, reason: 'fcm-not-initialized' };

  if (tokens.length === 0) return { success: true, successCount: 0, failureCount: 0, results: [] };
  if (tokens.length > 500) {
    console.warn(`[fcm] Multicast capped at 500; got ${tokens.length}. Sending in batches.`);
    // Batch into chunks of 500
    let allResults = [];
    let totalSuccess = 0;
    let totalFailure = 0;
    for (let i = 0; i < tokens.length; i += 500) {
      const batch = tokens.slice(i, i + 500);
      const result = await sendMulticast({ title, body, tokens: batch, deepLink, imageUrl });
      if (result.success) {
        totalSuccess += result.successCount;
        totalFailure += result.failureCount;
        allResults = allResults.concat(result.results || []);
      } else {
        return result; // propagate error
      }
    }
    return { success: true, successCount: totalSuccess, failureCount: totalFailure, results: allResults };
  }

  const message = {
    notification: { title, body },
    android: {
      priority: 'high',
      notification: {
        channelId: 'streamify_events',
        clickAction: 'FLUTTER_NOTIFICATION_CLICK',
        ...(imageUrl && { imageUrl })
      }
    },
    apns: {
      payload: {
        aps: {
          sound: 'default',
          badge: 1,
          ...(imageUrl && { 'mutable-content': 1 })
        }
      },
      ...(imageUrl && { fcmOptions: { imageUrl } })
    },
    tokens,
    data: deepLink ? { deepLink } : {}
  };

  try {
    const response = await admin.messaging().sendEachForMulticast(message);
    const results = response.responses.map((r, i) => ({
      token: tokens[i],
      success: r.success,
      messageId: r.messageId || null,
      error: r.error ? r.error.code || r.error.message : null
    }));
    console.log(
      `[fcm] Multicast: ${response.successCount} ok, ${response.failureCount} failed (${tokens.length} total)`
    );
    return {
      success: true,
      successCount: response.successCount,
      failureCount: response.failureCount,
      results
    };
  } catch (err) {
    console.error(`[fcm] Multicast failed:`, err.message);
    return { success: false, reason: err.code || 'unknown', error: err.message };
  }
}

/**
 * Subscribe devices to a topic.
 */
async function subscribeToTopic(tokens, topic) {
  initFCM();
  if (!fcmInitialized) return { success: false, reason: 'fcm-not-initialized' };

  try {
    const response = await admin.messaging().subscribeToTopic(tokens, topic);
    console.log(`[fcm] Subscribed ${response.successCount} tokens to "${topic}" (${response.failureCount} failed)`);
    return { success: true, successCount: response.successCount, failureCount: response.failureCount };
  } catch (err) {
    console.error(`[fcm] Subscribe to topic "${topic}" failed:`, err.message);
    return { success: false, reason: err.code || 'unknown', error: err.message };
  }
}

/**
 * Unsubscribe devices from a topic.
 */
async function unsubscribeFromTopic(tokens, topic) {
  initFCM();
  if (!fcmInitialized) return { success: false, reason: 'fcm-not-initialized' };

  try {
    const response = await admin.messaging().unsubscribeFromTopic(tokens, topic);
    console.log(`[fcm] Unsubscribed ${response.successCount} tokens from "${topic}" (${response.failureCount} failed)`);
    return { success: true, successCount: response.successCount, failureCount: response.failureCount };
  } catch (err) {
    console.error(`[fcm] Unsubscribe from topic "${topic}" failed:`, err.message);
    return { success: false, reason: err.code || 'unknown', error: err.message };
  }
}

module.exports = {
  sendToTopic,
  sendToToken,
  sendMulticast,
  subscribeToTopic,
  unsubscribeFromTopic,
  isInitialized: () => { initFCM(); return fcmInitialized; }
};
