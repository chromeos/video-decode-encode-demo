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

class AudioMixTrack(val startTimeUs: Long = 0L) {
    val BUFFER_LENGTH = 250 // About 5secs at ~0.02s per buffer
    val mediaClock = AudioMixTrackMediaClock(startTimeUs)

    private val audioBuffer = CircularArray<AudioBuffer>(BUFFER_LENGTH)

    fun hasData() : Boolean {
        return !audioBuffer.isEmpty
    }

    fun getFirstPresentationTimeUs() : Long {
        if (audioBuffer.isEmpty) {
            return Long.MAX_VALUE
        } else {
            // Buffers are in order so beginning of first buffer is first presentation time
            return audioBuffer.get(0).presentationTimeUs
        }
    }

    fun addAudioChunk(chunkToAdd: AudioBuffer) {
        audioBuffer.addLast(chunkToAdd)
    }

    // Pop audio chunk and update playhead. Note: do not update mediaClock as this needs to be
    // sync'd with the main audio track
    fun popAudioChunk(): AudioBuffer? {
        if (audioBuffer.isEmpty) {
            logd("ATTENTION: No data in mixaudiotrack")
        }

        val chunk = audioBuffer.popFirst()

        if (chunk == null) {
            return null
        }
        return chunk
    }

    // Pop all chunks that contain some audio >= fromUs and < toUs
    // This will simply discard chunks that are too early
    fun popChunksFromTo(fromUs: Long, toUs: Long) : CircularArray<AudioBuffer> {
        val chunksInRange = CircularArray<AudioBuffer>(5)

        // logd("Popping chunks from ${fromUs} to ${toUs}")
        while (!audioBuffer.isEmpty) {
            val chunk = audioBuffer.popFirst()
            if (chunk == null) {
                logd("Chunk is null exiting")
                break
            }
            var chunkStartUs = chunk.presentationTimeUs;
            var chunkEndUs = chunk.presentationTimeUs + chunk.lengthUs

            // logd("Chunk is popped: start: ${chunk.presentationTimeUs}, length: ${chunk.lengthUs}, end: ${chunkEndUs}, size: ${chunk.buffer.remaining()}. From: ${fromUs}, To: ${toUs}")

            // If the next chunk is after the range (starts at or after toUs), put it back and exit
            if (chunkStartUs >= toUs) {
                // logd("Chunk start is too late ${chunkStartUs} >= ${toUs}")
                audioBuffer.addFirst(chunk) // TODO: avoid popping and replacing
                break
            }

            // If the chunk is earlier than the range, simply discard it
            if (chunkEndUs < fromUs) {
                // logd("Chunk end is too early ${chunkEndUs} < ${fromUs}")
                continue
            }

            // It might be that only a part of a chunk is needed
            // Only latter part of chunk required, chop off beginning part and add chunk
            if (chunkStartUs < fromUs && chunkEndUs > fromUs) {
                val timeToDiscard = fromUs - chunkStartUs
                val bytesToDiscard = usToBytes(timeToDiscard)
                chunk.buffer.position(chunk.buffer.position() + bytesToDiscard)
                chunkStartUs = fromUs // Update new start for next check
                // logd("Cut off start of chunk: New state: ${chunkStartUs} to ${chunkEndUs}")
            }

            // If only the first part is needed, clone the chunk and adjust the old/new buffer positions
            // Note if a chunk is larger the the from<->to window, both the above situation and this
            // can apply: chop of some from beginning and some from the end.
            if (chunkStartUs < toUs && chunkEndUs > toUs) {
                val clone = chunk.cloneAudioBuffer()
                val timeToKeepUs = toUs - chunkStartUs;
                val bytesToKeep = usToBytes(timeToKeepUs)

                // logd("chunk start: ${chunkStartUs}, chunk end : ${chunkEndUs}, toUs: ${toUs}, time to keep ${timeToKeepUs}, bytesToKeep: ${bytesToKeep}")
                // Advance start position of clone and put back in the queue for future playback
                clone.buffer.position(clone.buffer.position() + bytesToKeep)
                clone.presentationTimeUs = toUs
                clone.lengthUs = chunkEndUs - toUs
                clone.size = clone.size - bytesToKeep
                audioBuffer.addFirst(clone)

                // Reduce the limit of this chunk to "cut off the end"
                chunk.buffer.limit(chunk.buffer.position() + bytesToKeep)
                chunkEndUs = toUs
                chunk.lengthUs = chunkEndUs - chunkStartUs
                chunk.size = bytesToKeep
                // logd("Cut off end of chunk: New state: ${chunkStartUs} to ${chunkEndUs}")
            }

            // If we reach this point, chunk is now a chunk that is perfectly in range
            chunksInRange.addLast(chunk)
        }
        // logd("Returning ${chunksInRange.size()} chunks in range.")
        return chunksInRange
    }
}