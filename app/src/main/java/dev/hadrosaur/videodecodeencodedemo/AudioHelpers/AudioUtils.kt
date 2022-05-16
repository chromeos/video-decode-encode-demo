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

import android.media.MediaFormat
import android.media.MediaFormat.KEY_CHANNEL_COUNT
import android.media.MediaFormat.KEY_SAMPLE_RATE
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
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
    val frames = bytes.toFloat() / (numChannels * bytesPerFramePerChannel)
    return round((frames * C.MICROS_PER_SECOND) / sampleRate).toLong()
}

fun usToBytes(durationUs: Long) : Int {
    return usToBytes(durationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
}

fun usToBytes(durationUs: Long, numChannels: Int, bytesPerFramePerChannel: Int, sampleRate: Int): Int {
    val frames = round((durationUs.toFloat() * sampleRate) / C.MICROS_PER_SECOND)
    return (frames * bytesPerFramePerChannel * numChannels).toInt()
}

fun usToSeconds(timeUs: Long): Long {
    return round(timeUs.toFloat() / C.MICROS_PER_SECOND).toLong()
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
