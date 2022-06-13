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

package dev.hadrosaur.videodecodeencodedemo.AudioHelpers

import android.media.AudioFormat
import android.media.AudioFormat.*
import android.media.AudioTrack
import android.media.MediaFormat
import android.media.MediaFormat.KEY_CHANNEL_COUNT
import android.media.MediaFormat.KEY_SAMPLE_RATE
import androidx.collection.CircularArray
import com.google.android.exoplayer2.C
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.round

// Default to 16-bit, 2 channel, 48KHz PCM
val BYTES_PER_FRAME_PER_CHANNEL = 2 // 2 bytes per frame for 16-bit PCM
val CHANNEL_COUNT = 2
val SAMPLE_RATE = 48000

fun bytesToDurationUs(bytes: Int, format: MediaFormat): Long {
    val sampleRate = format.getInteger(KEY_SAMPLE_RATE)
    return bytesToDurationUs(bytes, format.getInteger(KEY_CHANNEL_COUNT), BYTES_PER_FRAME_PER_CHANNEL, sampleRate)
}

fun bytesToDurationUs(bytes: Int) : Long {
    return bytesToDurationUs(bytes, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
}

fun bytesToDurationUs(bytes: Int, numChannels: Int, bytesPerFramePerChannel: Int, sampleRate: Int): Long {
    val frameSize = numChannels * bytesPerFramePerChannel
    val frames = bytes.toFloat() / frameSize
    return round((frames * C.MICROS_PER_SECOND) / sampleRate).toLong()
}

fun usToBytes(durationUs: Long) : Int {
    return usToBytes(durationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
}

fun usToBytes(durationUs: Long, numChannels: Int, bytesPerFramePerChannel: Int, sampleRate: Int): Int {
    val frames = round((durationUs.toFloat() * sampleRate) / C.MICROS_PER_SECOND)
    val frameSize = numChannels * bytesPerFramePerChannel
    return (frames * frameSize).toInt()
}

fun usToSeconds(timeUs: Long): Float {
    return timeUs / 1000000.0F
}

fun usToSecondsString(timeUs: Long): String {
    return usToSeconds(timeUs).toString()
}

// Copy a ByteBuffer (only data between position and limit)
// Adapted from: https://stackoverflow.com/a/21388198/3151916
fun cloneByteBuffer(original: ByteBuffer): ByteBuffer {
    // Create a read-only copy of the original so position is not altered while reading
    val readOnlyCopy = original.asReadOnlyBuffer()

    // Note: this does not need to be released manually
    val clone =
        if (readOnlyCopy.isDirect)
            ByteBuffer.allocateDirect(readOnlyCopy.remaining())
        else
            ByteBuffer.allocate(readOnlyCopy.remaining())

    // Copy from the original.
    clone.put(readOnlyCopy)

    // Reset position to start of buffer
    clone.flip()
    clone.order(original.order()) // Note: asReadOnlyBuffer does not preserve endian-ness
    return clone
}

fun copyIntoByteBuffer(source: ByteBuffer, destination: ByteBuffer) {
    val readOnlyCopy = source.asReadOnlyBuffer()
    destination.clear()
    destination.order(source.order())
    destination.put(readOnlyCopy)
    destination.flip()
}

/**
 * Mixes an array of AudioBuffers into the given mainAudio, applying gain.
 *
 * Assumes samples in mixAudioArray are 16-bits and in order.
 */
fun mixAudioByteBuffer(mainAudio: AudioBuffer, mixAudioArray: CircularArray<AudioBuffer>, gain: Float = 0.5F) {
    val TOLERANCE_US = 21 // 1 frame 16-bit stereo PCM = 20.8us, tolerate presentation time differences smaller than this
    val mainAudioInitPosition = mainAudio.buffer.position()
    var mainAudioMixheadUs = mainAudio.presentationTimeUs

    while (!mixAudioArray.isEmpty) {
        val mixAudioBuffer = mixAudioArray.popFirst()
        var timeToAdvanceMixHead = 0L

        if (mixAudioBuffer != null) {
            // mixAudioBuffer is a single, continuous audio buffer to mix into main
            val mixInitPosition = mixAudioBuffer.buffer.position() // Save init position
            val mixToMainDeltaUs = mixAudioBuffer.presentationTimeUs - mainAudioMixheadUs

            // Skip bytes from main if mix start position is ahead of main position
            if (mixToMainDeltaUs > TOLERANCE_US) {
                val timeToSkipUs = mixToMainDeltaUs
                val bytesToSkip = usToBytes(timeToSkipUs)

                // logd("Skipping ${bytesToSkip} because mix audio is after main presentation. mainstart: ${mainAudio.presentationTimeUs}, toskip: ${timeToSkipUs}, mixBuff: ${mixAudioBuffer.presentationTimeUs}. bufPos: ${mainAudio.buffer.position()}, bufLimit: ${mainAudio.buffer.limit()}")
                if (mainAudio.buffer.position() + bytesToSkip >= mainAudio.buffer.limit()) {
                    continue // mix track starts after mainAudio limit so do not mix
                }

                // Advance the mainAudio position to the mix position
                mainAudio.buffer.position(mainAudio.buffer.position() + bytesToSkip)
                timeToAdvanceMixHead += timeToSkipUs
            }

            // Skip bytes from mix if main start position is ahead of mix position more than 100us
            if (mixToMainDeltaUs < TOLERANCE_US * -1) {
                val timeToSkipUs = mainAudioMixheadUs - mixAudioBuffer.presentationTimeUs
                val bytesToSkip = usToBytes(timeToSkipUs)
                val newPosition = minOf(mixAudioBuffer.buffer.limit(), mixAudioBuffer.buffer.position() + bytesToSkip)
                mixAudioBuffer.buffer.position(newPosition)
                // logd("Skipping ${timeToSkipUs}us and ${bytesToSkip} bytes because main audio is after mix presentation")
            }

            // Actually mix the audio samples from mixAudioBuffer to main
            var bytesMixed = 0
            while (mainAudio.buffer.remaining() > 0 && mixAudioBuffer.buffer.remaining() > 0) {
                val mainSample = mainAudio.buffer.getShort(mainAudio.buffer.position()) // get no advance
                val mixSample = mixAudioBuffer.buffer.getShort().toFloat() // get and advance
                val mixSampleWithGain = round(mixSample * gain).toInt() // apply gain
                mainAudio.buffer.putShort((mainSample + mixSampleWithGain).toShort()) // put and advance
                bytesMixed += 2
            }
            mixAudioBuffer.buffer.position(mixInitPosition) // Restore mix buffer position
            timeToAdvanceMixHead += bytesToDurationUs(bytesMixed)

            mainAudioMixheadUs += timeToAdvanceMixHead
        }
    } // while audio buffers to mix

    mainAudio.buffer.position(mainAudioInitPosition) // Re-set main position
}

/**
 * Helper function for building an AudioFormat
 */
fun getAudioFormat(sampleRate: Int, channelMask: Int, encoding: Int): AudioFormat {
    return AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()
}

/**
 * Helper function for building AudioAttributes
 */
fun getVideoPlaybackAudioTrackAttributes() : android.media.AudioAttributes {
    return android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
}

/**
 * Convert number of channels to an AudioTrack channel mask
 */
fun channelCountToChannelMask(channelCount: Int): Int {
    return when (channelCount) {
        1 -> CHANNEL_OUT_MONO
        2 -> CHANNEL_OUT_STEREO
        4 -> CHANNEL_OUT_QUAD
        else -> 0
    }
}

fun createPcmAudioTrack(): AudioTrack {
    val channelMask = channelCountToChannelMask(CHANNEL_COUNT) // Note channel count != mask
    val audioFormat: AudioFormat =
        getAudioFormat(SAMPLE_RATE, channelMask, ENCODING_PCM_16BIT)
    val audioTrackAttributes = getVideoPlaybackAudioTrackAttributes()
    return AudioTrack.Builder()
        .setAudioAttributes(audioTrackAttributes)
        .setAudioFormat(audioFormat)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(4096 * 1)
        .build()
}

/**
 * Logs value of byte buffer if absolute value is > 50
 */
fun logAudioBufferValues(viewModel: MainViewModel, original: ByteBuffer, presentationTimeUs: Long = 0L) {
    // Create a read-only copy of the original so position is not altered while reading
    val readOnlyCopy = original.asReadOnlyBuffer()

    var sampleNum = 0
    while (readOnlyCopy.remaining() > 0) {
        val value = readOnlyCopy.get().toLong()

        if (abs(value) > 50) {
            viewModel.updateLog("@time: ${presentationTimeUs} + sample: ${sampleNum}, Audio value: ${value}")
        }
        sampleNum++
    }
}