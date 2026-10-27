package com.samin.objectdetection.arcore

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** OES camera quad + ARCore transformed UVs, following Google's BackgroundRenderer approach.
 * Reference: google-ar/arcore-android-sdk samples/hello_ar_java/.../BackgroundRenderer.java
 * This focused renderer contains no copied sample framework or depth visualization pipeline.
 */
class ArCameraBackground {
    var textureId: Int = 0
        private set
    private var program = 0
    private var positionLocation = -1
    private var uvLocation = -1
    private var samplerLocation = -1
    private val vertices = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uvs = floats(FloatArray(8))

    fun create() {
        val texture = IntArray(1)
        GLES20.glGenTextures(1, texture, 0)
        textureId = texture[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPosition; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPosition,0.0,1.0);vUv=aUv;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES uCamera; varying vec2 vUv; void main(){gl_FragColor=texture2D(uCamera,vUv);}")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) { "카메라 shader 연결 실패: ${GLES20.glGetProgramInfoLog(program)}" }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        uvLocation = GLES20.glGetAttribLocation(program, "aUv")
        samplerLocation = GLES20.glGetUniformLocation(program, "uCamera")
    }

    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) return
        vertices.position(0); uvs.position(0)
        frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, vertices,
            Coordinates2d.TEXTURE_NORMALIZED, uvs)
        vertices.position(0); uvs.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glEnableVertexAttribArray(positionLocation); GLES20.glEnableVertexAttribArray(uvLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, 0, uvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation); GLES20.glDisableVertexAttribArray(uvLocation)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "ARCore 카메라 OpenGL 렌더링 오류" }
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) { "카메라 shader 컴파일 실패: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
