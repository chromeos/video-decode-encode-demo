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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class VideoVideoModel() : ViewModel() {
    private val previewFrameFrequency = MutableLiveData<Int>(10)
    private val applyGlFilter = MutableLiveData<Boolean>(false)
    private val encodeStream1 = MutableLiveData<Boolean>(false)

    private val decodeStream1 = MutableLiveData<Boolean>(true)
    private val decodeStream2 = MutableLiveData<Boolean>(true)
    private val decodeStream3 = MutableLiveData<Boolean>(true)
    private val decodeStream4 = MutableLiveData<Boolean>(true)

    fun getPreviewFrameFrequency(): MutableLiveData<Int> = previewFrameFrequency
    fun getApplyGlFilter(): MutableLiveData<Boolean> = applyGlFilter
    fun getEncodeStream1(): MutableLiveData<Boolean> = encodeStream1
    fun getDecodeStream1(): MutableLiveData<Boolean> = decodeStream1
    fun getDecodeStream2(): MutableLiveData<Boolean> = decodeStream2
    fun getDecodeStream3(): MutableLiveData<Boolean> = decodeStream3
    fun getDecodeStream4(): MutableLiveData<Boolean> = decodeStream4

    fun getPreviewFrameFrequencyVal(): Int = previewFrameFrequency.value ?: 10
    fun getApplyGlFilterVal(): Boolean = applyGlFilter.value ?: false
    fun getEncodeStream1Val(): Boolean = encodeStream1.value ?: false
    fun getDecodeStream1Val(): Boolean = decodeStream1.value ?: true
    fun getDecodeStream2Val(): Boolean = decodeStream2.value ?: true
    fun getDecodeStream3Val(): Boolean = decodeStream3.value ?: true
    fun getDecodeStream4Val(): Boolean = decodeStream4.value ?: true

    fun setPreviewFrameFrequency(value: Int) {
        previewFrameFrequency.value = value
    }
    fun setApplyGlFilter(value: Boolean) {
        applyGlFilter.value = value
    }
    fun setEncodeStream1(value: Boolean) {
        encodeStream1.value = value
    }
    fun setDecodeStream1(value: Boolean) {
        decodeStream1.value = value
    }
    fun setDecodeStream2(value: Boolean) {
        decodeStream2.value = value
    }
    fun setDecodeStream3(value: Boolean) {
        decodeStream3.value = value
    }
    fun setDecodeStream4(value: Boolean) {
        decodeStream4.value = value
    }
}