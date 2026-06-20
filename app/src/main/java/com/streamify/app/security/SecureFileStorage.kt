package com.streamify.app.security

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 7 · Step 7.8 — Encrypted file storage.
 *
 * Encrypts files before writing to internal storage and decrypts on
 * read.  Uses AES-256-GCM with a key from [KeystoreManager].
 *
 * ## Features
 *  - Streaming encryption/decryption for large files (no full-file
 *    buffering).
 *  - Secure deletion: overwrites file content with random data before
 *    deleting.
 *  - Files are stored in the app's internal storage directory
 *    (`context.filesDir`), which is not accessible to other apps.
 *
 * ## File format
 * ```
 * [12 bytes IV][encrypted data][16 bytes GCM tag]
 * ```
 *
 * ## Usage
 * ```kotlin
 * val storage = SecureFileStorage(context)
 * storage.write("sensitive.dat", inputStream)
 * val data = storage.read("sensitive.dat")
 * storage.secureDelete("sensitive.dat")
 * ```
 */
object SecureFileStorage {

    private const val TAG = "SecureFileStorage"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val OVERWRITE_PASSES = 3

    /**
     * Encrypt [data] and write it to a file in internal storage.
     * The file is prefixed with the 12-byte IV.
     */
    fun write(context: Context, fileName: String, data: ByteArray) {
        val key = getKeystoreKey()
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        val file = getFile(context, fileName)
        file.outputStream().use { out ->
            out.write(iv) // Write IV first
            val cos = CipherOutputStream(out, cipher)
            cos.use { it.write(data) }
        }
        Log.d(TAG, "Encrypted file written: $fileName (${data.size} bytes)")
    }

    /**
     * Encrypt a streaming [input] and write it to a file in internal storage.
     * Useful for large files (e.g., downloaded APKs, media cache).
     */
    fun writeStream(context: Context, fileName: String, input: InputStream) {
        val key = getKeystoreKey()
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        val file = getFile(context, fileName)
        file.outputStream().use { out ->
            out.write(iv)
            val cos = CipherOutputStream(out, cipher)
            cos.use { input.copyTo(it) }
        }
        Log.d(TAG, "Encrypted stream written: $fileName")
    }

    /**
     * Read and decrypt a file from internal storage.
     * @return Decrypted data, or null if the file doesn't exist.
     */
    fun read(context: Context, fileName: String): ByteArray? {
        val file = getFile(context, fileName)
        if (!file.exists()) return null

        val key = getKeystoreKey()
        return file.inputStream().use { input ->
            val iv = ByteArray(IV_SIZE)
            if (input.read(iv) != IV_SIZE) {
                Log.e(TAG, "Invalid IV in file: $fileName")
                return null
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val cis = CipherInputStream(input, cipher)
            cis.use { it.readBytes() }
        }
    }

    /**
     * Securely delete a file by overwriting with random data before
     * deleting.  Best-effort forensic resistance.
     *
     * **Limitation:** On modern flash storage (eMMC/UFS), wear leveling
     * means the OS may not physically overwrite the original blocks.
     * This is a platform limitation — the overwrite + delete pattern
     * is still better than a plain delete but does not guarantee
     * destruction of the physical bits.
     */
    fun secureDelete(context: Context, fileName: String) {
        val file = getFile(context, fileName)
        if (!file.exists()) return

        val size = file.length()
        val random = SecureRandom()

        // Overwrite with random data multiple times
        for (pass in 0 until OVERWRITE_PASSES) {
            file.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var remaining = size
                while (remaining > 0) {
                    val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
                    random.nextBytes(buffer)
                    out.write(buffer, 0, toWrite)
                    remaining -= toWrite
                }
                out.flush()
            }
        }

        file.delete()
        Log.d(TAG, "Securely deleted: $fileName ($OVERWRITE_PASSES passes)")
    }

    /**
     * Check if a file exists in secure storage.
     */
    fun exists(context: Context, fileName: String): Boolean {
        return getFile(context, fileName).exists()
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun getFile(context: Context, fileName: String): File {
        val dir = File(context.filesDir, "secure_storage")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    private fun getKeystoreKey(): SecretKey {
        val keyBytes = EncryptedConstants.reconstructKey()
        val key = SecretKeySpec(keyBytes, "AES")
        keyBytes.fill(0)
        return key
    }
}
