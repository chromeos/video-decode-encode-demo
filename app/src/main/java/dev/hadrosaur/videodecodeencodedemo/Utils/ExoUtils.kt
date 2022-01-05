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
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import com.google.android.exoplayer2.video.VideoRendererEventListener
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.CopyAndPlayAudioSink
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel

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

class StandardExoRenderersFactory(
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val streamNumber: Int,
    private val audioBufferManager: AudioBufferManager? = null
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        return arrayOf(
            MediaCodecVideoRenderer(mainActivity,
                MediaCodecSelector.DEFAULT,
                0,
                true,
                null,
                null,
                -1),
            // Add an audio render even if audioBufferManager is null and no audio played, this allows fast rendering
            MediaCodecAudioRenderer(mainActivity, MediaCodecSelector.DEFAULT, null, null, CopyAndPlayAudioSink(viewModel, streamNumber, audioBufferManager))
        )
    }
}