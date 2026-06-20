/**
 * Phase 8 · Step 8.3 — File upload routes.
 *
 * Handles image/video uploads. In production, buffers are piped to
 * Supabase Storage. For dev without Supabase, saves to a local /uploads dir.
 */
const express = require('express');
const path = require('path');
const fs = require('fs');
const { uploadAny } = require('../middleware/upload');
const { authRequired } = require('../middleware/auth');
const { requireRole } = require('../middleware/rbac');

const router = express.Router();

// Ensure uploads directory exists for dev fallback
const UPLOAD_DIR = path.join(__dirname, '..', '..', 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

/** POST /api/upload — Upload a file (EDITOR+) */
router.post('/', authRequired, requireRole('EDITOR'), (req, res) => {
  uploadAny(req, res, async (err) => {
    if (err) {
      if (err.code === 'LIMIT_FILE_SIZE') {
        return res.status(413).json({ error: 'file too large (max 100 MB)' });
      }
      return res.status(400).json({ error: err.message });
    }

    if (!req.file) {
      return res.status(400).json({ error: 'no file uploaded' });
    }

    try {
      const ext = path.extname(req.file.originalname) || '.bin';
      const filename = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}${ext}`;
      const filePath = path.join(UPLOAD_DIR, filename);

      fs.writeFileSync(filePath, req.file.buffer);

      // In production, this URL would be a Supabase Storage public URL
      const url = `/api/upload/uploads/${filename}`;

      return res.status(201).json({
        url,
        filename,
        size: req.file.size,
        mimetype: req.file.mimetype
      });
    } catch (e) {
      console.error('[upload]', e);
      return res.status(500).json({ error: 'file save failed' });
    }
  });
});

// Serve uploaded files in dev
router.use('/uploads', express.static(UPLOAD_DIR));

module.exports = router;
