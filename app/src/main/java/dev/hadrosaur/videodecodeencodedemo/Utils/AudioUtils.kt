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

import android.media.AudioFormat.*
import android.media.MediaFormat
import android.media.MediaFormat.KEY_CHANNEL_COUNT
import android.media.MediaFormat.KEY_SAMPLE_RATE
import androidx.collection.CircularArray
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.round

// Default to 16-bit, 2 channel, 48KHz PCM
// Note: 44 KHz streams need conversion
val BYTES_PER_FRAME_PER_CHANNEL = 2 // 2 bytes per frame for 16-bit PCM
val CHANNEL_COUNT = 2
//val SAMPLE_RATE = 96000 // This is weird.
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

fun channelCountToChannelMask(channelCount: Int): Int {
    return when (channelCount) {
        1 -> CHANNEL_OUT_MONO
        2 -> CHANNEL_OUT_STEREO
        4 -> CHANNEL_OUT_QUAD
        else -> 0
    }

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

/**
 * Mixes array of AudioBuffers into mainAudio with given gain
 *
 * Assume mixAudioArray is in order
 *
 * Returns earliest position written to
 */
fun mixAudioByteBuffer(mainAudio: AudioBuffer, mixAudioArray: CircularArray<AudioBuffer>, gain: Float = 0.5F) : Int {
    val initPosition = mainAudio.buffer.position()
    var earliestPositionMixed = mainAudio.buffer.limit()
    var bytesMixed = 0

    while (!mixAudioArray.isEmpty) {
        val mixAudioBuffer = mixAudioArray.popFirst()
        if (mixAudioBuffer != null) {
            val timeMixedSoFar = bytesToDurationUs(bytesMixed)
            val mixInitPosition = mixAudioBuffer.buffer.position()

            //logd("Checking. mainstart: ${mainAudio.presentationTimeUs}, main length: ${mainAudio.lengthUs}, mixed: ${timeMixedSoFar}, mixBuff start: ${mixAudioBuffer.presentationTimeUs}, mix length: ${mixAudioBuffer.lengthUs}")
            // Skip bytes from main if mix start position is ahead of main position
            if (mixAudioBuffer.presentationTimeUs > mainAudio.presentationTimeUs + timeMixedSoFar) {
                val timeToSkipUs = mixAudioBuffer.presentationTimeUs - (mainAudio.presentationTimeUs + timeMixedSoFar)
                val bytesToSkip = usToBytes(timeToSkipUs)

                //logd("Skipping ${bytesToSkip} because mix audio is after main presentation. mainstart: ${mainAudio.presentationTimeUs}, mixed: ${timeMixedSoFar}, toskip: ${timeToSkipUs}, mixBuff: ${mixAudioBuffer.presentationTimeUs}. bufPos: ${mainAudio.buffer.position()}, bufLimit: ${mainAudio.buffer.limit()}")
                if (mainAudio.buffer.position() + bytesToSkip > mainAudio.buffer.limit()) {
                    continue
                }
                val newPosition = mainAudio.buffer.limit().coerceAtMost(mainAudio.buffer.position() + bytesToSkip)
                mainAudio.buffer.position(newPosition)
            }

            // Skip bytes from mix if main start position is ahead of mix position
            if (mainAudio.presentationTimeUs + timeMixedSoFar > mixAudioBuffer.presentationTimeUs) {
                //logd("Main start is ahead of mix. ${mainAudio.presentationTimeUs} + so far: ${timeMixedSoFar} and mix presentation: ${mixAudioBuffer.presentationTimeUs}")
                val timeToSkipUs = mainAudio.presentationTimeUs + timeMixedSoFar - mixAudioBuffer.presentationTimeUs
                val bytesToSkip = usToBytes(timeToSkipUs)
                val newPosition = mixAudioBuffer.buffer.limit().coerceAtMost(mixAudioBuffer.buffer.position() + bytesToSkip)
                mixAudioBuffer.buffer.position(newPosition)
                // logd("SKipping ${bytesToSkip} because main audio is after mix presentation")
            }

            // Keep track of the earliest place in main that is mixed too
            if (mainAudio.buffer.remaining() > 0 && mixAudioBuffer.buffer.remaining() > 0) {
                earliestPositionMixed = earliestPositionMixed.coerceAtMost(mainAudio.buffer.position())
            }

            // logd("main pos: ${mainAudio.buffer.position()}, remain: ${mainAudio.buffer.remaining()}, mix remain: ${mixAudioBuffer.buffer.remaining()}")
            while (mainAudio.buffer.remaining() > 0 && mixAudioBuffer.buffer.remaining() > 0) {
                val mainSample = mainAudio.buffer.getShort(mainAudio.buffer.position()) // get no advance
                val mixSample = round(mixAudioBuffer.buffer.getShort().toFloat() * gain).toInt() // get and advance
                mainAudio.buffer.putShort((mainSample + mixSample).toShort()) // put and advance
                bytesMixed += 2
            }
            mixAudioBuffer.buffer.position(mixInitPosition) // Restore mix buffer position
        }

        //logd("Mixed down ${bytesMixed} bytes and ${bytesToDurationUs(bytesMixed) / 1000}ms from ${mixAudioBuffer.presentationTimeUs} to ${mixAudioBuffer.presentationTimeUs + mixAudioBuffer.lengthUs}. Check remaining: ${mainAudio.buffer.remaining()}")
    }

    mainAudio.buffer.position(earliestPositionMixed) // Set start position
    mainAudio.buffer.limit(earliestPositionMixed + bytesMixed)
    return earliestPositionMixed
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
