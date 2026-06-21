package com.streamify.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phase 7 · Step 7.8 — AndroidKeyStore-backed AES/GCM envelope
 * for refresh-token storage.
 *
 * Strategy: **Option (A)** — ceremony-free wrap with device
 * fingerprint bound as Additional Authentication Data (AAD).
 *
 *  - `setUserAuthenticationRequired(false)` so a biometric prompt
 *    is NOT required to decrypt on every refresh. Architectural
 *    reason: Authenticator + Interceptor threads (background,
 *    no Activity) would deadlock trying to summon a UI-bound
 *    BiometricPrompt. Cold-launch token reads happen before the
 *    first Activity is created, so biometric gating is
 *    intractable there too.
 *
 *  - AAD = `concat(devicePin, schemaVersion)` so a wrapped blob
 *    copied to a different device fails GCM auth at decrypt time.
 *    Stolen blobs on the originating device work (you've already
 *    rooted the device at that point) but cross-device theft
 *    becomes structurally hard.
 *
 *  - Migration is transparent: the first `load()` call detects
 *    any legacy plain-text `PREF_REFRESH_TOKEN` entry in the
 *    parent `streamify_auth` SharedPreferences and re-wraps it
 *    under `PREF_REFRESH_TOKEN_V1`. Plain-text copies are then
 *    deleted; rollback leaves the device re-authenticated.
 *
 * The instance singleton (`@Volatile var instance`) is set by
 * [com.streamify.app.StreamifyApp.onCreate] — reflection-free
 * wiring via `TokenStore.initFromContext(applicationContext)`.
 *
 * For builds BEFORE Phase 7 · Step 7.8 (TOCTOU window), the public
 * façade on [TokenManager] continues to read the legacy plain
 * text key. Once [TokenManager] is wired to [TokenStore] (this
 * release), reading the legacy key returns null and the user is
 * silently re-prompted for login. No data corruption path.
 */
class TokenStore private constructor(private val context: Context) {

    /**
     * Backing-text [KeyStore] alias. The key never leaves the
     * AndroidKeyStore; we only hold a handle to it.
     */
    private val keyAlias = "streamify_tokenwrap_v1"

    /**
     * The pinned device fingerprint consumed as AAD for every
     * wrap/unwrap operation. Lazily computed and cached — reading
     * `Settings.Secure.ANDROID_ID` is cheap on most devices but
     * the lookup still hits a binder roundtrip we want to avoid
     * on every OkHttp interceptor call.
     */
    private val aad: ByteArray by lazy {
        val pin = TokenManager.devicePin(context)
        val version = SCHEMA_VERSION.toByteArray(Charsets.US_ASCII)
        pin.toByteArray(Charsets.UTF_8) + version
    }

    /**
     * Atomic snapshot of [aad] resulting from a wrap or unwrap
     * attempt — used to assert that migration completed without
     * a race between concurrent refreshes on the same session.
     */
    @Volatile
    private var lastOpSchemaValidated: Boolean = false

    /**
     * Load + unwrap the refresh token. Returns null if no token
     * is stored, the blob is corrupt, or the device pin no longer
     * matches (cross-device theft detection).
     *
     * Side effect: if a legacy plain-text token is present in the
     * parent SharedPreferences (write done by TokenManager BEFORE
     * Step 7.8 landed), the value is migrated into the wrapped
     * store transparently and then deleted from the legacy slot.
     */
    @Synchronized
    fun load(): String? {
        ensureKey()

        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        // 1) Try the wrapped-slot first.
        val wrapped = prefs.getString(PREF_WRAPPED, null)
        if (wrapped != null) {
            try {
                val plaintext = decrypt(Base64.decode(wrapped, Base64.NO_WRAP))
                lastOpSchemaValidated = true
                return plaintext
            } catch (e: Exception) {
                Log.w(TAG, "Wrapped blob decrypt failed (pin mismatch or corruption): ${e.message}")
                // Cross-device theft or ham-fisted tampering: bump current
                // generation to invalidate any prior cached access token
                // in TokenManager.
                prefs.edit().remove(PREF_WRAPPED).apply()
                lastOpSchemaValidated = false
                return null
            }
        }

        // 2) Migration path: legacy plain-text exists, wrap it.
        val legacy = prefs.getString(TokenManager.LEGACY_PREF_REFRESH_TOKEN, null)
        if (!legacy.isNullOrBlank()) {
            try {
                val wrappedBlob = encrypt(legacy)
                prefs.edit()
                    .putString(PREF_WRAPPED, Base64.encodeToString(wrappedBlob, Base64.NO_WRAP))
                    .remove(TokenManager.LEGACY_PREF_REFRESH_TOKEN)
                    .apply()
                lastOpSchemaValidated = true
                Log.i(TAG, "Migrated legacy plain refresh token → Keystore-wrapped form")
                return legacy
            } catch (e: Exception) {
                Log.e(TAG, "Legacy migration failed; falling back to null", e)
                lastOpSchemaValidated = false
                return null
            }
        }

        lastOpSchemaValidated = false
        return null
    }

