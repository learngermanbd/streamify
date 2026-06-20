/**
 * Phase 8 · Step 8.3 — Zod validation middleware.
 *
 * Uses Zod schemas to validate request body, query, and params.
 * Returns a 400 JSON error with field-level details on failure.
 */
const { ZodError } = require('zod');

/**
 * Returns middleware that validates req.body against the given Zod schema.
 * On success, replaces req.body with the parsed (and defaulted/transformed) value.
 * On failure, returns a 400 error with per-field messages.
 */
function validateBody(schema) {
  return (req, res, next) => {
    try {
      req.body = schema.parse(req.body);
      next();
    } catch (err) {
      if (err instanceof ZodError) {
        return res.status(400).json({
          error: 'validation failed',
          fields: err.errors.map(e => ({
            path: e.path.join('.'),
            message: e.message
          }))
        });
      }
      next(err);
    }
  };
}

/**
 * Returns middleware that validates req.query against the given Zod schema.
 */
function validateQuery(schema) {
  return (req, res, next) => {
    try {
      req.query = schema.parse(req.query);
      next();
    } catch (err) {
      if (err instanceof ZodError) {
        return res.status(400).json({
          error: 'invalid query parameters',
          fields: err.errors.map(e => ({
            path: e.path.join('.'),
            message: e.message
          }))
        });
      }
      next(err);
    }
  };
}

/**
 * Returns middleware that validates req.params against the given Zod schema.
 */
function validateParams(schema) {
  return (req, res, next) => {
    try {
      req.params = schema.parse(req.params);
      next();
    } catch (err) {
      if (err instanceof ZodError) {
        return res.status(400).json({
          error: 'invalid route parameters',
          fields: err.errors.map(e => ({
            path: e.path.join('.'),
            message: e.message
          }))
        });
      }
      next(err);
    }
  };
}

module.exports = { validateBody, validateQuery, validateParams };
