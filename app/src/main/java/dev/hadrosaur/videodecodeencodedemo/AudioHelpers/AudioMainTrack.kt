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

import android.media.AudioTrack
import androidx.collection.CircularArray
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.Utils.CircularBuffer
import dev.hadrosaur.videodecodeencodedemo.Utils.minOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.ArrayList

class AudioMainTrack {
    private val audioMixTracks = ArrayList<AudioMixTrack>()

    val MAX_BUFFER_LENGTH = 250 // About 5secs at ~0.02s per buffer
    //val DEFAULT_BUFFERING_DURATION_US = 1024000L // 48 4096-byte buffers
    //val DEFAULT_BUFFERING_DURATION_US = 512000L // 24 4096-byte buffers
    val DEFAULT_BUFFERING_DURATION_US = 128000L // 6 4096-byte buffers

    enum class STATE {
        STOPPED, PLAYING, PAUSED
    }
    private val audioTrack: AudioTrack
    private var playheadUs = 0L
    private var state = STATE.STOPPED
    private var audioBufferCounter = 0 // monotonically increasing counter

    private val UNDERRUN_TOLERANCE = 1 // How many underruns can occur before logging
    private var lastUnderrunCount = 0
    private var lastUnderrunCountTimeMs = 0L

    private var isMuted = false

    private val audioBuffer = CircularBuffer<AudioBuffer>(MAX_BUFFER_LENGTH, createEmptyAudioBuffer())

    init {
        // Note: AudioMainTrack assumes audio samples are 16-bit PCM at 48kHz. It does not currently
        // respect the input format of the AudioSink. This could be a problem on some devices
        audioTrack = createPcmAudioTrack()
    }

    companion object {
        val BUFFER_SIZE = 4096
        val BUFFER_DURATION = bytesToDurationUs(BUFFER_SIZE, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)

        /**
         * Create 4096 byte (21333uS) silent audio sample
         */
        fun createEmptyAudioSample(): ByteBuffer {
            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            buffer.limit(BUFFER_SIZE)
            buffer.order(ByteOrder.nativeOrder())
            return buffer
        }

        fun createEmptyAudioBuffer() : AudioBuffer {
            return AudioBuffer(createEmptyAudioSample(), 0, 0L, BUFFER_DURATION, BUFFER_SIZE)
        }
    }

    fun reset() {
        stop()
        playheadUs = 0L
        audioMixTracks.clear()
    }

    fun addAudioChunk(chunkToAdd: AudioBuffer) {
        audioBuffer.add(chunkToAdd)
    }

    fun addMixTrack(trackToAdd: AudioMixTrack) {
        audioMixTracks.add(trackToAdd)
    }

    // Create an empty audio buffer and mix down all mix tracks into it up to bufferToPositionUs.
    // This is a "rolling" mix-down, mixing from arbitrarily aligned AudioMixTracks down into the
    // playback buffer on-the-fly.
    // Gain function: output_volume = mix_volume / #_of_tracks, eg. 4 tracks == 25% gain each.
    fun bufferAndMixToUs(bufferToPositionUs: Long) {
        val bufferToDurationUs = bufferToPositionUs - playheadUs
        val bufferToBytes = usToBytes(bufferToDurationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)
        val numMainAudioBuffersRequired = (bufferToBytes + BUFFER_SIZE - 1) / BUFFER_SIZE // Ceil
        var bufferingPlayheadUs = playheadUs

        for (i in 0 until numMainAudioBuffersRequired) {
            var chunkDurationUs = bufferToPositionUs - bufferingPlayheadUs // Remaining time
            // Don't buffer more than main audio buffer's duration
            if (chunkDurationUs > BUFFER_DURATION) {
                chunkDurationUs = BUFFER_DURATION
            }
            val chunkSize = usToBytes(chunkDurationUs, CHANNEL_COUNT, BYTES_PER_FRAME_PER_CHANNEL, SAMPLE_RATE)

            // Get the next buffer from the ring buffer. Zero it and set it up for mixing
            val newAudioBuffer = audioBuffer.peekHead()
            newAudioBuffer.zeroBuffer()
            newAudioBuffer.buffer.limit(chunkSize) // May not be a full buffer
            newAudioBuffer.id = audioBufferCounter++
            newAudioBuffer.presentationTimeUs = bufferingPlayheadUs
            newAudioBuffer.lengthUs = chunkDurationUs
            newAudioBuffer.size = chunkSize

            // Find groups of AudioBuffers from each mix track with audio within the buffer's range
            // Note: there may be mix tracks without any audio in this time range
            val AudioBufferArraysInRange = ArrayList<CircularArray<AudioBuffer>>()
            for (mixTrack in audioMixTracks) {
                val AudioBufferArray = mixTrack.popChunksFromTo(bufferingPlayheadUs, bufferingPlayheadUs + chunkDurationUs)
                if (!AudioBufferArray.isEmpty) {
                    AudioBufferArraysInRange.add(AudioBufferArray)
                }
            }

            // Gain: apply a simple gain by mixing in tracks at 1/X of their volume, where X is the
            // the total number of mix tracks. Note: If a track is silent, this will still reduce
            // the volume of all other tracks. Normalization could be done here instead.
            val gain = 1F/audioMixTracks.size

            for (AudioBufferArray in AudioBufferArraysInRange) {
                // Mix the audiobuffer array into the main track with gain adjustment
                mixAudioByteBuffer(newAudioBuffer, AudioBufferArray, gain)
            }

            // Add the now fully mixed main audio buffer for playback
            addAudioChunk(newAudioBuffer)

            bufferingPlayheadUs += chunkDurationUs
        } // For all main audio buffers in this buffering duration
    }

