# =========================================================================
# Phase 7 · Step 7.4 — Native security library build configuration
# =========================================================================
cmake_minimum_required(VERSION 3.22.1)
project("native_security" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Linker hardening ─────────────────────────────────────────────────────
# -Wl,--strip-all          : strip all symbols from the .so.
# -Wl,--gc-sections        : garbage-collect unused sections.
# -Wl,--exclude-libs,ALL   : do not export any symbols from static libs.
set(CMAKE_SHARED_LINKER_FLAGS
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--strip-all -Wl,--gc-sections -Wl,--exclude-libs,ALL"
)

# ── Source files ─────────────────────────────────────────────────────────
add_library(native_security SHARED native_security.cpp)

# ── Aggressive hardening flags ───────────────────────────────────────────
# Placed AFTER add_library so the target exists.
# -fvisibility=hidden    : all symbols hidden by default (only JNI_OnLoad
#                          is exported via __attribute__((visibility))).
# -fno-rtti              : disable RTTI (smaller binary, no typeinfo leaks).
# -fno-exceptions        : disable exceptions (smaller binary, no unwind
#                          tables that reveal control flow).
# -ffunction-sections    : each function gets its own section so
#                          --gc-sections / --exclude-libs can strip them.
# -fdata-sections        : same for data.
# -fstack-protector-strong : stack canary to detect buffer overflows.
target_compile_options(native_security PRIVATE
    -fvisibility=hidden
    -fno-rtti
    -fno-exceptions
    -ffunction-sections
    -fdata-sections
    -fstack-protector-strong
)

# ── System libraries ─────────────────────────────────────────────────────
find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(native_security ${log-lib} ${android-lib})
