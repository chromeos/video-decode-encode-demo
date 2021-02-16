/*
 * Copyright (c) 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.hadrosaur.videodecodeencodedemo.Utils

import android.opengl.*
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Utility functions for using EGL.
 */
object GlUtils {
    const val POSITION_ATTRIBUTE_NAME = "a_position"
    const val TEXCOORD_ATTRIBUTE_NAME = "a_texcoord"
    const val POS_MATRIX_UNIFORM_NAME = "uMVPMatrix"
    const val ST_MATRIX_UNIFORM_NAME = "uSTMatrix"

    const val TEX_SAMPLER_NAME = "tex_sampler_0"
    const val TEX_COORDINATE_NAME = "v_texcoord"

    const val NO_FBO = 0

    /**
     * Vertex shader that renders a quad filling the viewport.
     *
     * Applies provided matrix transformations (needed for SurfaceTextures)
     */
    private val BLIT_VERTEX_SHADER = String.format(Locale.US,
        "attribute vec4 %1${"$"}s;\n" +
                "attribute vec4 %2${"$"}s;\n" +
                "varying vec2 %3${"$"}s;\n" +
                "uniform mat4 %4${"$"}s;\n" +
                "uniform mat4 %5${"$"}s;\n" +
                "void main() {\n" +
                   "gl_Position = %4${"$"}s * %1${"$"}s;\n" +
                   "%3${"$"}s = ((%5${"$"}s) * %2${"$"}s).xy;\n" +
                "}\n"
        , POSITION_ATTRIBUTE_NAME, TEXCOORD_ATTRIBUTE_NAME, TEX_COORDINATE_NAME, POS_MATRIX_UNIFORM_NAME, ST_MATRIX_UNIFORM_NAME
    )

    /**
     * Fragment shader that renders from an external shader to the current target.
     */
    private val COPY_EXTERNAL_FRAGMENT_SHADER = String.format(Locale.US,
        "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "uniform samplerExternalOES %1${"$"}s;\n" +
                "varying vec2 %2${"$"}s;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(%1${"$"}s, %2${"$"}s);\n" +
                "}\n"
        , TEX_SAMPLER_NAME, TEX_COORDINATE_NAME
    )

    /**
     * Fragment shader that simply samples the textures with no modifications.
     */
    private val PASSTHROUGH_FRAGMENT_SHADER = String.format(Locale.US,
    "precision mediump float;\n" +
            "uniform sampler2D %1${"$"}s;\n" +
            "varying vec2 %2${"$"}s;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(%1${"$"}s, %2${"$"}s);\n" +
            "}\n"
        , TEX_SAMPLER_NAME, TEX_COORDINATE_NAME
    )

    /**
     * Fragment shader that applies a simple Sepia filter.
     */
    private val SEPIA_FRAGMENT_SHADER = String.format(Locale.US,
        "precision mediump float;\n" +
            "uniform sampler2D %1${"$"}s;\n" +
            "varying vec2 %2${"$"}s;\n" +
            "void main() {\n" +
            "  vec4 sampleColor = texture2D(%1${"$"}s, %2${"$"}s);\n" +
            "  gl_FragColor = vec4(sampleColor.r * 0.493 + sampleColor. g * 0.769 + sampleColor.b * 0.289, sampleColor.r * 0.449 + sampleColor.g * 0.686 + sampleColor.b * 0.268, sampleColor.r * 0.272 + sampleColor.g * 0.534 + sampleColor.b * 0.131, 1.0);\n" +
            "}\n"
        , TEX_SAMPLER_NAME, TEX_COORDINATE_NAME
    )

    /**
     * Returns an initialized default display.
     */
    fun createEglDisplay(): EGLDisplay {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(!(eglDisplay === EGL14.EGL_NO_DISPLAY)) { "no EGL display" }
        val major = IntArray(1)
        val minor = IntArray(1)
        check(EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) { "error in eglInitialize" }
        checkGlError()
        return eglDisplay
    }

