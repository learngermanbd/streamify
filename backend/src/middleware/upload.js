/**
 * Phase 8 · Step 8.3 — File upload middleware (multer).
 *
 * Parses multipart/form-data uploads into memory buffers. The controller
 * is responsible for piping the buffer to Supabase Storage.
 *
 * Limits:
 *   - Images: max 5 MB, types jpg/png/webp
 *   - Videos: max 100 MB, types mp4/webm (highlights only)
 */

const multer = require('multer');

const storage = multer.memoryStorage();

const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
const VIDEO_TYPES = ['video/mp4', 'video/webm'];

function fileFilter(req, file, cb) {
  const allowed = [...IMAGE_TYPES, ...VIDEO_TYPES];
  if (allowed.includes(file.mimetype)) {
    cb(null, true);
  } else {
    cb(new Error(`Unsupported file type: ${file.mimetype}. Allowed: ${allowed.join(', ')}`));
  }
}

/** Image upload: 5 MB */
const uploadImage = multer({
  storage,
  limits: { fileSize: 5 * 1024 * 1024 },
  fileFilter
}).single('file');

/** Video upload: 100 MB */
const uploadVideo = multer({
  storage,
  limits: { fileSize: 100 * 1024 * 1024 },
  fileFilter
}).single('file');

/** Any file: 100 MB (used when type is dynamic) */
const uploadAny = multer({
  storage,
  limits: { fileSize: 100 * 1024 * 1024 },
  fileFilter
}).single('file');

module.exports = { uploadImage, uploadVideo, uploadAny, IMAGE_TYPES, VIDEO_TYPES };
