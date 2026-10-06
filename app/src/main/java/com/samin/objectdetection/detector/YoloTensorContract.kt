package com.samin.objectdetection.detector

import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

internal enum class RgbTensorLayout { NHWC, NCHW }

/** Tensor shape determines storage order; it does not determine RGB normalization. */
internal data class RgbTensorShape(val layout: RgbTensorLayout, val width: Int, val height: Int) {
    companion object {
        fun from(shape: IntArray): RgbTensorShape {
            require(shape.size == 4 && shape[0] == 1 && shape.all { it > 0 }) {
                "Expected a static batch-1 RGB input, actual=${shape.contentToString()}"
            }
            val nchw = shape[1] == 3
            val nhwc = shape[3] == 3
            require(nchw != nhwc) { "Unsupported/ambiguous RGB layout: ${shape.contentToString()}" }
            return if (nchw) RgbTensorShape(RgbTensorLayout.NCHW, shape[3], shape[2])
            else RgbTensorShape(RgbTensorLayout.NHWC, shape[2], shape[1])
        }
    }
}

internal data class YoloOutputShape(val channelsFirst: Boolean, val channels: Int, val candidates: Int) {
    companion object {
        fun from(shape: IntArray, classes: Int): YoloOutputShape {
            require(classes > 0 && shape.size == 3 && shape[0] == 1 && shape.all { it > 0 }) {
                "Unsupported YOLO output: ${shape.contentToString()}"
            }
            val channels = 4 + classes // Verified YOLO11 Detect export: no objectness, no extra sigmoid.
            val first = shape[1] == channels
            val last = shape[2] == channels
            require(first != last) {
                "Output ${shape.contentToString()} does not uniquely match xywh + $classes class scores. " +
                    "Objectness/end-to-end/multi-head exports require an explicit parser contract."
            }
            return YoloOutputShape(first, channels, if (first) shape[2] else shape[1])
        }
    }
}

internal data class TensorValueCodec(val type: DataType, val scale: Float = 0f, val zeroPoint: Int = 0) {
    init {
        require(type == DataType.FLOAT32 || type == DataType.UINT8 || type == DataType.INT8) {
            "Unsupported tensor dtype=$type"
        }
        require(type == DataType.FLOAT32 || (scale.isFinite() && scale > 0)) {
            "Quantized tensor requires a positive scale, actual=$scale"
        }
    }
    fun write(buffer: ByteBuffer, real: Float) {
        if (type == DataType.FLOAT32) buffer.putFloat(real)
        else {
            val quantized = (real / scale + zeroPoint).roundToInt()
            buffer.put(quantized.coerceIn(if (type == DataType.UINT8) 0 else -128,
                if (type == DataType.UINT8) 255 else 127).toByte())
        }
    }
    fun read(buffer: ByteBuffer): Float = when (type) {
        DataType.FLOAT32 -> buffer.float
        DataType.UINT8 -> ((buffer.get().toInt() and 255) - zeroPoint) * scale
        else -> (buffer.get().toInt() - zeroPoint) * scale
    }
}

internal object RgbTensorWriter {
    // Both checked-in Ultralytics exports expect RGB /255, without graph-side input normalization.
    fun fill(buffer: ByteBuffer, pixels: IntArray, layout: RgbTensorLayout, codec: TensorValueCodec) {
        buffer.rewind()
        fun write(pixel: Int, channel: Int) = codec.write(buffer,
            ((pixel shr (16 - channel * 8)) and 255) / 255.0f)
        if (layout == RgbTensorLayout.NCHW) {
            for (channel in 0..2) for (pixel in pixels) write(pixel, channel)
        } else {
            for (pixel in pixels) for (channel in 0..2) write(pixel, channel)
        }
        require(buffer.position() == buffer.capacity()) { "Input byte count differs from tensor.numBytes()" }
        buffer.rewind()
    }
}

internal data class TensorStatistics(
    val min: Float?, val max: Float?, val mean: Double?, val nonFinite: Int,
    val channelMeans: List<Double>, val firstValues: List<Float>, val sha256: String
) {
    fun description(includeFirst: Boolean): String = "min=$min max=$max mean=$mean nonFinite=$nonFinite " +
        "channelMeansRGB=$channelMeans ${if (includeFirst) "firstValues=$firstValues " else ""}sha256=$sha256"

    companion object {
        fun measure(buffer: ByteBuffer, codec: TensorValueCodec, rgb: RgbTensorShape? = null): TensorStatistics {
            val values = buffer.duplicate().order(buffer.order()).apply { rewind() }
            val sums = DoubleArray(3)
            val counts = IntArray(3)
            val first = mutableListOf<Float>()
            var minimum = Float.POSITIVE_INFINITY
            var maximum = Float.NEGATIVE_INFINITY
            var sum = 0.0
            var finite = 0
            var nonFinite = 0
            var index = 0
            while (values.hasRemaining()) {
                val v = codec.read(values)
                if (first.size < 12) first.add(v)
                if (v.isFinite()) {
                    minimum = minOf(minimum, v); maximum = maxOf(maximum, v); sum += v; finite++
                    if (rgb != null) {
                        val c = if (rgb.layout == RgbTensorLayout.NCHW) index / (rgb.width * rgb.height) else index % 3
                        sums[c] += v; counts[c]++
                    }
                } else nonFinite++
                index++
            }
            val bytes = buffer.duplicate().apply { rewind() }
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes) }
            return TensorStatistics(minimum.takeIf { finite > 0 }, maximum.takeIf { finite > 0 },
                (sum / finite).takeIf { finite > 0 }, nonFinite,
                if (rgb == null) emptyList() else sums.mapIndexed { c, s -> s / counts[c].coerceAtLeast(1) },
                first, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
