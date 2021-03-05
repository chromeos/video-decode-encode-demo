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

import java.nio.ByteBuffer

/**
 * Holds an audio buffer and meta-data for encoding
 *
 * @param buffer The ByteBuffer containing audio data
 * @param id ID int used for debugging and logging purposes
 * @param presentationTimeUs presentation time of the buffer
 * @param lengthUs length of audio sample in uS
 * @param size size of sample in bytes
 * @param isLastBuffer flag to indicate this is the last audio sample in a stream
 */
class AudioBuffer(
    val buffer: ByteBuffer,
    val id: Int,
    var presentationTimeUs: Long,
    val lengthUs: Long,
    val size: Int,
    val isLastBuffer: Boolean = false
)