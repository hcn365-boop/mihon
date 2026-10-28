package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Where an ONNX model runs. XNNPACK optimizes execution for mobile processors,
 * providing high performance on CPU/mobile architectures.
 */
enum class Acceleration {
    CPU,
    NNAPI,
    ;

    companion object {
        fun fromName(name: String?): Acceleration =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CPU
    }
}

internal object OrtSessions {

    /**
     * @param threads CPU threads for the parts that run on the CPU; 0 lets ONNX Runtime decide
     * @return a session optimized with XNNPACK and thread configurations.
     */
    fun create(env: OrtEnvironment, path: String, acceleration: Acceleration, threads: Int): OrtSession {
        logcat(LogPriority.DEBUG) { "Preparing to create ONNX session for model: $path with threads: $threads" }
        return env.createSession(path, options(threads))
    }

    private fun options(threads: Int) = OrtSession.SessionOptions().apply {
        if (threads > 0) setIntraOpNumThreads(threads)
        try {
            // محاولة تفعيل محرك التسريع المحمول XNNPACK
            addXnnpack()
            logcat(LogPriority.DEBUG) { "XNNPACK acceleration was successfully enabled for this session." }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "XNNPACK initialization failed, falling back to standard CPU execution." }
        }
    }
}
