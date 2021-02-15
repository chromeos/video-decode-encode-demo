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

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.util.MediaClock

// MediaClock implementation. Render as fast as possible: use last rendered frame + 1 as current
// position. See: https://github.com/google/ExoPlayer/issues/3978#issuecomment-372709173
// Note: no frames should be dropped so minFps just makes sure clock advances far enough to
// include the next frame. Too far is ok in this case.
class SpeedyMediaClock: MediaClock {
    val MIN_FPS: Int = 1
    var internalPlaybackParameters = PlaybackParameters(1f)
    var lastProcessedFrameUs = 0L

    fun updateLastProcessedFrame(frameProcessedUs: Long) {
        // If a later frame has been processed, advance the clock tick
        if (frameProcessedUs > lastProcessedFrameUs) {
            lastProcessedFrameUs = frameProcessedUs
        }
    }

    override fun getPositionUs(): Long {
        return lastProcessedFrameUs + ((1 / MIN_FPS) * 100000)
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return internalPlaybackParameters
    }

    // Note: this implementation actually ignores parameters like speed.
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        internalPlaybackParameters = playbackParameters
    }
}