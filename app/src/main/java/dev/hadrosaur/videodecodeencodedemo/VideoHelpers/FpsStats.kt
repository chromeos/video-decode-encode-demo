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

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import androidx.annotation.MainThread
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.NUMBER_OF_STREAMS

/**
 * A singleton logging class for FPS stats
 */
class FpsStats() {
    // TODO: is there a useful/elegant way of getting rid of this global constant?
    val NUM_STREAMS = NUMBER_OF_STREAMS

    // Set up an int array for fps stats to get an idea of choppiness. Ex. 0-55fps+, 12 buckets
    // 0-4, 5-9, 10-14, 15-19, 20-24 . . . 50-54, 55+
    private val MAX_FPS_STATS = 60 // max fps we care about for stats
    private val NUM_FPS_BUCKETS = 6
    private val FPS_BUCKET_SIZE = MAX_FPS_STATS / NUM_FPS_BUCKETS // 10fps
    private val LAST_FPS_BUCKET_START = MAX_FPS_STATS - FPS_BUCKET_SIZE // 50+

    // Choppiness = num frames < 25fps
    private val CHOPPINESS_CUTOFF = 25
    private val TOO_MANY_CHOPPY_FRAMES_PERCENTAGE = 0.1f // 10% of frames

    private val streamFpsStats = ArrayList<StreamFpsStats>(NUM_STREAMS)

    // Output variables
    private var lastSummaryLogFrame = -1

    // Singleton instance of the FPS stats holder for all streams
    companion object {
        private lateinit var instance: FpsStats
        @MainThread
        fun get(): FpsStats {
            instance = if (::instance.isInitialized) instance else FpsStats()
            return instance
        }
    }

    init {
        reset()
    }

    fun reset() {
        lastSummaryLogFrame = -1
        streamFpsStats.clear()
        for (i in 0 until NUM_STREAMS) {
            streamFpsStats.add(StreamFpsStats(i))
        }
    }

    fun getFps(streamNumber: Int): Float {
        return streamFpsStats[streamNumber].getAverageFps()
    }

    fun isChoppy(streamNumber: Int): Boolean {
        return streamFpsStats[streamNumber].isChoppy()
    }

    // Convenience function to update stats for a single stream and return summary of all strings
    // only if all streams have the newests fps info
    fun updateStatsAndGetAll(streamNumber: Int) : String {
        var outputString = ""

        // Update stats
        if (streamNumber in 0 until NUM_STREAMS) {
            streamFpsStats[streamNumber].updateStats()
        } else {
            return ""
        }

        // Get output string if relevant
        var shouldOutputStats = true
        // Check all streams for new info
        for (streamStats in streamFpsStats) {
            if (streamStats.recentStats.isDecoding && !streamStats.recentStats.hasNewStatsToShow) {
                shouldOutputStats = false
                break
            }
        }
        if (shouldOutputStats) {
            // Build up output string
            for (streamStats in streamFpsStats) {
                if (!streamStats.recentStats.latestStatsString.equals("")) {
                    outputString += streamStats.recentStats.latestStatsString + '\n'
                }
                streamStats.recentStats.hasNewStatsToShow = false
            } // For all streams

            if (!outputString.equals("")) {
                outputString = "Video stats\n" + outputString
            }
        }

        return outputString
    }

    fun updateStats(streamNumber: Int) : String {
        return if (streamNumber in 0 until NUM_STREAMS) {
            streamFpsStats[streamNumber].updateStats()
        } else {
            ""
        }
    }

    fun getStatsString(streamNumber: Int) : String {
        return if (streamNumber in 0 until NUM_STREAMS) {
            streamFpsStats[streamNumber].getStatsString()
        } else {
            ""
        }
    }

    fun getStatsString() : String {
        var statsString = ""
        for (streamStats in streamFpsStats) {
            statsString += streamStats.getStatsString()
        }
        return statsString
    }

