package com.samin.objectdetection.detector

import org.junit.Assert.*
import org.junit.Test
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloTensorContractTest {
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

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
