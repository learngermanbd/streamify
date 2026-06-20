/**
 * Phase 8 \u00b7 Step 8.2 \u2014 health endpoint.
 * No auth; used by Render/Railway liveness probes + Android admin boot check.
 */
const express = require('express');

const router = express.Router();

router.get('/', (_req, res) => {
  res.json({
    status: 'ok',
    service: 'streamify-admin-backend',
    version: process.env.npm_package_version || '0.1.0',
    phase: 'Phase 8.2 skeleton',
    time: new Date().toISOString(),
    uptime_seconds: Math.round(process.uptime()),
    node: process.version
  });
});

module.exports = router;
