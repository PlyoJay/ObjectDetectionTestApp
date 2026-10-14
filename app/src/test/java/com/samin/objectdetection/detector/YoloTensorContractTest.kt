package com.samin.objectdetection.detector

import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloTensorContractTest {
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun floatFastPathIsByteIdenticalForEveryChannelValueAndBothLayoutsOnReuse() {
        val pixels = IntArray(256) { (0xff shl 24) or (it shl 16) or ((255 - it) shl 8) or ((it * 73 % 256)) }
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            for (layout in RgbTensorLayout.entries) {
                val legacy = ByteBuffer.allocate(pixels.size * 12).order(order)
                val optimized = ByteBuffer.allocateDirect(pixels.size * 12).order(order)
                val view = optimized.asFloatBuffer()
                repeat(3) { iteration ->
                    val frame = if (iteration % 2 == 0) pixels else pixels.reversedArray()
                    RgbTensorWriter.fill(legacy, frame, layout, TensorValueCodec(DataType.FLOAT32))
                    RgbTensorWriter.fillFloat32(view, frame, layout)
                    val actual = ByteArray(optimized.capacity())
                    optimized.rewind(); optimized.get(actual); optimized.rewind()
                    assertArrayEquals("layout=$layout order=$order frame=$iteration", legacy.array(), actual)
                    assertEquals(0, view.position())
                }
            }
        }
    }

    @Test fun outputReaderPreservesValuesAndAxesForFloatAndQuantizedOutputsOnReuse() {
        val codecs = listOf(TensorValueCodec(DataType.FLOAT32), TensorValueCodec(DataType.UINT8, 1/255f, 0),
            TensorValueCodec(DataType.INT8, 1/255f, -128))
        val values = floatArrayOf(0f, 0.01f, 0.2f, 0.5f, 1f, 0.45f)
        for (codec in codecs) for (channelsFirst in listOf(true, false)) {
            val bytes = buffer(values.size * if (codec.type == DataType.FLOAT32) 4 else 1)
            val view = if (codec.type == DataType.FLOAT32) bytes.asFloatBuffer() else null
            val rows = Array(3) { FloatArray(2) }
            repeat(2) { iteration ->
                bytes.clear()
                val frame = if (iteration == 0) values else values.reversedArray()
                frame.forEach { codec.write(bytes, it) }
                bytes.rewind()
                val expected = FloatArray(values.size) { codec.read(bytes) }
                YoloOutputReader.copy(bytes, view, codec, channelsFirst, rows)
                for (c in rows.indices) for (i in rows[c].indices) {
                    assertEquals(expected[if (channelsFirst) c * 2 + i else i * 3 + c], rows[c][i], 0f)
                }
                assertEquals(0, bytes.position())
            }
        }
    }

    @Test fun bulkOutputCopyPreservesNonFiniteAndSignedZeroBitsForParserDiagnostics() {
        val bits = intArrayOf(0x80000000.toInt(), 0x7fc01234, 0x7f800000, 0xff800000.toInt())
        val bytes = buffer(16)
        bits.forEach { bytes.putInt(it) }
        val rows = Array(2) { FloatArray(2) }
        bytes.rewind()
        YoloOutputReader.copy(bytes, bytes.asFloatBuffer(), TensorValueCodec(DataType.FLOAT32), true, rows)
        assertArrayEquals(bits, rows.flatMap { row -> row.map { it.toRawBits() } }.toIntArray())
    }

    @Test fun nchwWritesCompleteRgbPlanesAndNhwcPreservesInterleaving() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xff804020.toInt())
        val codec = TensorValueCodec(DataType.FLOAT32)
        val nchw = buffer(48)
        RgbTensorWriter.fill(nchw, pixels, RgbTensorShape.from(intArrayOf(1,3,2,2)).layout, codec)
        val expected = floatArrayOf(1f,0f,0f,128/255f, 0f,1f,0f,64/255f, 0f,0f,1f,32/255f)
        assertArrayEquals(expected, FloatArray(12) { nchw.float }, 0f)
        val nhwc = buffer(48)
        RgbTensorWriter.fill(nhwc, pixels, RgbTensorShape.from(intArrayOf(1,2,2,3)).layout, codec)
        assertArrayEquals(floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f, 128/255f,64/255f,32/255f),
            FloatArray(12) { nhwc.float }, 0f)
    }

    @Test fun parserUsesClassCountWhenCandidatesAreFewerThanChannels() {
        assertEquals(YoloOutputShape(true, 5, 2), YoloOutputShape.from(intArrayOf(1,5,2),1))
        assertEquals(YoloOutputShape(false, 5, 8400), YoloOutputShape.from(intArrayOf(1,8400,5),1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun objectnessOrEndToEndShapeRequiresAnExplicitContract() {
        YoloOutputShape.from(intArrayOf(1,8400,6),1)
    }

    @Test fun nineClassDetectSupportsBothOutputLayouts() {
        assertEquals(YoloOutputShape(true, 13, 8400), YoloOutputShape.from(intArrayOf(1, 13, 8400), 9))
        assertEquals(YoloOutputShape(false, 13, 8400), YoloOutputShape.from(intArrayOf(1, 8400, 13), 9))
    }

    @Test fun nineClassDetectRejectsOldModelAndExtraObjectnessChannels() {
        for (channels in listOf(5, 12, 14, 84)) {
            for (shape in listOf(intArrayOf(1, channels, 8400), intArrayOf(1, 8400, channels))) {
                val error = assertThrows(IllegalArgumentException::class.java) { YoloOutputShape.from(shape, 9) }
                assertTrue(error.message!!.contains("9 class scores"))
                assertTrue(error.message!!.contains(shape.contentToString()))
            }
        }
    }

    @Test fun nineClassDetectRejectsAmbiguousChannelAxis() {
        assertThrows(IllegalArgumentException::class.java) { YoloOutputShape.from(intArrayOf(1, 13, 13), 9) }
    }

    @Test fun signedAndUnsignedQuantizationUseScaleAndZeroPoint() {
        for (codec in listOf(TensorValueCodec(DataType.INT8,1/255f,-128), TensorValueCodec(DataType.UINT8,1/255f,0))) {
            val bytes = buffer(3)
            listOf(0f,128/255f,1f).forEach { codec.write(bytes,it) }
            bytes.rewind()
            assertEquals(0f,codec.read(bytes),1e-6f)
            assertEquals(128/255f,codec.read(bytes),1e-6f)
            assertEquals(1f,codec.read(bytes),1e-6f)
        }
    }

    @Test fun statisticsMeasureActualBufferWithoutChangingItsPosition() {
        val bytes = buffer(48)
        val codec = TensorValueCodec(DataType.FLOAT32)
        val shape = RgbTensorShape(RgbTensorLayout.NCHW,2,2)
        RgbTensorWriter.fill(bytes, intArrayOf(0xffff0000.toInt(),0xffff0000.toInt(),0xffff0000.toInt(),0xffff0000.toInt()),shape.layout,codec)
        bytes.position(8)
        val stats = TensorStatistics.measure(bytes,codec,shape)
        assertEquals(listOf(1.0,0.0,0.0),stats.channelMeans)
        assertEquals(1.0/3,stats.mean!!,1e-8)
        assertEquals(8,bytes.position())
        assertEquals(64,stats.sha256.length)
    }
}
