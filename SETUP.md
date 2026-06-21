# Streamify — Local Setup Guide

> 📅 **Last verified:** 2026-05-21 (against commit `6f3e7f7`).
> Run `git log -1` for the current commit this doc targets; if your repo is
> ahead, every absolute path/commit hash in this doc may have drifted.

This guide walks through every credential a developer needs to drop in to take
the Streamify Android app from a freshly-cloned repo to a Play-shippable
release build that uploads Sentry `mapping.txt` on every release.

> ⏱️ If you only want to RUN THE APP for development (debug builds calling
> the test API), skip **Steps 4–7**. Step 3 is still required because the
> `io.sentry.android.gradle` plugin reads its `sentry { org = …; projectName = … }`
> configuration at file-evaluation time regardless of whether any
> `SENTRY_AUTH_TOKEN` is present; if Step 3 is missing the
> `uploadSentryProguardMappingsRelease` task will 404 on every release build.
> Steps 4–7 themselves are only required for `:app:assembleRelease`
> (Play-shippable) builds.

---

## 1. Quick reference: which file holds what

| File | Holds | Tracked? | When to update |
|---|---|---|---|
| `app/google-services.json` | Firebase project config (`project_id`, API key, `mobilesdk_app_id`) | ✅ Yes | Only when migrating to a different Firebase project |
| `gradle/libs.versions.toml` | `firebaseBom` + `google-services` plugin versions | ✅ Yes | Firebase SDK upgrades |
| `app/build.gradle.kts` `sentry { }` block | Sentry `org` slug + `projectName` slug | ✅ Yes | Sentry org or project renames |
| `signing.properties` (repo root) | Release keystore path + 4 password/alias fields + Sentry DSN + Sentry auth token | ❌ NO — gitignored | Every secret rotation |
| `secrets.properties` (repo root) | API_BASE_URL, API_CONFIG_URL, UPDATE_URL, TELEGRAM_LINK | ❌ NO — gitignored | Backend URL changes |

`signing.properties` and `secrets.properties` are gitignored for security.
`.example` templates sibling to them show the structure (also tracked).

---

## 2. Firebase — `app/google-services.json`

### When this is needed
For any build that uses Firebase Cloud Messaging (push notifications). The
`google-services` Gradle plugin reads this at task-graph configuration time and
bakes the resulting `project_id` / `google_api_key` into generated resources.

### What's expected
A real Firebase console export with:
- `project_info.project_id` — e.g. `sports-live-9e218`
- `client_info.mobilesdk_app_id` — `1:<project_number>:android:<hash>`
- `client_info.android_client_info.package_name` = **`com.streamify.app`** (must
  exactly match `applicationId` in `app/build.gradle.kts`)
- `api_key[0].current_key` — a real Firebase API key

### How to obtain one
1. Firebase Console → your project → ⚙ Project settings → **General** tab
2. Scroll to **Your apps** → Android list → click the row matching
   `com.streamify.app`
3. Click **Download google-services.json**
4. Drop it over `app/google-services.json`

### Verification
Run BOTH variants' google-services resources to confirm the JSON landed in
both debug and release pipelines (a common gotcha: only running the debug
variant misses release-only failures):

```bash
./gradlew :app:processDebugGoogleServices :app:processReleaseGoogleServices --console=plain --no-daemon
```

Then inspect both generated files. The debug one is at:
`app/build/generated/res/processDebugGoogleServices/values.xml`

The release one is at:
`app/build/generated/res/processReleaseGoogleServices/values.xml`

Search each for `<string name="project_id"`. Both should show your real
project ID, not a placeholder like `streamify-placeholder`.

---

## 3. Sentry — `sentry { org = ...; projectName = ... }`

### When this is needed
The `io.sentry.android.gradle` plugin targets uploads at this org/project pair.
If they don't match the actual project that owns your DSN, every release build
will log a `404 Not Found` from `sentry.io` even with a valid auth token.

### What's expected
Two slugs as they appear in your Sentry URL bar:
- `https://sentry.io/settings/<org>/projects/<project>/`

### How to obtain them
Open Sentry → top-left org switcher → the name there is the **org slug** →
click **Settings ⚙** (bottom of left sidebar) → click **Projects** → find the
project whose DSN matches the one you'll paste in Step 4 → that name is the
**project slug**.

### What to edit
In `app/build.gradle.kts`, the `sentry { }` extension block:

```kotlin
sentry {
    org = "<your-org-slug>"          // e.g. "streamify-0p"
    projectName = "<your-project>"   // e.g. "android"
    ...
}
```

Commit this edit — it's tracked in git. The auth-token line below is read
from `signing.properties` at build time and stays out of git.

---

## 4. Sentry auth token — `SENTRY_AUTH_TOKEN`

