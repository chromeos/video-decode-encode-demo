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

package dev.hadrosaur.videodecodeencodedemo.AudioHelpers

import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.audio.DefaultAudioSink
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer
import com.google.android.exoplayer2.mediacodec.MediaCodecAdapter
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.util.MediaClock
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.SpeedyMediaClock
import java.nio.ByteBuffer


/**
 * Currently just uses the default audio renderer
 */
class VideoMediaCodecAudioRenderer (
    val mainActivity: MainActivity,
    val viewModel: MainViewModel,
    private val streamNumber: Int,
    private val audioMixTrack: AudioMixTrack,
    audioBufferManager: AudioBufferManager?
) : MediaCodecAudioRenderer(mainActivity, MediaCodecSelector.DEFAULT, null, null, CopyAndPlayAudioSink(viewModel, streamNumber, audioMixTrack, audioBufferManager)) {

    private var decodeCounter = 0
    private var startTime = 0L

    /**
     * Return null to indicate to ExoPlayer not to use this clock
     */
    override fun getMediaClock(): MediaClock? {
        return null
    }

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

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        if (startTime == 0L) {
            startTime = System.currentTimeMillis()
        }
        decodeCounter++

        // viewModel.updateLog("I have decoded ${decodeCounter} audio frames.")

        if (decodeCounter % MainActivity.LOG_AUDIO_EVERY_N_FRAMES == 0) {
            val currentBPS =
                decodeCounter / ((System.currentTimeMillis() - startTime) / 1000.0)
            val bpsString = String.format("%.2f", currentBPS)
            viewModel.updateLog("Decoding audio Stream ${streamNumber + 1}: ${bpsString} buf/sec @buffer $decodeCounter.")
        }

        super.onProcessedOutputBuffer(presentationTimeUs)
    }


    /**
     * Override default decoding flags here and record them for VideoEncoder
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxInputSize: Int,
        codecOperatingRate: Float
    ): MediaFormat {
        // Get default mediaFormat
        val mediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)

        // TODO: Verify experimentally if this makes a difference or if ExoPlayer overrides this
        // internally (in MediaCodecRenderer). KEY_OPERATING_RATE will be optimised for battery
        // life on some systems. Here we try to burn battery to increase decoding rate
//        if (Build.VERSION.SDK_INT > 23) {
//            mediaFormat.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt());
//            mediaFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
//        }

        return mediaFormat
    }
    */
}
