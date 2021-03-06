/*
 * Copyright (c) 2020 Google LLC
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

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import android.opengl.*
import android.util.Log
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.TEX_SAMPLER_NAME
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.UnsupportedEglVersionException
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.allocateTexture
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.copyExternalShaderProgram
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.deleteFbo
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.deleteTexture
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.focusSurface
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.getAttributes
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.getFboForTexture
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.getUniforms
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.passthroughShaderProgram
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.sepiaShaderProgram

/**
 * Copies a frame from the input texture to the output surface, adjusting for size and applying
 * requested filters.
 *
 * Note: this can be used for both display and for encode.
 */
class DrawFrameProcessor(
    private val inputTextureId: Int,
    private val eglContext: EGLContext,
    private val eglDisplay: EGLDisplay,
    private val outputEglSurface: EGLSurface,
    val width: Int,
    val height: Int
) {
    private val TAG = "DrawFrameProcessor"

    // Create a program that copies from an external texture to a framebuffer
    val copyExternalProgram = copyExternalShaderProgram
    private val copyAttributes = getAttributes(copyExternalProgram)
    private val copyUniforms = getUniforms(copyExternalProgram)

    // Create a program that renders the final output.
    private val renderProgram = passthroughShaderProgram
    private val renderAttributes = getAttributes(renderProgram)
    private val renderUniforms = getUniforms(renderProgram)

    // The sepiaProgram can use the attributes and uniforms of the pass-through renderProgram
    private val sepiaProgram = sepiaShaderProgram

    // Variables to handle texture shader coordinate matrices
    private val positionMatrix = FloatArray(16)
    private val renderTransformMatrix = FloatArray(16)
    private var shaderPositionMatrixHandle = -1
    private var shaderSurfaceTexTransformMatrixHandle = -1

    private val drawFrameHandler: DrawFrameHandler

    init {
        // The copy program flips the input texture.
        for (i in copyAttributes.indices) {
            when (copyAttributes[i]!!.name) {
                GlUtils.POSITION_ATTRIBUTE_NAME -> {
                    copyAttributes[i]!!.setBuffer(
                        floatArrayOf(
                            -1.0f, -1.0f, 0.0f, 1.0f,
                            1.0f, -1.0f, 0.0f, 1.0f,
                            -1.0f, 1.0f, 0.0f, 1.0f,
                            1.0f, 1.0f, 0.0f, 1.0f
                        ), 4
                    )
                }
                GlUtils.TEXCOORD_ATTRIBUTE_NAME -> {
                    copyAttributes[i]!!.setBuffer(
                        floatArrayOf(
                            0.0f, 0.0f, 1.0f, 1.0f,
                            1.0f, 0.0f, 1.0f, 1.0f,
                            0.0f, 1.0f, 1.0f, 1.0f,
                            1.0f, 1.0f, 1.0f, 1.0f
                        ), 4
                    )
                }
                else -> {
                    throw IllegalStateException("unexpected attribute name")
                }
            }
        }

        shaderPositionMatrixHandle =
            GLES20.glGetUniformLocation(copyExternalProgram, GlUtils.POS_MATRIX_UNIFORM_NAME)
        shaderSurfaceTexTransformMatrixHandle =
            GLES20.glGetUniformLocation(copyExternalProgram, GlUtils.ST_MATRIX_UNIFORM_NAME)

        Matrix.setIdentityM(positionMatrix, 0)
        Matrix.setIdentityM(renderTransformMatrix, 0)

        // The render program blits the input vertices without any transformations.
        for (i in renderAttributes.indices) {
            when (renderAttributes[i]!!.name) {
                GlUtils.POSITION_ATTRIBUTE_NAME -> {
                    renderAttributes[i]!!.setBuffer(
                        floatArrayOf(
                            -1.0f, -1.0f, 0.0f, 1.0f,
                            1.0f, -1.0f, 0.0f, 1.0f,
                            -1.0f, 1.0f, 0.0f, 1.0f,
                            1.0f, 1.0f, 0.0f, 1.0f
                        ), 4
                    )
                }
                GlUtils.TEXCOORD_ATTRIBUTE_NAME -> {
                    renderAttributes[i]!!.setBuffer(
                        floatArrayOf(
                            0.0f, 0.0f, 1.0f, 1.0f,
                            1.0f, 0.0f, 1.0f, 1.0f,
                            0.0f, 1.0f, 1.0f, 1.0f,
                            1.0f, 1.0f, 1.0f, 1.0f
                        ), 4
                    )
                }
                else -> {
                    throw IllegalStateException("unexpected attribute name")
                }
            }
        }
        drawFrameHandler = DrawFrameHandler()
    }

    fun drawFrame(applyFilters: Boolean, surfaceTextureMatrix: FloatArray) {
        try {
            focusSurface(eglDisplay, eglContext, outputEglSurface, GlUtils.NO_FBO, width, height)
            doFrameDraw(applyFilters, drawFrameHandler, surfaceTextureMatrix)

        } catch (e: UnsupportedEglVersionException) {
                Log.w(TAG, "Unsupported EGL version", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception drawing frame: ", e)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun doFrameDraw(applyFilters: Boolean, frameHandler: FrameHandler, surfaceTextureMatrix: FloatArray) {
        var exception: java.lang.Exception? = null
        try {

            val intermediateTexture: Int = frameHandler.getTexture(eglDisplay, eglContext)
            val intermediateFbo = getFboForTexture(intermediateTexture)

            // Copy from input texture to framebuffer:
            frameHandler.focusSurface(eglDisplay, eglContext, outputEglSurface, intermediateFbo)
            GLES20.glUseProgram(copyExternalProgram)
            for (i in copyAttributes.indices) {
                copyAttributes[i]!!.bind()
            }
            for (i in copyUniforms.indices) {
                // We don't want to bind non-sampler uniforms
                if (copyUniforms[i]!!.mName != TEX_SAMPLER_NAME) {
                    continue
                }
                copyUniforms[i]!!.setSamplerTexId(this.inputTextureId, 0)
                copyUniforms[i]!!.bindToTextureSampler()
            }

            // Apply surface texture transforms
            // This is required as copying out from a surface texture is not 1:1 for some reason
            // See: https://developer.android.com/reference/android/graphics/SurfaceTexture#getTransformMatrix(float%5B%5D)
            Matrix.setIdentityM(positionMatrix, 0)
            GLES20.glUniformMatrix4fv(shaderPositionMatrixHandle, 1, false, positionMatrix, 0)
            GLES20.glUniformMatrix4fv(shaderSurfaceTexTransformMatrixHandle, 1, false, surfaceTextureMatrix, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Render from framebuffer to output surface
            frameHandler.focusSurface(eglDisplay, eglContext, outputEglSurface, GlUtils.NO_FBO)

            // Apply filters if requested (otherwise just pass-through)
            if (applyFilters) {
                GLES20.glUseProgram(sepiaProgram)
            } else {
                GLES20.glUseProgram(renderProgram)
            }
            for (i in renderAttributes.indices) {
                renderAttributes[i]!!.bind()
            }
            for (i in renderUniforms.indices) {
                // We don't want to bind non-sampler uniforms
                if (renderUniforms[i]!!.mName != TEX_SAMPLER_NAME) {
                    continue
                }
                renderUniforms[i]!!.setSamplerTexId(intermediateTexture, 0)
                renderUniforms[i]!!.bindToTextureSampler()
            }

            // Note: here do not apply surface texture transform matrix, just use the identity matrix as the
            // frame has already been adjusted in the copy step
            GLES20.glUniformMatrix4fv(shaderPositionMatrixHandle, 1, false, positionMatrix, 0)
            GLES20.glUniformMatrix4fv(shaderSurfaceTexTransformMatrixHandle, 1, false, renderTransformMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Display the frame (note already focused from last render)
            frameHandler.present(eglDisplay, outputEglSurface)

            // Free up Fbo and texture
            deleteFbo(intermediateFbo)
            frameHandler.releaseTexture(intermediateTexture)

        } catch (e: java.lang.Exception) {
            exception = e
        } finally {
            if (exception != null) {
                throw exception
            }
        }
    }

    /**
     * Handler interface for SurfaceTexture frames
     */
    private interface FrameHandler {
        /**
         * Returns a new texture in the specified context.
         */
        fun getTexture(eglDisplay: EGLDisplay?, eglContext: EGLContext?): Int

        /**
         * Releases the previous generated texture
         */
        fun releaseTexture(texId: Int)

        /**
         * Makes `eglSurface` current for rendering.
         */
        fun focusSurface(
            eglDisplay: EGLDisplay?, eglContext: EGLContext?, eglSurface: EGLSurface?, fbo: Int
        )

        /**
         * Presents the frame in `eglSurface` by calling [EGL14.eglSwapBuffers].
         */
        fun present(eglDisplay: EGLDisplay?, eglSurface: EGLSurface?)
    }


    inner class DrawFrameHandler: FrameHandler {
        override fun present(eglDisplay: EGLDisplay?, eglSurface: EGLSurface?) {
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        override fun focusSurface(
            eglDisplay: EGLDisplay?, eglContext: EGLContext?,
            eglSurface: EGLSurface?, fbo: Int
        ) {
            focusSurface(eglDisplay, eglContext, eglSurface, fbo, width, height)
        }

        override fun getTexture(eglDisplay: EGLDisplay?, eglContext: EGLContext?): Int {
            return allocateTexture(width, height)
        }

        override fun releaseTexture(texId: Int) {
            deleteTexture(texId)
        }
    }
}