### When this is needed
The `uploadSentryProguardMappingsRelease` task authenticates to Sentry's REST
API using this token. Without it, release builds still produce an APK but the
mapping.txt upload fails and Kotlin stack traces in your Sentry dashboard
won't be deobfuscated.

### Three token flavors Sentry exposes

| Type | Path | Required role | Survives user deletion? |
|---|---|---|---|
| Org-level | Org Settings → API → Auth Tokens | Owner / Manager | ✅ Yes |
| Project-level | Project Settings → Security & API | Owner / Manager / Admin | ✅ Yes |
| Personal | Settings → Account → API → Personal Tokens | Any user | ❌ No |

**Pick Org-level** if your role allows. Personal as fallback works but is tied
to your user account (and dies with it if you leave the org).

### How to mint
1. Sentry → Auth Tokens → **Create New Token**
2. Name it `streamify-android-build-pipeline`
3. Scopes: ✅ **`project:releases`** + ✅ **`project:write`** — nothing else
4. Click **Create Token**; Sentry displays the value **once** — copy immediately

### Token format reference
Sentry tokens start with `sntr` followed by a single lowercase letter that
indicates the kind:
- `sntrys_…` — **org-scoped** auth token (recommended for build pipelines)
- `sntrys_` base64 payload decodes to `{"iat":...,"org":"...","region_url":...}`
- `sntryu_…` — **user-scoped** personal auth token (typically 64-char hex after the prefix in current Sentry SaaS)

The logged copy-pasteable plaintext (e.g., `sntrys_eyJpYXQ….Mp2Qw` from a
typical org token) is what gradle reads from `signing.properties`. Length
varies — the org-token payload after `sntrys_` is a base64 JSON object that
runs 100–250 chars depending on Sentry server version.

### Where it goes
In your terminal, edit `C:\Users\RDP\Desktop\streamify\signing.properties`:
```
SENTRY_AUTH_TOKEN=sntr[a-z]+_<your-token-here>
```
No quotes around the value, no spaces around `=`.

### 🔒 CRITICAL security rule
**NEVER paste auth tokens (or DSNs) into chat, GitHub issues, Notion, or any
other conversational surface.** If an attacker obtains your Sentry Auth Token
they can act as your build pipeline against Sentry: pollute crashes, read
breadcrumbs (which may contain user PII), modify issue assignments, and
trigger upstream GitHub alerts tied to webhook integrations.

If you ever leak one: Sentry UI → Settings → API → the leaked token's row →
**Revoke** (instant; CDN-cached table refreshes within ~30 s) → mint a fresh
one with a v2-suffixed name so you can spot prior leak-attributable traffic in
audit logs.

---

## 5. Release keystore

### Why you need one
Without a real release keystore, `app/build.gradle.kts`'s
`signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null } ?: signingConfigs.getByName("debug")`
resolver falls back to the **debug** keystore and the release APK ships
unsigned-by-release-key — Play Store to v1.1.0 will accept it but **future v1.1.1+
upgrades will require the same keystore, and you don't own that one**.

### Create the file first
The `.properties` files don't exist on a freshly-cloned repo (they're
gitignored). Create them from the templates before editing:

```bash
cp signing.properties.example signing.properties
cp secrets.properties.example   secrets.properties
```

Then run **Steps 4–6** below to fill them.

### Generate the keystore (Windows git-bash)
```bash
mkdir -p ~/keystores
keytool -genkeypair -v \
  -keystore ~/keystores/streamify-release.jks \
  -keyalg RSA -keysize 2048 -validity 25000 \
  -alias release \
  -storepass '<your-strong-store-password>' \
  -keypass  '<your-strong-key-password>' \
  -dname "CN=Streamify,OU=Mobile,O=Streamify,L=City,ST=State,C=US"
```

> `-validity 25000` = ~68 years (Play Store requires ≥25 years).
> `-storepass` and `-keypass` needn't be the same but storing them both in
> the same password manager is fine.
> Stash the `.jks` outside the repo. The repo's `.gitignore` excludes `*.jks`
> anyway — defense in depth.

### Verify
```bash
ls -la ~/keystores/streamify-release.jks
keytool -list -v -keystore ~/keystores/streamify-release.jks -storepass '<your-strong-store-password>' | head -20
```

### Windows path for gradle
Gradle's `file(String)` accepts forward slashes on Windows. Convert path:
```bash
cygpath -w ~/keystores/streamify-release.jks
```

---

## 6. `signing.properties` — all 6 keys

Open `C:\Users\RDP\Desktop\streamify\signing.properties` in your local editor
(NOT this chat — gitignored, secrets stay local). Set each:

```properties
# ── Release keystore ──
RELEASE_STORE_FILE=C:/Users/<you>/keystores/streamify-release.jks
RELEASE_STORE_PASSWORD=<your-strong-store-password>
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=<your-strong-key-password>

# ── Sentry crash reporting ──
APP_SENTRY_DSN=https://<public-key>@o<org-id>.ingest.<region>.sentry.io/<project-id>

# ── Sentry mapping.txt upload auth token ──
SENTRY_AUTH_TOKEN=sntr[a-z]+_<your-minted-token-here>
```

### `<region>` in the DSN template
Real DSNs can use any of:
- `ingest.us.sentry.io` (US-region Sentry SaaS)
- `ingest.eu.sentry.io` (EU-region)
- `ingest.de.sentry.io` (Germany-region)
- `ingest.sentry.io` (region-agnostic legacy self-hosted)
Use whichever region your Sentry org is hosted in. The region is encoded in
the DSN itself, so copy it verbatim from your Sentry UI.

### Acceptance check
- No quotes around the value
- No spaces around `=`
- All 6 keys non-empty
- `RELEASE_STORE_FILE` is a Windows absolute path with forward slashes
- `APP_SENTRY_DSN` matches the URL format
  `https://<32hex>@o<digits>.ingest.<region>.sentry.io/<digits>`
- `SENTRY_AUTH_TOKEN` starts with `sntr` (org `sntrys_` or user `sntryu_`)

---

## 7. `secrets.properties` — all 4 keys

If you cloned fresh, create the file (it's gitignored):

```bash
cp secrets.properties.example secrets.properties
```

These are AES-256-GCM encrypted at build time (random per-build key) into
`EncryptedConstants.kt`. Plaintext never appears in the APK.

```properties
# ── Backend API endpoints ──
API_BASE_URL=https://api.your-domain.com/api
API_CONFIG_URL=https://api.your-domain.com/config
UPDATE_URL=https://api.your-domain.com/update

# ── Telegram channel (drawer → Join Us) ──
TELEGRAM_LINK=https://t.me/your-channel
```

Format: same as `signing.properties` — no quotes, no spaces around `=`,
gitignored at repo root.

---

## 8. Build verification

After all four files exist with real values, run from your terminal (where
`signing.properties` and `secrets.properties` live):

```bash
cd 'C:\Users\RDP\Desktop\streamify'
./gradlew :app:assembleRelease -x lint --console=plain --no-daemon
```

### Expected happy path
```
> Task :app:verifyReleaseSecrets
verifyReleaseSecrets: signing.properties (6/6 keys) + secrets.properties (4/4 keys) all present ✓
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 4m 12s
109 actionable tasks: 109 executed
```

The `verifyReleaseSecrets` task above is a pre-flight guard that short-circuits
the build with a clear error if any of the 10 keys is missing. Its reference
to `assembleRelease` is wrapped in `afterEvaluate { }` (in this same series
of commits) to avoid AGP's variant-task registration ordering — never call
`tasks.named("assembleRelease")` at file evaluation time without that wrapper.
Future readers: `git log --grep "verifyReleaseSecrets" --oneline` will show
the exact commit introducing this guard.

### Failure modes you might see

| Symptom | Likely cause | Fix |
|---|---|---|
| `:app:assembleRelease pre-flight failed.` listing gaps | One of the 10 keys is missing/blank | Edit the named `.properties` file, fill the key |
| `Task 'uploadSentryProguardMappingsRelease' FAILED` `401 Unauthorized` | Token revoked OR wrong scope | Sentry UI → Revoke leaked token → create fresh one with `project:releases` + `project:write` scopes |
| Same task `403 Forbidden` | Your user role below required | Skip auth token for v1.1.0 (set `SENTRY_AUTH_TOKEN=` empty); ship without deobfuscated stack traces |
| Same task `404 Not Found` on `…/projects/<org>/<project>/…` | Gradle's `projectName` doesn't match the actual Sentry project slug | Edit `app/build.gradle.kts` `sentry { projectName = ... }` |
| `SigningConfig "release" is missing required property storeFile` (during `packageRelease`) | `RELEASE_STORE_FILE` is non-blank, but the path doesn't exist | Generate the keystore first (Step 5) |
| `keytool -genkeypair` doesn't print `[Storing …]` | Permission issue on `~/keystores/` | Run with `sudo`, or move to a path you can write |

---

## 9. Security checklist

| Do ✅ | Don't ❌ |
|---|---|
| Generate keystore + store in `~/keystores/` | Commit `*.jks` or `*.keystore` to git |
| Paste real DSN into `signing.properties` | Paste DSN into chat / Slack / GitHub issues |
| Mint and paste Sentry auth token locally | Paste `SENTRY_AUTH_TOKEN` into chat for any reason |
| Rotate (revoke + recreate) any leaked token immediately | Reuse the leaked token — assume it was harvested |
| Tail `git status` before every `git add -A` | `git push --force` to remote — could surface previously-leaked `signing.properties` in history |

### Token threat model (clarified)
Anyone who obtains a Sentry Auth Token can act as your build pipeline
against Sentry's API: write events, read breadcrumbs (which may contain
snippets of user PII from beforeSend scrub), modify issue assignments,
trigger upstream webhook alerts. The token is the only secret required to
AD as your build. Treat it like a GitHub PAT: never paste into chat,
review git history before pushing, rotate on any suspected leak.

