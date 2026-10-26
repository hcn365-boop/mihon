package eu.kanade.tachiyomi.ui.reader.colorize

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Where an ONNX model runs. NNAPI lets Android hand the work to the phone's GPU, NPU or DSP when the
 * maker provided a driver for it; operations it cannot run still fall back to the CPU.
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
     * @return a session using [acceleration], or a plain CPU session if the accelerator is not available
     */
    fun create(env: OrtEnvironment, path: String, acceleration: Acceleration, threads: Int): OrtSession {
        if (acceleration == Acceleration.NNAPI) {
            try {
                logcat(LogPriority.DEBUG) { "Attempting to create ONNX session with NNAPI acceleration..." }
                return env.createSession(path, options(threads).apply { addNnapi() }).also {
                    logcat(LogPriority.DEBUG) { "Successfully created ONNX session with NNAPI." }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "NNAPI acceleration failed, falling back to CPU session" }
            }
        }
        logcat(LogPriority.DEBUG) { "Creating ONNX session with CPU acceleration (threads: $threads)" }
        return env.createSession(path, options(threads))
    }

    private fun options(threads: Int) = OrtSession.SessionOptions().apply {
        if (threads > 0) setIntraOpNumThreads(threads)
    }
}
