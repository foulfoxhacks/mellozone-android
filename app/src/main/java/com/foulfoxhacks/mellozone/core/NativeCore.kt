package com.foulfoxhacks.mellozone.core

/**
 * JNI entry point into the Rust engine (`libmellozone_core.so`).
 *
 * Every call is blocking and thread-safe. Use [Core], which moves calls off
 * the main thread and decodes the JSON envelope.
 */
object NativeCore {
    init {
        System.loadLibrary("mellozone_core")
    }

    @JvmStatic
    external fun nativeCall(method: String, payload: String): String?
}
