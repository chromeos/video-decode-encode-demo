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
import android.media.*
import android.media.MediaFormat.*
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel


/**
 * Returns the first codec capable of encoding the specified MIME type, or null if no
 * match was found.
 */
fun selectEncoder(mimeType: String): MediaCodecInfo? {
    val numCodecs = MediaCodecList.getCodecCount()
    val validCodecs = ArrayList<MediaCodecInfo>()
    for (i in 0 until numCodecs) {
        val codecInfo = MediaCodecList.getCodecInfoAt(i)
        if (!codecInfo.isEncoder) {
            continue
        }
        val types = codecInfo.supportedTypes
        for (j in types.indices) {
            if (types[j].equals(mimeType, ignoreCase = true)) {
                validCodecs.add(codecInfo)
            }
        }
    }

    // Default to returning the first codec
    if (validCodecs.size > 0) {
        return validCodecs.get(0)
    } else {
        return null
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
    val DEFAULT_BITRATE = 20000000

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
            // viewModel.updateLog("Video MIME type: ${mimeType}")
            inputMediaFormat = trackFormat
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
    intFormatSettings.add(KEY_PRIORITY)
    intFormatSettings.add(KEY_WIDTH)
    intFormatSettings.add(KEY_HEIGHT)
    intFormatSettings.add(KEY_LEVEL)
    intFormatSettings.add(KEY_COLOR_STANDARD)
    intFormatSettings.add(KEY_COLOR_RANGE)
    intFormatSettings.add(KEY_COLOR_TRANSFER)
    intFormatSettings.add(KEY_BIT_RATE)
    intFormatSettings.add(KEY_FRAME_RATE)
    intFormatSettings.add(KEY_I_FRAME_INTERVAL)

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
                if (inputMediaFormat.containsKey("crop-left") && inputMediaFormat.containsKey("crop-right")) {
                    encoderWidth = inputMediaFormat.getInteger("crop-right") + 1 - inputMediaFormat.getInteger("crop-left")
                } else {
                    encoderWidth = inputMediaFormat.getInteger(setting)
                }
            }
            if (setting == KEY_HEIGHT) {
                if (inputMediaFormat.containsKey("crop-top") && inputMediaFormat.containsKey("crop-bottom")) {
                    encoderHeight = inputMediaFormat.getInteger("crop-bottom") + 1 - inputMediaFormat.getInteger("crop-top")
                } else {
                    encoderHeight = inputMediaFormat.getInteger(setting)
                }
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

        // Width / Height
        if (!videoCapabilities.isSizeSupported(encoderWidth, encoderHeight)) {
            encoderWidth = videoCapabilities.supportedWidths.upper
            encoderHeight = videoCapabilities.getSupportedHeightsFor(encoderWidth).upper
        }

        // Framerate. If decoder does not tell us the frame rate, choose 33, 30, 25, or whatever.
        if (!inputMediaFormat.containsKey(KEY_FRAME_RATE)) {
            val supportedFrameRates = videoCapabilities.getSupportedFrameRatesFor(encoderWidth, encoderHeight)
            if (supportedFrameRates.contains(33.0)) {
                encoderFormat.setFloat(KEY_FRAME_RATE, 33F)
            } else if (supportedFrameRates.contains(30.0)) {
                encoderFormat.setFloat(KEY_FRAME_RATE, 30F)
            } else if (supportedFrameRates.contains(25.0)) {
                encoderFormat.setFloat(KEY_FRAME_RATE, 25F)
            } else {
                encoderFormat.setFloat(KEY_FRAME_RATE, supportedFrameRates.upper.toFloat())
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
                val bitrateRange = videoCapabilities.getBitrateRange()
                if (bitrateRange.contains(DEFAULT_BITRATE)) {
                    encoderFormat.setInteger(KEY_BIT_RATE, DEFAULT_BITRATE)
                } else {
                    encoderFormat.setInteger(KEY_BIT_RATE, bitrateRange.upper)
                }
            }
        }

        // Profile level
        val profileLevels = capabilities.profileLevels
        var containsProfile = false
        for (profileLevel in profileLevels) {
            if (profileLevel.profile == inputMediaFormat.getInteger(KEY_PROFILE)
                && profileLevel.level == inputMediaFormat.getInteger(KEY_LEVEL)) {
                    // This encoder supports the input media profile/level, use it for the encoder
                    encoderFormat.setInteger(KEY_PROFILE, inputMediaFormat.getInteger(KEY_PROFILE))
                    encoderFormat.setInteger(KEY_LEVEL, inputMediaFormat.getInteger(KEY_LEVEL))
                    containsProfile = true
            }
        }

        // If this encoder cannot encode with this level and profile, choose something basic
        // TODO: Seems to be better just to let the device default. Some Samsung phones don't support Main Profile 4
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

    encoderFormat.setInteger(KEY_WIDTH, encoderWidth)
    encoderFormat.setInteger(KEY_HEIGHT, encoderHeight)

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
    val outputAudioFormat = createAudioFormat(mimeType,
        inputAudioFormat.getInteger(KEY_SAMPLE_RATE),
        inputAudioFormat.getInteger(KEY_CHANNEL_COUNT))
    outputAudioFormat.setInteger(KEY_BIT_RATE, inputAudioFormat.getInteger(KEY_BIT_RATE))

    extractor.release()
    return outputAudioFormat
}

/**
 * For convenience, set up the encoder to use the same video/audio format as the originial file
 *
 * This method should be run to save these variables in the view model for later use
 */
fun setDefaultEncoderFormats(mainActivity: MainActivity, viewModel: MainViewModel) {
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
    // viewModel.updateLog("Video encoder: ${viewModel.videoEncoderCodecInfo?.name}, ${viewModel.encoderVideoFormat}")
    // viewModel.updateLog("Audio encoder: ${viewModel.audioEncoderCodecInfo?.name},  ${viewModel.encoderAudioFormat}")

    videoFd.close()
}