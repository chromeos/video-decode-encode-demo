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
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.media.MediaFormat.*
import android.os.Environment
import android.view.Surface
import dev.hadrosaur.videodecodeencodedemo.*
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.cloneByteBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.getBufferDurationUs
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_VIDEO_EVERY_N_FRAMES
import dev.hadrosaur.videodecodeencodedemo.Utils.*
import java.io.*
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger


// TODO: there is a memory leak of about 20mb per run. Track it down
/**
 * Encode frames sent into the encoderInputSurface
 *
 * This does not check for an EOS flag. The encode ends when "decodeComplete" is indicated and the
 * number of encoded frames matches the number of decoded frames
 */
class AudioVideoEncoder(val viewModel: MainViewModel, val frameLedger: VideoFrameLedger, val audioBufferManager: AudioBufferManager) {
    // Video encoding variables
    val videoEncoderInputSurface: Surface
    val videoEncoder: MediaCodec
    val videoEncoderCallback: VideoEncoderCallback
    val encoderVideoFormat: MediaFormat
    var videoDecodeComplete = false
    var numDecodedVideoFrames = AtomicInteger(0)
    var videoEncodeComplete = false
    var numEncodedVideoFrames = AtomicInteger(0)

    // Video encoder default values, will be changed when video encoder is set up
    var width = 1920
    var height = 1080

    // FPS trackers
    var startTime = 0L
    var endTime = 0L
    var lastPresentationTime = 0L

    // Audio encoding variables
    val audioEncoder: MediaCodec
    val audioEncoderCallback: AudioEncoderCallback
    val encoderAudioFormat: MediaFormat
    var audioEncodeComplete = false

    // Muxer variables
    var muxer: MediaMuxer? = null
    var encodedFilename = ""
    var isMuxerRunning = false
    var videoTrackIndex: Int = -1
    var audioTrackIndex: Int = -1

    val audioBufferListener : AudioBufferManager.AudioBufferManagerListener