    private inner class StreamFpsStats(val streamNumber: Int) {
        // Stats counters
        private var fpsDecodeCounter = 0
        var fpsTotalDecodeCounter = 0
        private var fpsLastMeasuredTime = 0L
        private var fpsLastLoggedTime = 0L

        private val RUNNING_FPS_LAST_N_FRAMES = 24
        private var runningFpsLastRecordedTime = 0L

        // Set up an int array for fps stats to get an idea of choppiness. Ex. 0-55fps+, 12 buckets
        // 0-4, 5-9, 10-14, 15-19, 20-24 . . . 50-54, 55+
        private val fpsBuckets = IntArray(NUM_FPS_BUCKETS) { 0 }

        // Keep track of mix/max fps
        private var minFps = MAX_FPS_STATS
        private var maxFps = 0
        private var averageFps: Float = 0.0f
        private var numChoppyFrames = 0
        private var isChoppy = false

        // Keep track of latest log string and frame number
        var recentStats = RecentStats()

        fun resetStatsCounters() {
            minFps = MAX_FPS_STATS
            maxFps = 0
            numChoppyFrames = 0
            for (i in 0 until NUM_FPS_BUCKETS) {
                fpsBuckets[i] = 0
            }
        }

        fun getAverageFps() : Float {
            return averageFps
        }

        fun isChoppy() : Boolean {
            return isChoppy
        }

        // Update stats at current time, returns string if LOG_VIDEO_EVERY_N_FRAMES, otherwise ""
        fun updateStats() : String {
            val currentTime = System.currentTimeMillis()
            var logString = ""

            // If this is the first frame, don't calculate stats
            if (fpsLastLoggedTime == 0L || fpsLastMeasuredTime == 0L) {
                fpsLastLoggedTime = currentTime
                fpsLastMeasuredTime = currentTime
                runningFpsLastRecordedTime = currentTime
            } else {
                recentStats.isDecoding = true

                fpsDecodeCounter++
                fpsTotalDecodeCounter++

                // viewModel.updateLog("I have decoded ${decodeCounter} video frames.")
                if (fpsLastMeasuredTime == currentTime) {
                    fpsLastMeasuredTime -= 3 // 0ms since last frame, subtract time to avoid divide by 0
                }
                val currentFrameFps = 1000 / (currentTime - fpsLastMeasuredTime)


                // Calculate stats for this frame
                minFps = minOf(minFps, currentFrameFps.toInt())
                maxFps = maxOf(maxFps, currentFrameFps.toInt())

                // Keep track of a running FPS
                if (fpsTotalDecodeCounter % RUNNING_FPS_LAST_N_FRAMES == 0) {
                    averageFps =
                        (RUNNING_FPS_LAST_N_FRAMES / ((currentTime - runningFpsLastRecordedTime) / 1000.0)).toFloat()
                    runningFpsLastRecordedTime = currentTime
                }

                if (currentFrameFps < CHOPPINESS_CUTOFF) {
                    numChoppyFrames++
                }

                isChoppy = numChoppyFrames.toFloat() / fpsDecodeCounter >= TOO_MANY_CHOPPY_FRAMES_PERCENTAGE

                // Place this frame's fps in the bucket
                val currentfpsBucketIndex = minOf(currentFrameFps.toInt(), MAX_FPS_STATS - 1) / FPS_BUCKET_SIZE
                fpsBuckets[currentfpsBucketIndex]++

                // If this is a logging frame, create logString and reset counters
                if (fpsDecodeCounter % MainActivity.LOG_VIDEO_EVERY_N_FRAMES == 0) {
                    averageFps = (fpsDecodeCounter / ((currentTime - fpsLastLoggedTime) / 1000.0)).toFloat()

                    logString = getStatsString()

                    // Keep track of latest info
                    recentStats.latestStatsString = logString
                    recentStats.hasNewStatsToShow = true

                    fpsLastLoggedTime = currentTime // Update for next fps measurement
                    fpsDecodeCounter = 0
                    resetStatsCounters()
                }
            }

            fpsLastMeasuredTime = currentTime // Update for next fps measurement
            return  logString
        }// updateStats

        fun getStatsString() : String {
            val averageFpsString = String.format("%.2f", averageFps)
            val choppyString = if (isChoppy) " ---CHOPPY---" else ""

            // FPS buckets line
            var bucketsString1 = ""
            for (i in 0 until NUM_FPS_BUCKETS) {
                if (i == NUM_FPS_BUCKETS -1) {
                    // Last bucket
                    bucketsString1 += "[${i * FPS_BUCKET_SIZE}+: ${fpsBuckets[i]}]"
                } else {
                    bucketsString1 += "[${i * FPS_BUCKET_SIZE}-${(i+1) * FPS_BUCKET_SIZE - 1}: ${fpsBuckets[i]}]    "
                }
            }

            val logString = "V${streamNumber + 1}@$fpsTotalDecodeCounter. FPS min: ${minFps}" +
                    " max: ${maxFps} avg: ${averageFpsString}. Choppy frames: " +
                    "${numChoppyFrames}/${MainActivity.LOG_VIDEO_EVERY_N_FRAMES} " +
                    "(${numChoppyFrames * 100 / MainActivity.LOG_VIDEO_EVERY_N_FRAMES}%)" +
                    "${choppyString}.\n\t" +
                    bucketsString1

            return logString
        }
    }// StreamFpsStats

    private inner class RecentStats {
        var latestStatsString = ""
        var isDecoding = false
        var hasNewStatsToShow = false
    }
}