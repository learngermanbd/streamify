/**
 * Phase 8 · Step 8.20 — /api/playlists route (stub).
 *
 * Returns user playlists. Currently a placeholder — the Android client's
 * PlaylistsViewModel is local-only (Room) and does not call this endpoint
 * at runtime. The route exists so the full APK endpoint catalog returns
 * 200 instead of 404.
 *
 * Full playlist sync (POST/PUT/DELETE with ownerId scoping) lands in a
 * future phase when a Playlist Prisma model is added.
 */
const express = require('express');
const router = express.Router();

// GET /api/playlists?ownerId=X — returns user's playlists (stub: empty)
router.get('/', (_req, res) => {
  res.json([]);
});

module.exports = router;
