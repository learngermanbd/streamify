# Streamify — Local Setup Guide

> 📅 **Last verified:** 2026-05-21 (against commit `6f3e7f7`).
> Run `git log -1` for the current commit this doc targets; if your repo is
> ahead, every absolute path/commit hash in this doc may have drifted.

This guide walks through every credential a developer needs to drop in to take
the Streamify Android app from a freshly-cloned repo to a Play-shippable
release build.

---

## 1. Quick reference: which file holds what

| File | Holds | Tracked? | When to update |
|---|---|---|---|
| `app/google-services.json` | Firebase project config (`project_id`, API key, `mobilesdk_app_id`) | ✅ Yes | Only when migrating to a different Firebase project |
| `gradle/libs.versions.toml` | `firebaseBom` + `google-services` plugin versions | ✅ Yes | Firebase SDK upgrades |
| `signing.properties` (repo root) | Release keystore path + 4 password/alias fields | ❌ NO — gitignored | Every secret rotation |
| `secrets.properties` (repo root) | API_BASE_URL, API_CONFIG_URL, UPDATE_URL, TELEGRAM_LINK | ❌ NO — gitignored | Backend URL changes |
| `local.properties` (repo root) | `sdk.dir` pointing at the Android SDK install | ❌ NO — gitignored | Reinstall the SDK elsewhere |
| `gradle.properties` (repo root) | `org.gradle.java.installations.paths` (Temurin JDK 17) + `org.gradle.java.installations.auto-download=false` (Gradle toolchain mechanism) | ✅ Yes | Major AGP / AS upgrade or JDK 17 patch bump (must update path to exact `hostedtoolcache` patch) |

`signing.properties` and `secrets.properties` are gitignored for security.
`.example` templates sibling to them show the structure (also tracked).

### User-level environment variables (Windows)

These were set on **2026-06-21** as part of the Android Studio SDK
migration. They live in `HKCU\Environment` so they apply to every process
this user launches (AS, Cmdline Tools, adb, aapt, this project's Gradle
daemon).

| Variable | Value | Read by |
|---|---|---|
| `ANDROID_HOME` | `C:\Users\RDP\AppData\Local\Android\Sdk` | AS, Gradle, sdkmanager, AGP — first precedence over `local.properties` |
| `ANDROID_SDK_ROOT` | `C:\Users\RDP\AppData\Local\Android\Sdk` | Older build-tools, adb, AVD manager |

If you wipe the SDK and reinstall elsewhere, re-run these two `setx`
commands and update both. The Gradle daemon picks them up on next launch.

### Gradle Java toolchain (dual JDK — Temurin 17 for compile, JetBrains 21 for daemon)

**Always run `./gradlew --stop` after editing this file.** A Gradle daemon
that was started BEFORE a `gradle.properties` edit keeps its original JVM
choice for the rest of its lifetime; the new `installations.paths` only
applies to daemons spawned after the property change. Without `--stop`,
the old daemon JVM silently continues and you can't tell from a build
success that the new toolchain even kicked in.

Gradle 8.13's daemon distribution ships a `gradle-daemon-jvm.properties`
that **requires** the daemon host process to spawn on a JetBrains JDK 21.
This is separate from anything AGP needs for its compile actions. To
satisfy both without going to the foojay network, both JDKs are listed
in `gradle.properties`:

```
org.gradle.java.installations.paths=\
  C:/hostedtoolcache/windows/Java_Temurin-Hotspot_jdk/17.0.19-10/x64,\
  C:/Program Files/Android/Android Studio/jbr
```

| Path | JDK | Used for |
|---|---|---|
| `hostedtoolcache/...Temurin-Hotspot_jdk/17.0.19-10/x64` | Eclipse Adoptium Temurin 17.0.19+10 | AGP 8.9.2 Java/Kotlin compile (per `compileOptions VERSION_17` + `kotlinOptions jvmTarget 17`) |
| `C:/Program Files/Android/Android Studio/jbr` | JetBrains Runtime 21.0.10 (bundled with Android Studio) | Gradle 8.13 daemon JVM (matches the `{languageVersion=21, vendor=JetBrains}` spec in `gradle-daemon-jvm.properties`) |

The companion setting `org.gradle.java.installations.auto-download=false`
forces the build to fail loud (instead of silently foojay-downloading
a third JDK 21 into `~/.gradle/jdks/`) if EITHER path ever goes
missing — preserving hermeticity.

#### Path fragility — exact patch version + AS install required

`C:/hostedtoolcache/windows/Java_Temurin-Hotspot_jdk/17.0.19-10/x64`
is hard-coded to a specific Temurin patch release. If the holding
environment ships a different patch (`17.0.20-7`, `17.0.21-8`, etc.),
the path is gone and `auto-download=false` causes a hard failure.
Update the path string to match the actual patch on whichever
machines run the build (dev / CI / build-server).

