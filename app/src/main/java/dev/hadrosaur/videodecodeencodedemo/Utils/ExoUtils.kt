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

import android.os.Handler
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.video.VideoRendererEventListener
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.VideoMediaCodecAudioRenderer
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.SpeedyMediaClock
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoMediaCodecVideoRenderer
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoSurfaceManager


// Create an ExoPlayer media source from a raw resource ID
fun buildExoMediaSource(mainActivity: MainActivity, raw: Int): MediaSource {
    val uri = RawResourceDataSource.buildRawResourceUri(raw)

    val dataSourceFactory: DataSource.Factory =
        DefaultDataSourceFactory(mainActivity,
            MainActivity.LOG_TAG
        )
    return ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(uri)
}

class CustomExoRenderersFactory(
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val videoSurfaceManager: VideoSurfaceManager,
    private val streamNumber: Int,
    val audioBufferManager: AudioBufferManager?,
    val encoderDecoderFallback: Boolean = true
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        val mediaClock = SpeedyMediaClock()

        return arrayOf(
            VideoMediaCodecVideoRenderer(mainActivity, viewModel, videoSurfaceManager, encoderDecoderFallback, streamNumber, mediaClock),
            VideoMediaCodecAudioRenderer(mainActivity, viewModel, streamNumber, audioBufferManager)
        )
    }
}

