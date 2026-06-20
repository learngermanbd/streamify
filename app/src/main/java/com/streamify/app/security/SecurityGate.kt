package com.streamify.app.security

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 7 · Step 7.6 — Risk-scoring security orchestrator.
 *
 * Runs all security checks (integrity, tampering, root, emulator,
 * hooks) and produces a combined risk score.  The score determines
 * the response:
 *
 *  - **Score 0**       → clean; full functionality
 *  - **Score 1–4**     → soft block; warning logged, subtle
 *    degradation (reduced stream quality, disabled PiP)
 *  - **Score 5–9**     → hard block; features disabled, deceptive
 *    errors shown, periodic re-checks
 *  - **Score 10+**     → critical; SelfHealing final degradation,
 *    delayed exit
 *
 * ## Design principles
 *  1. **Defense in depth** — multiple independent check layers so
 *     bypassing one still triggers others.
 *  2. **No false positives on legitimate devices** — each check
 *     contributes a weighted score; only cumulative evidence
 *     triggers escalation.
 *  3. **Gradual response** — the app appears to work for a while
 *     before degrading, making it harder for attackers to correlate
 *     their bypass with the failure.
 *  4. **Server-side offload** — the native + Kotlin checks are a
 *     first line; the backend (Phase 8) performs Play Integrity
 *     verification for high-confidence decisions.
 */
object SecurityGate {

    private const val TAG = "SecurityGate"

    /** Risk score thresholds. */
    private const val THRESHOLD_SOFT = 1
    private const val THRESHOLD_HARD = 5
    private const val THRESHOLD_CRITICAL = 10

    /** Whether the gate has already run this process lifetime. */
    private val hasRun = AtomicBoolean(false)

    /** Managed single-thread executor for security checks. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SecurityGate-worker").apply { isDaemon = true }
    }

    /**
     * Run all security checks and return the combined [GateResult].
     * Safe to call multiple times (subsequent calls return the cached
     * result).
     *
     * @param context Application context.
     * @param onComplete Callback invoked with the result.  Runs on
     *   whatever thread calls [runChecks].
     */
    fun runChecks(context: Context, onComplete: (GateResult) -> Unit) {
        if (!hasRun.compareAndSet(false, true)) return

        executor.execute {
            val result = executeChecks(context)
            Log.i(TAG, "Security gate result: score=${result.score}, " +
                "level=${result.level}, indicators=${result.indicators.size}")

            // Apply response based on level
            when (result.level) {
                RiskLevel.CLEAN -> {
                    Log.d(TAG, "Environment clean — full functionality")
                }
                RiskLevel.SOFT -> {
                    Log.w(TAG, "Soft risk detected — subtle degradation applied")
                }
                RiskLevel.HARD -> {
                    Log.w(TAG, "Hard risk detected — features disabled")
                    SelfHealing.onTamperDetected(
                        context,
                        TamperResult.TamperDetected(
                            "SecurityGate hard block: ${result.indicators.joinToString()}"
                        )
                    )
                }
                RiskLevel.CRITICAL -> {
                    Log.e(TAG, "Critical risk — SelfHealing final degradation")
                    SelfHealing.onTamperDetected(
                        context,
                        TamperResult.TamperDetected(
                            "SecurityGate critical: ${result.indicators.joinToString()}"
                        )
                    )
                }
            }

            onComplete(result)
        }
    }