**Android Studio 2024.2 (Meerkat) or later is REQUIRED.** The daemon-side
path points at `C:/Program Files/Android/Android Studio/jbr`, which AS
shipped JetBrains Runtime 21 starting with Meerkat (2024.2). Earlier AS
versions (Koala 2024.1 = JBR 17, Hedgehog 2023.1.1 = JBR 17, etc.) provide
JBR 17 instead, which doesn't match Gradle 8.13's daemon-JVM spec
(`{languageVersion=21, vendor=JetBrains}`) — and the build will then fail
with `Cannot find a Java installation matching {languageVersion=21, vendor=JetBrains}`
during the very first daemon spawn. Uninstalling Android Studio entirely
also breaks the daemon. If you can't use a recent AS install, the fallback
is `--no-daemon` on every Gradle invocation, but that defeats most of
the speed benefit of the toolchain switch.

#### Why no explicit `java { toolchain { languageVersion = 17 } }`

A direct `languageVersion` declaration would be the most hermetic
form, but in this Gradle wrapper version the Kotlin DSL script
compiler surfaces an `Unresolved reference: JavaLanguageVersion`
error on any `import org.gradle.api.JavaLanguageVersion` in module
`build.gradle.kts` files (and even when hoisted to root via
`subprojects { java { toolchain { ... } } }`, where the `java {}`
accessor is also unavailable on the Subprojects container). The
minimal configuration (installations.paths + auto-download=false +
per-module `compileOptions VERSION_17 / kotlinOptions jvmTarget 17`)
is what ships today. Future Gradle wrapper upgrades that resolve
this should re-introduce a typed toolchain block. Do **not** file a
"missing explicit toolchain spec" review comment without first
testing whether the import resolves in the current wrapper.

#### Cached JetBrains JDK 21 in `~/.gradle/jdks/` — keep or evict

The `~/.gradle/jdks/` cache may still contain a JetBrains 21 that
Gradle auto-downloaded BEFORE `auto-download=false` was set. With
the AS-bundled JBR now in `installations.paths`, this cached JBR is
REDUNDANT — Gradle's daemon can find JetBrains 21 at the AS path.
You may evict the cache to reclaim 1.6 GB of disk space:

```bash
./gradlew --stop             # kill any running daemon so its locks release
rm -rf ~/.gradle/jdks/       # safe; auto-download=false blocks re-downloads
./gradlew --version          # Launcher = Temurin 17, Daemon = JetBrains 21 (AS path)
```

If you DON'T evict, builds still work — Gradle picks the required
JDK from any source. If you DO evict, verify `:app:tasks` still
exits 0 to confirm the daemon was happy spawning on the AS path.

#### Verified end-state (2026-06-21)

- `./gradlew --version` → Launcher JVM: `17.0.19 (Eclipse Adoptium 17.0.19+10)`; Daemon JVM: `JetBrains 21` (matched to AS bundled JBR).
- `./gradlew :app:tasks` exit 0; `./gradlew :admin:tasks` exit 0.
- `./gradlew javaToolchains` lists both Temurin 17 and JetBrains 21 as enabled.
- AGP compile-actions run on Temurin 17 via toolchain resolution against `installations.paths`.

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

## 3. Release keystore

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

Then run **Steps 4–5** below to fill them.

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

## 4. `signing.properties` — all 4 keys

Open `C:\Users\RDP\Desktop\streamify\signing.properties` in your local editor
(NOT this chat — gitignored, secrets stay local). Set each:

```properties
# ── Release keystore ──
RELEASE_STORE_FILE=C:/Users/<you>/keystores/streamify-release.jks
RELEASE_STORE_PASSWORD=<your-strong-store-password>
RELEASE_KEY_ALIAS=release
RELEASE_KEY_PASSWORD=<your-strong-key-password>
```

### Acceptance check
- No quotes around the value
- No spaces around `=`
- All 4 keys non-empty
- `RELEASE_STORE_FILE` is a Windows absolute path with forward slashes

---

## 5. `secrets.properties` — all 4 keys

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

## 6. Build verification

After all four files exist with real values, run from your terminal (where
`signing.properties` and `secrets.properties` live):

### Preflight: confirm Gradle toolchain resolves

Before running the full assemble, verify both JDKs are reachable at the
paths declared in `gradle.properties`. This catches the "different Temurin
patch on CI" or "AS not installed" failure modes early, with a much clearer
error than waiting for `assembleRelease` to fail mid-build:

```bash
cd 'C:\Users\RDP\Desktop\streamify'
./gradlew --stop            # always — kill any stale daemon before reading toolchain
./gradlew javaToolchains    # lists Temurin 17 + AS-bundled JetBrains 21 when both are reachable
```

Expected output: a table with two rows — Temurin-Hotspot-JDK-17 (enabled,
located at the `hostedtoolcache` path) and JetBrains Runtime 21 (enabled,
located at the AS bundle path). If either row is missing, see the
`### Gradle Java toolchain` subsection of §1 above for recovery.

### Full assemble

```bash
cd 'C:\Users\RDP\Desktop\streamify'
./gradlew :app:assembleRelease -x lint --console=plain --no-daemon
```

### Expected happy path
```
> Task :app:verifyReleaseSecrets
verifyReleaseSecrets: signing.properties (4/4 keys) + secrets.properties (4/4 keys) all present ✓
> Task :app:packageRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 4m 12s
109 actionable tasks: 109 executed
```

The `verifyReleaseSecrets` task above is a pre-flight guard that short-circuits
the build with a clear error if any of the 8 keys is missing. Its reference
to `assembleRelease` is wrapped in `afterEvaluate { }` (in this same series
of commits) to avoid AGP's variant-task registration ordering — never call
`tasks.named("assembleRelease")` at file evaluation time without that wrapper.
Future readers: `git log --grep "verifyReleaseSecrets" --oneline` will show
the exact commit introducing this guard.

### Failure modes you might see

| Symptom | Likely cause | Fix |
|---|---|---|
| `:app:assembleRelease pre-flight failed.` listing gaps | One of the 8 keys is missing/blank | Edit the named `.properties` file, fill the key |
| `SigningConfig "release" is missing required property storeFile` (during `packageRelease`) | `RELEASE_STORE_FILE` is non-blank, but the path doesn't exist | Generate the keystore first (Step 3) |
| `keytool -genkeypair` doesn't print `[Storing …]` | Permission issue on `~/keystores/` | Run with `sudo`, or move to a path you can write |

---

## 7. Security checklist

| Do ✅ | Don't ❌ |
|---|---|
| Generate keystore + store in `~/keystores/` | Commit `*.jks` or `*.keystore` to git |
| Rotate (revoke + recreate) any leaked token immediately | Reuse a leaked token — assume it was harvested |
| Tail `git status` before every `git add -A` | `git push --force` to remote — could surface previously-leaked `signing.properties` in history |

### If you accidentally commit a `.properties` file
1. `git rm --cached signing.properties` (removes from index, not the file on disk)
2. Add it to `.gitignore` (already there, but verify)
3. `git commit --amend` (or new commit) to remove it from history
4. `git push` and hope no-one pulled (if they did, rotate every secret in the file immediately)
5. For an already-pushed leak: rotate every secret in the file immediately
   and push a `--force-with-lease` to overwrite the leaking commit

---

## 8. Local-development shortcut (skip most above)

If you only want to run the app for dev/debug work:

```bash
cd 'C:\Users\RDP\Desktop\streamify'
./gradlew :app:assembleDebug
```

The debug build:
- ✅ Uses the debug keystore (auto-managed, no setup)
- ✅ Reads `AppConfig.defaults().apiBaseUrl = "https://learngermanwith.fun"`
   when `secrets.properties` is absent (placeholder host — UI shows "no data
   loaded" gracefully)
- ✅ Firebase Cloud Messaging still works because `google-services.json` IS
   tracked in git

This is enough to explore the codebase and run UI flows.

---

## 9. Where to go from here

After all 8 secrets.properties + signing.properties keys are real and the
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
Mirror Steps 4–5 of this guide against your runner's secret store (GitHub
Actions → encrypted secrets, GitLab CI → masked variables, etc.). The
`.example` files in the repo are also CI-friendly placeholders; just
materialise the values from the runner's `env` at build time.

---

## Reference: file locations cheat sheet

| Path | What |
|---|---|
| `app/google-services.json` | Firebase project export (tracked) |
| `signing.properties` | Release keystore (4 keys, gitignored) |
| `signing.properties.example` | Empty template, committed |
| `secrets.properties` | 4 backend URLs (gitignored) |
| `secrets.properties.example` | Empty template, committed |
| `app/src/main/java/com/streamify/app/security/SecretsValidator.kt` | Boot-time placeholder detector |
| `app/build.gradle.kts` — end of file | `verifyReleaseSecrets` pre-flight (commit `6f3e7f7`) |
| `app/build/outputs/apk/release/app-release.apk` | Final Play-shippable APK |
| `app/build/outputs/bundle/release/app-release.aab` | Final Play App Bundle (preferred for Play Store) |
