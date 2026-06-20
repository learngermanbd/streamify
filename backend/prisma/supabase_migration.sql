-- ============================================================
-- SportStream Supabase Migration (v2 — bulletproof)
-- Run in: Supabase Dashboard → SQL Editor → New Query
-- Select ALL text and click Run
-- ============================================================

BEGIN;

-- ─── Admin ───
CREATE TABLE IF NOT EXISTS "Admin" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "email" TEXT UNIQUE NOT NULL,
  "passwordHash" TEXT NOT NULL,
  "name" TEXT NOT NULL,
  "role" TEXT DEFAULT 'EDITOR' CHECK ("role" IN ('SUPER_ADMIN', 'EDITOR', 'VIEWER')),
  "avatarUrl" TEXT,
  "lastLoginAt" TIMESTAMPTZ,
  "createdAt" TIMESTAMPTZ DEFAULT now(),
  "updatedAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── Category ───
CREATE TABLE IF NOT EXISTS "Category" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "name" TEXT NOT NULL,
  "iconUrl" TEXT,
  "sortOrder" INTEGER DEFAULT 0,
  "isVisible" BOOLEAN DEFAULT true
);

-- ─── Channel ───
CREATE TABLE IF NOT EXISTS "Channel" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "name" TEXT NOT NULL,
  "logoUrl" TEXT,
  "streamUrl" TEXT NOT NULL,
  "categoryId" TEXT NOT NULL REFERENCES "Category"("id") ON DELETE CASCADE,
  "isActive" BOOLEAN DEFAULT true,
  "sortOrder" INTEGER DEFAULT 0,
  "createdAt" TIMESTAMPTZ DEFAULT now(),
  "updatedAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── Event ───
CREATE TABLE IF NOT EXISTS "Event" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "title" TEXT NOT NULL,
  "description" TEXT,
  "categoryId" TEXT REFERENCES "Category"("id") ON DELETE SET NULL,
  "teamAName" TEXT,
  "teamALogoUrl" TEXT,
  "teamBName" TEXT,
  "teamBLogoUrl" TEXT,
  "date" TEXT,
  "time" TEXT,
  "isLive" BOOLEAN DEFAULT false,
  "status" TEXT DEFAULT 'DRAFT' CHECK ("status" IN ('DRAFT', 'SCHEDULED', 'LIVE', 'ENDED')),
  "thumbnailUrl" TEXT,
  "scheduledFor" TIMESTAMPTZ,
  "createdAt" TIMESTAMPTZ DEFAULT now(),
  "updatedAt" TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_event_status ON "Event"("status");
CREATE INDEX IF NOT EXISTS idx_event_category ON "Event"("categoryId");

-- ─── StreamLink ───
CREATE TABLE IF NOT EXISTS "StreamLink" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "eventId" TEXT NOT NULL REFERENCES "Event"("id") ON DELETE CASCADE,
  "name" TEXT NOT NULL,
  "url" TEXT NOT NULL,
  "quality" TEXT DEFAULT 'AUTO' CHECK ("quality" IN ('AUTO', 'HD', 'SD')),
  "sortOrder" INTEGER DEFAULT 0,
  "isActive" BOOLEAN DEFAULT true
);
CREATE INDEX IF NOT EXISTS idx_streamlink_event ON "StreamLink"("eventId");

-- ─── Highlight ───
CREATE TABLE IF NOT EXISTS "Highlight" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "title" TEXT NOT NULL,
  "thumbnailUrl" TEXT NOT NULL,
  "videoUrl" TEXT NOT NULL,
  "date" BIGINT,
  "duration" INTEGER DEFAULT 0,
  "views" INTEGER DEFAULT 0,
  "createdAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── Banner ───
CREATE TABLE IF NOT EXISTS "Banner" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "title" TEXT NOT NULL,
  "imageUrl" TEXT NOT NULL,
  "linkUrl" TEXT,
  "isActive" BOOLEAN DEFAULT true,
  "sortOrder" INTEGER DEFAULT 0,
  "createdAt" TIMESTAMPTZ DEFAULT now(),
  "updatedAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── AppConfig ───
CREATE TABLE IF NOT EXISTS "AppConfig" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "apiBaseUrl" TEXT NOT NULL DEFAULT '',
  "updateUrl" TEXT,
  "telegramLink" TEXT,
  "noticeText" TEXT,
  "maintenanceMode" BOOLEAN DEFAULT false,
  "minAppVersion" TEXT,
  "featureFlags" JSONB DEFAULT '{}'::jsonb,
  "updatedAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── Notification ───
CREATE TABLE IF NOT EXISTS "Notification" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "title" TEXT NOT NULL,
  "body" TEXT NOT NULL,
  "targetTopic" TEXT,
  "targetToken" TEXT,
  "deepLink" TEXT,
  "scheduledAt" TIMESTAMPTZ,
  "sentAt" TIMESTAMPTZ,
  "status" TEXT DEFAULT 'PENDING' CHECK ("status" IN ('PENDING', 'SCHEDULED', 'SENT', 'FAILED', 'CANCELLED')),
  "sentById" TEXT REFERENCES "Admin"("id") ON DELETE SET NULL,
  "createdAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── DeviceToken ───
CREATE TABLE IF NOT EXISTS "DeviceToken" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "token" TEXT UNIQUE NOT NULL,
  "platform" TEXT,
  "lastSeenAt" TIMESTAMPTZ DEFAULT now(),
  "createdAt" TIMESTAMPTZ DEFAULT now()
);

-- ─── AnalyticsEvent ───
CREATE TABLE IF NOT EXISTS "AnalyticsEvent" (
  "id" TEXT PRIMARY KEY DEFAULT gen_random_uuid()::text,
  "eventType" TEXT NOT NULL,
  "eventName" TEXT NOT NULL,
  "properties" JSONB DEFAULT '{}'::jsonb,
  "deviceId" TEXT,
  "platform" TEXT,
  "appVersion" TEXT,
  "country" TEXT,
  "occurredAt" TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_analytics_type ON "AnalyticsEvent"("eventType", "occurredAt");

-- ─── Seed: first SUPER_ADMIN (password: admin123) ───
INSERT INTO "Admin" ("id", "email", "passwordHash", "name", "role")
VALUES (
  gen_random_uuid()::text,
  'admin@sportstream.app',
  '$2a$10$rOzZCmQZG1vH7NRXj.tCYeZGcFHR0O7wGQp.dx0YkQPjOXKiE1.t.',
  'Super Admin',
  'SUPER_ADMIN'
) ON CONFLICT (email) DO NOTHING;

-- ─── Seed: default AppConfig ───
INSERT INTO "AppConfig" ("id", "apiBaseUrl")
VALUES (gen_random_uuid()::text, 'http://localhost:4000');

COMMIT;
