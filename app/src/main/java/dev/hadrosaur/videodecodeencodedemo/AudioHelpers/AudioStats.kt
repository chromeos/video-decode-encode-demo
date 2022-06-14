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

import androidx.annotation.MainThread
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_AUDIO_EVERY_N_FRAMES
import dev.hadrosaur.videodecodeencodedemo.NUMBER_OF_STREAMS

/**
 * A singleton logging class for audio stats
 *
 * Note: BPS == audio Buffers Per Second
 */
// TODO: This is almost identical to FpsStats. Can we parameterize this?
class AudioStats {
    // TODO: is there a useful/elegant way of getting rid of this global constant?
    val NUM_STREAMS = NUMBER_OF_STREAMS

    // Set up an int array for BPS stats to get an idea of choppiness. <25bps, <50bps, <75bps, etc
    private val MAX_BPS_STATS = 200 // max bps we care about for stats
    private val NUM_BPS_BUCKETS = 4
    private val BPS_BUCKET_SIZE = MAX_BPS_STATS / NUM_BPS_BUCKETS // 50bps
    private val LAST_BPS_BUCKET_START = MAX_BPS_STATS - BPS_BUCKET_SIZE // 150+

    // Choppiness = num buffers < 50bps
    private val CHOPPINESS_CUTOFF = 50
    private val TOO_MANY_CHOPPY_BUFFERS = LOG_AUDIO_EVERY_N_FRAMES / 10

    private val streamBpsStats = ArrayList<StreamBpsStats>(NUM_STREAMS)

    // Output variables
    private var lastSummaryLogFrame = -1

    // Singleton instance of the FPS stats holder for all streams
    companion object {
        private lateinit var instance: AudioStats
        @MainThread
        fun get(): AudioStats {
            instance = if (::instance.isInitialized) instance else AudioStats()
            return instance
        }
    }

    init {
        reset()
    }

    fun reset() {
        lastSummaryLogFrame = -1
        streamBpsStats.clear()
        for (i in 0 until NUM_STREAMS) {
            streamBpsStats.add(StreamBpsStats(i))
        }
    }

    // Convenience function to update stats for a single string and return summary of all strings
    // only if all streams have the newests fps info
    fun updateStatsAndGetAll(streamNumber: Int) : String {
        var outputString = ""
        var maxFrameCount = -1

        if (streamNumber in 0 until NUM_STREAMS) {
            // Update stats
            streamBpsStats[streamNumber].updateStats()

            // Build up output string, only if each stream is at the same frame
            outputString = ""
            for (streamStats in streamBpsStats) {
                // If this stream is not being decoded, skip it
                if (streamStats.bpsTotalDecodeCounter == 0) {
                    continue
                }
                // If this is just starting, record the lastest frame from recents
                if (maxFrameCount == -1) {
                    maxFrameCount = streamStats.recentStats.latestBufferCount
                }
                // If this frame count has been logged already, skip it
                if (streamStats.recentStats.latestBufferCount <= lastSummaryLogFrame) {
                    outputString = ""
                    break
                }
                // If there are recent stats with a different frame count, do not output the summary
                if (streamStats.recentStats.latestBufferCount > 0 &&
                    streamStats.recentStats.latestBufferCount != maxFrameCount) {
                    outputString = ""
                    break
                }

                // Otherwise, this is new info and at the same frame as the other stats, keep it
                if (!streamStats.recentStats.latestStatsString.equals("")) {
                    outputString += streamStats.recentStats.latestStatsString + '\n'
                } else {
                    outputString = ""
                    break
                }
            }
            if (!outputString.equals("")) {
                lastSummaryLogFrame = maxFrameCount
                outputString = "Audio stats\n" + outputString
            }
        }

        return outputString
    }

    fun updateStats(streamNumber: Int) : String {
        return if (streamNumber in 0 until NUM_STREAMS) {
            streamBpsStats[streamNumber].updateStats()
        } else {
            ""
        }
    }

    fun getStatsString(streamNumber: Int) : String {
        return if (streamNumber in 0 until NUM_STREAMS) {
            streamBpsStats[streamNumber].getStatsString()
        } else {
            ""
        }
    }

    fun getStatsString() : String {
        var statsString = ""
        for (streamStats in streamBpsStats) {
            statsString += streamStats.getStatsString()
        }
        return statsString
    }

