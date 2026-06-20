/**
 * Phase 7 · Step 7.4 — Native security checks.
 *
 * All JNI bindings are registered via JNI_OnLoad / RegisterNatives so
 * the exported symbol table contains only `JNI_OnLoad` (and that is
 * stripped in release builds by the linker flags in CMakeLists.txt).
 *
 * Internal C++ function names use short, opaque names to further
 * frustrate static analysis.
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <dirent.h>
#include <errno.h>

#define TAG "NativeSecurity"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ── Anti-debugging: ptrace(PTRACE_TRACEME) ──────────────────────────────
// If a debugger is already attached, ptrace returns -1.
// We call this early in JNI_OnLoad so the check happens before any
// security-sensitive code runs.
static int xs_antidebug(void) {
    if (ptrace(PTRACE_TRACEME, 0, 1, 0) < 0) {
        LOGW("ptrace(PTRACE_TRACEME) failed — debugger detected");
        return 1; // debugger present
    }
    // Note: after PTRACE_TRACEME, the parent is the tracer.
    // We cannot detach from ourselves; only the tracer can PTRACE_DETACH.
    // The TRACEME call itself is sufficient — if it succeeds, no debugger.
    return 0;
}

// ── Root detection ───────────────────────────────────────────────────────
// Checks for su binaries, Magisk, and common root management paths.
static const char *SU_PATHS[] = {
    "/system/xbin/su",
    "/system/bin/su",
    "/sbin/su",
    "/su/bin/su",
    "/system/su",
    "/system/bin/.ext/.su",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/data/local/su",
    "/system/app/Superuser.apk",
    "/system/app/SuperSU.apk",
    "/system/app/com.topjohnwu.magisk",
    nullptr
};

static int xs_check_root(void) {
    for (int i = 0; SU_PATHS[i] != nullptr; i++) {
        if (access(SU_PATHS[i], F_OK) == 0) {
            LOGW("Root indicator found: %s", SU_PATHS[i]);
            return 1;
        }
    }
    // Check for Magisk's /data/adb directory
    struct stat st;
    if (stat("/data/adb", &st) == 0 && S_ISDIR(st.st_mode)) {
        // /data/adb exists on rooted Magisk devices.  We also check
        // for the Magisk-specific "magisk" subdirectory to avoid
        // false positives on stock devices that use /data/adb for
        // other purposes (e.g. adb keys).
        if (stat("/data/adb/magisk", &st) == 0) {
            LOGW("Magisk directory detected: /data/adb/magisk");
            return 1;
        }
    }
    return 0;
}

// ── Emulator detection ──────────────────────────────────────────────────
// Checks system properties and files that indicate an emulator.
static int xs_check_emulator(JNIEnv *env) {
    // Check ro.hardware for known emulator values.
    // We use __system_property_get which is available on all API levels.
    char value[92];
    memset(value, 0, sizeof(value));

    // Common emulator hardware identifiers
    const char *emu_props[] = {
        "ro.hardware",
        "ro.product.model",
        "ro.product.device",
        "ro.product.brand",
        "ro.product.name",
        nullptr
    };
    const char *emu_values[] = {
        "goldfish", "ranchu", "vbox86", "nox", "sdk", "generic",
        "ttVM_Hdragon", "nox", "droid4x", "generic_x86",
        nullptr
    };

    for (int p = 0; emu_props[p] != nullptr; p++) {
        // Read the system property via JNI to avoid NDK API restrictions
        // on newer API levels.  For simplicity we use the C API which
        // is available on all our target API levels (23+).
        jclass sysClass = env->FindClass("android/os/SystemProperties");
        if (sysClass == nullptr) continue;

        jmethodID getMethod = env->GetStaticMethodID(
            sysClass, "get",
            "(Ljava/lang/String;)Ljava/lang/String;"
        );
        if (getMethod == nullptr) continue;

        jstring key = env->NewStringUTF(emu_props[p]);
        jstring val = (jstring)env->CallStaticObjectMethod(
            sysClass, getMethod, key
        );
        env->DeleteLocalRef(key);

        if (val != nullptr) {
            const char *cval = env->GetStringUTFChars(val, nullptr);
            if (cval != nullptr) {
                for (int v = 0; emu_values[v] != nullptr; v++) {
                    if (strstr(cval, emu_values[v]) != nullptr) {
                        LOGW("Emulator property detected: %s=%s", emu_props[p], cval);
                        env->ReleaseStringUTFChars(val, cval);
                        env->DeleteLocalRef(val);
                        return 1;
                    }
                }
                env->ReleaseStringUTFChars(val, cval);
            }
            env->DeleteLocalRef(val);
        }
    }

    // Check for emulator-specific files
    const char *emu_files[] = {
        "/dev/socket/qemud",
        "/dev/qemu_pipe",
        "/system/lib/libc_malloc_debug_qemu.so",
        "/sys/qemu_trace",
        nullptr
    };
    for (int i = 0; emu_files[i] != nullptr; i++) {
        if (access(emu_files[i], F_OK) == 0) {
            LOGW("Emulator file detected: %s", emu_files[i]);
            return 1;
        }
    }

    return 0;
}

// ── Hook detection: Frida / Xposed / Substrate ──────────────────────────
// Scans /proc/self/maps for injected libraries.
static int xs_check_hooks(void) {
    FILE *fp = fopen("/proc/self/maps", "r");
    if (fp == nullptr) return 0;

    char line[512];
    int found = 0;

    // Strings to search for — each identifies a known hooking framework.
    const char *patterns[] = {
        "frida",
        "xposed",
        "substrate",
        "libriru",
        "magisk",
        "gadget",      // Frida gadget (standalone injection)
        "libdexposed",
        "liblspd",
        nullptr
    };

    while (fgets(line, sizeof(line), fp) != nullptr) {
        for (int i = 0; patterns[i] != nullptr; i++) {
            if (strcasestr(line, patterns[i]) != nullptr) {
                LOGW("Hook framework detected in maps: %s (pattern: %s)",
                     line, patterns[i]);
                found = 1;
                goto done;
            }
        }
    }
done:
    fclose(fp);
    return found;
}

// ── APK signature verification ──────────────────────────────────────────
// Verifies the app's signing certificate SHA-256 fingerprint matches
// the expected value.  Returns JNI_TRUE if the signature is valid.
static const char *EXPECTED_SHA256 = nullptr; // populated from Kotlin side

static jboolean xs_verify_sig(JNIEnv *env, jobject /* thiz */,
                              jobject context, jstring expectedHash) {
    if (expectedHash == nullptr) return JNI_FALSE;

    // Get PackageManager.getPackageInfo(..., GET_SIGNATURES)
    jclass ctxClass = env->GetObjectClass(context);
    jmethodID getPkgMgr = env->GetMethodID(
        ctxClass, "getPackageManager",
        "()Landroid/content/pm/PackageManager;"
    );
    jobject pkgMgr = env->CallObjectMethod(context, getPkgMgr);
    if (pkgMgr == nullptr) return JNI_FALSE;

    // Get package name
    jmethodID getPkgName = env->GetMethodID(
        ctxClass, "getPackageName", "()Ljava/lang/String;"
    );
    jstring pkgName = (jstring)env->CallObjectMethod(context, getPkgName);
    if (pkgName == nullptr) return JNI_FALSE;

    // PackageInfo.signatures (deprecated but works on all API levels)
    jclass pmClass = env->GetObjectClass(pkgMgr);
    jmethodID getPkgInfo = env->GetMethodID(
        pmClass, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"
    );

    // PackageManager.GET_SIGNATURES = 0x40
    // PackageManager.GET_SIGNING_CERTIFICATES = 0x8000000 (API 28+)
    // We use GET_SIGNATURES for max compatibility (API 23+).
    jobject pkgInfo = env->CallObjectMethod(
        pkgMgr, getPkgInfo, pkgName, 0x40
    );
    if (pkgInfo == nullptr) return JNI_FALSE;

    // PackageInfo.signatures array
    jclass piClass = env->GetObjectClass(pkgInfo);
    jfieldID sigField = env->GetFieldID(piClass, "signatures",
                                        "[Landroid/content/pm/Signature;");
    jobjectArray sigs = (jobjectArray)env->GetObjectField(pkgInfo, sigField);
    if (sigs == nullptr || env->GetArrayLength(sigs) == 0) return JNI_FALSE;

    // Get first signature's SHA-256
    jobject sig = env->GetObjectArrayElement(sigs, 0);
    jclass sigClass = env->GetObjectClass(sig);
    jmethodID toByteArray = env->GetMethodID(sigClass, "toByteArray", "()[B");
    jbyteArray certBytes = (jbyteArray)env->CallObjectMethod(sig, toByteArray);
    if (certBytes == nullptr) return JNI_FALSE;

    // MessageDigest.getInstance("SHA-256")
    jclass mdClass = env->FindClass("java/security/MessageDigest");
    jmethodID getInstance = env->GetStaticMethodID(
        mdClass, "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;"
    );
    jstring algo = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(mdClass, getInstance, algo);
    env->DeleteLocalRef(algo);
    if (md == nullptr) return JNI_FALSE;

    // md.digest(certBytes)
    jmethodID digest = env->GetMethodID(mdClass, "digest", "([B)[B");
    jbyteArray hash = (jbyteArray)env->CallObjectMethod(md, digest, certBytes);
    if (hash == nullptr) return JNI_FALSE;

    // Convert hash to hex string
    jmethodID hexMethod = env->GetStaticMethodID(
        env->FindClass("com/sportstream/app/security/NativeSecurityManager"),
        "bytesToHex", "([B)Ljava/lang/String;"
    );
    jstring actualHash = (jstring)env->CallStaticObjectMethod(
        env->FindClass("com/sportstream/app/security/NativeSecurityManager"),
        hexMethod, hash
    );
    if (actualHash == nullptr) return JNI_FALSE;

    // Compare
    const char *actual = env->GetStringUTFChars(actualHash, nullptr);
    const char *expected = env->GetStringUTFChars(expectedHash, nullptr);
    jboolean match = (strcmp(actual, expected) == 0) ? JNI_TRUE : JNI_FALSE;
    env->ReleaseStringUTFChars(actualHash, actual);
    env->ReleaseStringUTFChars(expectedHash, expected);

    if (!match) {
        LOGW("APK signature mismatch!");
    }

    // Cleanup
    env->DeleteLocalRef(hash);
    env->DeleteLocalRef(md);
    env->DeleteLocalRef(certBytes);
    env->DeleteLocalRef(sig);
    env->DeleteLocalRef(sigs);
    env->DeleteLocalRef(pkgInfo);
    env->DeleteLocalRef(pkgName);
    env->DeleteLocalRef(pkgMgr);

    return match;
}

