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

package dev.hadrosaur.videodecodeencodedemo

import android.content.res.AssetFileDescriptor
import android.media.*
import android.media.MediaFormat.KEY_HEIGHT
import android.media.MediaFormat.KEY_WIDTH
import android.os.Handler
import com.google.android.exoplayer2.Renderer
import com.google.android.exoplayer2.RenderersFactory
import com.google.android.exoplayer2.audio.AudioRendererEventListener
import com.google.android.exoplayer2.metadata.MetadataOutput
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.text.TextOutput
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.RawResourceDataSource
import com.google.android.exoplayer2.video.VideoRendererEventListener
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioOutputBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.VideoMediaCodecAudioRenderer
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.InternalSurfaceTextureComponent
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.SpeedyMediaClock
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoMediaCodecVideoRenderer
import java.util.concurrent.ConcurrentLinkedQueue

// Create an ExoPlayer media source from a raw resource ID
fun buildExoMediaSource(mainActivity: MainActivity, raw: Int): MediaSource {
    val uri = RawResourceDataSource.buildRawResourceUri(raw)

    val dataSourceFactory: DataSource.Factory =
        DefaultDataSourceFactory(mainActivity,
            MainActivity.LOG_TAG
        )
    return ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(uri)
}

class CustomExoRenderersFactory(val mainActivity: MainActivity, val internalSurfaceTextureComponent: InternalSurfaceTextureComponent, val streamNumber: Int,
                                val audioBufferQueue: ConcurrentLinkedQueue<AudioOutputBuffer>, val shouldEncode: Boolean) :
    RenderersFactory {
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput
    ): Array<Renderer> {
        val mediaClock = SpeedyMediaClock()

        return arrayOf(
            VideoMediaCodecVideoRenderer(mainActivity, internalSurfaceTextureComponent, true, streamNumber, mediaClock),
            VideoMediaCodecAudioRenderer(mainActivity, streamNumber, audioBufferQueue, shouldEncode)
        )
    }
}

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
        mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
        if (mimeType.startsWith("video/")) {
            // mainActivity.updateLog("Video MIME type: ${mimeType}")
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
        mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
        if (mimeType.startsWith("audio/")) {
            // mainActivity.updateLog("Audio MIME type: ${mimeType}")
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
        mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
        if (mimeType.startsWith("video/")) {
            // mainActivity.updateLog("Video MIME type: ${mimeType}")
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
    var encoderWidth = inputMediaFormat.getInteger(MediaFormat.KEY_WIDTH)
    var encoderHeight = inputMediaFormat.getInteger(MediaFormat.KEY_HEIGHT)

    // Configure encoder with the same format as the source media
    val encoderFormat = MediaFormat.createVideoFormat(
        mimeType,
        encoderWidth,
        encoderHeight
    )
    encoderFormat.setInteger(
        MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
    )

    // Settings to copy from the original video format
    val intFormatSettings = ArrayList<String>()
    intFormatSettings.add(MediaFormat.KEY_LEVEL)
    intFormatSettings.add(MediaFormat.KEY_PROFILE)
    intFormatSettings.add(MediaFormat.KEY_PRIORITY)
    intFormatSettings.add(MediaFormat.KEY_WIDTH)
    intFormatSettings.add(MediaFormat.KEY_HEIGHT)
    intFormatSettings.add(MediaFormat.KEY_LEVEL)
    intFormatSettings.add(MediaFormat.KEY_COLOR_STANDARD)
    intFormatSettings.add(MediaFormat.KEY_COLOR_RANGE)
    intFormatSettings.add(MediaFormat.KEY_COLOR_TRANSFER)
    intFormatSettings.add(MediaFormat.KEY_BIT_RATE)
    intFormatSettings.add(MediaFormat.KEY_FRAME_RATE)
    intFormatSettings.add(MediaFormat.KEY_I_FRAME_INTERVAL)

    // Crop values are essential as some media decodes to larger width/height than the actual
    // video. For example, 1920x1080 will decode to 1920x1088, with 8px crop offset.
    // Encoding without correct crop values will show weird smearing/glitches.
    intFormatSettings.add("crop-bottom")
    intFormatSettings.add("crop-top")
    intFormatSettings.add("crop-left")
    intFormatSettings.add("crop-right")

    val stringFormatSettings = ArrayList<String>()
    stringFormatSettings.add(MediaFormat.KEY_MIME)

    // Copy int settings from input video format to encoder format
    for (setting in intFormatSettings) {
        if (inputMediaFormat.containsKey(setting)) {
            encoderFormat.setInteger(
                setting,
                inputMediaFormat.getInteger(setting)
            )

            // Get the real width and height
            // See: https://developer.android.com/reference/android/media/MediaCodec#accessing-raw-video-bytebuffers-on-older-devices
            if (setting == MediaFormat.KEY_WIDTH) {
                if (inputMediaFormat.containsKey("crop-left") && inputMediaFormat.containsKey("crop-right")) {
                    encoderWidth = inputMediaFormat.getInteger("crop-right") + 1 - inputMediaFormat.getInteger("crop-left")
                } else {
                    encoderWidth = inputMediaFormat.getInteger(setting)
                }
            }
            if (setting == MediaFormat.KEY_HEIGHT) {
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
    if (!encoderFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
        encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE);
    }
    if (!encoderFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
        encoderFormat.setFloat(MediaFormat.KEY_FRAME_RATE, 33F);
    }
    if (!encoderFormat.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
        encoderFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30)
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
        if (!inputMediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val supportedFrameRates = videoCapabilities.getSupportedFrameRatesFor(encoderWidth, encoderHeight)
            if (supportedFrameRates.contains(33.0)) {
                encoderFormat.setFloat(MediaFormat.KEY_FRAME_RATE, 33F);
            } else if (supportedFrameRates.contains(30.0)) {
                encoderFormat.setFloat(MediaFormat.KEY_FRAME_RATE, 30F);
            } else if (supportedFrameRates.contains(25.0)) {
                encoderFormat.setFloat(MediaFormat.KEY_FRAME_RATE, 25F);
            } else {
                encoderFormat.setFloat(MediaFormat.KEY_FRAME_RATE, supportedFrameRates.upper.toFloat());
            }
        }

        // Bitrate - Choose VBR or a default value
        if (!encoderFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
            if (encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)) {
                encoderFormat.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
            } else {
                val bitrateRange = videoCapabilities.getBitrateRange()
                if (bitrateRange.contains(DEFAULT_BITRATE)) {
                    encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE);
                } else {
                    encoderFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrateRange.upper);
                }
            }
        }

        // Profile level
        val profileLevels = capabilities.profileLevels
        var containsProfile = false
        for (profileLevel in profileLevels) {
            if (profileLevel.profile == encoderFormat.getInteger(MediaFormat.KEY_PROFILE) && profileLevel.level == encoderFormat.getInteger(
                    MediaFormat.KEY_LEVEL
                )) {
                containsProfile = true
            }
        }
        // If this encoder cannot encode with this level and profile, choose something basic
        if (!containsProfile) {
            // Basically everything should support this
            encoderFormat.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            )
            encoderFormat.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4
            )
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
        mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
        if (mimeType.startsWith("audio/")) {
            // mainActivity.updateLog("Audio MIME type: ${mimeType}")
            inputAudioFormat = trackFormat
            break
        }
    }

    extractor.release()
    return inputAudioFormat
}

