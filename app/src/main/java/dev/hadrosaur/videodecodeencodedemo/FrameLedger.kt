/*
 * Copyright (c) 2020 Google LLC
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

package dev.hadrosaur.videodecodeencodedemo

import android.media.MediaFormat
import android.util.Log
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * There is an issue decoding via Surface prior to Android Q. The Surface can silently drop frames
 * if the decode speed is faster than realtime. This is generally desirable for video editing.
 *
 * The FrameLedger keeps track of frames sent for decode and those actually decoded and rendered.
 * In addition, it provides an atomic lock to ensure frames do not get clobbered.
 *
 * The ledger records the release and presentation time of each frame as it is about to be rendered
 * to the Surface so it can be checked when it is actually rendered. releaseTimeNs is really just
 * used as an ID for the frame.
 */
class FrameLedger : VideoFrameMetadataListener {
    val ledger = ConcurrentHashMap<Long, Long>()
    var frames_entered = AtomicInteger(0)
    var frames_rendered = AtomicInteger(0)

    // Atomic lock to block the pipeline to prevent frame drops
    var eglBlockRenderer = AtomicBoolean(false)

    var haveSkippedFirst = false

    // Check if the block render flag is engaged.
    fun shouldBlockRender() : Boolean {
        return eglBlockRenderer.get()
    }

    fun engageRenderBlock() {
        eglBlockRenderer.set(true)
    }

    fun releaseRenderBlock() {
        eglBlockRenderer.set(false)
    }

    /**
     * This is the VideoFrameMetadataListener called by ExoPlayer right before a frame is about to
     * be sent to be rendered. To prevent overloading the surface and dropping frames, it is here
     * that we set an atomic lock in the render pipeline to be released after updateTexImage for
     * this frame has been called.
     *
     * The lock is released in InternalSurfaceTextureRenderer's onFrameAvailable call.
     */
    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {

//        if (presentationTimeUs == 0L && !haveSkippedFirst) {
//            Log.d("VD", "Presentation time in 0")
//            Log.d("VD","in OnVideoFrameAboutToBeRendered: ptime: " + presentationTimeUs + ", rtime: " + releaseTimeNs + ", format: " + format.toString())
//            Log.d("VD", "Media Format: " + mediaFormat.toString())
//            haveSkippedFirst = true
//            return
//        }

        // Block pipeline until updateTexSurface has been called
        engageRenderBlock()
        ledger.put(releaseTimeNs, presentationTimeUs)
        frames_entered.incrementAndGet()
    }
}