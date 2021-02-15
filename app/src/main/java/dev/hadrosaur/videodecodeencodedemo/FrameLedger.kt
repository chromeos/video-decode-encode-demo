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
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * There is an issue decoding via Surface prior to Android Q. The Surface can silently drop frames
 * if the decode speed is faster than realtime. This is generally desirable for video editing.
 *
 * The FrameLedger keeps track of frames sent for decode and those actually decoded and rendered.
 * In addition, it provides an atomic lock to ensure frames do not get overwritten before they can
 * be rendered.
 *
 * The decodeLedger records the release and presentation time of each frame as it is about to be rendered
 * to the Surface so it can be checked when it is actually rendered. releaseTimeNs is really just
 * used as an ID for the frame.
 *
 * The encodeLedger counts frames received from the decoder rendered and records their presentation
 * time for use in encoding.
 */
class FrameLedger : VideoFrameMetadataListener {
    val decodeLedger = ConcurrentHashMap<Long, Long>()
    val encodeLedger = ConcurrentHashMap<Int, Long>()
    var frames_entered = AtomicInteger(0)
    var frames_rendered = AtomicInteger(0)
    var encodingCounter = AtomicInteger(0)

    // Atomic lock to block the pipeline to prevent frame drops
    var eglBlockRenderer = AtomicBoolean(false)

    var haveSkippedFirst = false

    var lastVideoBufferPresentationTimeUs = 0L

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

    // The encode ledger just counts frames (1 -> last frame) and records proper presentation time
    fun addEncodePresentationTime(presentationTimeUs: Long) {
        encodeLedger.put(encodingCounter.incrementAndGet(), presentationTimeUs)
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

        // Block pipeline until updateTexSurface has been called
        engageRenderBlock()
        decodeLedger.put(releaseTimeNs, presentationTimeUs)

        // Record frame presentation time in the encode ledger
        addEncodePresentationTime(presentationTimeUs)

        frames_entered.incrementAndGet()
    }
}