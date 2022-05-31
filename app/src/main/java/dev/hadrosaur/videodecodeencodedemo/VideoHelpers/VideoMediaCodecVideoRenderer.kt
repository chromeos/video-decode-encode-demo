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

import android.media.MediaFormat
import android.os.Build.VERSION.SDK_INT
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.util.MediaClock
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioMixTrackMediaClock
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer


class VideoMediaCodecVideoRenderer(
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val videoSurfaceManager: VideoSurfaceManager,
    enableDecoderFallback: Boolean,
    private val streamNumber: Int,
    private val mediaClock: AudioMixTrackMediaClock
) :
    MediaCodecVideoRenderer(
        mainActivity,
        MediaCodecSelector.DEFAULT,
        0,
        enableDecoderFallback,
        null,
        null,
        -1
    )  {
    private var droppedFrames = 0

    /**
     * Keep track of dropped frames
     */
    override fun dropOutputBuffer(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long) {
        droppedFrames++
        viewModel.updateLog("Dropped frame in surface ${index}. Total dropped: ${droppedFrames}")
        super.dropOutputBuffer(codec, index, presentationTimeUs)
    }

    /**
     * Override default decoding flags here
     */
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int
    ): MediaFormat {
        // Get default mediaFormat
        val mediaFormat = super.getMediaFormat(
            format, codecMimeType, codecMaxValues, codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId
        )

        // TODO: Verify experimentally if this makes a difference or if ExoPlayer overrides this
        // internally (in MediaCodecRenderer). KEY_OPERATING_RATE will be optimised for battery
        // life on some systems. Here we try to burn battery to increase decoding rate
        if (SDK_INT > 23) {
            mediaFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        // This decoding flag removes the necessity to manually block the render pipeline. It only
        // works in Android 10 and greater
        // Prevent frames from being dropped
        // if (Build.VERSION.SDK_INT >= 29) {
        //   mediaFormat.setInteger("allow-frame-drop", 0)
        //}

        return mediaFormat
    }

    /**
     * Use a MediaClock that renders as fast as possible, instead of in real-time
     */
    override fun getMediaClock(): MediaClock {
        return mediaClock
    }

    /**
     * Update media clock after a seek
     */
    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        mediaClock.setPositionUs(positionUs)
    }

    /**
     * If the render pipeline is free, returns true here. If a frame is already in flight, returns
     * false to wait and prevent frame drops.
     */
    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {

        // Check the atomic lock to see if a frame can be rendered. If not, return false and wait
        if (videoSurfaceManager.renderer.frameLedger.isRenderLocked()) {
            // viewModel.updateLog("I am in processOutputBuffer: The renderer is blocked.")
            return false
        }

        if (isLastBuffer) {
            videoSurfaceManager.renderer.frameLedger.lastVideoBufferPresentationTimeUs = bufferPresentationTimeUs
        }
//        viewModel.updateLog("I am in processOutputBuffer. Renderer not blocked.")
        val processSuccess = super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )

        return processSuccess
    }

    /**
     * Update media clock after each buffer processed
     */
    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        mediaClock.updateLastProcessedVideoPosition(presentationTimeUs)
        super.onProcessedOutputBuffer(presentationTimeUs)
    }

    override fun shouldDropBuffersToKeyframe(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastBuffer: Boolean
    ): Boolean {
        return false
    }

    override fun shouldDropOutputBuffer(
        earlyUs: Long,
        elapsedRealtimeUs: Long,
        isLastBuffer: Boolean
    ): Boolean {
        return false
    }
}