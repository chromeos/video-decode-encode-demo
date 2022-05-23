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

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.util.MediaClock
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_VIDEO_EVERY_N_FRAMES
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import dev.hadrosaur.videodecodeencodedemo.Utils.getMax
import dev.hadrosaur.videodecodeencodedemo.Utils.getMin
import java.nio.ByteBuffer


class VideoMediaCodecVideoRenderer(
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val videoSurfaceManager: VideoSurfaceManager,
    enableDecoderFallback: Boolean,
    private val streamNumber: Int,
    private val mediaClock: SpeedyMediaClock
) :
    MediaCodecVideoRenderer(
        mainActivity,
        VideoSWMediaCodecSelector(enableDecoderFallback),
        0,
        enableDecoderFallback,
        null,
        null,
        -1
    )  {

    // Stats counters
    private var fpsDecodeCounter = 0
    private var fpsTotalDecodeCounter = 0
    private var fpsLastMeasuredTime = 0L
    private var fpsLastLoggedTime = 0L
    private var droppedFrames = 0
    private var lastPresentTime = 0L

    // Set up an int array for fps stats to get an idea of choppiness. Ex. 0-55fps+, 12 buckets
    // 0-4, 5-9, 10-14, 15-19, 20-24 . . . 50-54, 55+
    private val MAX_FPS_STATS = 60 // max fps we care about for stats
    private val NUM_FPS_BUCKETS = 6
    private val FPS_BUCKET_SIZE = MAX_FPS_STATS / NUM_FPS_BUCKETS // 10fps
    private val LAST_FPS_BUCKET_START = MAX_FPS_STATS - FPS_BUCKET_SIZE // 50+
    private val fpsBuckets = IntArray(NUM_FPS_BUCKETS) { 0 }

    // Keep track of mix/max fps
    private var minFps = MAX_FPS_STATS
    private var maxFps = 0

    // Choppiness = num frames < 30fps
    private val CHOPPINESS_CUTOFF = 30
    private val TOO_MANY_CHOPPY_FRAMES = 10
    private var numChoppyFrames = 0

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

    override fun onCodecInitialized(
        name: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        mainActivity.updateLog("A codec has been initialized: ${name}")
        super.onCodecInitialized(name, initializedTimestampMs, initializationDurationMs)
    }

    override fun onCodecReleased(name: String) {
        mainActivity.updateLog("A codec has been released: ${name}")
        super.onCodecReleased(name)
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

    private fun resetStatsCounters() {
        minFps = MAX_FPS_STATS
        maxFps = 0
        numChoppyFrames = 0
        for (i in 0 until NUM_FPS_BUCKETS) {
            fpsBuckets[i] = 0
        }
    }

    /**
     * Adds some logging after each buffer processed to keep track of decode
     */
    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        val currentTime = System.currentTimeMillis()
        // If this is the first frame, don't calculate stats
        if (fpsLastLoggedTime == 0L || fpsLastMeasuredTime == 0L) {
            fpsLastLoggedTime = currentTime
            fpsLastMeasuredTime = currentTime
        } else {
            fpsDecodeCounter++
            fpsTotalDecodeCounter++

            // viewModel.updateLog("I have decoded ${decodeCounter} video frames.")
            if (fpsLastMeasuredTime == currentTime) {
                fpsLastMeasuredTime -= 3 // 0ms since last frame, subtract time to avoid divide by 0
            }
            val currentFrameFps = 1000 / (currentTime - fpsLastMeasuredTime)


            // Calculate stats for this frame
            minFps = getMin(minFps, currentFrameFps.toInt())
            maxFps = getMax(maxFps, currentFrameFps.toInt())

            if (currentFrameFps < CHOPPINESS_CUTOFF) {
                numChoppyFrames++
            }

            // Place this frame's fps in the bucket
            val currentfpsBucketIndex = getMin(currentFrameFps.toInt(), MAX_FPS_STATS - 1) / FPS_BUCKET_SIZE
            fpsBuckets[currentfpsBucketIndex]++


            // If this is a logging frame, output states and reset counters
            if (fpsDecodeCounter % LOG_VIDEO_EVERY_N_FRAMES == 0) {
                val averageFps = fpsDecodeCounter / ((currentTime - fpsLastLoggedTime) / 1000.0)

                val averageFpsString = String.format("%.2f", averageFps)
                val choppyString = if (numChoppyFrames >= TOO_MANY_CHOPPY_FRAMES) " ---CHOPPY---" else ""

                // FPS buckets line
                var bucketsString1 = ""
                for (i in 0 until NUM_FPS_BUCKETS) {
                    if (i == NUM_FPS_BUCKETS -1) {
                        // Last bucket
                        bucketsString1 += "[${i * FPS_BUCKET_SIZE}+: ${fpsBuckets[i]}]"
                    } else {
                        bucketsString1 += "[${i * FPS_BUCKET_SIZE}-${(i+1) * FPS_BUCKET_SIZE - 1}: ${fpsBuckets[i]}]    "
                    }
                }
                if (streamNumber == 0)
                    viewModel.updateLog("\n")

                val logString = "V${streamNumber + 1}@frame $fpsTotalDecodeCounter. FPS: min: ${minFps} max: ${maxFps} avg: ${averageFpsString}. Choppy frames: ${numChoppyFrames}${choppyString}.\n\t" +
                    bucketsString1
                viewModel.updateLog(logString)

                fpsLastLoggedTime = currentTime // Update for next fps measurement
                fpsDecodeCounter = 0
                resetStatsCounters()
            }
        }

        if (lastPresentTime == presentationTimeUs && lastPresentTime != 0L) {
            viewModel.updateLog("Last present time is current present time. Frame is stuck! Time: ${presentationTimeUs}")
        }

        lastPresentTime = presentationTimeUs
        mediaClock.updateLastProcessedFrame(presentationTimeUs) // Update media clock with last frame

        fpsLastMeasuredTime = currentTime // Update for next fps measurement
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
