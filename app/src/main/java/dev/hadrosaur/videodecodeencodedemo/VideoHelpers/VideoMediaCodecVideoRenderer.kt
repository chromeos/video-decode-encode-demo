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

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.Nullable
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.util.MediaClock
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_EVERY_N_FRAMES
import java.nio.ByteBuffer

class VideoMediaCodecVideoRenderer(val mainActivity: MainActivity, val internalSurfaceTextureComponent: InternalSurfaceTextureComponent, enableDecoderFallback: Boolean, val streamNumber: Int, val minFps: Int = 1) :
    MediaCodecVideoRenderer(mainActivity, MediaCodecSelector.DEFAULT, 0, enableDecoderFallback, null, null, -1), MediaClock {
    var decodeCounter = 0
    var startTime = 0L
    var droppedFrames = 0
    var lastProcessedFrameUs = 0L

    var internalPlaybackParameters = PlaybackParameters(1f)

    var haveSeenFirst = false
    var haveSeenSecond = false

    /**
     * Keep track of dropped frames
     */
    override fun dropOutputBuffer(codec: MediaCodec, index: Int, presentationTimeUs: Long) {
        droppedFrames++
        mainActivity.updateLog("Dropped frame in surface 1. Total dropped: ${droppedFrames}")
        super.dropOutputBuffer(codec, index, presentationTimeUs)
    }

    /**
     * Override default decoding flags here
     */
    override fun getMediaFormat(format: Format, codecMimeType: String, codecMaxValues: CodecMaxValues,
                                codecOperatingRate: Float, deviceNeedsNoPostProcessWorkaround: Boolean, tunnelingAudioSessionId: Int): MediaFormat {

        // Get default mediaFormat
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)

        // This decoding flag removes the necessity to manually block the render pipeline. It only
        // works in Android 10 and greater
        // Prevent frames from being dropped
        // if (Build.VERSION.SDK_INT >= 29) {
        //   mediaFormat.setInteger("allow-frame-drop", 0)
        //}

        return mediaFormat
    }

    /**
     * Use an internal MediaClock that renders as fast as possible, instead of in real-time
     */
    override fun getMediaClock(): MediaClock? {
        return this
    }

    /**
     * If the render pipeline is free, returns true here. If a frame is already in flight, returns
     * false to wait and prevent frame drops.
     */
     override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: MediaCodec?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {

        // In ExoPlayer < 12.2.0, skip null buffers
        if (buffer == null) {
//            return false
        }

        // In ExpoPlayer >= 12.2.0, all buffers are null, skip the first one on Chrome OS.
        // Output buffer info for debug
        if (!haveSeenFirst) {
            haveSeenFirst = true
            mainActivity.updateLog("FIRST BUFFER:" +
                    "\n\tpos: ${positionUs}, elap: ${elapsedRealtimeUs}, buffer: ${buffer}, bufIdx: ${bufferIndex}, bufFlags: ${bufferFlags}," +
                    "\n\tcodec: ${codec?.name}, samCount: ${sampleCount}, bufPresTime: ${bufferPresentationTimeUs}, isDecodeOnly: ${isDecodeOnlyBuffer}, " +
                    "\n\tisLast: ${isLastBuffer}" +
                    "\n\tformat: ${format}")

            // ExoPlayer on Chrome OS does not render the first frame for some reason
            if (mainActivity.isArc()) {
                return false
            }
        }

        // Output buffer info for debug
        if (!haveSeenSecond) {
            haveSeenSecond = true
            mainActivity.updateLog("SECOND BUFFER:" +
                    "\n\tpos: ${positionUs}, elap: ${elapsedRealtimeUs}, buffer: ${buffer}, bufIdx: ${bufferIndex}, bufFlags: ${bufferFlags}," +
                    "\n\tcodec: ${codec?.name}, samCount: ${sampleCount}, bufPresTime: ${bufferPresentationTimeUs}, isDecodeOnly: ${isDecodeOnlyBuffer}, " +
                    "\n\tisLast: ${isLastBuffer}" +
                    "\n\tformat: ${format}")
        }

        // Check the atomic lock to see if a frame can be rendered. If not, return false and wait
        if (internalSurfaceTextureComponent.renderer.frameLedger.shouldBlockRender()) {
//            mainActivity.updateLog("I am in processOutputBuffer: The renderer is blocked.")
            return false
        }

//        mainActivity.updateLog("I am in processOutputBuffer. Renderer not blocked.")

        return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex,
            bufferFlags, sampleCount, bufferPresentationTimeUs, isDecodeOnlyBuffer, isLastBuffer, format)
    }

    /**
     * Adds some logging after each buffer processed to keep track of decode
     */
    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        if (startTime == 0L) {
            startTime = System.currentTimeMillis()
        }
        decodeCounter++

        if (decodeCounter % LOG_EVERY_N_FRAMES == 0) {
            val currentFPS =
                decodeCounter / ((System.currentTimeMillis() - startTime) / 1000.0)
            val FPSString = String.format("%.2f", currentFPS)
            mainActivity.updateLog("Stream ${streamNumber}: ${FPSString}fps @frame $decodeCounter.")
        }

        lastProcessedFrameUs = presentationTimeUs

        super.onProcessedOutputBuffer(presentationTimeUs)
    }

    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean {
        return false
    }

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean {
        return false
    }

    // MediaClock implementation. Render as fast as possible: use last rendered frame + 1 as current
    // position. See: https://github.com/google/ExoPlayer/issues/3978#issuecomment-372709173
    // Note: no frames should be dropped so minFps just makes sure clock advances far enough to
    // include the next frame. Too far is ok in this case.
    override fun getPositionUs(): Long {
        return lastProcessedFrameUs + ((1 / minFps) * 100000)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return internalPlaybackParameters
    }

    // Note: this implementation actually ignores parameters like speed.
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        internalPlaybackParameters = playbackParameters
    }
}