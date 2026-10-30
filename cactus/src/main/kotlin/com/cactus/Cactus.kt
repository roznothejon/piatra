package com.cactus

import android.system.Os

// Kotlin bindings to the Cactus engine (libcactus_engine.so, prebuilt C++).
// Trimmed from upstream cactus/src/androidMain/kotlin/com/cactus/Cactus.kt: only the calls
// Piatra uses. The engine has more (embeddings, RAG, indexes, telemetry); add them from
// upstream if a step needs one. The C side finds each `external fun` by its full name
// (Java_com_cactus_CactusJNI_nativeInit), so package, object and function names must not change.

// Called by the engine for each piece of text as it's produced (streaming output).
fun interface CactusTokenCallback {
    fun onToken(token: String, tokenId: Int)
}

private object CactusCpuJNI {
    init { System.loadLibrary("cactus_cpu") }
    @JvmStatic external fun nativeIsCactusSupported(): Boolean
}

// lazy: computed on first use, then remembered.
private val cactusSupported: Boolean by lazy {
    try {
        CactusCpuJNI.nativeIsCactusSupported()
    } catch (_: Throwable) {
        false // library missing, e.g. a non-ARM emulator
    }
}

// True if this phone's CPU has the instructions the engine needs. Check before loading a model.
fun isCactusSupported(): Boolean = cactusSupported

// `object` + `init`: the library loads the first time any of these functions is called.
private object CactusJNI {
    init {
        // Piatra is fully local. The engine can send usage telemetry to Cactus's servers and
        // "hand off" hard inputs (audio included) to their cloud API. These environment
        // variables, read by the engine, switch both off. Set before the engine loads.
        Os.setenv("CACTUS_NO_CLOUD_TELE", "1", true)
        Os.setenv("CACTUS_DISABLE_CLOUD_HANDOFF", "1", true)
        System.loadLibrary("cactus_engine")
    }

    @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeComplete(handle: Long, messagesJson: String, responseBuffer: ByteArray, optionsJson: String?, toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): Int
    @JvmStatic external fun nativeTranscribe(handle: Long, audioPath: String?, prompt: String, responseBuffer: ByteArray, optionsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): Int
    @JvmStatic external fun nativeGetLastError(): String
}

// The engine reports errors as a negative return code; the message is fetched separately.
private fun check(rc: Int) {
    if (rc < 0) throw RuntimeException(CactusJNI.nativeGetLastError().ifEmpty { "Unknown error" })
}

// The engine writes a C string (ending in a 0 byte) into our buffer.
private fun ByteArray.decodeResponse(): String {
    val nul = indexOf(0)
    return decodeToString(0, if (nul >= 0) nul else size)
}

// Loads a model from its folder. Returns a handle: really a pointer to the model in native
// memory. Pass it to the other calls, and free it with cactusDestroy (the garbage collector
// can't see native memory).
fun cactusInit(modelPath: String, corpusDir: String? = null, cacheIndex: Boolean = false): Long {
    val handle = CactusJNI.nativeInit(modelPath, corpusDir, cacheIndex)
    if (handle == 0L) throw RuntimeException(CactusJNI.nativeGetLastError().ifEmpty { "Failed to initialize model" })
    return handle
}

fun cactusDestroy(handle: Long) = CactusJNI.nativeDestroy(handle)

// Clears the model's conversation state.
fun cactusReset(handle: Long) = CactusJNI.nativeReset(handle)

// Asks a running call (on another thread) to stop early.
fun cactusStop(handle: Long) = CactusJNI.nativeStop(handle)

// Language model: messages (and tools) in as JSON, the response JSON out.
fun cactusComplete(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray? = null): String {
    val buffer = ByteArray(1024 * 1024)
    check(CactusJNI.nativeComplete(handle, messagesJson, buffer, optionsJson, toolsJson, callback, pcmData))
    return buffer.decodeResponse()
}

// Speech to text, from a WAV file (audioPath) or raw 16 kHz 16-bit mono PCM (pcmData). Returns JSON.
fun cactusTranscribe(handle: Long, audioPath: String?, prompt: String, optionsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?): String {
    val buffer = ByteArray(1024 * 1024)
    check(CactusJNI.nativeTranscribe(handle, audioPath, prompt, buffer, optionsJson, callback, pcmData))
    return buffer.decodeResponse()
}

fun cactusGetLastError(): String = CactusJNI.nativeGetLastError()
