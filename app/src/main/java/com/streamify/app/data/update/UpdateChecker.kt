package com.streamify.app.data.update

import com.streamify.app.data.remote.AppConfig

/**
 * Phase 6 · Step 6.2 — semver comparator + decision reducer.
 *
 * `AppConfig` carries two independent server-driven fields:
 *  - [AppConfig.minAppVersion]   — a floor; below this triggers [UpdateDecision.Forced].
 *  - [AppConfig.latestVersion]   — the newest version the admin uploaded; below
 *                                  this but above the floor triggers [UpdateDecision.Optional].
 *
 * If [AppConfig.updateUrl] is blank we treat the decision as [UpdateDecision.UpToDate]
 * regardless of version diff — a server that emits a version bump without giving us
 * an APK to install is treated as "no update available".
 *
 * Semver parsing pads missing parts with 0 ("1.0" → [1,0,0]) so a server that
 * emits "1.2" and a local build of "1.2.1" doesn't crash on `IndexOutOfBoundsException`
 * during left-to-right zip-compare (thinker-gemini step-6.2 review point G).
 */
object UpdateChecker {

    /**
     * Parse a dotted version string into a padded-`IntArray`. Non-integer
     * parts collapse to 0 so a malformed "v1.0-beta" still produces
     * `[0, 0]` instead of crashing.
     */
    fun parseVersion(raw: String): IntArray {
        if (raw.isBlank()) return intArrayOf(0, 0, 0)
        val parts = raw.trimStart('v', 'V')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
        // pad to 3 parts: major.minor.patch
        return when (parts.size) {
            3 -> parts.toIntArray()
            2 -> intArrayOf(parts[0], parts[1], 0)
            1 -> intArrayOf(parts[0], 0, 0)
            else -> parts.take(3).toIntArray()
        }
    }

    /**
     * Compare two dotted version strings. Returns a negative number if
     * [a] < [b], positive if [a] > [b], zero if equal.
     */
    fun compare(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Decide whether [currentVersion] qualifies for any update relative
     * to the server-supplied [config].
     */
    fun check(currentVersion: String, config: AppConfig): UpdateDecision {
        // No APK URL → server wants us to behave as up-to-date.
        if (config.updateUrl.isBlank()) return UpdateDecision.UpToDate

        val min = config.minAppVersion.ifBlank { currentVersion }
        val latest = config.latestVersion.ifBlank { min }

        // Forced: local is strictly below the floor.
        if (compare(currentVersion, min) < 0) {
            return UpdateDecision.Forced(minVersion = min, apkUrl = config.updateUrl)
        }
        // Optional: local is strictly below the latest, but at-or-above the floor.
        if (compare(currentVersion, latest) < 0) {
            return UpdateDecision.Optional(
                latestVersion = latest,
                changelog = "",
                apkUrl = config.updateUrl
            )
        }
        return UpdateDecision.UpToDate
    }
}
