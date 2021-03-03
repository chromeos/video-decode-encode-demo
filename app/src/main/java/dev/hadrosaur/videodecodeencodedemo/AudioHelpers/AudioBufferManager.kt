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

package dev.hadrosaur.videodecodeencodedemo.AudioHelpers

import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.C.ENCODING_PCM_16BIT
import java.util.concurrent.LinkedBlockingDeque

class AudioBufferManager {
    /**
     * Callback to be triggered if a user of the AudioBufferManager wants to be notified of new
     * audio data added via addData
     */
    interface AudioBufferManagerListener {
        fun newAudioData()
    }

    private val queue = LinkedBlockingDeque<AudioBuffer>()
    var listener: AudioBufferManagerListener? = null
    var audioDecodeComplete = false
    var pcmEncoding = ENCODING_PCM_16BIT

    /**
     * Adds AudioBuffer to the queue. If a callback is registered, trigger it
     */
    fun addData(audioBuffer: AudioBuffer, triggerCallback: Boolean = true): Boolean {
        // Add to tail of queue
        val success = queue.add(audioBuffer)

        // If there is a listener registered, trigger it
        if (triggerCallback) {
            listener?.newAudioData()
        }

        return success
    }

    fun addDataFirst(audioBuffer: AudioBuffer, triggerCallback: Boolean = true) {
        // Add to front of queue
        queue.addFirst(audioBuffer)

        // If there is a listener registered, trigger it
        if (triggerCallback) {
            listener?.newAudioData()
        }
    }

    fun pollData() : AudioBuffer? {
        return queue.poll()
    }
}