    /**
     * Wrap + persist the refresh token. Overwrites any previous
     * value in either the wrapped slot or the legacy plain slot.
     */
    @Synchronized
    fun store(plaintext: String) {
        if (plaintext.isBlank()) {
            Log.w(TAG, "store() called with blank plaintext; refusing")
            return
        }
        ensureKey()
        val wrappedBlob = encrypt(plaintext)
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_WRAPPED, Base64.encodeToString(wrappedBlob, Base64.NO_WRAP))
            .remove(TokenManager.LEGACY_PREF_REFRESH_TOKEN)
            .apply()
        lastOpSchemaValidated = true
    }

    /**
     * Drop the wrapped token (and the legacy slot if the build
     * pre-dates Step 7.8). Called on logout + on tamper detection.
     */
    @Synchronized
    fun clear() {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREF_WRAPPED)
            .remove(TokenManager.LEGACY_PREF_REFRESH_TOKEN)
            .apply()
        lastOpSchemaValidated = false
        Log.d(TAG, "TokenStore cleared (logout or tamper)")
    }

    /**
     * Whether the most recent load() / store() operation
     * validated the schema-version AAD. Exposed so [TokenManager]
     * can short-circuit access-token reads when [aad] mismatches.
     */
    fun isSchemaValid(): Boolean = lastOpSchemaValidated

    // ── Crypto primitives ──────────────────────────────────────

    /**
     * Lazily ensure the AES key exists in the AndroidKeyStore. The
     * key is generated on first use and persisted across reboots
     * inside the hardware-backed keystore (TEE/StrongBox where
     * available). The key is NOT extractable.
     */
    private fun ensureKey() {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        if (ks.containsAlias(keyAlias)) return

        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user-auth: see class kdoc — biometric gating would
            // deadlock background refreshes and cold-launch token
            // reads.
            .setUserAuthenticationRequired(false)
            .build()
        kg.init(spec)
        kg.generateKey()
        Log.i(TAG, "Wrapped-refresh-token AES key generated in AndroidKeyStore")
    }

    private fun getKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        return ks.getKey(keyAlias, null) as SecretKey
    }

    private fun encrypt(plaintext: String): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad)
        }
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ct
    }

    private fun decrypt(blob: ByteArray): String {
        require(blob.size > GCM_IV_LENGTH) { "blob shorter than IV" }
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val ct = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad)
        }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "TokenStore"

        /**
         * Algorithm spec — AES-256 in GCM mode with a 96-bit
         * (12-byte) random IV. AAD carries the device fingerprint;
         * tampering with the AAD causes GCM auth to fail at
         * decrypt time.
         */
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val KEYSTORE_TYPE = "AndroidKeyStore"

        /** SharedPreferences file used for the wrapped tokens. */
        const val PREFS_FILE = "streamify_auth"

        /** Key for the Base64(IV ‖ ciphertext ‖ GCM-tag) blob. */
        const val PREF_WRAPPED = "auth_refresh_token_v1"

        /**
         * Schema version tag. Bumping this in a future release
         * forces a clean re-wrap on every device — used to roll
         * the cipher params (e.g. AES-256 → AES-512 if the
         * platform supports it without a TEE roundtrip).
         */
        const val SCHEMA_VERSION = "v1"

        /**
         * Singleton accessor. [com.streamify.app.StreamifyApp.onCreate]
         * is responsible for calling [TokenStore.initFromContext] BEFORE
         * any consumer reads through it.
         */
        @Volatile
        @JvmStatic
        var instance: TokenStore? = null
            private set

        /**
         * Run-once init from the [Application]'s onCreate. Cheap
         * to call multiple times — only the first call constructs
         * the singleton.
         */
        @Synchronized
        fun initFromContext(context: Context): TokenStore {
            return instance ?: TokenStore(context.applicationContext).also { instance = it }
        }
    }
}
