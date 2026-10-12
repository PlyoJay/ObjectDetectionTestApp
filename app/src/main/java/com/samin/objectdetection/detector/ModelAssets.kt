package com.samin.objectdetection.detector

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Shared asset access for inference and the read-only settings screen. */
object ModelAssets {
    fun load(context: Context, modelName: String = ModelConfig.MODEL_ASSET_NAME): MappedByteBuffer {
        context.assets.openFd(modelName).use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    fun sha256(context: Context, modelName: String = ModelConfig.MODEL_ASSET_NAME): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(modelName).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun identity(context: Context, interpreter: Interpreter, modelName: String, classCount: Int): ModelIdentity {
        return ModelIdentity(
            assetName = modelName,
            assetSizeBytes = context.assets.openFd(modelName).use { it.declaredLength },
            sha256 = sha256(context, modelName),
            inputShape = interpreter.getInputTensor(0).shape().contentToString(),
            inputType = interpreter.getInputTensor(0).dataType().toString(),
            outputShape = interpreter.getOutputTensor(0).shape().contentToString(),
            outputType = interpreter.getOutputTensor(0).dataType().toString(),
            classCount = classCount
        )
    }
}
