/**
 * Phase 8 · Step 8.3 — Rate limiter middleware.
 *
 * Simple in-memory sliding-window rate limiter. In production, replace
 * with Redis or express-rate-limit backed by a shared store.
 */

/** @type {Map<string, {count: number, resetAt: number}>} */
const store = new Map();

// Clean up expired entries every 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [key, entry] of store) {
    if (now > entry.resetAt) store.delete(key);
  }
}, 5 * 60 * 1000).unref();

/**
 * Creates rate-limiting middleware.
 *
 * @param {object} opts
 * @param {number} opts.windowMs - time window in milliseconds
 * @param {number} opts.max - max requests within the window
 * @param {string} [opts.message] - custom error message
 * @param {function} [opts.keyGenerator] - custom key function (default: req.ip)
 */
function rateLimiter(opts = {}) {
  const {
    windowMs = 15 * 60 * 1000,
    max = 100,
    message = 'Too many requests, please try again later.',
    keyGenerator = (req) => req.ip || req.connection.remoteAddress || 'unknown'
  } = opts;

  return (req, res, next) => {
    const key = keyGenerator(req);
    const now = Date.now();

    let entry = store.get(key);
    if (!entry || now > entry.resetAt) {
      entry = { count: 0, resetAt: now + windowMs };
      store.set(key, entry);
    }

    entry.count++;

    // Set headers
    res.setHeader('X-RateLimit-Limit', max);
    res.setHeader('X-RateLimit-Remaining', Math.max(0, max - entry.count));
    res.setHeader('X-RateLimit-Reset', Math.ceil(entry.resetAt / 1000));

    if (entry.count > max) {
      return res.status(429).json({
        error: message,
        retryAfter: Math.ceil((entry.resetAt - now) / 1000)
      });
    }

    next();
  };
}

module.exports = { rateLimiter };
