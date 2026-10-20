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
        val descriptor = try { context.assets.openFd(modelName) } catch (error: java.io.IOException) {
            throw IllegalArgumentException("모델 파일을 열 수 없습니다: $modelName (app/src/main/assets/에 파일이 있는지 확인하세요)", error)
        }
        descriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                return input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /** Same contract for the settings inspector and the production detector. */
    internal fun validate(interpreter: Interpreter, modelName: String, classes: Int): YoloModelContract {
        require(interpreter.inputTensorCount == 1 && interpreter.outputTensorCount == 1) {
            "$modelName: 단일 RGB 입력과 단일 raw YOLO 출력만 지원합니다. " +
                "inputs=${interpreter.inputTensorCount} outputs=${interpreter.outputTensorCount}"
        }
        val input = interpreter.getInputTensor(0)
        val output = interpreter.getOutputTensor(0)
        require(input.shapeSignature().all { it > 0 } && output.shapeSignature().all { it > 0 }) {
            "$modelName: 동적 텐서는 지원하지 않습니다. input=${input.shapeSignature().contentToString()} " +
                "output=${output.shapeSignature().contentToString()}"
        }
        val rgb = RgbTensorShape.from(input.shape())
        YoloModel.entries.firstOrNull { it.assetName == modelName }?.let { selected ->
            require(rgb.width == selected.inputSize && rgb.height == selected.inputSize) {
                "$modelName: 선택 모델은 ${selected.inputSize}×${selected.inputSize} 입력이어야 합니다. " +
                    "실제 입력=${input.shape().contentToString()}"
            }
        }
        fun codec(tensor: org.tensorflow.lite.Tensor) = tensor.quantizationParams().let {
            TensorValueCodec(tensor.dataType(), it.scale, it.zeroPoint)
        }
        val parsedOutput = YoloOutputShape.from(output.shape(), classes)
        val inputCodec = codec(input)
        val outputCodec = codec(output)
        require(input.numBytes().toLong() == rgb.width.toLong() * rgb.height * 3 * input.dataType().byteSize() &&
            output.numBytes().toLong() == parsedOutput.channels.toLong() * parsedOutput.candidates * output.dataType().byteSize()) {
            "$modelName: 텐서 크기와 버퍼 바이트 수가 일치하지 않습니다."
        }
        return YoloModelContract(rgb, parsedOutput, inputCodec, outputCodec)
    }

    fun inspect(context: Context, model: YoloModel, threads: Int = 1, useXnnpack: Boolean = false): ModelIdentity {
        val labels = context.assets.open(ModelConfig.LABEL_ASSET_NAME).bufferedReader().useLines {
            it.map(String::trim).filter(String::isNotEmpty).toList()
        }
        require(labels == ModelConfig.CLASS_LABELS) { "클래스 순서가 labels.txt 계약과 일치하지 않습니다: $labels" }
        val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(useXnnpack)
        Interpreter(load(context, model.assetName), options).use { interpreter ->
            validate(interpreter, model.assetName, labels.size)
            return identity(context, interpreter, model.assetName, labels.size)
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
            classCount = classCount,
            inputWidth = RgbTensorShape.from(interpreter.getInputTensor(0).shape()).width,
            inputHeight = RgbTensorShape.from(interpreter.getInputTensor(0).shape()).height
        )
    }
}

internal data class YoloModelContract(
    val input: RgbTensorShape,
    val output: YoloOutputShape,
    val inputCodec: TensorValueCodec,
    val outputCodec: TensorValueCodec
)