    /**
     * Returns a new GL context for the specified `eglDisplay`.
     *
     * @throws UnsupportedEglVersionException if the device does not support EGL version 2.
     * `eglDisplay` is terminated before the exception is thrown in this case.
     */
    @Throws(UnsupportedEglVersionException::class)
    fun createEglContext(eglDisplay: EGLDisplay, recordable: Boolean): EGLContext {
        val contextAttributes =
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(eglDisplay,
            getEglConfig(eglDisplay, recordable), EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        if (eglContext == null) {
            EGL14.eglTerminate(eglDisplay)
            throw UnsupportedEglVersionException()
        }
        checkGlError()
        return eglContext
    }

    /**
     * Returns a new [EGLSurface] wrapping the specified `surface`.
     *
     * @param eglDisplay to attach the surface to
     * @param surface to wrap; must be a surface, surface texture or surface holder
     * @param recordable if the surface will be used to record from
     */
    fun getEglSurface(
        eglDisplay: EGLDisplay, surface: Any?, recordable: Boolean
    ): EGLSurface {
        return EGL14.eglCreateWindowSurface(
            eglDisplay,
            getEglConfig(eglDisplay, recordable),
            surface, intArrayOf(EGL14.EGL_NONE),
            0
        )
    }

    /**
     * Returns the texture identifier for a newly-allocated surface with the specified dimensions.
     *
     * @param width of the new texture in pixels
     * @param height of the new texture in pixels
     */
    fun allocateTexture(width: Int, height: Int): Int {
        val texId = generateTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val byteBuffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, byteBuffer
        )
        checkGlError()
        return texId
    }

