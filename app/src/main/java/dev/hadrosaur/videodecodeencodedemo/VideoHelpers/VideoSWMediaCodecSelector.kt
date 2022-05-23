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

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import android.media.MediaCodecList
import android.os.Build
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil

class VideoSWMediaCodecSelector(val forceSoftware: Boolean = false) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): MutableList<MediaCodecInfo> {
        val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType,
            requiresSecureDecoder, requiresTunnelingDecoder)
        val swOnlyList = ArrayList<MediaCodecInfo>()
        // Filter out HW decoders
        for (codecInfo in defaultList) {
            if(codecInfo.softwareOnly) {
                swOnlyList.add(codecInfo)
            }
        }

        if (forceSoftware) return swOnlyList else return defaultList
    }
}