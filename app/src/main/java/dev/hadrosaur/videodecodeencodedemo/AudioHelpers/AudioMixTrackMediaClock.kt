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

import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.util.MediaClock
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd

/**
 * A MediaClock that advances relative to a main audio track. This track may have a non-zero start
 * time to facilitate multi-track video/audio playback.
 */
class AudioMixTrackMediaClock (private var startTimeUs: Long = 0L) : MediaClock {
    private val MIN_FPS = 1L
    private val MAX_FRAME_DURATION_US =  100000L / MIN_FPS
    var lastProcessedFrameUs = -1 * MAX_FRAME_DURATION_US // Initialize at -1 frame

    private val runAsFastAsPossible = false
    private var internalPlaybackParameters = PlaybackParameters(1.0f)
    private var positionUs = 0L

    fun setStartTime(newStartTimeUs: Long) { startTimeUs = newStartTimeUs }

    fun setPositionUs(newPosition: Long) { positionUs = newPosition }
    fun updatePositionFromMain(newMainTrackPosition: Long) {
        positionUs = newMainTrackPosition - startTimeUs
//        logd("New pos from main: ${positionUs}")
    }
    fun advancePosition(advanceByUs: Long) { positionUs += advanceByUs}

    override fun getPositionUs(): Long {
        if (runAsFastAsPossible) {
            return lastProcessedFrameUs + MAX_FRAME_DURATION_US
        } else {
            return positionUs
        }
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return internalPlaybackParameters
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        internalPlaybackParameters = playbackParameters
    }

    fun updateLastProcessedFrame(frameProcessedUs: Long) {
        // If a later frame has been processed, advance the clock tick
        if (frameProcessedUs > lastProcessedFrameUs) {
            lastProcessedFrameUs = frameProcessedUs
        }
    }
}