    /**
     * Returns a new framebuffer for the texture.
     *
     * @param texId of the texture to attach to the framebuffer
     */
    fun getFboForTexture(texId: Int): Int {
        val fbo = generateFbo()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        checkGlError()
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0
        )
        checkGlError()
        return fbo
    }

    /**
     * Makes the specified `surface` the render target, using a viewport of `width` by
     * `height` pixels.
     */
    fun focusSurface(
        eglDisplay: EGLDisplay?, eglContext: EGLContext?,
        surface: EGLSurface?, fbo: Int, width: Int, height: Int
    ) {
        val fbos = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fbos, 0)
        if (fbos[0] != fbo) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        }
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        GLES20.glViewport(0, 0, width, height)
    }

    /**
     * Destroys the GL context identifier by `eglDisplay` and `eglContext`.
     */
    fun destroyEglContext(
        eglDisplay: EGLDisplay?,
        eglContext: EGLContext?
    ) {
        if (eglDisplay == null) {
            return
        }
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        var error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("error releasing context: $error")
        }
        if (eglContext != null) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw RuntimeException("error destroying context: $error")
            }
        }
        EGL14.eglReleaseThread()
        error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("error releasing thread: $error")
        }
        EGL14.eglTerminate(eglDisplay)
        error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) {
            throw RuntimeException("error terminating display: $error")
        }
    }

    /**
     * Returns a new GL texture identifier.
     */
    fun generateTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGlError()
        return textures[0]
    }

    /**
     * Returns a new framebuffer identifier.
     */
    fun generateFbo(): Int {
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        checkGlError()
        return fbos[0]
    }

    /**
     * Deletes a GL texture.
     *
     * @param texId of the texture to delete
     */
    fun deleteTexture(texId: Int) {
        val textures = intArrayOf(texId)
        GLES20.glDeleteTextures(1, textures, 0)
    }

    /**
     * Deletes a GL texture.
     *
     * @param texId of the texture to delete
     */
    fun deleteFbo(fboId: Int) {
        val fbos = intArrayOf(fboId)
        GLES20.glDeleteFramebuffers(1, fbos, 0)
    }

    /**
     * Returns the [Attribute]s in the specified `program`.
     */
    fun getAttributes(program: Int): Array<Attribute?> {
        val attributeCount = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTES, attributeCount, 0)
        check(attributeCount[0] == 2) { "expected two attributes" }
        val attributes =
            arrayOfNulls<Attribute>(attributeCount[0])
        for (i in 0 until attributeCount[0]) {
            attributes[i] = Attribute(program, i)
        }
        return attributes
    }

    /**
     * Returns the [Uniform]s in the specified `program`.
     */
    fun getUniforms(program: Int): Array<Uniform?> {
        val uniformCount = IntArray(3)
        GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORMS, uniformCount, 0)
        val uniforms =
            arrayOfNulls<Uniform>(uniformCount[0])
        for (i in 0 until uniformCount[0]) {
            uniforms[i] = Uniform(program, i)
        }
        return uniforms
    }

    /**
     * Returns a GL shader program identifier for a compiled program that copies from an external
     * texture.
     *
     *
     * It has two vertex attributes, [.POSITION_ATTRIBUTE_NAME] and
     * [.TEXCOORD_ATTRIBUTE_NAME] which should be set to the output position (vec4) and
     * texture coordinates (vec3) respectively of the output quad.
     */
    val copyExternalShaderProgram: Int
        get() {
            val vertexShader =
                compileShader(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER)
            val fragmentShader =
                compileShader(GLES20.GL_FRAGMENT_SHADER, COPY_EXTERNAL_FRAGMENT_SHADER)
            return linkProgram(vertexShader, fragmentShader)
        }

    /**
     * Returns a GL shader program identifier for a compiled program that composites two textures.
     *
     *
     * It has two vertex attributes, [.POSITION_ATTRIBUTE_NAME] and
     * [.TEXCOORD_ATTRIBUTE_NAME] which should be set to the output position (vec4) and
     * texture coordinates (vec3) respectively of the output quad.
     */
    val passthroughShaderProgram: Int
        get() {
            val vertexShader =
                compileShader(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER)
            val fragmentShader =
                compileShader(GLES20.GL_FRAGMENT_SHADER, PASSTHROUGH_FRAGMENT_SHADER)
            return linkProgram(vertexShader, fragmentShader)
        }

    val sepiaShaderProgram: Int
        get() {
            val vertexShader =
                compileShader(GLES20.GL_VERTEX_SHADER, BLIT_VERTEX_SHADER)
            val fragmentShader =
                compileShader(GLES20.GL_FRAGMENT_SHADER, SEPIA_FRAGMENT_SHADER)
            return linkProgram(vertexShader, fragmentShader)
        }

    private fun getEglConfig(
        eglDisplay: EGLDisplay,
        recordable: Boolean
    ): EGLConfig? {
        // Get an EGLConfig.
        val EGL_OPENGL_ES2_BIT = 4
        val RED_SIZE = 8
        val GREEN_SIZE = 8
        val BLUE_SIZE = 8
        val ALPHA_SIZE = 8
        val DEPTH_SIZE = 0
        val STENCIL_SIZE = 0
        val EGL_RECORDABLE_ANDROID = 0x3142
        val RECORDABLE_CONFIGURATION = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, RED_SIZE,
            EGL14.EGL_GREEN_SIZE, GREEN_SIZE,
            EGL14.EGL_BLUE_SIZE, BLUE_SIZE,
            EGL14.EGL_ALPHA_SIZE, ALPHA_SIZE,
            EGL14.EGL_DEPTH_SIZE, DEPTH_SIZE,
            EGL14.EGL_STENCIL_SIZE, STENCIL_SIZE,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val DEFAULT_CONFIGURATION = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, RED_SIZE,
            EGL14.EGL_GREEN_SIZE, GREEN_SIZE,
            EGL14.EGL_BLUE_SIZE, BLUE_SIZE,
            EGL14.EGL_ALPHA_SIZE, ALPHA_SIZE,
            EGL14.EGL_DEPTH_SIZE, DEPTH_SIZE,
            EGL14.EGL_STENCIL_SIZE, STENCIL_SIZE,
            EGL14.EGL_NONE
        )
        val configsCount = IntArray(1)
        val eglConfigs =
            arrayOfNulls<EGLConfig>(1)
        if (!EGL14.eglChooseConfig(
                eglDisplay,
                if (recordable) RECORDABLE_CONFIGURATION else DEFAULT_CONFIGURATION,
                0, eglConfigs, 0, 1, configsCount, 0
            )
        ) {
            throw RuntimeException("eglChooseConfig failed")
        }
        return eglConfigs[0]
    }




    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw RuntimeException("could not create shader: " + GLES20.glGetError())
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("could not compile shader $type:$info")
        }
        return shader
    }

    private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES20.glCreateProgram()
        if (program == 0) {
            throw RuntimeException("could not create shader program")
        }
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("could not link shader $info")
        }
        return program
    }

    /**
     * Returns a [Buffer] containing the specified floats, suitable for passing to
     * [GLES20.glVertexAttribPointer].
     */
    private fun getVertexBuffer(values: FloatArray): Buffer {
        val FLOAT_SIZE = 4
        return ByteBuffer.allocateDirect(values.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(values)
            .position(0)
    }

    /**
     * Returns the length of the null-terminated string in `strVal`.
     */
    private fun strlen(strVal: ByteArray): Int {
        for (i in strVal.indices) {
            if (strVal[i].toChar() == '\u0000') {
                return i
            }
        }
        return strVal.size
    }

    /**
     * Checks for a GL error using [GLES20.glGetError].
     *
     * @throws RuntimeException if there is a GL error
     */
    private fun checkGlError() {
        var errorCode: Int
        if (GLES20.glGetError().also { errorCode = it } != GLES20.GL_NO_ERROR) {
            throw RuntimeException("gl error: " + Integer.toHexString(errorCode))
        }
    }

    class UnsupportedEglVersionException : Exception()

    /**
     * GL attribute, which can be attached to a buffer with
     * [Attribute.setBuffer].
     */
    class Attribute(program: Int, index: Int) {
        val name: String
        private val mIndex: Int
        private val mLocation: Int
        private var mBuffer: Buffer? = null
        private var mSize = 0

        /**
         * Configures [.bind] to attach vertices in `buffer` (each of size
         * `size` elements) to this [Attribute].
         *
         * @param buffer to bind to this attribute
         * @param size elements per vertex
         */
        fun setBuffer(buffer: FloatArray?, size: Int) {
            requireNotNull(buffer) { "buffer must be non-null" }
            mBuffer = getVertexBuffer(buffer)
            mSize = size
        }

        /**
         * Sets the vertex attribute to whatever was attached via [.setBuffer].
         *
         *
         * Should be called before each drawing call.
         */
        fun bind() {
            checkNotNull(mBuffer) { "call setBuffer before bind" }
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glVertexAttribPointer(
                mLocation,
                mSize,  // count
                GLES20.GL_FLOAT,  // type
                false,  // normalize
                0,  // stride
                mBuffer
            )
            GLES20.glEnableVertexAttribArray(mIndex)
            checkGlError()
        }

        init {
            val len = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH, len, 0)
            val type = IntArray(1)
            val size = IntArray(1)
            val nameBytes = ByteArray(len[0])
            val ignore = IntArray(1)
            GLES20.glGetActiveAttrib(
                program, index, len[0], ignore, 0, size, 0, type, 0, nameBytes, 0
            )
            name = String(nameBytes, 0, strlen(nameBytes))
            mLocation = GLES20.glGetAttribLocation(program, name)
            mIndex = index
        }
    }

    /**
     * GL uniform, which can be attached to a sampler using
     * [Uniform.setSamplerTexId].
     */
    class Uniform(program: Int, index: Int) {
        val mName: String
        val mLocation: Int
        val mType: Int
        var mTexId = 0
        var mUnit = 0

        /**
         * Configures [.bind] to use the specified `texId` for this sampler uniform.
         *
         * @param texId from which to sample
         * @param unit for this texture
         */
        fun setSamplerTexId(texId: Int, unit: Int) {
            mTexId = texId
            mUnit = unit
        }

        /**
         * Sets the uniform to whatever was attached via [.setSamplerTexId].
         *
         * Should be called before each drawing call, for sampler uniforms only.
         */
        fun bindToTextureSampler() {
            check(mTexId != 0) { "call setSamplerTexId before bind" }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + mUnit)
            if (mType == GLES11Ext.GL_SAMPLER_EXTERNAL_OES) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTexId)
            } else if (mType == GLES20.GL_SAMPLER_2D) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexId)
            } else {
                throw IllegalStateException("unexpected uniform type: $mType")
            }
            GLES20.glUniform1i(mLocation, mUnit)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
            checkGlError()
        }

        init {
            val len = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_ACTIVE_UNIFORM_MAX_LENGTH, len, 0)
            val type = IntArray(1)
            val size = IntArray(1)
            val name = ByteArray(len[0])
            val ignore = IntArray(1)
            GLES20.glGetActiveUniform(
                program,
                index,
                len[0],
                ignore,
                0,
                size,
                0,
                type,
                0,
                name,
                0
            )
            mName = String(name, 0, strlen(name))
            mLocation = GLES20.glGetUniformLocation(program, mName)
            mType = type[0]
        }
    }
}