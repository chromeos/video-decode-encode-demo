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

package dev.hadrosaur.videodecodeencodedemo.Utils

import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MutableLiveData that will not lose data if postValue is called multiple times.
 *
 * Adapted from: https://stackoverflow.com/questions/56097647/can-we-use-livedata-without-loosing-any-value
 */
class SmartMutableLiveData<T>(defaultValue: T? = null) : MutableLiveData<T>() {
    private val queue = ConcurrentLinkedQueue<T>()

    init {
        if (defaultValue != null) {
            super.setValue(defaultValue)
        }
    }

    /**
     * Always queue every value received, then pass it up to super
     */
    override fun postValue(value: T) {
        queue.add(value)
        super.postValue(value)
    }

    /**
     * This is either called directly or from a previous postValue call
     */
    override fun setValue(value: T) {
        // Remove if previously queue as it will be requeued
        queue.remove(value)

        // Add value back to then end of the queue
        queue.add(value)

        // Now the queue has:
        //  - any values that have been queued but normally would have been overwritten by postValue
        //  - this new value that has been passed from setValue / is the last value set by postValue
        while (!queue.isEmpty()) {
            super.setValue(queue.poll())
        }
    }
}