/*
 * Copyright (c) 2022 Google LLC
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

import android.os.Handler
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.video.VideoRendererEventListener
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioMixTrack
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.VideoMediaCodecAudioRenderer
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoMediaCodecVideoRenderer
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoSurfaceManager

class CustomExoRenderersFactory(
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val videoSurfaceManager: VideoSurfaceManager,
    private val streamNumber: Int,
    private val audioMixTrack: AudioMixTrack,
    val audioBufferManager: AudioBufferManager?
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        return arrayOf(
            VideoMediaCodecVideoRenderer(mainActivity, viewModel, videoSurfaceManager, true, streamNumber, audioMixTrack.mediaClock),
            VideoMediaCodecAudioRenderer(mainActivity, viewModel, streamNumber, audioMixTrack, audioBufferManager)
        )
    }
}
