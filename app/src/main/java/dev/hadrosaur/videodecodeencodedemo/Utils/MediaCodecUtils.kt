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

import android.content.res.AssetFileDescriptor
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaFormat.*
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel


/**
 * Returns the first codec capable of encoding the specified MIME type, or null if no
 * match was found.
 */
fun selectEncoder(mimeType: String): MediaCodecInfo? {
    val numCodecs = MediaCodecList.getCodecCount()
    val validCodecs = ArrayList<MediaCodecInfo>()

    MainActivity.logd("Select: Looking for a codec of mime: ${mimeType}")

    for (i in 0 until numCodecs) {
        val codecInfo = MediaCodecList.getCodecInfoAt(i)
        if (!codecInfo.isEncoder) {
            continue
        }
        val types = codecInfo.supportedTypes
        for (j in types.indices) {
            if (types[j].equals(mimeType, ignoreCase = true)) {
                MainActivity.logd("Select: Found a match, adding: ${codecInfo.name}")
                validCodecs.add(codecInfo)
            }
        }
    }
/*
    // Let's privilege the Android software video encoder4
    if ((SDK_INT >= Build.VERSION_CODES.Q && mimeType.equals("video/avc"))) {
        for (i in 0 until validCodecs.size) {
            if (validCodecs[i].isSoftwareOnly || (validCodecs[i].name.equals("c2.android.avc.encoder"))) {
                MainActivity.logd("Returning a software codec. ${validCodecs[i].name}")
                return validCodecs[i]
            }
        }
    }
*/
    // Default to returning the first codec
    return if (validCodecs.size > 0) {
        validCodecs[0]
    } else {
        null
    }
}

/**
 * Gets the MIME type for the video stream
 */
fun getVideoTrackMimeType(videoFd: AssetFileDescriptor) : String {
    val extractor = MediaExtractor()
    var mimeType = ""

    extractor.setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)

    // Find the video track format from the raw video file
    for (i in 0 until extractor.trackCount) {
        val trackFormat = extractor.getTrackFormat(i)
        mimeType = trackFormat.getString(KEY_MIME) ?: ""
        if (mimeType.startsWith("video/")) {
            // viewModel.updateLog("Video MIME type: ${mimeType}")
            break
        }
    }

    extractor.release()
    return mimeType
}

/**
 * Gets the MIME type for the audio stream
 */
fun getAudioTrackMimeType(videoFd: AssetFileDescriptor) : String {
    val extractor = MediaExtractor()
    var mimeType = ""

    extractor.setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)

    // Find the video track format from the raw video file
    for (i in 0 until extractor.trackCount) {
        val trackFormat = extractor.getTrackFormat(i)
        mimeType = trackFormat.getString(KEY_MIME) ?: ""
        if (mimeType.startsWith("audio/")) {
            // viewModel.updateLog("Audio MIME type: ${mimeType}")
            break
        }
    }

    extractor.release()
    return mimeType
}

/**
 * Tries to get the best encoding settings for the device, using the setting from the original
 * media as a guide.
 */
