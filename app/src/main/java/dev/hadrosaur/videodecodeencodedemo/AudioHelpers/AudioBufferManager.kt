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

import dev.hadrosaur.videodecodeencodedemo.MainActivity
import java.util.concurrent.ConcurrentLinkedQueue

class AudioBufferManager {
    /**
     * Callback to be triggered if a user of the AudioBufferManager wants to be notified of new
     * audio data added via addData
     */
    interface AudioBufferManagerListener {
        fun newAudioData()
    }

    val queue = ConcurrentLinkedQueue<AudioBuffer>()
    var listener: AudioBufferManagerListener? = null

    /**
     * Adds AudioBuffer to the queue. If a callback is registered, trigger it
     */
    fun addData(audioBuffer: AudioBuffer): Boolean {
        val success = queue.add(audioBuffer)

        // If there is a listener registered, trigger it
        listener?.newAudioData()

        return success
    }
}



