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

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.hadrosaur.videodecodeencodedemo.Utils.SmartMutableLiveData
import java.io.File

class MainViewModel : ViewModel() {
    // App options
    private val previewFrameFrequency = SmartMutableLiveData<Int>(10)
    private val applyGlFilter = SmartMutableLiveData<Boolean>(false)
    private val encodeStream1 = SmartMutableLiveData<Boolean>(false)
    private val playAudio = SmartMutableLiveData<Boolean>(false)

    // Stream decode switches
    private val decodeStream1 = SmartMutableLiveData<Boolean>(true)
    private val decodeStream2 = SmartMutableLiveData<Boolean>(false)
    private val decodeStream3 = SmartMutableLiveData<Boolean>(false)
    private val decodeStream4 = SmartMutableLiveData<Boolean>(false)

    // Log text window
    private val logText = SmartMutableLiveData<String>("")

    // Flag for the encoder to signal when encoding is complete
    private val encodingInProgress = SmartMutableLiveData<Boolean>(false)

    // Convenience MediaFormats for the encoder
    var originalRawFileId: Int = R.raw.paris_01_1080p
    var encodeOutputDir: File? = null
    var videoEncoderCodecInfo: MediaCodecInfo? = null
    var audioEncoderCodecInfo: MediaCodecInfo? = null
    var encoderVideoFormat: MediaFormat = MediaFormat.createVideoFormat("video/avc", 1920, 1080)
    var encoderAudioFormat: MediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2)

    // Getters for the MutableLiveData variables for use with observers
    fun getPreviewFrameFrequency(): MutableLiveData<Int> = previewFrameFrequency
    fun getApplyGlFilter(): MutableLiveData<Boolean> = applyGlFilter
    fun getEncodeStream1(): MutableLiveData<Boolean> = encodeStream1
    fun getPlayAudio(): MutableLiveData<Boolean> = playAudio
    fun getDecodeStream1(): MutableLiveData<Boolean> = decodeStream1
    fun getDecodeStream2(): MutableLiveData<Boolean> = decodeStream2
    fun getDecodeStream3(): MutableLiveData<Boolean> = decodeStream3
    fun getDecodeStream4(): MutableLiveData<Boolean> = decodeStream4
    fun getLogText(): MutableLiveData<String> = logText
    fun getEncodingInProgress(): MutableLiveData<Boolean> = encodingInProgress

    // Convenience methods to get the actual values of variables
    fun getPreviewFrameFrequencyVal(): Int = previewFrameFrequency.value ?: 10
    fun getApplyGlFilterVal(): Boolean = applyGlFilter.value ?: false
    fun getEncodeStream1Val(): Boolean = encodeStream1.value ?: false
    fun getPlayAudioVal(): Boolean = playAudio.value ?: false
    fun getDecodeStream1Val(): Boolean = decodeStream1.value ?: true
    fun getDecodeStream2Val(): Boolean = decodeStream2.value ?: false
    fun getDecodeStream3Val(): Boolean = decodeStream3.value ?: false
    fun getDecodeStream4Val(): Boolean = decodeStream4.value ?: false
    fun getLogTextVal(): String = logText.value ?: ""
    fun getEncodingInProgressVal(): Boolean = encodingInProgress.value ?: false

    // Setters
    fun setPreviewFrameFrequency(value: Int) {
        previewFrameFrequency.setValue(value)
    }
    fun setApplyGlFilter(value: Boolean) {
        applyGlFilter.setValue(value)
    }
    fun setEncodeStream1(value: Boolean) {
        encodeStream1.setValue(value)
    }
    fun setPlayAudio(value: Boolean) {
        playAudio.setValue(value)
    }
    fun setDecodeStream1(value: Boolean) {
        decodeStream1.setValue(value)
    }
    fun setDecodeStream2(value: Boolean) {
        decodeStream2.setValue(value)
    }
    fun setDecodeStream3(value: Boolean) {
        decodeStream3.setValue(value)
    }
    fun setDecodeStream4(value: Boolean) {
        decodeStream4.setValue(value)
    }
    fun setLogText(value: String) {
        logText.postValue(value)
    }
    fun updateLog(newText: String) {
        setLogText(newText)
    }
    fun setEncodingInProgress(value: Boolean) {
        encodingInProgress.postValue(value)
    }
}