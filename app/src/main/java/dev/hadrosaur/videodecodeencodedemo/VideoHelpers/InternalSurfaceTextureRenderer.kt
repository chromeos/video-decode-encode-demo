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

import android.os.Handler
import android.view.Surface
import android.view.SurfaceView
import dev.hadrosaur.videodecodeencodedemo.Utils.GlManager
import dev.hadrosaur.videodecodeencodedemo.MainViewModel

/**
 * The custom SurfaceTexture Renderer. Manages the underlying SurfaceTexture and keeps track of
 * frames rendered and encoded.
 *
 * Logs to the main log as the render progresses.
 */
class InternalSurfaceTextureRenderer(val viewModel: MainViewModel, glManager: GlManager, displaySurface: SurfaceView, handler: Handler) : InternalSurfaceTexture.TextureImageListener {
    val frameLedger = VideoFrameLedger()
    val internalSurfaceTexture: InternalSurfaceTexture = InternalSurfaceTexture(viewModel, glManager, displaySurface, handler, this)
    var onFrameAvailableCounter = 0
    var doEncode = false

    // The internal Surface from the SurfaceTexture to be decoded to, used by ExoPlayer
    lateinit var decoderSurface: Surface

    fun initialize(encoderInputSurface: Surface? = null, encoderWidth: Int = 0, encoderHeight: Int = 0) {
        // Initialize decoding surfaces only
        if (encoderInputSurface == null) {
            internalSurfaceTexture.initialize()

        // Initialize both decoding and encoding surfaces
        } else {
            internalSurfaceTexture.initialize(encoderInputSurface, encoderWidth, encoderHeight)
            doEncode = true
        }

        decoderSurface = Surface(internalSurfaceTexture.surfaceTexture)
    }

    /** Called when a new frame is available on the SurfaceTexture from it's image producer.
     *
     * Note: this implementation draws or encodes frames as fast as they are available, by design.
     *  This will not result in smooth preview playback for variable rate media because the decoder
     *  will run more or less at a constant speed (determined by system resources) but variable
     *  media will have frames that are intended to be shown at different durations. For proper
     *  playback, be sure to respect each frames' presentation time.
     */
    override fun onFrameAvailable() {
        onFrameAvailableCounter++
        val surfaceTimestamp = internalSurfaceTexture.surfaceTexture.timestamp

        // updateTexImage has just been called by the renderer, release the render pipeline lock
        // that was set in the FrameLedger's onVideoFrameAboutToBeRendered callback
        frameLedger.releaseRenderLock()

        if (surfaceTimestamp != 0L) {
            // Check if this frame was decoded and sent to the rendered
            if (frameLedger.decodeLedger.containsKey(surfaceTimestamp)) {
                // Frame matched, increment the frames rendered counter
                val framesRendered = frameLedger.framesRendered.incrementAndGet()

                // If this frame matches the requested draw frequency, or the frequency is set to
                // draw every frame, draw the frame to the preview surface
                if (framesRendered % viewModel.getPreviewFrameFrequencyVal() == 1 ||
                    viewModel.getPreviewFrameFrequencyVal() == 1) {
                    internalSurfaceTexture.drawFrameToScreen()
                }

                // If encoding is engaged, encode this frame
                if (doEncode) {
                    internalSurfaceTexture.encodeFrame()
                }

            } else {
                // Frame not found in the ledger. This should not happen.
                viewModel.updateLog("Frame NOT FOUND! Key: ${surfaceTimestamp}, frame: ${frameLedger.decodeLedger.get(surfaceTimestamp)}")
            }
        } else {
            // Surface timestamp 0 - This frame not really rendered, but add to keep the ledger even
            frameLedger.framesRendered.incrementAndGet()
        }

        // Log the current state to make sure no frames have been dropped
        // if (frameLedger.frames_entered.get() % LOG_VIDEO_EVERY_N_FRAMES == 0) {
            // viewModel.updateLog("Decoded: ${frameLedger.frames_entered.get()}. Rendered: ${frameLedger.frames_rendered.get()}. Dropped: ${frameLedger.frames_entered.get() - frameLedger.frames_rendered.get()}")
        // }
    }

    fun release() {
        internalSurfaceTexture.release()
        decoderSurface.release()
    }
}