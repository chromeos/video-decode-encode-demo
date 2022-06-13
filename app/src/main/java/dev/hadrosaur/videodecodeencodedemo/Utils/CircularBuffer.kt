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

package dev.hadrosaur.videodecodeencodedemo.Utils

/**
 * A fixed size circular buffer implementation
 * 
 * - Requires objects to implement the Copyable interface
 * - Requires an empty object of type T for initialization purposes
 * - fullBehaviour determines what happens if the buffer is full, default is
 *   simply to ignore incoming items (DISCARD). OVERWRITE will overwrite existing data. REPLACE_LAST
 *   will keep overwriting the last slot available without advancing head or tail.
 */
class CircularBuffer<T>(val size: Int, emptyObject: Copyable, val fullBehaviour: FULL_BEHAVIOUR = FULL_BEHAVIOUR.DISCARD) {
    enum class FULL_BEHAVIOUR { OVERWRITE, DISCARD, REPLACE_LAST }
    val array: ArrayList<Copyable> = ArrayList(size)
    var numItems = 0
    var head = 0
    var tail = 0

    init {
        // Populate backing array until it contains the right number of elements
        for (i in 0 until size) {
            array.add(emptyObject.copy())
        }
    }

    /**
     * Safe add item, making sure the underlying array item has been created
     *
     * This is necessary because Kotlin doesn't allow for a fixed size array of generics
     */
    fun set(index: Int, item: T, copyInPlace: Boolean) {
        if (copyInPlace) {
            (item as Copyable).copyInto(array[index])
        } else {
            array[index] = (item as Copyable)
        }
    }

    fun incrementHead() {
        head = (head + 1) % size
    }
    fun incrementTail() {
        tail = (tail + 1) % size
    }
    fun decrementHead() {
        head = (head - 1) % size
        if (head < 0) {
            head += size
        }
    }
    fun decrementTail() {
        tail = (tail - 1) % size
        if (tail < 0) {
            tail += size
        }
    }

    fun add(item: T, copyInPlace: Boolean = true) {
        if(isFull()) {
            when (fullBehaviour) {
                FULL_BEHAVIOUR.OVERWRITE -> {
                    // Add item, replace any data currently there
                    set(head, item, copyInPlace)
                    // Advance front of buffer
                    incrementHead()
                    // Buffer is full, also advance tail
                    incrementTail()

                }
                FULL_BEHAVIOUR.DISCARD -> {
                    // No-op, just discard item
                }
                FULL_BEHAVIOUR.REPLACE_LAST -> {
                    // Add item, do not advance pointers
                    set(head, item, copyInPlace)
                }
            }

        } else {
            // Buffer is not full, just add and advance head
            set(head, item, copyInPlace)
            incrementHead()
            numItems++;
        }
    }

    /**
     * Adds an item onto the tail
     */
    fun addTail(item: T, copyInPlace: Boolean = true) {
        if(isFull()) {
            when (fullBehaviour) {
                FULL_BEHAVIOUR.OVERWRITE -> {
                    // Add item to tail, drop item at head
                    decrementTail()
                    decrementHead()
                    set(tail, item, copyInPlace)
                }
                FULL_BEHAVIOUR.DISCARD -> {
                    // No-op, just discard item
                }
                FULL_BEHAVIOUR.REPLACE_LAST -> {
                    // Add item, replace current tail
                    set(tail, item, copyInPlace)
                }
            }
        } else {
            // Decrement tail and add item
            decrementTail()
            set(tail, item, copyInPlace)
            numItems++
        }
    }

    /**
     * Rewinds tail by one
     *
     * The caller is assuming that no new data has replaced the old data. This is convenient if an
     * item was removed by mistake and prevents an extra copy needed to put it back
     */
    fun rewindTail() {
        decrementTail()
        if(!isFull()) {
            numItems++
        } else {
            // This is probably not doing what the caller expects, decrement head or this will have
            // essentially "emptied" the queue
            decrementHead()
        }
    }

    fun peek() : T {
        // Return data, do not advance tail
        return array[tail] as T
    }

    /**
     * This is used to get the "next" element for mixing purposes
     */
    fun peekHead() : T {
        // Return data, do not advance head
        return array[head] as T
    }

    fun get() : T {
        val item = array[tail] as T

        if (!isEmpty()) {
            // Advance tail
            incrementTail()
            numItems--
        }

        return item
    }

    fun isFull() : Boolean {
        return (numItems == size)
    }

    fun isEmpty() : Boolean {
        return (numItems == 0)
    }

    fun size() : Int {
        return numItems
    }

    fun clear() {
        head = 0
        tail = 0
        numItems = 0
    }
}