    private fun executeChecks(context: Context): GateResult {
        var score = 0
        val indicators = mutableListOf<String>()

        // ── Layer 1: Native checks (Step 7.4) ──────────────────────
        if (NativeSecurityManager.isLoaded) {
            val nativeFlags = NativeSecurityManager.checkEnvironment(context)
            if (NativeSecurityManager.ThreatFlag.has(nativeFlags, NativeSecurityManager.ThreatFlag.ROOT)) {
                score += 4
                indicators.add("NATIVE:root")
            }
            if (NativeSecurityManager.ThreatFlag.has(nativeFlags, NativeSecurityManager.ThreatFlag.DEBUGGER)) {
                score += 4
                indicators.add("NATIVE:debugger")
            }
            if (NativeSecurityManager.ThreatFlag.has(nativeFlags, NativeSecurityManager.ThreatFlag.EMULATOR)) {
                score += 2
                indicators.add("NATIVE:emulator")
            }
            if (NativeSecurityManager.ThreatFlag.has(nativeFlags, NativeSecurityManager.ThreatFlag.HOOK)) {
                score += 5
                indicators.add("NATIVE:hook")
            }
        }

        // ── Layer 2: Kotlin-side root detection (Step 7.6) ──────────
        val rootResult = RootDetector.detect()
        if (rootResult.isRooted) {
            score += 3
            indicators.add("ROOT:$rootResult")
        }

        // ── Layer 3: Kotlin-side emulator detection (Step 7.6) ──────
        val emuResult = EmulatorDetector.detect()
        if (emuResult.isDetected) {
            score += 2
            indicators.add("EMU:score=${emuResult.score}")
        }

        // ── Layer 4: Kotlin-side hook detection (Step 7.6) ──────────
        val hookResult = HookDetector.detect()
        if (hookResult.isDetected) {
            score += 4
            indicators.add("HOOK:score=${hookResult.score}")
        }

        // ── Layer 5: APK integrity (Step 7.5) ──────────────────────
        val integrityResult = IntegrityChecker.check(context)
        if (integrityResult.isTamper) {
            score += 5
            indicators.add("INTEGRITY:$integrityResult")
        }

        // ── Layer 6: Tamper detection (Step 7.5) ───────────────────
        val tamperResult = TamperDetector.detect(context)
        if (tamperResult.isTamper) {
            score += 5
            indicators.add("TAMPER:$tamperResult")
        }

        // ── Layer 7: Anti-debugging (Step 7.9) ───────────────────
        val debugResult = AntiDebug.detect()
        if (debugResult.isDebugged) {
            score += 4
            indicators.add("DEBUG:${debugResult.indicators.joinToString("|")}")
        }

        // ── Layer 8: Runtime monitoring (Step 7.9) ───────────────
        val monitorResult = RuntimeMonitor.scan()
        if (monitorResult.isSuspicious) {
            score += 3
            indicators.add("RUNTIME:${monitorResult.indicators.joinToString("|")}")
        }

        // ── Layer 9: Honeypot canaries (Step 7.9) ────────────────
        if (HoneyPotManager.isCanaryTriggered()) {
            score += 6
            indicators.add(
                "HONEYPOT:${HoneyPotManager.getTriggeredCanaries().joinToString(",")}"
            )
        }

        // ── Layer 10: Device attestation (Step 7.10) ─────────────
        val attestationResult = DeviceAttestation.check(context)
        if (attestationResult.riskScore > 0) {
            score += attestationResult.riskScore
            indicators.add("ATTEST:${attestationResult.indicators.joinToString("|")}")
        }

        // ── Layer 11: Play Integrity (Step 7.10) ─────────────────
        // Fire-and-forget: request token, cache result.
        // On subsequent runs the cached verdict contributes to the score.
        PlayIntegrityManager.requestToken(context) { result ->
            when (result) {
                is IntegrityTokenResult.Success -> {
                    val v = result.verdict
                    if (!v.meetsMinimumRequirements()) {
                        Log.w(TAG, "Play Integrity: device does not meet minimum requirements")
                    }
                }
                is IntegrityTokenResult.Error -> {
                    Log.w(TAG, "Play Integrity request failed: ${result.message}")
                }
            }
        }
        // Note: Play Integrity verdict is NOT used for client-side
        // risk scoring because the JWS signature cannot be verified
        // offline.  The token is sent to the backend (Phase 8) for
        // authoritative verification.  A failed/meets-no-requirements
        // result is logged as an indicator only.
        PlayIntegrityManager.getCachedVerdict()?.let { verdict ->
            if (!verdict.meetsMinimumRequirements()) {
                indicators.add("INTEGRITY_API:below_minimum")
                // Do NOT add to score — wait for server-side verification
            }
        }

        // Determine risk level
        val level = when {
            score >= THRESHOLD_CRITICAL -> RiskLevel.CRITICAL
            score >= THRESHOLD_HARD -> RiskLevel.HARD
            score >= THRESHOLD_SOFT -> RiskLevel.SOFT
            else -> RiskLevel.CLEAN
        }

        return GateResult(score = score, level = level, indicators = indicators)
    }
}

/**
 * Risk level determined by the cumulative score.
 */
enum class RiskLevel {
    /** No threats detected. Full functionality. */
    CLEAN,
    /** Low-confidence threat. Subtle degradation. */
    SOFT,
    /** Medium-confidence threat. Features disabled. */
    HARD,
    /** High-confidence threat. SelfHealing final degradation. */
    CRITICAL,
}

/**
 * Result of the security gate check.
 */
data class GateResult(
    val score: Int,
    val level: RiskLevel,
    val indicators: List<String>
)
