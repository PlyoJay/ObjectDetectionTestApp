package com.samin.objectdetection.mlkit

import android.content.Context
import android.os.SystemClock
import java.io.File

/** One app-process owner, with no Activity reference or filesystem IO in its constructor. */
internal object MlKitDiagnosticLogs {
    @Volatile private var recorder: MlKitDiagnosticLogRecorder? = null

    fun peek(): MlKitDiagnosticLogRecorder? = recorder

    fun get(context: Context): MlKitDiagnosticLogRecorder = recorder ?: synchronized(this) {
        recorder ?: context.applicationContext.let { app ->
            MlKitDiagnosticLogRecorder(File(app.filesDir, "mlkit_diagnostics"), File(app.cacheDir, "shared_logs"),
                elapsedNs = SystemClock::elapsedRealtimeNanos).also { recorder = it }
        }
    }
}
