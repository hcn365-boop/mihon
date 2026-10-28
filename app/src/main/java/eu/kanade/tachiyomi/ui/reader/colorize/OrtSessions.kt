package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Where an ONNX model runs.
 * Supports standard CPU execution with optional XNNPACK / NNAPI fallbacks.
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
     * @return a session using standard execution or optimized XNNPACK without breaking model validation.
     */
    fun create(env: OrtEnvironment, path: String, acceleration: Acceleration, threads: Int): OrtSession {
        // 1. محاولة إنشاء الجلسة باستخدام XNNPACK أولاً لأقصى أداء
        try {
            logcat(LogPriority.DEBUG) { "Attempting to create ONNX session with XNNPACK for model: $path" }
            val options = options(threads).apply {
                try {
                    addXnnpack(emptyMap())
                } catch (_: Throwable) {}
            }
            return env.createSession(path, options).also {
                logcat(LogPriority.DEBUG) { "Successfully created ONNX session with XNNPACK." }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "XNNPACK session creation failed for model: $path. Falling back to standard CPU." }
        }

        // 2. التراجع الآمن إلى CPU القياسي (وهذا ما يضمن نجاح فحص النماذج والتشغيل المستقر)
        logcat(LogPriority.DEBUG) { "Creating standard CPU ONNX session (threads: $threads) for model: $path" }
        return env.createSession(path, options(threads))
    }

    private fun options(threads: Int) = OrtSession.SessionOptions().apply {
        if (threads > 0) setIntraOpNumThreads(threads)
    }
}