fun getBestVideoEncodingFormat(videoFd: AssetFileDescriptor) : MediaFormat {
    val DEFAULT_BITRATE = 12000000

    // Logging variables, will be changed to actual settings
    var bitrate = DEFAULT_BITRATE
    var frameRate = 30

    // Use the original decoded file to help set up the encode values
    // This is not necessary but just convenient
    val extractor = MediaExtractor()
    var inputMediaFormat = MediaFormat()
    var mimeType = ""

    extractor.setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)

    // Find the video track format from the raw video file
    for (i in 0 until extractor.trackCount) {
        val trackFormat = extractor.getTrackFormat(i)
        mimeType = trackFormat.getString(KEY_MIME) ?: ""
        if (mimeType.startsWith("video/")) {
            //viewModel.updateLog("Video MIME type: ${mimeType}")
            inputMediaFormat = trackFormat
            MainActivity.logd("getBest : inputformat: width ${trackFormat.getInteger(KEY_WIDTH)} height: ${trackFormat.getInteger(KEY_HEIGHT)}")
            break
        }
    }

    // Get the best video encoder for the given MIME type
    val videoEncoderCodecInfo: MediaCodecInfo? = selectEncoder(mimeType)
    if (videoEncoderCodecInfo == null) {
        MainActivity.logd("WARNING: No valid video encoder codec. Encoded file may be broken.")
        return MediaFormat()
    }

    // Start with decoder width/height
    var encoderWidth = inputMediaFormat.getInteger(KEY_WIDTH)
    var encoderHeight = inputMediaFormat.getInteger(KEY_HEIGHT)
    MainActivity.logd("getBest2 : inputformat: width ${encoderWidth} height: ${encoderHeight}")

    // Configure encoder with the same format as the source media
    val encoderFormat = createVideoFormat(
        mimeType,
        encoderWidth,
        encoderHeight
    )
    encoderFormat.setInteger(
        KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
    )

    // Settings to copy from the original video format
    val intFormatSettings = ArrayList<String>()
    intFormatSettings.add(KEY_WIDTH)
    intFormatSettings.add(KEY_HEIGHT)
    intFormatSettings.add(KEY_BIT_RATE)
    intFormatSettings.add(KEY_FRAME_RATE)
    intFormatSettings.add(KEY_I_FRAME_INTERVAL)

    if (SDK_INT >= 23) {
        intFormatSettings.add(KEY_PRIORITY)
        intFormatSettings.add(KEY_LEVEL)
    }
    if (SDK_INT >= 24) {
        intFormatSettings.add(KEY_COLOR_STANDARD)
        intFormatSettings.add(KEY_COLOR_RANGE)
        intFormatSettings.add(KEY_COLOR_TRANSFER)
    }

    // Crop values are essential as some media decodes to larger width/height than the actual
    // video. For example, 1920x1080 will decode to 1920x1088, with 8px crop offset.
    // Encoding without correct crop values will show weird smearing/glitches.
    intFormatSettings.add("crop-bottom")
    intFormatSettings.add("crop-top")
    intFormatSettings.add("crop-left")
    intFormatSettings.add("crop-right")

    val stringFormatSettings = ArrayList<String>()
    stringFormatSettings.add(KEY_MIME)

    // Copy int settings from input video format to encoder format
    for (setting in intFormatSettings) {
        if (inputMediaFormat.containsKey(setting)) {
            encoderFormat.setInteger(
                setting,
                inputMediaFormat.getInteger(setting)
            )

            // Get the real width and height
            // See: https://developer.android.com/reference/android/media/MediaCodec#accessing-raw-video-bytebuffers-on-older-devices
            if (setting == KEY_WIDTH) {
                encoderWidth = if (inputMediaFormat.containsKey("crop-left") && inputMediaFormat.containsKey("crop-right")) {
                    inputMediaFormat.getInteger("crop-right") + 1 - inputMediaFormat.getInteger("crop-left")
                } else {
                    inputMediaFormat.getInteger(setting)
                }
                MainActivity.logd("getBest3 : inputformat: width ${encoderWidth}")
            }
            if (setting == KEY_HEIGHT) {
                encoderHeight = if (inputMediaFormat.containsKey("crop-top") && inputMediaFormat.containsKey("crop-bottom")) {
                    inputMediaFormat.getInteger("crop-bottom") + 1 - inputMediaFormat.getInteger("crop-top")
                } else {
                    inputMediaFormat.getInteger(setting)
                }
                MainActivity.logd("getBest3 : inputformat: height ${encoderHeight}")
            }
        }
    }

    // Copy string settings from input video format to encoder format
    for (setting in stringFormatSettings) {
        if (inputMediaFormat.containsKey(setting)) {
            encoderFormat.setString(
                setting,
                inputMediaFormat.getString(setting)
            )
        }
    }

    // Bitrate, Framerate, and I Frame are required, try to choose sensible defaults
    if (!encoderFormat.containsKey(KEY_BIT_RATE)) {
        encoderFormat.setInteger(KEY_BIT_RATE, DEFAULT_BITRATE)
    }
    if (!encoderFormat.containsKey(KEY_FRAME_RATE)) {
        encoderFormat.setFloat(KEY_FRAME_RATE, 33F)
    }
    if (!encoderFormat.containsKey(KEY_I_FRAME_INTERVAL)) {
        encoderFormat.setInteger(KEY_I_FRAME_INTERVAL, 30)
    }

    // Choose the best settings best on device capabilities
    val typeArray: Array<String> = videoEncoderCodecInfo.supportedTypes
    if (typeArray.contains(mimeType)) {
        val capabilities: MediaCodecInfo.CodecCapabilities = videoEncoderCodecInfo.getCapabilitiesForType(mimeType)
        val encoderCapabilities = capabilities.encoderCapabilities
        val videoCapabilities = capabilities.videoCapabilities

        MainActivity.logd("getBest PRE 4 : size supported: width ${encoderWidth} height: ${encoderHeight}")
        MainActivity.logd("getBest supported Widths: ${videoCapabilities.supportedWidths}")
        MainActivity.logd("getBest supported Heights: ${videoCapabilities.supportedWidths}")
        MainActivity.logd("getBest supported bitrates: ${videoCapabilities.bitrateRange}")

/*
 *      Completely ignore advertised capabilities and see what happens
        // Width / Height
        if (!videoCapabilities.isSizeSupported(encoderWidth, encoderHeight)) {
            encoderWidth = videoCapabilities.supportedWidths.upper
            encoderHeight = videoCapabilities.getSupportedHeightsFor(encoderWidth).upper
        }

 */
        MainActivity.logd("getBest4 : size supported: width ${encoderWidth} height: ${encoderHeight}")

        // Framerate. If decoder does not tell us the frame rate, choose 33, 30, 25, or whatever.
        if (!inputMediaFormat.containsKey(KEY_FRAME_RATE)) {
            val supportedFrameRates = videoCapabilities.getSupportedFrameRatesFor(encoderWidth, encoderHeight)
            when {
                supportedFrameRates.contains(33.0) -> {
                    encoderFormat.setFloat(KEY_FRAME_RATE, 33F)
                    frameRate = 33
                }
                supportedFrameRates.contains(30.0) -> {
                    encoderFormat.setFloat(KEY_FRAME_RATE, 30F)
                    frameRate = 30
                }
                supportedFrameRates.contains(25.0) -> {
                    encoderFormat.setFloat(KEY_FRAME_RATE, 25F)
                    frameRate = 25
                }
                else -> {
                    encoderFormat.setFloat(KEY_FRAME_RATE, supportedFrameRates.upper.toFloat())
                    frameRate = supportedFrameRates.upper.toInt()
                }
            }
        }

        // Bitrate - Choose VBR or a default value
        if (!encoderFormat.containsKey(KEY_BIT_RATE)) {
            if (encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                encoderFormat.setInteger(
                    KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            } else {
                val bitrateRange = videoCapabilities.bitrateRange
                if (bitrateRange.contains(DEFAULT_BITRATE)) {
                    bitrate = DEFAULT_BITRATE
                    encoderFormat.setInteger(KEY_BIT_RATE, DEFAULT_BITRATE)
                } else {
                    encoderFormat.setInteger(KEY_BIT_RATE, bitrateRange.upper)
                    bitrate = bitrateRange.upper
                }
            }
        }

        // Profile level
        if (SDK_INT >= 24) {
            val profileLevels = capabilities.profileLevels
            var containsProfile = false
            for (profileLevel in profileLevels) {
                if (inputMediaFormat.containsKey(KEY_PROFILE) && profileLevel.profile == inputMediaFormat.getInteger(KEY_PROFILE)
                    && inputMediaFormat.containsKey(KEY_LEVEL) &&  profileLevel.level == inputMediaFormat.getInteger(KEY_LEVEL)
                ) {
                    // This encoder supports the input media profile/level, use it for the encoder
                    encoderFormat.setInteger(KEY_PROFILE, inputMediaFormat.getInteger(KEY_PROFILE))
                    encoderFormat.setInteger(KEY_LEVEL, inputMediaFormat.getInteger(KEY_LEVEL))
                    containsProfile = true
                }
            }

            // If this encoder cannot encode with this level and profile, choose something basic
            // TODO: Seems to be better just to let the device default. E.g. some Samsung phones don't support Main Profile 4
            if (!containsProfile) {
                /*
                    // Basically everything should support this
                    encoderFormat.setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    )
                    encoderFormat.setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel4
                    )
                */
            }
        }
    }

    encoderFormat.setInteger(KEY_WIDTH, encoderWidth)
    encoderFormat.setInteger(KEY_HEIGHT, encoderHeight)

    MainActivity.logd("BestEncoder: width: ${encoderWidth}, height: ${encoderHeight}, bitrate: ${bitrate}, frameRate: ${frameRate}")

    extractor.release()
    return encoderFormat
}