    private inner class StreamBpsStats(val streamNumber: Int) {
        // Stats counters
        private var bpsDecodeCounter = 0
        var bpsTotalDecodeCounter = 0
        private var bpsLastMeasuredTime = 0L
        private var bpsLastLoggedTime = 0L

        private val bpsBuckets = IntArray(NUM_BPS_BUCKETS) { 0 }

        // Keep track of mix/max fps
        private var minBps = MAX_BPS_STATS
        private var maxBps = 0
        private var averageBps: Double = 0.0
        private var numChoppyBuffers = 0

        // Keep track of latest log string and frame number
        var recentStats = RecentBpsStats()

        fun resetStatsCounters() {
            minBps = MAX_BPS_STATS
            maxBps = 0
            numChoppyBuffers = 0
            for (i in 0 until NUM_BPS_BUCKETS) {
                bpsBuckets[i] = 0
            }
        }

        // Update stats at current time, returns string if LOG_AUDIO_EVERY_N_FRAMES, otherwise ""
        fun updateStats() : String {
            val currentTime = System.currentTimeMillis()
            var logString = ""

            // If this is the first frame, don't calculate stats
            if (bpsLastLoggedTime == 0L || bpsLastMeasuredTime == 0L) {
                bpsLastLoggedTime = currentTime
                bpsLastMeasuredTime = currentTime
            } else {
                bpsDecodeCounter++
                bpsTotalDecodeCounter++

                if (bpsLastMeasuredTime == currentTime) {
                    bpsLastMeasuredTime -= 3 // 0ms since last frame, subtract time to avoid divide by 0
                }
                val currentFrameBps = 1000 / (currentTime - bpsLastMeasuredTime)

                // Calculate stats for this buffer
                minBps = minOf(minBps, currentFrameBps.toInt())
                maxBps = maxOf(maxBps, currentFrameBps.toInt())

                if (currentFrameBps < CHOPPINESS_CUTOFF) {
                    numChoppyBuffers++
                }

                // Place this frame's bps in the bucket
                val currentfpsBucketIndex = minOf(currentFrameBps.toInt(), MAX_BPS_STATS - 1) / BPS_BUCKET_SIZE
                bpsBuckets[currentfpsBucketIndex]++


                // If this is a logging frame, create logString and reset counters
                if (bpsDecodeCounter % MainActivity.LOG_AUDIO_EVERY_N_FRAMES == 0) {
                    averageBps = bpsDecodeCounter / ((currentTime - bpsLastLoggedTime) / 1000.0)

                    logString = getStatsString()

                    // Keep track of latest info
                    recentStats.latestBufferCount = bpsTotalDecodeCounter
                    recentStats.latestStatsString = logString

                    bpsLastLoggedTime = currentTime // Update for next fps measurement
                    bpsDecodeCounter = 0
                    resetStatsCounters()
                }
            }

            bpsLastMeasuredTime = currentTime // Update for next fps measurement
            return  logString
        }// updateStats

        fun getStatsString() : String {
            val averageBpsString = String.format("%.2f", averageBps)
            val choppyString = if (numChoppyBuffers >= TOO_MANY_CHOPPY_BUFFERS) " ---CHOPPY---" else ""

            // FPS buckets line
            var bucketsString1 = ""
            for (i in 0 until NUM_BPS_BUCKETS) {
                if (i == NUM_BPS_BUCKETS -1) {
                    // Last bucket
                    bucketsString1 += "[${i * BPS_BUCKET_SIZE}+: ${bpsBuckets[i]}]"
                } else {
                    bucketsString1 += "[${i * BPS_BUCKET_SIZE}-${(i+1) * BPS_BUCKET_SIZE - 1}: ${bpsBuckets[i]}]    "
                }
            }

            val logString = "A${streamNumber + 1}@$bpsTotalDecodeCounter. BPS min: ${minBps}" +
                    " max: ${maxBps} avg: ${averageBpsString}. Choppy frames: " +
                    "${numChoppyBuffers}/${MainActivity.LOG_AUDIO_EVERY_N_FRAMES} " +
                    "(${numChoppyBuffers * 100 / MainActivity.LOG_AUDIO_EVERY_N_FRAMES}%)" +
                    "${choppyString}.\n\t" +
                    bucketsString1

            return logString
        }
    }// StreamFpsStats

    private inner class RecentBpsStats {
        var latestBufferCount = 0
        var latestStatsString = ""
    }
}