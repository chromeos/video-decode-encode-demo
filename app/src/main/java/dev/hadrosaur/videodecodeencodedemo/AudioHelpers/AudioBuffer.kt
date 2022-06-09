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

import android.provider.MediaStore.Audio
import dev.hadrosaur.videodecodeencodedemo.Utils.Copyable
import java.nio.ByteBuffer
import java.util.*

/**
 * Holds an audio buffer and meta-data for encoding
 *
 * @param buffer The ByteBuffer containing audio data
 * @param id ID int used for debugging and logging purposes
 * @param presentationTimeUs presentation time of the buffer
 * @param lengthUs length of audio sample in uS
 * @param size size of sample in bytes
 * @param isLastBuffer flag to indicate this is the last audio sample in a stream
 */
class AudioBuffer (
    val buffer: ByteBuffer,
    var id: Int,
    var presentationTimeUs: Long,
    var lengthUs: Long,
    var size: Int,
    var isLastBuffer: Boolean = false) : Copyable {

    /**
     * Duplicate the Audio buffer, making a deep copy of the ByteBuffer
     */
    fun copy() : AudioBuffer {
        val clone = AudioBuffer(
                cloneByteBuffer(buffer),
                id,
                presentationTimeUs,
                lengthUs,
                buffer.remaining(),
                isLastBuffer
        )
        return clone
    }
    override fun createCopy(): Copyable {
        return copy()
    }

    /**
     * Actually zero the buffer. This is so we can re-use it for mixing
     */
    fun zeroBuffer() {
        buffer.clear()
        Arrays.fill(buffer.array(), 0.toByte())
        buffer.clear()
    }
}