package com.samin.objectdetection.settings

import android.content.Context
import com.samin.objectdetection.detector.ModelAssets
import com.samin.objectdetection.detector.ModelConfig
import com.samin.objectdetection.detector.ModelIdentity
import com.samin.objectdetection.detector.YoloModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Test-app model inspection and apply validation share one serialized native operation. */
internal class SettingsModelInspector(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()

    suspend fun inspect(model: YoloModel, threads: Int = 1, useXnnpack: Boolean = false): Result<ModelIdentity> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { ModelAssets.inspect(context, model, threads, useXnnpack) }
            }
        }
}

internal fun ModelIdentity.settingsDescription() = "Model: $assetName\n입력 크기: ${inputWidth}×$inputHeight\n" +
    "Input: $inputShape ($inputType)\nOutput: $outputShape ($outputType)\n" +
    "클래스 ($classCount): ${ModelConfig.labelsFor(assetName).joinToString()}\nSHA256: $sha256"