fun getBestAudioEncodingFormat(videoFd: AssetFileDescriptor) : MediaFormat {
    // Use the original decoded file to help set up the encode values
    // This is not necessary but just convenient
    val extractor = MediaExtractor()
    var inputAudioFormat = MediaFormat()
    var mimeType = ""

    extractor.setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)

    // Find the audio track format from the raw video file
    for (i in 0 until extractor.trackCount) {
        val trackFormat = extractor.getTrackFormat(i)
        mimeType = trackFormat.getString(KEY_MIME) ?: ""
        if (mimeType.startsWith("audio/")) {
            // viewModel.updateLog("Audio MIME type: ${mimeType}")
            inputAudioFormat = trackFormat
            break
        }
    }

    // The input format has a CSD-0 buffer and other information that can causes glitches in the
    // encoder. Just provide the basic needed info.
    // Passing in a CSD-0 buffer will cause errors in the audio stream
    // If there is no sample-rate or channel count, this is probably an empty audio stream so
    // using the input stream is ok
    val outputAudioFormat =  if (inputAudioFormat.containsKey(KEY_SAMPLE_RATE)
        && inputAudioFormat.containsKey(KEY_CHANNEL_COUNT)) {
        createAudioFormat(mimeType,
            inputAudioFormat.getInteger(KEY_SAMPLE_RATE),
            inputAudioFormat.getInteger(KEY_CHANNEL_COUNT))
    } else {
        inputAudioFormat
    }

    if (inputAudioFormat.containsKey(KEY_BIT_RATE)) {
        outputAudioFormat.setInteger(KEY_BIT_RATE, inputAudioFormat.getInteger(KEY_BIT_RATE))
    } else {
        outputAudioFormat.setInteger(KEY_BIT_RATE, 48000)
    }

    // Default encoder audio format is PCM_16BIT. Explicit reference here. If PCM_FLOAT or another
    // encoding is used without the correct key here, audio will be glitchy in the output file.
    // outputAudioFormat.setInteger(KEY_PCM_ENCODING, C.ENCODING_PCM_16BIT)

    extractor.release()
    return outputAudioFormat
}

