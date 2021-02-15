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

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.*
import android.os.Handler
import android.view.SurfaceView
import com.google.android.exoplayer2.util.Assertions
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioOutputBuffer
import dev.hadrosaur.videodecodeencodedemo.FrameLedger
import dev.hadrosaur.videodecodeencodedemo.GlManager
import dev.hadrosaur.videodecodeencodedemo.GlManager.Companion.generateTextureIds
import dev.hadrosaur.videodecodeencodedemo.GlManager.Companion.getEglSurface
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The underlying SurfaceTexture used for decoding. Calls back to the InternalSurfaceTexturerRenderer
 * when a frame has been received and updateTexImage has been called.
 *
 * Has runners for rendering out to a display surface or to a video encoder.
 */
class InternalSurfaceTexture @JvmOverloads constructor(
    val mainActivity: MainActivity, val glManager: GlManager, val outputSurface: SurfaceView,
    val frameLedger: FrameLedger,
    val audioBufferQueue: ConcurrentLinkedQueue<AudioOutputBuffer>,
    private val handler: Handler,
    private val callback: TextureImageListener? =  /* callback= */null) : OnFrameAvailableListener {

    // Interal GL Surfaces
    private var surfaceTextureEGLSurface: EGLSurface? = null
    private var outputSurfaceEGLSurface: EGLSurface? = null
    private var encodeSurfaceEGLSurface: EGLSurface? = null
    private var texture: SurfaceTexture? = null
    var textureId: Int = 0

    // Runners and gl frame drawers for rendering to screen or video encoder
    val drawFrameRunner = DrawFrameRunner()
    lateinit var drawFrameProcessor: DrawFrameProcessor
    val encodeFrameRunner = EncodeFrameRunner()
    lateinit var encodeFrameProcessor: DrawFrameProcessor
    val updateTexRunner = UpdateTexImageRunner()

    // It is simpler to manage the encoder from here. Original media file ID is passed in only
    // as an aid in setting up the encode parameters to match.
    var shouldEncode = false
    lateinit var audioVideoEncoder: AudioVideoEncoder

    // Call back to InternalSurfaceTextureRenderer after updateTexImage has been called for a new frame
    interface TextureImageListener {
        fun onFrameAvailable()
    }

    // Render the current frame to the output surface
    // Copying from a surface texture requires a matrix transformation
    inner class DrawFrameRunner() : Runnable {
        override fun run() {
            val matrix = FloatArray(16)
            texture!!.getTransformMatrix(matrix)
            drawFrameProcessor.drawFrame(mainActivity.viewModel.getApplyGlFilterVal(), matrix)
        }
    }

    // Write the current frame to the encoder
    // Copying from a surface texture requires a matrix transformation
    inner class EncodeFrameRunner() : Runnable {
        override fun run() {
            val matrix = FloatArray(16)
            texture!!.getTransformMatrix(matrix)
            encodeFrameProcessor.drawFrame(mainActivity.viewModel.getApplyGlFilterVal(), matrix)
            // videoEncoder.encodeAvailableFrames()
        }
    }

    // Called each time a new frame is available on the internal decoding surface
    inner class UpdateTexImageRunner() : Runnable {
        override fun run() {
            if (texture != null) {
                try {
                    texture!!.updateTexImage()
                } catch (e: RuntimeException) {
                    // Ignore
                }
            }
            // Let renderer know that updateTexImage has been called and render pipeline can be
            // unblocked.
            dispatchOnFrameAvailable()
        }
    }

    /**
     * Initializes required EGL parameters and creates the underlying internal SurfaceTexture.
     *
     * Initializes the preview and encoder runners and renderers
     */
    fun initialize() {
        surfaceTextureEGLSurface = GlManager.createEGLSurface(glManager.eglDisplay, glManager.eglConfig, glManager.eglContext)
        textureId = generateTextureIds()
        texture = SurfaceTexture(textureId)
        texture!!.setOnFrameAvailableListener(this)

        outputSurfaceEGLSurface = getEglSurface(glManager.eglConfig, glManager.eglDisplay, outputSurface.holder.surface)
        drawFrameProcessor = DrawFrameProcessor(textureId, glManager.eglContext, glManager.eglDisplay, outputSurfaceEGLSurface!!, outputSurface.width, outputSurface.height)

        if (shouldEncode) {
            encodeSurfaceEGLSurface = getEglSurface(glManager.eglConfig, glManager.eglDisplay, audioVideoEncoder.videoEncoderInputSurface)
            encodeFrameProcessor = DrawFrameProcessor(textureId, glManager.eglContext, glManager.eglDisplay, encodeSurfaceEGLSurface!!, audioVideoEncoder.width, audioVideoEncoder.height )
        }
    }

    fun initializeVideoEncoder() {
        shouldEncode = true
        audioVideoEncoder = AudioVideoEncoder(mainActivity, mainActivity.encodeFileOriginalRawFileId, frameLedger, audioBufferQueue)
    }

    fun release() {
        handler.removeCallbacks(updateTexRunner)
        handler.removeCallbacks(drawFrameRunner)
        try {
            if (texture != null) {
                texture!!.release()
                GLES20.glDeleteTextures(1, IntArray(1) {textureId}, 0)
            }
        } finally {
        }

        if (shouldEncode) {
            audioVideoEncoder.release()
        }
    }

    /**
     * Returns the wrapped [SurfaceTexture]. This can only be called after [.init].
     */
    val surfaceTexture: SurfaceTexture
        get() = Assertions.checkNotNull(texture)

    /**
     *     SurfaceTexture.OnFrameAvailableListener. Call runner to call updateTexImage and notify
     *     renderer.
     */
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        handler.post(updateTexRunner)
    }

    // Call runner to render frame to preview surface
    fun drawFrameToScreen() {
        handler.post(drawFrameRunner)
    }

    // Call runner to send frame to the encoder
    fun encodeFrame() {
        handler.post(encodeFrameRunner)
    }

    // Call the callback to the renderer
    private fun dispatchOnFrameAvailable() {
        callback?.onFrameAvailable()
    }
}