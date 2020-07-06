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
import dev.hadrosaur.videodecodeencodedemo.FrameLedger
import dev.hadrosaur.videodecodeencodedemo.GlManager
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_EVERY_N_FRAMES

/**
 * The custom SurfaceTexture Renderer. Manages the underlying SurfaceTexture and keeps track of
 * frames rendered and encoded.
 *
 * Logs to the main log as the render progresses.
 */
class InternalSurfaceTextureRenderer(val mainActivity: MainActivity, glManager: GlManager, displaySurface: SurfaceView, handler: Handler) : InternalSurfaceTexture.TextureImageListener {
    val internalSurfaceTexture: InternalSurfaceTexture = InternalSurfaceTexture(mainActivity, glManager, displaySurface, handler, this)
    val frameLedger = FrameLedger()
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
    }

    // Called when a new frame is available on the SurfaceTexture from it's image producer
    override fun onFrameAvailable() {
        onFrameAvailableCounter++
        val surfaceTimestamp = internalSurfaceTexture.surfaceTexture.timestamp

        // updateTexImage has just been called by the renderer, now release the render pipeline lock
        // that was set in the FrameLedger's onVideoFrameAboutToBeRendered callback
        frameLedger.releaseRenderBlock()

        if (surfaceTimestamp != 0L) {
            if (frameLedger.ledger.containsKey(surfaceTimestamp)) {
                // Frame matched, add to the ledger
                val framesRendered = frameLedger.frames_rendered.incrementAndGet()

                // If this frame matches the requested draw frequency, or the frequency is set to
                // draw every frame, draw a preview frame
                if (framesRendered % mainActivity.viewModel.getPreviewFrameFrequencyVal() == 1 ||
                    mainActivity.viewModel.getPreviewFrameFrequencyVal() == 1) {
                    internalSurfaceTexture.drawFrameToScreen()
                }

                // If encoding is engaged, encode this frame
                if (doEncode) {
                    if(!isEncoderStarted) {
                        internalSurfaceTexture.videoEncoder.startEncode()
                        isEncoderStarted = true
                    }

                    // Because the encoder is not directly receiving frames from a media stream but
                    // but from a surface, no EOS flag will be received. Instead, just count the
                    // number of frames that should be encoded to know the encoding is done
                    internalSurfaceTexture.videoEncoder.numDecodedFrames.incrementAndGet()

                    // Indicate to encoder that all frames have been sent to decoder
                    if (mainActivity.stream1DecodeFinished) {
                        internalSurfaceTexture.videoEncoder.signalDecodingComplete()
                    }

                    internalSurfaceTexture.encodeFrame()
                }

            } else {
                // Frame not found in the ledger
                mainActivity.updateLog("Frame NOT FOUND! Key: ${surfaceTimestamp}, frame: ${frameLedger.ledger.get(surfaceTimestamp)}")
            }
        } else {
            // Surface timestamp 0 - This frame not really rendered, but add to keep the ledger even
            frameLedger.frames_rendered.incrementAndGet()
        }

        if (frameLedger.frames_entered.get() % LOG_EVERY_N_FRAMES == 0) {
            mainActivity.updateLog("Decoded: ${frameLedger.frames_entered.get()}. Rendered: ${frameLedger.frames_rendered.get()}. Dropped: ${frameLedger.frames_entered.get() - frameLedger.frames_rendered.get()}")
        }
    }

    fun release() {
        internalSurfaceTexture.release()
    }
}