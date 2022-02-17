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
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import dev.hadrosaur.videodecodeencodedemo.Utils.GlManager

/**
 * Holder for the internal decoding SurfaceTexture. Ties together the ExoPlayer VideoComponent,
 * and the custom SurfaceTexture and Renderer
 */
class VideoSurfaceManager(val viewModel: MainViewModel, glManager: GlManager, displaySurface: SurfaceView) {
    private val handler: Handler = Handler()
    var renderer: InternalSurfaceTextureRenderer =
        InternalSurfaceTextureRenderer(viewModel, glManager, displaySurface, handler)

    private lateinit var player: ExoPlayer
    // Set up the internal surfaces
    // If encoding is desired, ensure setupEncodeSurfaces has been called
    fun initialize(player: ExoPlayer, encoderInputSurface: Surface? = null, encoderWidth: Int = 0, encoderHeight: Int = 0) {
        // Initialize decoding surfaces only
        if (encoderInputSurface == null) {
            renderer.initialize()

        // Initialize both decoding and encoding surfaces
        } else {
            renderer.initialize(encoderInputSurface, encoderWidth, encoderHeight)
        }

        this.player = player

        // Tell ExoPlayer to decode to this surface
        player.setVideoSurface(renderer.decoderSurface)

        // Use the FrameLedger to keep track of when frames are actually rendered and when a frame
        // is about to be rendered to prevent frame drops
        player.setVideoFrameMetadataListener(renderer.frameLedger)
    }

    fun release() {
        renderer.release()
    }
}