// ── Combined environment check ──────────────────────────────────────────
// Returns a bitmask of detected threats.
//   bit 0 = root detected
//   bit 1 = debugger detected
//   bit 2 = emulator detected
//   bit 3 = hook framework detected
static jint xs_check_all(JNIEnv *env, jobject /* thiz */) {
    jint flags = 0;
    if (xs_check_root())     flags |= (1 << 0);
    if (xs_antidebug())      flags |= (1 << 1);
    if (xs_check_emulator(env)) flags |= (1 << 2);
    if (xs_check_hooks())    flags |= (1 << 3);
    return flags;
}

// ── RegisterNatives table ────────────────────────────────────────────────
// Maps Kotlin external function names to our obfuscated C++ functions.
// The Kotlin side declares these as `external fun` in NativeSecurityManager;
// the actual C++ function names are unrelated, frustrating static analysis.
static const JNINativeMethod methods[] = {
    {(char *)"nativeCheckEnvironment",
     (char *)"(Landroid/content/Context;)I",
     (void *)xs_check_all},
    {(char *)"nativeVerifySignature",
     (char *)"(Landroid/content/Context;Ljava/lang/String;)Z",
     (void *)xs_verify_sig},
};

// ── JNI_OnLoad ──────────────────────────────────────────────────────────
// Called automatically when System.loadLibrary("native_security") fires.
// Registers all native methods and runs the anti-debug check.
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Register native methods
    jclass clazz = env->FindClass(
        "com/sportstream/app/security/NativeSecurityManager"
    );
    if (clazz == nullptr) {
        LOGW("JNI_OnLoad: NativeSecurityManager class not found");
        return JNI_ERR;
    }

    jint rc = env->RegisterNatives(
        clazz,
        methods,
        sizeof(methods) / sizeof(methods[0])
    );
    if (rc < 0) {
        LOGW("JNI_OnLoad: RegisterNatives failed");
        return JNI_ERR;
    }

    // Run anti-debug check immediately at load time
    xs_antidebug();

    LOGD("JNI_OnLoad: native_security loaded, %zu methods registered",
         sizeof(methods) / sizeof(methods[0]));

    env->DeleteLocalRef(clazz);
    return JNI_VERSION_1_6;
}
