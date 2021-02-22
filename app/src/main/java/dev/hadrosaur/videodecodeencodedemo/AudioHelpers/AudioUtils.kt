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
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer

fun getBufferDurationUs(bytes: Int, format: MediaFormat): Long {
    val bytesPerFrame = format.getInteger(KEY_CHANNEL_COUNT) * 2
    val sampleRate = format.getInteger(KEY_SAMPLE_RATE)
    return getBufferDurationUs(bytes, bytesPerFrame, sampleRate)
}

fun getBufferDurationUs(bytes: Int, format: Format): Long {
    val bytesPerFrame = format.channelCount * 2 // 2 bytes per frame for 16-bit PCM
    val sampleRate = format.sampleRate
    return getBufferDurationUs(bytes, bytesPerFrame, sampleRate)
}

fun getBufferDurationUs(bytes: Int, bytesPerFrame: Int, sampleRate: Int): Long {
    val framesWritten = bytes / bytesPerFrame
    return framesWritten * C.MICROS_PER_SECOND / sampleRate
}

fun usToSeconds(timeUs: Long): Float {
    return timeUs / 100000.0F
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

    // Copy original attributes
    clone.position(0)
    clone.limit(original.limit())
    clone.order(original.order())

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

        if (Math.abs(value) > 50) {
            viewModel.updateLog("@time: ${presentationTimeUs} + sample: ${sampleNum}, Audio value: ${value}")
        }
        sampleNum++
    }
}