### If you accidentally leak a token
1. **Revoke immediately**: Sentry UI → Settings → API → find the token row →
   click **Revoke**. Sentry's CDN-cached token table refreshes within ~30
   seconds, so the old token becomes useless fast.
2. **Mint a fresh one** with same scopes, different name (e.g., add a v2
   suffix so audit logs can attribute any post-leak traffic to this incident).
3. **Update `signing.properties → SENTRY_AUTH_TOKEN=`** locally with the new
   value.
4. **Audit Sentry's event timeline** for unusual activity in the leak window:
   Audit Log → Token Use, and Issues → new errors with payloads that don't
   match your codebase.
5. **Notify your team** if multiple devs share the org — a leaked token
   affects audit log integrity for everyone.

### If you accidentally commit a `.properties` file
1. `git rm --cached signing.properties` (removes from index, not the file on disk)
2. Add it to `.gitignore` (already there, but verify)
3. `git commit --amend` (or new commit) to remove it from history
4. `git push` and hope no-one pulled (if they did, rotate every secret in the file immediately)
5. For an already-pushed leak: rotate every secret in the file immediately
   and push a `--force-with-lease` to overwrite the leaking commit

---

## 10. Local-development shortcut (skip most above)

If you only want to run the app for dev/debug work:

```bash
cd 'C:\Users\RDP\Desktop\streamify'
./gradlew :app:assembleDebug
```

The debug build:
- ✅ Uses the debug keystore (auto-managed, no setup)
- ✅ Reads `BuildConfig.SENTRY_DSN = ""` if `signing.properties` is absent, so
   the Sentry SDK no-ops (no crashes are reported)
- ✅ Reads `AppConfig.defaults().apiBaseUrl = "https://learngermanwith.fun"`
   when `secrets.properties` is absent (placeholder host — UI shows "no data
   loaded" gracefully)
- ✅ Firebase Cloud Messaging still works because `google-services.json` IS
   tracked in git

This is enough to explore the codebase and run UI flows.

---

## 11. Where to go from here

After all 6 secrets.properties + signing.properties keys are real and the
build is green, ship v1.1.0:

### Build APK + AAB for Play
```bash
./gradlew :app:bundleRelease -x lint          # preferred for Play Store
./gradlew :app:assembleRelease -x lint        # for sideloading / direct distribution
```

Play Store's default since 2021 is **`.aab` (Android App Bundle)** for dynamic
delivery — generate `.aab` with `bundleRelease`. Direct distribution can use
`.apk` from `assembleRelease`.

### Push to remote (when your local is ahead of origin/main)

Three-command pattern (rebase then push):

```bash
git status                                # verify working tree is clean
git pull --rebase origin main             # fold in upstream changes (no merge commit)
                                         # use --autostash if you have uncommitted changes
git push origin main                      # publish the local-ahead commits
```

If `git pull --rebase` reports conflicts, resolve them locally (the local
should always win for our setup; pick `ours` if contested), `git rebase
--continue`, then push.

Push the tag too (NOT auto-pushed):

```bash
git tag v1.1.0
git push origin v1.1.0
```

### CI side
Mirror Steps 4–7 of this guide against your runner's secret store (GitHub
Actions → encrypted secrets, GitLab CI → masked variables, etc.). The
`.example` files in the repo are also CI-friendly placeholders; just
materialise the values from the runner's `env` at build time.

---

## Reference: file locations cheat sheet

| Path | What |
|---|---|
| `app/google-services.json` | Firebase project export (tracked) |
| `signing.properties` | Release keystore + Sentry DSN + Sentry auth token (gitignored) |
| `signing.properties.example` | Empty template, committed |
| `secrets.properties` | 4 backend URLs (gitignored) |
| `secrets.properties.example` | Empty template, committed |
| `app/build.gradle.kts` | `sentry { org = ; projectName = }` block, tracked |
| `app/src/main/java/com/streamify/app/security/SecretsValidator.kt` | Boot-time placeholder detector |
| `app/build.gradle.kts` — end of file | `verifyReleaseSecrets` pre-flight (commit `6f3e7f7`) |
| `app/build/outputs/apk/release/app-release.apk` | Final Play-shippable APK |
| `app/build/outputs/bundle/release/app-release.aab` | Final Play App Bundle (preferred for Play Store) |