/**
 * For convenience, set up the encoder to use the same video/audio format as the original file
 *
 * This method should be run to save these variables in the view model for later use
 */
fun setDefaultEncoderFormats(mainActivity: MainActivity, viewModel: MainViewModel) {
    viewModel.updateLog("I AM IN DEFAULT ENCODER FORMATS!!!!")

    // Video
    val videoFd = mainActivity.resources.openRawResourceFd(viewModel.originalRawFileId)
    val videoMimeType = getVideoTrackMimeType(videoFd)
    viewModel.videoEncoderCodecInfo = selectEncoder(videoMimeType)
    if (viewModel.videoEncoderCodecInfo == null) {
        viewModel.updateLog("WARNING: No valid video encoder codec. Encoded file may be broken.")
    }
    viewModel.encoderVideoFormat = getBestVideoEncodingFormat(videoFd)

    // Audio
    val audioMimeType = getAudioTrackMimeType(videoFd)
    viewModel.audioEncoderCodecInfo = selectEncoder(audioMimeType)
    if (viewModel.audioEncoderCodecInfo == null) {
        viewModel.updateLog("WARNING: No valid audio encoder codec. Audio in encoded file will not work")
    }
    viewModel.encoderAudioFormat = getBestAudioEncodingFormat(videoFd)

    // Encoder debug info
    viewModel.updateLog("setDefaultFormats: Video encoder: ${viewModel.videoEncoderCodecInfo?.name}, ${viewModel.encoderVideoFormat}")
    viewModel.updateLog("setDefaultFormats: Audio encoder: ${viewModel.audioEncoderCodecInfo?.name},  ${viewModel.encoderAudioFormat}")

    videoFd.close()
}