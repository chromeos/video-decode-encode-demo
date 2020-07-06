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

package dev.hadrosaur.videodecodeencodedemo.VideoHelpers

import android.media.*
import android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4
import android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
import android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
import android.media.MediaFormat.*
import android.os.Environment
import android.view.Surface
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_EVERY_N_FRAMES
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import java.io.*
import java.lang.Thread.sleep
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList


// TODO: there is a memory leak of about 20mb per run. Track it down
/**
 * Encode frames sent into the encoderInputSurface
 *
 * This does not check for an EOS flag. The encode ends when "decodeComplete" is indicated and the
 * number of encoded frames matches the number of decoded frames
 */
class VideoEncoder(val mainActivity: MainActivity, originalRawFileId: Int){
    val encoderInputSurface: Surface
    val encoder: MediaCodec
    val encoderFormat: MediaFormat
    var muxer: MediaMuxer? = null

    val timeOutUs: Long = 5000
    val encoderBufferInfo = MediaCodec.BufferInfo()

    var decodeComplete = false
    var numDecodedFrames = AtomicInteger(0)
    var encodeComplete = false
    var numEncodedFrames = AtomicInteger(0)

    // FPS trackers
    var startTime = 0L
    var endTime = 0L
    var lastPresentationTime = 0L

    // Encoder variables
    var muxerInitialized = false
    var videoTrackIndex: Int = 0
    var width = 1920
    var height = 1080

    init{
        // Use the original decoded file to help set up the encode values
        val extractor = MediaExtractor()
        var format = MediaFormat()
        var mimeType = ""

        // Load file from raw directory
        val videoFd = mainActivity.resources.openRawResourceFd(originalRawFileId)
        extractor.setDataSource(videoFd.fileDescriptor, videoFd.startOffset, videoFd.length)

        // Find the MIME type from the raw video file
        for (i in 0 until extractor.trackCount) {
            format = extractor.getTrackFormat(i)
            mimeType = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mimeType.startsWith("video/")) {
                extractor.selectTrack(i)
                break
            }
        }
        extractor.release()
        videoFd.close()

        // Get the best encoder for give MIME type
        val encoderCodecInfo: MediaCodecInfo? = selectEncoder(mimeType)
        if (encoderCodecInfo == null) {
            logd("WARNING: No valid encoder codec.")
        }
        encoder = MediaCodec.createByCodecName(encoderCodecInfo?.getName()!!)

