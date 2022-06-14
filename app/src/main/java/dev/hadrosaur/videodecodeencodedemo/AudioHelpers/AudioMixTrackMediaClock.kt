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

/**
 * A MediaClock that needs to be advanced manually. The intended use is to keep an individual
 * track's decoding in sync with another track, like an AudioMainTrack for synchronized multi-track
 * decoding. This clock can have a non-zero start time to for the case where this track begins at a
 * later time than the reference track. Ie. not at 0s on the main timeline.
 *
 * This clock can also be set to run as fast as possible, for example if audio is muted and a decode
 * wants to proceed at maximum speed.
 */
class AudioMixTrackMediaClock (private var startTimeUs: Long = 0L) : MediaClock {
    // Set a minimum frame rate for when this clock runs as fast as possible
    private val MIN_FPS = 1L
    private val MAX_FRAME_DURATION_US =  1000000L / MIN_FPS

    private var runAsFastAsPossible = false
    private var internalPlaybackParameters = PlaybackParameters(1.0f)
    private var positionUs = 0L

    fun setRunAsFastAsPossible(shouldRunFast: Boolean) { runAsFastAsPossible = shouldRunFast }
    fun setStartTime(newStartTimeUs: Long) { startTimeUs = newStartTimeUs }
    fun setPositionUs(newPosition: Long) { positionUs = newPosition }

    fun updateRelativePosition(newRelativeTrackPosition: Long) {
        positionUs = newRelativeTrackPosition - startTimeUs
    }
    fun advancePosition(advanceByUs: Long) { positionUs += advanceByUs}

    fun tick() {
        positionUs = positionUs + MAX_FRAME_DURATION_US
    }

    fun reset() {
        positionUs = 0L
    }

    fun onRepeat() {
    }

    override fun getPositionUs(): Long {
        if (runAsFastAsPossible) {
            return positionUs + MAX_FRAME_DURATION_US
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

    fun updateLastProcessedVideoPosition(videoPositionUs: Long) {
        // If a later frame has been processed, advance
        if (videoPositionUs > positionUs) {
            positionUs = videoPositionUs
        }
    }
}