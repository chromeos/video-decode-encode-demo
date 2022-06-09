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
    val mediaClock = AudioMixTrackMediaClock(startTimeUs)

    private val audioBuffer = CircularBuffer<AudioBuffer>(BUFFER_LENGTH, AudioMainTrack.createEmptyAudioBuffer())

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
            val chunk = audioBuffer.get()
            var chunkStartUs = chunk.presentationTimeUs;
            var chunkEndUs = chunk.presentationTimeUs + chunk.lengthUs

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

            // If only the first part is needed, clone the chunk and adjust the old/new buffer positions
            // Note if a chunk is larger the the from<->to window, both the above situation and this
            // can apply: chop of some from beginning and some from the end.
            if (chunkStartUs < toUs && chunkEndUs > toUs) {
                val clone = chunk.copy()
                val timeToKeepUs = toUs - chunkStartUs;
                val bytesToKeep = usToBytes(timeToKeepUs)

                // Advance start position of clone and put back in the queue for future playback
                clone.buffer.position(clone.buffer.position() + bytesToKeep)
                clone.presentationTimeUs = toUs
                clone.lengthUs = chunkEndUs - toUs
                clone.size = clone.size - bytesToKeep
                audioBuffer.addTail(clone)

                // Reduce the limit of this chunk to "cut off the end"
                chunk.buffer.limit(chunk.buffer.position() + bytesToKeep)
                chunkEndUs = toUs
                chunk.lengthUs = chunkEndUs - chunkStartUs
                chunk.size = bytesToKeep
            }

            // If we reach this point, chunk is now perfectly in range
            chunksInRange.addLast(chunk)
        }
        return chunksInRange
    }
}