    fun advanceMixTrackMediaClocks() {
        for (mixTrack in audioMixTracks) {
            mixTrack.mediaClock.tick()
        }
    }
    fun updateMixTrackMediaClocks(newPositionUs: Long) {
        for (mixTrack in audioMixTracks) {
            mixTrack.mediaClock.updateRelativePosition(newPositionUs)
        }
    }

    fun getEarliestMediaClockTime() : Long {
        var earliestUs = Long.MAX_VALUE
        for (mixTrack in audioMixTracks) {
            earliestUs = minOf(earliestUs, mixTrack.mediaClock.positionUs)
        }
        return earliestUs
    }

    /**
     * Get the earliest presentation time available from the mix tracks
     *
     * Return Long.MAX_VALUE if no audio yet
     */
    fun getFirstAudioPresentationTimeUs() : Long {
        var earliestUs = Long.MAX_VALUE
        for (mixTrack in audioMixTracks) {
            val mixTrackEarliestUs = mixTrack.getFirstPresentationTimeUs()
            earliestUs = minOf(earliestUs, mixTrackEarliestUs)
        }
        return earliestUs
    }

    fun start() {
        playheadUs = getFirstAudioPresentationTimeUs()
        state = STATE.PLAYING
        audioTrack.play()
        playMainAudio()
    }

    fun pause() {
        state = STATE.PAUSED
        audioTrack.pause()
    }

    fun stop() {
        state = STATE.STOPPED
        audioTrack.stop()
        playheadUs = 0L

        for (mixTrack in audioMixTracks) {
            mixTrack.reset()
            audioBuffer.clear()
        }
    }

    fun mute(shouldMute: Boolean = false) {
        isMuted = shouldMute
        for (mixTrack in audioMixTracks) {
            mixTrack.mediaClock.setRunAsFastAsPossible(isMuted)
        }

        // When unmuting, mixtracks should be brought back to the main audio track playhead
        if(!shouldMute) {
            updateMixTrackMediaClocks(playheadUs)
        }
    }

    fun playMainAudio() {
        var firstAudioPresentationTime = 0L

        while (state == STATE.PLAYING) {
            // Don't start playing until there is audio in the mix tracks
            // ie. (playhead < Long.MAX_VALUE)
            if (playheadUs < Long.MAX_VALUE){
                bufferAndMixToUs(playheadUs + DEFAULT_BUFFERING_DURATION_US)
            } else {
                // Check if there is any audio yet, save the start presentation time for later
                firstAudioPresentationTime = getFirstAudioPresentationTimeUs()
                playheadUs = firstAudioPresentationTime
            }

            // Loops and plays out whatever has already been mixed-down
            while (!audioBuffer.isEmpty()) {
                val chunk = audioBuffer.get()
                val buffer = chunk.buffer

                if (isMuted) {
                    // If the track is muted, the clock is being driven by video frames being
                    // processed. This can get stuck on first frame so we manually tick if needed.
                    // TODO: can we avoid this check every loop when muted.
                    if (playheadUs == firstAudioPresentationTime) {
                        // We are at the start. Bump the clock forward
                        advanceMixTrackMediaClocks()
                    }
                    playheadUs = getEarliestMediaClockTime() // Keep main playhead in sync with mixtrack playback
                } else {
                    // Play the audio
                    val bytesPlayed = playBytes(buffer)
                    val timePlayedUs = bytesToDurationUs(bytesPlayed)

                    // Update the main track playhead and the media clock for the mix tracks
                    if (timePlayedUs > 0) {
                        playheadUs = chunk.presentationTimeUs + timePlayedUs
                    }
                    updateMixTrackMediaClocks(playheadUs)

                    // Clear buffer for reuse
                    buffer.clear()
                }
            }

            // checkForUnderruns() // main should not underrun, skip check for performance
        } // While playing
    }

    // Writes the given buffer to the audioTrack, returns bytes actually played
    fun playBytes(buffer: ByteBuffer) : Int {
        val audioTrackBufferSize = audioTrack.bufferSizeInFrames
        var bytesToPlay: Int
        var bytesPlayed = 0

        while (buffer.remaining() > 0) {
            bytesToPlay = minOf(buffer.remaining(), audioTrackBufferSize)
            bytesPlayed += audioTrack.write(buffer, bytesToPlay, AudioTrack.WRITE_BLOCKING)

            // If AudioTrack.write did not succeed, this loop can get stuck. Just exit.
            if (bytesPlayed <= 0) {
                logd("Malformed buffer, skipping chunk")
                break
            }
        }

        return bytesPlayed
    }

    fun checkForUnderruns() {
        val now = System.currentTimeMillis()
        if (now - lastUnderrunCountTimeMs <= 1000L) {
            return
        } else {
            val newUnderruns = audioTrack.underrunCount - lastUnderrunCount
            if (newUnderruns > UNDERRUN_TOLERANCE) {
                logd("AUDIO UNDERRUNS : ${newUnderruns} in ${now - lastUnderrunCountTimeMs}ms")
            }
            lastUnderrunCount = audioTrack.underrunCount
            lastUnderrunCountTimeMs = now
        }
    }
}