    init{
        videoEncoder = MediaCodec.createByCodecName(viewModel.videoEncoderCodecInfo?.getName()!!)
        encoderVideoFormat = viewModel.encoderVideoFormat

        // Save encoder width and height for surfaces that use this encoder
        width = encoderVideoFormat.getInteger(KEY_WIDTH)
        height = encoderVideoFormat.getInteger(KEY_HEIGHT)

        audioEncoder = MediaCodec.createByCodecName(viewModel.audioEncoderCodecInfo?.getName()!!)
        encoderAudioFormat = viewModel.encoderAudioFormat

        // Use asynchronous modes with callbacks - encoding logic contained in the callback classes
        // See: https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers

        // Video encoder
        videoEncoderCallback = VideoEncoderCallback(viewModel, encoderVideoFormat)
        videoEncoder.setCallback(videoEncoderCallback)
        videoEncoder.configure(encoderVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Audio encoder
        audioEncoderCallback = AudioEncoderCallback(viewModel, encoderAudioFormat)
        audioEncoder.setCallback(audioEncoderCallback)
        audioEncoder.configure(encoderAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        audioEncodeComplete = false

        // Register a listener for when the AudioBufferManager gets new data
        audioBufferListener = object: AudioBufferManager.AudioBufferManagerListener {
            override fun newAudioData() {
                // There is new audio data in the audioBufferQueue. If there are queued input buffers
                // in the audio encoder, use them.
                audioEncoderCallback.onNewAudioData()
            }
        }
        audioBufferManager.listener = audioBufferListener

        // Get the input surface from the encoder, decoded frames from the decoder should be
        // placed here.
        videoEncoderInputSurface = videoEncoder.createInputSurface()

        // Setup Muxer
        val outputFilename = MainActivity.FILE_PREFIX + "-" + generateTimestamp() + ".mp4"
        val outputVideoFile = File(viewModel.encodeOutputDir, outputFilename)
        encodedFilename = outputVideoFile.name
        muxer = MediaMuxer(outputVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun startEncode() {
        if (videoEncoderInputSurface.isValid) {
            videoEncoder.start()
            audioEncoder.start()
            startTime = System.currentTimeMillis()
            endTime = 0L
        }
    }

    fun finishEncode() {
        if (isMuxerRunning) {
            muxer?.stop()
            isMuxerRunning = false
        }
        // Signal to the MainActivity that the encoding has now finished
        viewModel.setEncodingInProgress(false)
    }

    fun release() {
        try {
            if (isMuxerRunning) {
                muxer?.stop()
                isMuxerRunning = false
            }
            muxer?.release()
        } catch (e: IllegalStateException) {
            // This will be thrown if the muxer was started but no data sent, ok for this app
            // viewModel.updateLog("Error stopping VideoEncoder MediaMuxer: ${e.message}.")
        }

        videoEncoder.stop()
        audioEncoder.stop()
        videoEncoder.release()
        audioEncoder.release()
        videoEncoderInputSurface.release();
    }

    fun signalDecodingComplete() {
        videoDecodeComplete = true
    }

    fun signalEncodingComplete() {
        videoEncodeComplete = true
    }

    /**
     * Because video frames are just raw frames coming in from a surface, the encoder needs to
     * manually check if the encode is complete.
     */
    fun checkIfEncodeDone() {
        if ((videoDecodeComplete && audioEncodeComplete && (numEncodedVideoFrames.get() == numDecodedVideoFrames.get()))) {
            endTime = System.currentTimeMillis()
            val totalTime = (endTime - startTime) / 1000.0
            val totalFPS = numEncodedVideoFrames.get() / totalTime
            val timeString = String.format("%.2f", totalTime)
            val FPSString = String.format("%.2f", totalFPS)

            viewModel.updateLog("Encode done, written to ${encodedFilename}. ${numEncodedVideoFrames.get()} frames in ${timeString}s (${FPSString}fps).")
            signalEncodingComplete()
            finishEncode()
        }
    }

    /**
     * Start the muxer if both the video track and audio track have been set up
     */
    fun startMuxerIfReady() {
        if (videoTrackIndex != -1 && audioTrackIndex != -1) {
            muxer?.start()
            isMuxerRunning = true
        }
    }

    /**
     * The callback functions for Video encoding
     */
    inner class VideoEncoderCallback(val viewModel: MainViewModel, var format: MediaFormat): MediaCodec.Callback() {
        val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()
        val ledgerQueue = LinkedBlockingQueue<LedgerBuffer>()

        // Do not do anything. Incoming frames should
        // be auto-queued into the encoder from the input surface
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            return
        }

        /**
         * A video frame is available from the video encoder.
         *
         * If not a config frame, send it to the muxer. There are 2 possible queues a frame may be
         * added to:
         *  - muxingQueue: if a frame is received from the encoder but the muxer is not yet started
         *  - ledgerQueue: if a frame is received but the decoder has not yet recorder it's proper
         *                 presentation time.
         *
         * Always mux muxing queue first, then ledger queue, then new frames
         */
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {
                // When a codec config buffer is received, no need to pass it to the muxer
                if (info.flags == BUFFER_FLAG_CODEC_CONFIG) {
                    videoTrackIndex = muxer?.addTrack(format) ?: -1
                    startMuxerIfReady()

                } else {
                    val frameNum = numEncodedVideoFrames.incrementAndGet()

                    // The encode ledger contains the correct presentation time from the decoder,
                    // stored based on the frame number. This assumes frames
                    // hit the decoder output surface in the same order as they exit the encoder.
                    // If this assumption is not true, frames may be encoded out of order.
                    // If the ledger value is not stored by this point in the code,
                    // add the frame to the ledger queue to be muxed when we the correct time
                    if (frameLedger.encodeLedger.containsKey(frameNum)) {
                        info.presentationTimeUs = frameLedger.encodeLedger.get(frameNum)!!
                        // viewModel.updateLog("Video encoder, got frame number ${frameNum}@${info.presentationTimeUs}, last time was ${lastPresentationTime}.")
                        lastPresentationTime = info.presentationTimeUs

                        // If the muxer hasn't started yet - eg. if the audio stream hasn't been
                        // configured yet - queue the output data for later.
                        if (!isMuxerRunning) {
                            viewModel.updateLog("Adding buffer to video muxing buffer: ${frameNum}")
                            muxingQueue.add(MuxingBuffer(cloneByteBuffer(outputBuffer), info))
                        } else {
                            // Mux any buffers that were waiting for the muxer to start
                            if (!muxingQueue.isEmpty()) {
                                while (muxingQueue.peek() != null) {
                                    val muxingBuffer = muxingQueue.poll()
                                    viewModel.updateLog("Muxing buffer out of mux queue: ${muxingBuffer.info.presentationTimeUs}")
                                    muxer?.writeSampleData(
                                        videoTrackIndex,
                                        muxingBuffer.buffer,
                                        muxingBuffer.info
                                    )
                                }
                            }

                            // Check if there are any frames that were not matched with ledger data
                            muxLedgerQueue()

                            // Send the new frame to the muxer
                            muxer?.writeSampleData(videoTrackIndex, outputBuffer, info)

                            // Log current encoding speed
                            if (numEncodedVideoFrames.get() % LOG_VIDEO_EVERY_N_FRAMES == 0) {
                                val currentFPS =
                                    numEncodedVideoFrames.get() / ((System.currentTimeMillis() - startTime) / 1000.0)
                                val FPSString = String.format("%.2f", currentFPS)
                                viewModel.updateLog("Encoding video stream at ${FPSString}fps, frame $numEncodedVideoFrames.")
                            }
                        } // Is muxer running

                    } else {
                        // No ledger info yet for this buffer, add it to the ledger queue to be processed later
                        viewModel.updateLog("WARNING: Frame number ${frameNum} not found in ledger, adding to ledgerQueue.")
                        ledgerQueue.add(LedgerBuffer(outputBuffer, info, frameNum))
                    }
               } // If not a config buffer
            } // Is output buffer null
            codec.releaseOutputBuffer(index, false)

            // If encode is finished, there will be no more output buffers received, check manually
            // if video encode is finished
            checkIfEncodeDone()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            viewModel.updateLog("ERROR: something occurred during video encoding: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }

        // Mux any buffers in the ledger queue, if the ledger entry is present and muxer is running
        fun muxLedgerQueue() {
            while (!ledgerQueue.isEmpty()) {
                val ledgerBuffer = ledgerQueue.peek()
                // If there is still no ledger data for this frame, exit and leave it in the queue
                if (frameLedger.encodeLedger.containsKey(ledgerBuffer.frameNum)) {
                    ledgerBuffer.info.presentationTimeUs = frameLedger.encodeLedger.get(ledgerBuffer.frameNum)!!
                    if (isMuxerRunning) {
                        viewModel.updateLog("Muxing frame ${ledgerBuffer.frameNum} from ledger queue at ${ledgerBuffer.info.presentationTimeUs}")
                        muxer?.writeSampleData(videoTrackIndex, ledgerBuffer.buffer, ledgerBuffer.info)
                        ledgerQueue.poll()
                    }
                } else {
                    break
                }
            } // while
        }
    }

    /**
     * The callback functions for Audio encoding
     *
     * Encoding can be "driven" in 2 ways.
     *   1 - onInputBufferAvailable is called. If there is audio data waiting, process it.
     *   2 - onNewAudioData is (manually) called. If there are queued input buffers, use them.
     *
     * Muxing happens (or is queued) when onOutputBufferAvailable is called
     */
    inner class AudioEncoderCallback(val viewModel: MainViewModel, var format: MediaFormat): MediaCodec.Callback() {
        val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()
        val inputBufferQueue = LinkedBlockingQueue<InputBufferIndex>()

        // Called when there is audio data ready to go. If there are any queued input buffers, use
        // them up.
        fun onNewAudioData() {
            var inputBufferIndex = inputBufferQueue.peek()

            while(inputBufferIndex != null) {
                // Queued buffer might be larger the codec input buffer, do not remove from queue
                // yet. encodeAudioData will remove it when all data is processed.
                val audioBuffer = audioBufferManager.queue.peek()

                if (audioBuffer != null) {
                    // There is audio data and an input buffer. Encode it.
                    encodeAudioData(inputBufferIndex.codec, inputBufferIndex.index, audioBuffer)

                    // Remove used input buffer index from the queue
                    inputBufferQueue.poll()
                } else {
                    // Not any(more) audio data, exit
                    break
                }

                // Check if there are anymore input buffers that can be used, don't remove until
                // it is used.
                inputBufferIndex = inputBufferQueue.peek()
            }
        }

        // Encoder is ready buffers to encode
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Queued buffer might be larger the codec input buffer, do not remove from queue yet
            val audioBuffer = audioBufferManager.queue.peek()

            if (audioBuffer != null) {
                // There is audio data and an input buffer. Encode it.
                encodeAudioData(codec, index, audioBuffer)
            } else {
                // No audio data yet, queue this input buffer so it can be used when data is available
                inputBufferQueue.add(InputBufferIndex(codec, index))
            }

        }

        fun encodeAudioData(codec: MediaCodec, bufferIndex: Int, audioBuffer: AudioBuffer) {
            audioBuffer.let { // The queued audio buffer
                // Get the available encoder input buffer
                val inputBuffer = codec.getInputBuffer(bufferIndex)
                if (inputBuffer != null) {
                    // Copy remaining bytes up to the encoder buffers max length
                    val bytesToCopy = Math.min(inputBuffer.capacity(), it.buffer.remaining())

                    // Save old queued buffer limit, set new limit to be the bytes needed
                    val oldQueuedBufferLimit = it.buffer.limit()
                    it.buffer.limit(it.buffer.position() + bytesToCopy)

                    // Copy bytes from queued buffer into encoder buffer, auto advance position
                    inputBuffer.put(it.buffer)

                    // Restore queued buffer's limit
                    it.buffer.limit(oldQueuedBufferLimit)

                    val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                    // viewModel.updateLog("Audio Encode audio buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${it.size}, remaining: ${it.buffer.remaining()}")
                    // viewModel.updateLog("Audio Encode audio buf verification: size: ${inputBuffer.capacity()}, bytes to copy: ${bytesToCopy}")
                    // viewModel.updateLog("Audio Encode input buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${bytesToCopy}")

                    // Send to the encoder
                    codec.queueInputBuffer(bufferIndex, 0, bytesToCopy, it.presentationTimeUs, if(it.isLastBuffer) BUFFER_FLAG_END_OF_STREAM else 0)

                    // If all bytes from the queued buffer have been sent to the encoder, remove from the queue
                    // Otherwise, advance the presentation time for the next chunk of the queued buffer
                    if (!it.buffer.hasRemaining()) {
                        audioBufferManager.queue.poll()
                    } else {
                        val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                        it.presentationTimeUs += bufferDurationUs
                    }
                }
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {
                // When a codec config buffer is received, no need to pass it to the muxer
                if (info.flags == BUFFER_FLAG_CODEC_CONFIG) {
                    audioTrackIndex = muxer?.addTrack(format) ?: -1
                    startMuxerIfReady()

                } else {
                    if(info.flags == BUFFER_FLAG_END_OF_STREAM) {
                        audioEncodeComplete = true
                    }

                    // If the mixer hasn't started yet - eg. if the video stream hasn't been configured
                    // yet - queue the output data for later.
                    if (!isMuxerRunning) {
                        //viewModel.updateLog("Need to add to mux queue: ${info.presentationTimeUs}")
                        muxingQueue.add(MuxingBuffer(cloneByteBuffer(outputBuffer), info))

                    } else {
                        // Mux any waiting data
                        if (!muxingQueue.isEmpty()) {
                            while(muxingQueue.peek() != null) {
                                val muxingBuffer = muxingQueue.poll()
                                muxer?.writeSampleData(audioTrackIndex, muxingBuffer.buffer, muxingBuffer.info)
                                //viewModel.updateLog("Muxing audio buffer out of mux queue: ${muxingBuffer.info.presentationTimeUs}")
                            }
                        }

                        // Send the new frame to the muxer
                        muxer?.writeSampleData(audioTrackIndex, outputBuffer, info)
                        //viewModel.updateLog("Muxing audio buffer: ${info.presentationTimeUs}")
                    }
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            viewModel.updateLog(("AudioEncoder error: ${e.errorCode} + ${e.diagnosticInfo}"))
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }
    }

    inner class InputBufferIndex(val codec: MediaCodec, val index: Int)
    inner class MuxingBuffer(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo)
    inner class LedgerBuffer(val buffer: ByteBuffer, val info: MediaCodec.BufferInfo, val frameNum: Int)
}