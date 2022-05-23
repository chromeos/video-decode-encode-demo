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

import android.media.AudioAttributes.CONTENT_TYPE_MOVIE
import android.media.AudioAttributes.USAGE_MEDIA
import android.media.AudioFormat
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.AudioTrack
import androidx.collection.CircularArray
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.Utils.minOf
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioMainTrack {
    private val audioMixTracks = ArrayList<AudioMixTrack>()

    val MAX_BUFFER_LENGTH = 250 // About 5secs at ~0.02s per buffer
    //val DEFAULT_BUFFERING_DURATION_US = 1024000L // 48 4096-byte buffers
    val DEFAULT_BUFFERING_DURATION_US = 512000L // 24 4096-byte buffers

    enum class STATE {
        STOPPED, PLAYING, PAUSED
    }
    private val audioTrack: AudioTrack
    private var playheadUs = 0L
    private var state = STATE.STOPPED
    private var audioBufferCounter = 0 // monotonically increasing counter

    // Note: CircularArray implementation auto-grows but avoid this if possible below
    private val audioBuffer = CircularArray<AudioBuffer>(MAX_BUFFER_LENGTH)

    init {
        audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(ATTRIB_USAGE)
                        .setContentType(ATTRIB_CONTENT)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_COUNT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
    }

    companion object {
        val BUFFER_SIZE = 4096
        val BUFFER_DURATION = bytesToDurationUs(BUFFER_SIZE, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
        val ENCODING = ENCODING_PCM_16BIT
        val ATTRIB_USAGE = USAGE_MEDIA
        val ATTRIB_CONTENT = CONTENT_TYPE_MOVIE

        /**
         * Create 4096 byte (21333uS) silent audio sample
         */
        fun createEmptyAudioSample(): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            buffer.limit(BUFFER_SIZE)
            buffer.order(ByteOrder.nativeOrder())
            return buffer
        }
    }

    fun addAudioChunk(chunkToAdd: AudioBuffer) {
        audioBuffer.addLast(chunkToAdd)
    }

    fun addMixTrack(streamNum: Int, trackToAdd: AudioMixTrack) {
        audioMixTracks.add(streamNum, trackToAdd)
    }
    fun getMixTrack(streamNum: Int): AudioMixTrack {
        return audioMixTracks[streamNum]
    }

    fun bufferToUs(bufferToPositionUs: Long) {

        // This is where the mixing from incoming AudioMixTracks down to the main track is done
        // This is a "rolling" playback/preview which means all of the buffers from all the tracks
        // are not nicely laid out, we cannot do a simple mix-down. We must do an on-the-fly mix.

        // For the period playheadUs -> bufferToPostionUs, look at each AudioMixTrack for buffers
        // with the correct presentation time. Mix down those
        // chunks into 4096 byte / 21333L uS chunks

        // Apply a gain function of track_output_volume = mix_volume / #_of_tracks_with_audio at any
        // given chunk in the main mix. So if 4 tracks have a audio for a given chunk, they will
        // each be mixed in at 25% gain.

        // Assume each AudioMixTrack's chunks are in order

        // Create a series of BUFFER_SIZE byte audio buffers for this range and mix down all the mix
        // tracks into it
        val bufferToDurationUs = bufferToPositionUs - playheadUs
        val bufferToBytes = usToBytes(bufferToDurationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
        val numMainAudioBuffers = (bufferToBytes + BUFFER_SIZE - 1) / BUFFER_SIZE // Ceil
        var bufferingPlayheadUs = playheadUs

        for (i in 0 until numMainAudioBuffers) {
            var chunkDurationUs = bufferToPositionUs - bufferingPlayheadUs
            if (chunkDurationUs > BUFFER_DURATION) {
                chunkDurationUs = BUFFER_DURATION
            }
            val chunkSize = usToBytes(chunkDurationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)

            val newAudioBuffer = AudioBuffer (
                createEmptyAudioSample(),
                audioBufferCounter++,
                bufferingPlayheadUs,
                chunkDurationUs,
                chunkSize)
            newAudioBuffer.buffer.limit(chunkSize) // In case this is not a full buffer

            // logd("New main buffer: duration: ${chunkDurationUs} buffersize: ${BUFFER_SIZE} bufferduration: ${BUFFER_DURATION} chunk size: ${chunkSize}, remaining: ${newAudioBuffer.buffer.remaining()}")
            // Find groups of AudioBuffers from each mix track with audio within the buffer's range
            // Note: there may be mix tracks without any audio in this time range
            val AudioBufferArraysInRange = ArrayList<CircularArray<AudioBuffer>>()
            for (mixTrack in audioMixTracks) {
                // logd("Popping chunks from ${bufferingPlayheadUs} to ${bufferingPlayheadUs + chunkDurationUs}")
                val AudioBufferArray = mixTrack.popChunksFromTo(bufferingPlayheadUs, bufferingPlayheadUs + chunkDurationUs)
                if (!AudioBufferArray.isEmpty) {
                    AudioBufferArraysInRange.add(AudioBufferArray)
                }
            }

            // Calculate the first byte location mixed to. This might not be the beginning of the
            // buffer. The first track cannot just mix down directly to the beginning of the buffer
            // because there may be silence at the beginning of first track and audio for other mix
            // tracks. This will be unknown until the track is fully mixed
            var earliestPositionMixed = newAudioBuffer.buffer.limit()
            val numTracksToMix = AudioBufferArraysInRange.size

            for (AudioBufferArray in AudioBufferArraysInRange) {
                // Mix the audiobuffer array to the main track with gain adjustment
                val mixStartPos = mixAudioByteBuffer(newAudioBuffer, AudioBufferArray, 1F/numTracksToMix)
                earliestPositionMixed = earliestPositionMixed.coerceAtMost(mixStartPos)
            }

            // Add newly mixed audio buffer if any mix tracks contained audio
            if (numTracksToMix > 0) {
                // logd("New remain: ${newAudioBuffer.buffer.remaining()}, New pos: ${newAudioBuffer.buffer.position()}, New limit: ${newAudioBuffer.buffer.limit()}. Earliest mixed: ${earliestPositionMixed}")
                newAudioBuffer.buffer.position(earliestPositionMixed) // Adjust start
                addAudioChunk(newAudioBuffer)
            }

            //logd("New remain: ${newAudioBuffer.buffer.remaining()}, New pos: ${newAudioBuffer.buffer.position()}, New limit: ${newAudioBuffer.buffer.limit()}")
            bufferingPlayheadUs += chunkDurationUs
        } // For all main audio buffers in this buffering duration

    }

    fun updateMixTrackMediaClocks(newPositionUs: Long) {
        for (mixTrack in audioMixTracks) {
            mixTrack.mediaClock.updatePositionFromMain(newPositionUs)
        }
    }

    /**
     * Get the earliest presentation time available from the mix tracks
     *
     * Return Long.MAX_VALUE if no audio yet
     */
    fun getFirstPresentationTimeUs() : Long {
        var earliestUs = Long.MAX_VALUE
        for (mixTrack in audioMixTracks) {
            val mixTrackEarliestUs = mixTrack.getFirstPresentationTimeUs()
            earliestUs = earliestUs.coerceAtMost(mixTrackEarliestUs) // Math.min
        }
        return earliestUs
    }

    fun start() {
        playheadUs = getFirstPresentationTimeUs()

        state = STATE.PLAYING
        audioTrack.play()
        playMainAudio()
    }

    fun stop() {
        state = STATE.STOPPED
        audioTrack.stop()
    }

    fun playMainAudio() {

/*
        val audioTrackBufferSize = audioTrack.bufferSizeInFrames

        while (state == STATE.PLAYING) {
            // If no audio in the mix tracks yet, playhead is Long.MAX_VALUE: don't buffer or play
            if (playheadUs < Long.MAX_VALUE){
                bufferToUs(playheadUs + DEFAULT_BUFFERING_DURATION_US)
            } else {
                playheadUs = getFirstPresentationTimeUs()
                continue
            }

            // Play whatever has been mixed-down
            while (!audioBuffer.isEmpty) {
                // We have some data
                val chunk = audioBuffer.popFirst()
                val buffer = chunk.buffer
                var chunkTimePlayedUs = 0L

                // audioTrackBufferSize may be less than bytesToPlay. Play one chunk at a time
                chunkTimePlayedUs += playBytes(buffer, audioTrackBufferSize)

                // Update the main track playhead and the media clock for the mix tracks
                if (chunkTimePlayedUs > 0) {
                    // logd("MAIN OLD playhead: ${playheadUs}, chunkPres: ${chunk.presentationTimeUs}, time played: ${chunkTimePlayedUs}")
                    playheadUs = chunk.presentationTimeUs + chunkTimePlayedUs
                    updateMixTrackMediaClocks(playheadUs)
                }
            }
        }

 */
    }

    fun playBytes(buffer: ByteBuffer, maxFrames: Int) : Long {
        var bytesToPlay: Int
        var timePlayedUs = 0L

        while (buffer.remaining() > 0) {
            bytesToPlay = minOf(buffer.remaining(), maxFrames)
            //logd("MAIN AUDIO: remaining: ${buffer.remaining()}, toPlay: ${bytesToPlay}, buffersize: ${audioTrack.bufferSizeInFrames}")
            val bytesPlayed = audioTrack.write(buffer, bytesToPlay, AudioTrack.WRITE_BLOCKING)
            timePlayedUs += bytesToDurationUs(bytesPlayed)
             logd("MAIN AUDIO played ${timePlayedUs/1000}ms, Tried: ${bytesToPlay} and played: ${bytesPlayed}")

            // If AudioTrack.write did not succeed, this loop can get stuck. Just exit.
            if (bytesPlayed <= 0) {
                logd("Malformed buffer, skipping chunk")
                break
            }
        }

        return timePlayedUs
    }
}