# SportStream Admin Backend

Phase 8 · Step 8.2-8.3 — Full REST API powering the SportStream admin panel (web + Android) plus the user app's `/api/config` and `/api/*` reads.

## Stack

- **Runtime**: Node.js 18+
- **Framework**: Express 4
- **Database**: PostgreSQL via Prisma 5 (Supabase in prod)
- **Auth**: JWT (`jsonwebtoken`) + bcrypt hashes (`bcryptjs`) + refresh tokens
- **Validation**: Zod schemas
- **Security**: helmet + cors + morgan + rate limiting + RBAC

## Quick start

```bash
cd backend
cp .env.example .env             # fill in DATABASE_URL + JWT_SECRET + JWT_REFRESH_SECRET
npm install                      # already done (Step 8.2)
node --check src/server.js       # fast syntax check
npx prisma generate              # generates @prisma/client
npx prisma migrate dev           # applies schema to DB
npm run dev                      # boots on :3000
curl http://localhost:3000/api/health
```

## Endpoints (Phase 8.3 — full REST API)

| Method | Path | Auth | Role | Description |
|--------|------|------|------|-------------|
| GET | `/api/health` | — | — | Liveness probe |
| POST | `/api/admin/auth/login` | — | — | Login, returns JWT + refresh token |
| POST | `/api/admin/auth/refresh` | Bearer | — | Rotate access token |
| GET | `/api/admin/auth/me` | Bearer | — | Current user info |
| POST | `/api/admin/auth/logout` | — | — | Client-side token discard |
| GET | `/api/admin/users` | Bearer | SUPER_ADMIN | List admin accounts |
| POST | `/api/admin/users` | Bearer | SUPER_ADMIN | Create admin |
| PUT | `/api/admin/users/:id` | Bearer | SUPER_ADMIN | Update admin |
| DELETE | `/api/admin/users/:id` | Bearer | SUPER_ADMIN | Delete admin |
| GET | `/api/events` | — | — | List events (public) |
| GET | `/api/events/:id` | — | — | Get event |
| POST | `/api/events` | Bearer | EDITOR | Create event |
| PUT | `/api/events/:id` | Bearer | EDITOR | Update event |
| DELETE | `/api/events/:id` | Bearer | SUPER_ADMIN | Delete event |
| GET | `/api/channels` | — | — | List channels (public) |
| GET | `/api/channels/:id` | — | — | Get channel |
| POST | `/api/channels` | Bearer | EDITOR | Create channel |
| PUT | `/api/channels/:id` | Bearer | EDITOR | Update channel |
| DELETE | `/api/channels/:id` | Bearer | SUPER_ADMIN | Delete channel |
| GET | `/api/highlights` | — | — | List highlights (public) |
| GET | `/api/highlights/:id` | — | — | Get highlight |
| POST | `/api/highlights` | Bearer | EDITOR | Create highlight |
| PUT | `/api/highlights/:id` | Bearer | EDITOR | Update highlight |
| DELETE | `/api/highlights/:id` | Bearer | SUPER_ADMIN | Delete highlight |
| GET | `/api/categories` | — | — | List categories (public) |
| GET | `/api/categories/:id` | — | — | Get category |
| POST | `/api/categories` | Bearer | EDITOR | Create category |
| PUT | `/api/categories/:id` | Bearer | EDITOR | Update category |
| DELETE | `/api/categories/:id` | Bearer | SUPER_ADMIN | Delete category |
| GET | `/api/banners` | — | — | List banners (public) |
| GET | `/api/banners/:id` | — | — | Get banner |
| POST | `/api/banners` | Bearer | EDITOR | Create banner |
| PUT | `/api/banners/:id` | Bearer | EDITOR | Update banner |
| DELETE | `/api/banners/:id` | Bearer | SUPER_ADMIN | Delete banner |
| GET | `/api/config` | — | — | App config (public) |
| PUT | `/api/config` | Bearer | SUPER_ADMIN | Update config |
| GET | `/api/notifications` | Bearer | — | List notifications |
| POST | `/api/notifications/send` | Bearer | EDITOR | Send notification |
| POST | `/api/notifications/:id/cancel` | Bearer | EDITOR | Cancel scheduled |
| DELETE | `/api/notifications/:id` | Bearer | SUPER_ADMIN | Delete notification |
| GET | `/api/analytics/overview` | Bearer | — | Dashboard stats |
| GET | `/api/analytics/events` | Bearer | — | Analytics events |
| POST | `/api/analytics/events` | — | — | Ingest event (public) |
| POST | `/api/upload` | Bearer | EDITOR | Upload file |

## Architecture

- **Step 8.2** — Prisma schema (10 models) ✓
- **Step 8.3** — Full routes + RBAC + Zod validation + rate limiting ✓
- **Step 8.4-8.9** — Web admin frontend (pending)
- **Step 8.10** — Mobile (user) ApiService integration (pending)
- **Step 8.13-8.17** — Separate Android admin app (pending)
- **Step 8.18** — FCM notifications targeting (pending)

## Files (25 total)

```
backend/
├── src/
│   ├── server.js                          # Entry point
│   ├── config/
│   │   └── database.js                    # Prisma client singleton
│   ├── middleware/
│   │   ├── auth.js                        # JWT + bcrypt
│   │   ├── rbac.js                        # Role-based access
│   │   ├── validate.js                    # Zod validation (utility)
│   │   ├── rateLimit.js                   # Rate limiting
│   │   └── upload.js                      # Multer config
│   ├── controllers/
│   │   ├── eventsController.js            # Events CRUD
│   │   ├── channelsController.js          # Channels CRUD
│   │   ├── highlightsController.js        # Highlights CRUD
│   │   ├── categoriesController.js        # Categories CRUD
│   │   ├── bannersController.js           # Banners CRUD
│   │   ├── configController.js            # App config
│   │   ├── notificationsController.js     # Notifications
│   │   ├── analyticsController.js         # Analytics
│   │   └── adminUsersController.js        # Admin user management
│   └── routes/
│       ├── health.js
│       ├── auth.js
│       ├── events.js
│       ├── channels.js
│       ├── highlights.js
│       ├── categories.js
│       ├── banners.js
│       ├── config.js
│       ├── notifications.js
│       ├── analytics.js
│       ├── adminUsers.js
│       └── upload.js
├── prisma/
│   └── schema.prisma
├── package.json
└── .env.example
```
