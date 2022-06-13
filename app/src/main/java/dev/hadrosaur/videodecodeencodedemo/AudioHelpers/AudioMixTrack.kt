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

import androidx.collection.CircularArray
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.Utils.CircularBuffer

class AudioMixTrack(startTimeUs: Long = 0L) {
    val BUFFER_LENGTH = 250 // About 5secs at ~0.02s per buffer
    val SPLIT_BUFFER_LENGTH = 50 // Allocate some temporary space for split audio buffers
    val mediaClock = AudioMixTrackMediaClock(startTimeUs)

    private val audioBuffer = CircularBuffer<AudioBuffer>(BUFFER_LENGTH, AudioMainTrack.createEmptyAudioBuffer())
    // This is temporary storage space, we will not pull from this buffer so allow overwriting
    private val splitBuffer = CircularBuffer<AudioBuffer>(SPLIT_BUFFER_LENGTH,
        AudioMainTrack.createEmptyAudioBuffer(),
        CircularBuffer.FULL_BEHAVIOUR.OVERWRITE)

    /**
     * Indicate if the buffer already has BUFFER_LENGTH - 1 elements so as not to make it auto-grow
     */
    fun isFull() : Boolean {
        return audioBuffer.isFull()
    }
    fun getFirstPresentationTimeUs() : Long {
        return if (audioBuffer.isEmpty()) {
            Long.MAX_VALUE
        } else {
            // Buffers are in order so beginning of first buffer is first presentation time
            audioBuffer.peek().presentationTimeUs
        }
    }

    fun reset() {
        audioBuffer.clear()
        mediaClock.reset()
    }

    fun addAudioChunk(chunkToAdd: AudioBuffer) {
        audioBuffer.add(chunkToAdd)
    }

    // Pop audio chunk and update playhead. Note: mediaClock is not updated here. It is expected to
    // be updated manually from the main track
    fun popAudioChunk(): AudioBuffer? {
        if (!audioBuffer.isEmpty()) {
            return null
        }
        return audioBuffer.get()
    }

    // Pop all chunks that contain some audio >= fromUs and < toUs
    // This will simply discard chunks that are too early
    fun popChunksFromTo(fromUs: Long, toUs: Long) : CircularArray<AudioBuffer> {
        val chunksInRange = CircularArray<AudioBuffer>(2)

        while (!audioBuffer.isEmpty()) {
            var chunk = audioBuffer.get()
            var chunkStartUs = chunk.presentationTimeUs;
            var chunkEndUs = chunk.presentationTimeUs + chunk.lengthUs
            var splitBufferPointer: AudioBuffer? = null
            var isSplitBuffer = false

            // If the next chunk is after the range (starts at or after toUs), put it back and exit
            if (chunkStartUs >= toUs) {
                audioBuffer.rewindTail() // This "puts back" the chunk, avoiding a copy
                break
            }

            // If the chunk is earlier than the range, simply discard it
            if (chunkEndUs < fromUs) {
                continue
            }

            //TODO: If time doesn't line up exactly on a short boundary, there could be data loss
            // or silence added here. Maybe presentation times need to be byte/short aligned?
            // Or from -> to times?

            // Only latter part of chunk required, chop off beginning part and add chunk
            if (chunkStartUs < fromUs && chunkEndUs > fromUs) {
                val timeToDiscard = fromUs - chunkStartUs
                val bytesToDiscard = usToBytes(timeToDiscard)
                chunk.buffer.position(chunk.buffer.position() + bytesToDiscard)
                chunkStartUs = fromUs // Update new start for next check
            }

            // If only the first part is needed, copy the first part of the chunk into the split
            // buffer queue and adjust the old/new buffer positions. Note if a chunk is larger than
            // the from<->to window, both the above situation and this can apply: chop of some from
            // beginning and some from the end.
            if (chunkStartUs < toUs && chunkEndUs > toUs) {
                val timeToKeepUs = toUs - chunkStartUs;
                val bytesToKeep = usToBytes(timeToKeepUs)

                // Copy into split buffer queue and reduce the limit to "cut off the end"
                splitBufferPointer = splitBuffer.peekHead() // Get pointer to storage location
                splitBuffer.add(chunk, true) // Copy full chunk into split buffer queue
                // Adjust the copy to just be the first part
                splitBufferPointer.buffer.limit(chunk.buffer.position() + bytesToKeep)
                splitBufferPointer.lengthUs = toUs - chunkStartUs
                splitBufferPointer.size = bytesToKeep
                isSplitBuffer = true

                // Advance start position of original buffer and rewind queue to cut off front and
                // "put it back" in the queue for future playback
                chunk.buffer.position(chunk.buffer.position() + bytesToKeep)
                chunk.presentationTimeUs = toUs
                chunk.lengthUs = chunkEndUs - toUs
                chunk.size = chunk.size - bytesToKeep
                audioBuffer.rewindTail()
            }

            // If we reach this point, chunk is now perfectly in range
            if (isSplitBuffer) {
                chunksInRange.addLast(splitBufferPointer)
            } else {
                chunksInRange.addLast(chunk)
            }
        }

        return chunksInRange
    }
}