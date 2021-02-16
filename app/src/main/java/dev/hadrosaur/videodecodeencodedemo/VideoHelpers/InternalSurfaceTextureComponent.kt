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
import android.view.SurfaceView
import com.google.android.exoplayer2.Player
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.Utils.GlManager
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Holder for the internal decoding SurfaceTexture. Ties to gather the ExoPlayer VideoComponent,
 * and the custom SurfaceTexture and Renderer
 */
class InternalSurfaceTextureComponent(val mainActivity: MainActivity, glManager: GlManager, displaySurface: SurfaceView, val audioBufferManager: AudioBufferManager) {
    val handler: Handler = Handler()
    var renderer: InternalSurfaceTextureRenderer
    lateinit var videoComponent: Player.VideoComponent

    init {
        renderer = InternalSurfaceTextureRenderer(mainActivity, glManager, displaySurface, handler, audioBufferManager)
    }

    fun shouldEncode(shouldEncode: Boolean) {
        renderer.shouldEncode(shouldEncode)
    }

    fun initialize(videoComponent: Player.VideoComponent) {
        renderer.initialize()
        this.videoComponent = videoComponent

        // Tell ExoPlayer to decode to this surface
        videoComponent.setVideoSurface(renderer.decoderSurface)

        // Use the FrameLedger to keep track of when frames are actually rendered and when a frame
        // is about to be rendered to prevent frame drops
        videoComponent.setVideoFrameMetadataListener(renderer.frameLedger)
    }

    fun release() {
        renderer.release()
    }
}