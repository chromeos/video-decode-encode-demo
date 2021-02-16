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
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.Utils.GlManager
import dev.hadrosaur.videodecodeencodedemo.MainActivity

/**
 * The custom SurfaceTexture Renderer. Manages the underlying SurfaceTexture and keeps track of
 * frames rendered and encoded.
 *
 * Logs to the main log as the render progresses.
 */
class InternalSurfaceTextureRenderer(val mainActivity: MainActivity, glManager: GlManager, displaySurface: SurfaceView, handler: Handler, audioBufferManager: AudioBufferManager) : InternalSurfaceTexture.TextureImageListener {
    val frameLedger = VideoFrameLedger()
    val internalSurfaceTexture: InternalSurfaceTexture = InternalSurfaceTexture(mainActivity, glManager, displaySurface, frameLedger, audioBufferManager, handler, this)
    var onFrameAvailableCounter = 0
    var isEncoderStarted = false
    var doEncode = false

    // The internal Surface from the SurfaceTexture to be decoded to, used by ExoPlayer
    lateinit var decoderSurface: Surface

    fun initialize() {
        internalSurfaceTexture.initialize()
        decoderSurface = Surface(internalSurfaceTexture.surfaceTexture)
    }

    fun shouldEncode(shouldEncode: Boolean) {
        doEncode = shouldEncode
        internalSurfaceTexture.initializeVideoEncoder()
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
        frameLedger.releaseRenderBlock()

        if (surfaceTimestamp != 0L) {
            // Check if this frame was decoded and sent to the rendered
            if (frameLedger.decodeLedger.containsKey(surfaceTimestamp)) {
                // Frame matched, increment the frames rendered counter
                val framesRendered = frameLedger.frames_rendered.incrementAndGet()

                // If this frame matches the requested draw frequency, or the frequency is set to
                // draw every frame, draw the frame to the preview surface
                if (framesRendered % mainActivity.viewModel.getPreviewFrameFrequencyVal() == 1 ||
                    mainActivity.viewModel.getPreviewFrameFrequencyVal() == 1) {
                    internalSurfaceTexture.drawFrameToScreen()
                }

                // If encoding is engaged, encode this frame
                if (doEncode) {
                    // Don't start the encoder until we actually need it
                    if(!isEncoderStarted) {
                        internalSurfaceTexture.audioVideoEncoder.startEncode()
                        isEncoderStarted = true
                    }

                    // Because the encoder is not directly receiving frames from a media stream but
                    // but from a surface, no EOS flag will be received. Instead, just count the
                    // number of frames that should be encoded to know the encoding is done
                    internalSurfaceTexture.audioVideoEncoder.numDecodedVideoFrames.incrementAndGet()

                    // Indicate to encoder that all frames have been sent to decoder
                    if (mainActivity.stream1DecodeFinished) {
                        internalSurfaceTexture.audioVideoEncoder.signalDecodingComplete()
                    }

                    if (!internalSurfaceTexture.audioVideoEncoder.videoEncodeComplete) {
                        internalSurfaceTexture.encodeFrame()
                    }
                }

            } else {
                // Frame not found in the ledger. This should not happen.
                mainActivity.updateLog("Frame NOT FOUND! Key: ${surfaceTimestamp}, frame: ${frameLedger.decodeLedger.get(surfaceTimestamp)}")
            }
        } else {
            // Surface timestamp 0 - This frame not really rendered, but add to keep the ledger even
            frameLedger.frames_rendered.incrementAndGet()
        }

        // Log the current state to make sure no frames have been dropped
        // if (frameLedger.frames_entered.get() % LOG_VIDEO_EVERY_N_FRAMES == 0) {
            // mainActivity.updateLog("Decoded: ${frameLedger.frames_entered.get()}. Rendered: ${frameLedger.frames_rendered.get()}. Dropped: ${frameLedger.frames_entered.get() - frameLedger.frames_rendered.get()}")
        // }
    }

    fun release() {
        internalSurfaceTexture.release()
    }
}