        encoderFormat = getBestEncodingFormat(mimeType, format, encoderCodecInfo)

        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Get the input surface from the encoder, decoded frames from the decoder should be
        // placed here.
        encoderInputSurface = encoder.createInputSurface()
    }

    /**
     * Encode any available frames on the encoderInputSurface
     */
    fun encodeAvailableFrames() {
        while (!encodeComplete) {

            // Get next available encoded frame if available
            val encoderBufferId =
                encoder.dequeueOutputBuffer(encoderBufferInfo, timeOutUs)

            // No encoded frames to work with, exit
            if (encoderBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break

            } else if (encoderBufferId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                logd("Encoder output buffers changed")
                continue

            } else if (encoderBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // This should happen once for an encode, set up the MediaMuxer
                val newFormat = encoder.outputFormat
                val filename = MainActivity.FILE_PREFIX + "-" + generateTimestamp() + ".mp4"
                val videoDir = getAppSpecificVideoStorageDir(mainActivity, MainActivity.FILE_PREFIX)
                val videoFile = File(videoDir, filename)
                muxer = MediaMuxer(videoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                muxerInitialized = true
                videoTrackIndex = muxer?.addTrack(newFormat) ?: -1
                muxer?.start()
                continue

            } else if (encoderBufferId < 0) {
                mainActivity.updateLog("ERROR: something occured during encoding: $encoderBufferId")
                break

            // Else if there is good encoded data, encode it
            } else {
                val encodedData: ByteBuffer? = encoder.getOutputBuffer(encoderBufferId)
                if (encodedData == null) {
                    // No data, continue
                    logd("ERROR: got empty encode buffer")
                    continue
                }

                // TODO: The first frame should have a MediaCodec.BUFFER_FLAG_CODEC_CONFIG set but
                // this may not come from this sdurface decode. Perhaps check for this and if the
                // first buffer is not a config buffer, construct it here

                if (encoderBufferInfo.size !== 0) {
                    encodedData.position(encoderBufferInfo.offset)
                    encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                    numEncodedFrames.incrementAndGet()

                    // This is a logging check that frames are not being encoded in the wrong order
                    if (lastPresentationTime > encoderBufferInfo.presentationTimeUs) {
                        mainActivity.updateLog("Out of order presentation time at frame: ${numEncodedFrames.get() - 1}, ${encoderBufferInfo.presentationTimeUs}")
                    }
                    lastPresentationTime = encoderBufferInfo.presentationTimeUs


                    // TODO: This is creating a video speed with playback speed tied to encoding
                    // speed. Fix this is to be a proper playback speed
                    // Send the frame to the muxer
                    if (muxerInitialized) {
                        muxer?.writeSampleData(videoTrackIndex, encodedData, encoderBufferInfo)
                    }

                    // Log current encoding speed
                    if (numEncodedFrames.get() % LOG_EVERY_N_FRAMES == 0) {
                        val currentFPS =
                            numEncodedFrames.get() / ((System.currentTimeMillis() - startTime) / 1000.0)
                        val FPSString = String.format("%.2f", currentFPS)
                        mainActivity.updateLog("Encoding at ${FPSString}fps, frame $numEncodedFrames.")
                    }
                }

                // Is the encode done?
                if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
                   || (decodeComplete && (numEncodedFrames.get() > 0) && (numEncodedFrames.get() == numDecodedFrames.get()))
                ) {
                    endTime = System.currentTimeMillis()
                    val totalTime = (endTime - startTime) / 1000.0
                    val totalFPS = numEncodedFrames.get() / totalTime
                    val timeString = String.format("%.2f", totalTime)
                    val FPSString = String.format("%.2f", totalFPS)

                    mainActivity.updateLog("Encode done. $numEncodedFrames frames in ${timeString}s (${FPSString}fps).")
                    encodeComplete = true
                    finishEncode()
                }

                encoder.releaseOutputBuffer(encoderBufferId, false)
            }
        }
    }

    fun startEncode() {
        if (encoderInputSurface.isValid) {
            encoder.start()
            startTime = System.currentTimeMillis()
            endTime = 0L
        }
    }

    fun finishEncode() {
        if (muxerInitialized) {
            muxer?.stop()
        }
        mainActivity.encodeFinished()
    }

    fun release() {
        muxer?.release()
        encoder.stop()
        encoder.release()
        encoderInputSurface.release();
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private fun selectEncoder(mimeType: String): MediaCodecInfo? {
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
//                    mainActivity.updateLog("Found encoder for $mimeType: ${codecInfo.name}")

                    // On Chrome OS there was a reported problem with the c2 encoders.
                    // Uncomment the next 3 lines to use OMX instead
//                    if(mainActivity.isArc() && codecInfo.name.contains("c2.")) {
//                        continue
//                    }

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
     * Tries to get the best encoding settings for the device. Currently this merely provides
     * mediocre values somewhat like the original file.
     *
     * TODO: improve this function to provide nice encoding that matches the original stream
     */
    fun getBestEncodingFormat(mimeType: String, decodeFormat: MediaFormat, codecInfo: MediaCodecInfo) : MediaFormat {
        val DEFAULT_BITRATE = 20000000

        // Configure encoder with the same format as the source media
        // Default to 1080p, but width and height will be over-written
        val encoderFormat = createVideoFormat(mimeType, 1920, 1080)
        encoderFormat.setInteger(
            KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        // Settings to copy from the original video format
        val intFormatSettings = ArrayList<String>()
        intFormatSettings.add(KEY_LEVEL)
        intFormatSettings.add(KEY_PROFILE)
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

        val stringFormatSettings = ArrayList<String>()
        stringFormatSettings.add(KEY_MIME)

        // Copy int settings from input video format to encoder format
        for (setting in intFormatSettings) {
            if (decodeFormat.containsKey(setting)) {
                encoderFormat.setInteger(
                    setting,
                    decodeFormat.getInteger(setting)
                )

                // Get the real width and height
                if (setting == KEY_WIDTH) {
                    width = decodeFormat.getInteger(setting)
                }
                if (setting == KEY_HEIGHT) {
                    height = decodeFormat.getInteger(setting)
                }
            }
        }

        // Copy string settings from input video format to encoder format
        for (setting in stringFormatSettings) {
            if (decodeFormat.containsKey(setting)) {
                encoderFormat.setString(
                    setting,
                    decodeFormat.getString(setting)
                )
            }
        }

        // Bitrate, Framerate, and I Frame are required, try to choose sensible defaults
        if (!encoderFormat.containsKey(KEY_BIT_RATE)) {
            encoderFormat.setInteger(KEY_BIT_RATE, DEFAULT_BITRATE);
        }
        if (!encoderFormat.containsKey(KEY_FRAME_RATE)) {
            encoderFormat.setFloat(KEY_FRAME_RATE, 33F);
        }
        if (!encoderFormat.containsKey(KEY_I_FRAME_INTERVAL)) {
            encoderFormat.setInteger(KEY_I_FRAME_INTERVAL, 0)
        }

        // Choose the best settings best on device capabilities
        val typeArray: Array<String> = codecInfo.supportedTypes
        if (typeArray.contains(mimeType)) {
            val capabilities: MediaCodecInfo.CodecCapabilities = codecInfo.getCapabilitiesForType(mimeType)
            val encoderCapabilities = capabilities.encoderCapabilities
            val videoCapabilities = capabilities.videoCapabilities

            // Width / Height
            if (!videoCapabilities.isSizeSupported(width, height)) {
                width = videoCapabilities.supportedWidths.upper
                height = videoCapabilities.getSupportedHeightsFor(width).upper
            }

            // Framerate. If decoder does not tell us the frame rate, choose 33, 30, 25, or whatever.
            if (!decodeFormat.containsKey(KEY_FRAME_RATE)) {
                val supportedFrameRates = videoCapabilities.getSupportedFrameRatesFor(width, height)
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
            if (!encoderFormat.containsKey(KEY_BIT_RATE)) {
                if (encoderCapabilities.isBitrateModeSupported(BITRATE_MODE_VBR)) {
                    encoderFormat.setInteger(KEY_BITRATE_MODE, BITRATE_MODE_VBR)
                } else {
                    val bitrateRange = videoCapabilities.getBitrateRange()
                    if (bitrateRange.contains(DEFAULT_BITRATE)) {
                        encoderFormat.setInteger(KEY_BIT_RATE, DEFAULT_BITRATE);
                    } else {
                        encoderFormat.setInteger(KEY_BIT_RATE, bitrateRange.upper);
                    }
                }
            }

            // Profile level
            val profileLevels = capabilities.profileLevels
            var containsProfile = false
            for (profileLevel in profileLevels) {
                if (profileLevel.profile == encoderFormat.getInteger(MediaFormat.KEY_PROFILE) && profileLevel.level == encoderFormat.getInteger(MediaFormat.KEY_LEVEL)) {
                    containsProfile = true
                }
            }
            // If this encoder cannot encode with this level and profile, choose something basic
            if (!containsProfile) {
                // Basically everything should support this
                encoderFormat.setInteger(MediaFormat.KEY_PROFILE, AVCProfileMain)
                encoderFormat.setInteger(MediaFormat.KEY_PROFILE, AVCLevel4)
            }
        }
        return encoderFormat
    }


    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
     */
    private fun selectDecoder(mimeType: String): MediaCodecInfo? {
        val numCodecs = MediaCodecList.getCodecCount()
        val validCodecs = ArrayList<MediaCodecInfo>()
        for (i in 0 until numCodecs) {
            val codecInfo = MediaCodecList.getCodecInfoAt(i)
            // Only decoders
            if (codecInfo.isEncoder) {
                continue
            }
            val types = codecInfo.supportedTypes
            for (j in types.indices) {
                if (types[j].equals(mimeType, ignoreCase = true)) {
                    // c2 decoders are WAY faster for decoding on Chrome OS.
                    // On Chrome OS there was a reported problem with the c2 encoders.
                    // Uncomment the next 3 lines to use OMX instead
                    // if(isArc() && codecInfo.name.contains("c2.")) {
                    //    continue
                    // }

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
     * Method the renderer can call to tell the VideoEncoder that the video decoding is complete.
     *
     * VideoEncoder will keep encoding until number of encoded frames equals number decoded frames.
     *
     * TODO: If a frame is dropped or something weird happens, this might get stuck in endless loop,
     * provide a reasonable time out.
     */
    fun signalDecodingComplete() {
        decodeComplete = true
        while (!encodeComplete) {
            encodeAvailableFrames()
            sleep(100)
            if ((numEncodedFrames.get() > 0) && (numEncodedFrames.get() == numDecodedFrames.get())) {
                encodeComplete = true
            }
        }
    }

    /**
     * Generate a timestamp to append to saved filenames.
     */
    fun generateTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return sdf.format(Date())
    }

    /**
     * Get the Movies directory that's inside the app-specific directory on
     * external storage.
     */
    fun getAppSpecificVideoStorageDir(mainActivity: MainActivity, prefix: String): File? {
        val file = File(mainActivity.getExternalFilesDir(
            Environment.DIRECTORY_MOVIES), prefix)

        // Make the directory if it does not exist yet
        if (!file.mkdirs()) {
            mainActivity.updateLog("Error creating encoding directory: ${file.absolutePath}")
        }
        return file
    }
}