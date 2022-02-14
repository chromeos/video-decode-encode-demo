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

import android.media.MediaCodec
import android.media.MediaCodec.*
import android.media.MediaFormat
import android.media.MediaFormat.KEY_HEIGHT
import android.media.MediaFormat.KEY_WIDTH
import android.media.MediaMuxer
import android.os.Build.VERSION.SDK_INT
import android.view.Surface
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.cloneByteBuffer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.getBufferDurationUs
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.LOG_VIDEO_EVERY_N_FRAMES
import dev.hadrosaur.videodecodeencodedemo.Utils.generateTimestamp
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoFrameLedger
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

// TODO: there is a memory leak of about 20mb per run. Track it down Seems like 10mb decode and 10-15mb encode
/**
 * Encode frames sent into the encoderInputSurface
 *
 * This does not check for an EOS flag. The encode ends when "decodeComplete" is indicated and the
 * number of encoded frames matches the number of decoded frames
 */
class AudioVideoEncoder(val viewModel: MainViewModel, val frameLedger: VideoFrameLedger, val audioBufferManager: AudioBufferManager) {
    // Video encoding variables
    val videoEncoderInputSurface: Surface
    var numEncodedVideoFrames = AtomicInteger(0)
    private var videoDecodeComplete = false
    private var videoEncodeComplete = false
    private val videoEncoder: MediaCodec
    private val videoEncoderCallback: VideoEncoderCallback
    private val encoderVideoFormat: MediaFormat

    // Video encoder default values, will be changed when video encoder is set up
    var encoderWidth = 1920
    var encoderHeight = 1080

    // FPS trackers
    var startTime = 0L
    private var endTime = 0L
    var lastPresentationTime = 0L

    // Audio encoding variables
    val audioEncoderCallback: AudioEncoderCallback
    var audioEncodeComplete = false
    private val encoderAudioFormat: MediaFormat
    private val audioEncoder: MediaCodec

    // Muxer variables
    var muxer: MediaMuxer? = null
    private var encodedFilename = ""
    var isMuxerRunning = false
    var videoTrackIndex: Int = -1
    var audioTrackIndex: Int = -1

    private val audioBufferListener : AudioBufferManager.AudioBufferManagerListener

    init{
        videoEncoder = createByCodecName(viewModel.videoEncoderCodecInfo?.name!!)
        encoderVideoFormat = viewModel.encoderVideoFormat

        // Save encoder width and height for surfaces that use this encoder
        encoderWidth = encoderVideoFormat.getInteger(KEY_WIDTH)
        encoderHeight = encoderVideoFormat.getInteger(KEY_HEIGHT)
        viewModel.updateLog("Encoder configuration: setting width to: ${encoderWidth} and height to ${encoderHeight}")

        audioEncoder = createByCodecName(viewModel.audioEncoderCodecInfo?.name!!)
        encoderAudioFormat = viewModel.encoderAudioFormat

        // Use asynchronous modes with callbacks - encoding logic contained in the callback classes
        // See: https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers

        // Video encoder
        videoEncoderCallback = VideoEncoderCallback(viewModel, encoderVideoFormat)
        videoEncoder.setCallback(videoEncoderCallback)
        videoEncoder.configure(encoderVideoFormat, null, null, CONFIGURE_FLAG_ENCODE)

        // Get the input surface from the encoder, decoded frames from the decoder should be
        // placed here.
        videoEncoderInputSurface = videoEncoder.createInputSurface()

        // Audio encoder
        audioEncoderCallback = AudioEncoderCallback(viewModel, encoderAudioFormat)
        audioEncoder.setCallback(audioEncoderCallback)
        audioEncoder.configure(encoderAudioFormat, null, null, CONFIGURE_FLAG_ENCODE)
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

    private fun finishEncode() {
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
        videoEncoderInputSurface.release()
    }

    fun signalDecodingComplete() {
        videoDecodeComplete = true
    }

    private fun signalEncodingComplete() {
        videoEncodeComplete = true
    }

    /**
     * Because video frames are just raw frames coming in from a surface, the encoder needs to
     * manually check if the encode is complete.
     */
    fun checkIfEncodeDone() {
        if ((videoDecodeComplete && audioEncodeComplete && (numEncodedVideoFrames.get() == frameLedger.framesRendered.get()))) {
            endTime = System.currentTimeMillis()
            val totalTime = (endTime - startTime) / 1000.0
            val totalFPS = numEncodedVideoFrames.get() / totalTime
            val timeString = String.format("%.2f", totalTime)
            val fpsString = String.format("%.2f", totalFPS)

            viewModel.updateLog("Encode done, written to ${encodedFilename}. ${numEncodedVideoFrames.get()} video frames in ${timeString}s (${fpsString}fps).")
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
        private val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()
        private val ledgerQueue = LinkedBlockingQueue<LedgerBuffer>()
        private var numMuxedVideoFrames = 0

        // Do not do anything. Incoming frames should
        // be auto-queued into the encoder from the input surface
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            return
        }

        /**
         * An encoded video frame is available from the video encoder.
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
            info: BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {
                when (info.flags) {
                    BUFFER_FLAG_CODEC_CONFIG -> {
                        // When a codec config buffer is received, no need to pass it to the muxer
                        videoTrackIndex = muxer?.addTrack(format) ?: -1
                        startMuxerIfReady()
                    }

                    BUFFER_FLAG_END_OF_STREAM -> {
                        // Do nothing
                    }

                    // Else, mux this frame
                    else -> {
                        val frameNum = numEncodedVideoFrames.incrementAndGet()

                        // The encode ledger contains the correct presentation time from the decoder,
                        // stored based on the frame number. This assumes frames
                        // hit the decoder output surface in the same order as they exit the encoder.
                        // If this assumption is not true, frames may be encoded out of order.
                        // If the ledger value is not stored by this point in the code,
                        // add the frame to the ledger queue to be muxed when we get the correct time
                        if (frameLedger.encodeLedger.containsKey(frameNum)) {
                            info.presentationTimeUs = frameLedger.encodeLedger[frameNum]!!
                             viewModel.updateLog("Video encoder, got frame number ${frameNum}@${info.presentationTimeUs}, last time was ${lastPresentationTime}.")
                            lastPresentationTime = info.presentationTimeUs

                            // If the muxer hasn't started yet - eg. if the audio stream hasn't been
                            // configured yet - queue the output data for later.
                            if (!isMuxerRunning) {
                                // viewModel.updateLog("Adding buffer to video muxing buffer: ${frameNum}")
                                muxingQueue.add(MuxingBuffer(cloneByteBuffer(outputBuffer), info))

                            } else {
                                // Mux any buffers that were waiting for the muxer to start
                                if (!muxingQueue.isEmpty()) {
                                    while (muxingQueue.peek() != null) {
                                        val muxingBuffer = muxingQueue.poll()

                                        // viewModel.updateLog("Muxing buffer out of mux queue: ${muxingBuffer.info.presentationTimeUs}")
                                        if (muxingBuffer != null) {
                                            muxer?.writeSampleData(
                                                videoTrackIndex, muxingBuffer.buffer, muxingBuffer.info)
                                        }
                                        numMuxedVideoFrames++
                                    }
                                }

                                // Check if there are any frames that were not matched with ledger data
                                muxLedgerQueue()

                                // Send the new frame to the muxer
                                muxer?.writeSampleData(videoTrackIndex, outputBuffer, info)
                                numMuxedVideoFrames++

                                // Log current encoding speed
                                if (numEncodedVideoFrames.get() % LOG_VIDEO_EVERY_N_FRAMES == 0) {
                                    val currentFPS =
                                        numEncodedVideoFrames.get() / ((System.currentTimeMillis() - startTime) / 1000.0)
                                    val fpsString = String.format("%.2f", currentFPS)
                                    viewModel.updateLog("Encoding video stream at ${fpsString}fps, frame $numEncodedVideoFrames.")
                                }
                            } // Is muxer running

                        } else {
                            // No ledger info yet for this buffer, add it to the ledger queue to be processed later
                            ledgerQueue.add(LedgerBuffer(outputBuffer, info, frameNum))
                        }
                    }
                } // when

            } // Is output buffer null
            codec.releaseOutputBuffer(index, false)

            // viewModel.updateLog("I have muxed ${numMuxedVideoFrames} video frames.")

            // If encode is finished, there will be no more output buffers received, check manually
            // if video encode is finished
            checkIfEncodeDone()
        }

        override fun onError(codec: MediaCodec, e: CodecException) {
            viewModel.updateLog("ERROR: something occurred during video encoding: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }

        // Mux any buffers in the ledger queue, if the ledger entry is present and muxer is running
        // TODO: generalise this for the muxingQueue
        private fun muxLedgerQueue() {
            while (!ledgerQueue.isEmpty()) {
                val ledgerBuffer = ledgerQueue.peek()
                // If there is still no ledger data for this frame, exit and leave it in the queue
                if (frameLedger.encodeLedger.containsKey(ledgerBuffer?.frameNum)) {
                    ledgerBuffer?.info?.presentationTimeUs = frameLedger.encodeLedger[ledgerBuffer?.frameNum]!!
                    if (isMuxerRunning) {
                        // viewModel.updateLog("Muxing frame ${ledgerBuffer?.frameNum} from ledger queue at ${ledgerBuffer?.info?.presentationTimeUs}")
                        if (ledgerBuffer != null) {
                            muxer?.writeSampleData(
                                videoTrackIndex,
                                ledgerBuffer.buffer,
                                ledgerBuffer.info
                            )
                            numMuxedVideoFrames++
                        }
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
     *   1 - onNewAudioData is called. If there are queued input buffers, use them.
     *   2 - onInputBufferAvailable is called, if decode is not fully done, queue the buffer. If
     *   decode is done and these is audio data waiting, process it.
     */
    inner class AudioEncoderCallback(val viewModel: MainViewModel, var format: MediaFormat): MediaCodec.Callback() {
        private val muxingQueue = LinkedBlockingQueue<MuxingBuffer>()
        private val inputBufferQueue = LinkedBlockingQueue<InputBufferIndex>()
        private var lastMuxedAudioPresentationTimeUs = 0L
        private var numMuxedBuffers = 0

        // Called when there is audio data ready to go. If there are any queued input buffers, use
        // them.
        fun onNewAudioData() {
            // See if there are any input buffers ready to be used
            var inputBufferIndex = inputBufferQueue.peek()

            while(inputBufferIndex != null) {
                val audioBuffer = audioBufferManager.pollData()

                if (audioBuffer != null) {
                    // There is audio data and an input buffer. Encode it.
                    encodeAudioData(inputBufferIndex.codec, inputBufferIndex.index, audioBuffer)

                    // Remove used input buffer index from the queue
                    inputBufferQueue.poll()
                } else {
                    // No more audio data, exit
                    break
                }

                // Check if there are anymore input buffers that can be used, don't remove until
                // it is actually used.
                inputBufferIndex = inputBufferQueue.peek()
            }
        }

        // Encoder has input buffers ready to encode
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Most of the time, the encode is "driven" by new data - when onNewAudioData is called,
            // In this case, just queue the input buffer for the next onNewAudioData call.
            // After the audio decode is complete, the encode will be driven here instead, when a
            // new input buffer become available.
            if (!audioBufferManager.audioDecodeComplete) {
                inputBufferQueue.add(InputBufferIndex(codec, index)) // Queue for onNewAudioData

            } else {
                // Audio decode is complete so encode is driven here with available input buffers
                val audioBuffer = audioBufferManager.pollData()
                if (audioBuffer != null) {
                    // There is audio data and an input buffer. Encode it.
                    encodeAudioData(codec, index, audioBuffer)
                }
            }
        }

        private fun encodeAudioData(codec: MediaCodec, bufferIndex: Int, audioBuffer: AudioBuffer) {
            audioBuffer.let { // it == the queued AudioBuffer
                // viewModel.updateLog("I am encoding audio buffer #${audioBuffer.id} @ time ${audioBuffer.presentationTimeUs}")

                // Get the available encoder input buffer
                val inputBuffer = codec.getInputBuffer(bufferIndex)
                if (inputBuffer != null) {
                    // Copy remaining bytes up to the encoder buffers max length
                    val bytesToCopy = inputBuffer.capacity().coerceAtMost(it.buffer.remaining()) // Like Math.min
                    var copyToPosition = it.buffer.position() + bytesToCopy

                    // Something is wrong, do not copy anything
                    // TODO: Remove this check after testing
                    if (bytesToCopy <= 0 || copyToPosition > it.buffer.capacity()) {
                        viewModel.updateLog("Something is wrong wrong copying audio data to encoder at ${it.presentationTimeUs}us: copy to position: ${copyToPosition}, bytesToCopy = ${bytesToCopy}, pos: ${it.buffer.position()}, cap: ${it.buffer.capacity()}, old limit: ${it.buffer.limit()}, remaining: ${it.buffer.remaining()}. Not copying data.")
                        copyToPosition = it.buffer.position()
                    }

                    // Save old queued buffer limit, set new limit to be the bytes needed
                    val oldQueuedBufferLimit = it.buffer.limit()
                    it.buffer.limit(copyToPosition)

                    // Copy bytes from queued buffer into encoder buffer, auto advance position
                    inputBuffer.put(it.buffer)

                    // Restore queued buffer's limit
                    it.buffer.limit(oldQueuedBufferLimit)

                    val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                    viewModel.updateLog("Audio Encode audio buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${it.size}, remaining: ${it.buffer.remaining()}")
                    // viewModel.updateLog("Audio Encode audio buf verification: size: ${inputBuffer.capacity()}, bytes to copy: ${bytesToCopy}")
                    // viewModel.updateLog("Audio Encode input buf verification: ${it.presentationTimeUs / 1000}, length: ${bufferDurationUs / 1000}, size: ${bytesToCopy}")

                    // Send to the encoder
                    codec.queueInputBuffer(bufferIndex, 0, bytesToCopy, it.presentationTimeUs, if(it.isLastBuffer) BUFFER_FLAG_END_OF_STREAM else 0)

                    // If not all bytes from buffer could be sent to the encoder, re-queue remaining
                    // bytes into the audio buffer manager.
                    // TODO: This does not handle interleaving correctly. For example, if encoder
                    // input buffers are 4096 but decoder output buffers are 8192, with L and then R,
                    // this will copy things in incorrectly re: presentation time/ L and R channels.
                    if (it.buffer.hasRemaining()) {
                        // viewModel.updateLog("Audio data did not fit into encoder input buffer (time: ${it.presentationTimeUs}, cap: ${inputBuffer.capacity()}, re-queueing remaining data. Remaining: ${it.buffer.remaining()}, limit: ${it.buffer.limit()}, pos: ${it.buffer.position()}")
                        // viewModel.updateLog("Buffer duration for re-queue: ${bufferDurationUs}")
                        val bufferDurationUs = getBufferDurationUs(bytesToCopy, format)
                        it.presentationTimeUs += bufferDurationUs
                        audioBufferManager.addDataFirst(it, false)
                    }
                }
            }
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: BufferInfo
        ) {
            val outputBuffer = codec.getOutputBuffer(index)

            if (outputBuffer != null) {

                when (info.flags) {
                    BUFFER_FLAG_CODEC_CONFIG -> {
                        // Add audio track to muxer and start, no need to mux this buffer
                        audioTrackIndex = muxer?.addTrack(format) ?: -1
                        startMuxerIfReady()
                    }

                    BUFFER_FLAG_END_OF_STREAM -> {
                        // This is a fake audio buffer constructed in AudioSink to indicate EOS. Don't mux.
                        audioEncodeComplete = true
                    }

                    // Otherwise mux in this buffer
                    else -> {
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

                                    // On devices where the encoder input buffer size is less than
                                    // the decoder output buffer size, there can be multiple buffers
                                    // with the same presentation time (left and right channels).
                                    // Very occasionally this can drift by a few micro-seconds.
                                    // Force it to be no earlier than the present mux position.
                                    // TODO: this should not be necessary and probably indicates a bug
                                    // in audio encoding logic
                                    if (muxingBuffer != null) {
                                        if (muxingBuffer.info.presentationTimeUs < lastMuxedAudioPresentationTimeUs) {
                                            viewModel.updateLog("Presentation time muxed buffer ${muxingBuffer.info.presentationTimeUs} is in the past, adjusting to ${lastMuxedAudioPresentationTimeUs} to prevent muxing errors.")
                                            muxingBuffer.info.presentationTimeUs =
                                                lastMuxedAudioPresentationTimeUs
                                        }

                                        muxer?.writeSampleData(
                                            audioTrackIndex,
                                            muxingBuffer.buffer,
                                            muxingBuffer.info
                                        )
                                        lastMuxedAudioPresentationTimeUs =
                                            muxingBuffer.info.presentationTimeUs
                                        numMuxedBuffers++
                                        viewModel.updateLog("Muxing audio buffer out #${numMuxedBuffers} of mux queue: ${muxingBuffer.info.presentationTimeUs}, size: ${muxingBuffer.info.size},  flags: ${info.flags}, offset: ${info.offset}")
                                    }
                                }
                            }

                            // On devices where the encoder input buffer size is less than
                            // the decoder output buffer size, there can be multiple buffers
                            // with the same presentation time (left and right channels).
                            // Very occasionally this can drift by a few micro-seconds.
                            // Force it to be no earlier than the present mux position.
                            // TODO: this should not be necessary and probably indicates a bug
                            // in audio encoding logic
                            if (info.presentationTimeUs < lastMuxedAudioPresentationTimeUs) {
                                viewModel.updateLog("Presentation time muxed buffer ${info.presentationTimeUs} is in the past, adjusting to ${lastMuxedAudioPresentationTimeUs} to prevent muxing errors.")
                                info.presentationTimeUs = lastMuxedAudioPresentationTimeUs
                            }

                            // Send the new frame to the muxer
                            muxer?.writeSampleData(audioTrackIndex, outputBuffer, info)
                            lastMuxedAudioPresentationTimeUs = info.presentationTimeUs
                            numMuxedBuffers++
                            viewModel.updateLog("Muxed audio buffer #${numMuxedBuffers}: ${info.presentationTimeUs}, size: ${info.size}, flags: ${info.flags}, offset: ${info.offset}")
                        }
                    }
                } // when
            }
            // viewModel.updateLog("I have muxed ${numMuxedBuffers} audio buffers. Is audio encode done? ${audioEncodeComplete}")
            codec.releaseOutputBuffer(index, false)
            checkIfEncodeDone()
        }

        override fun onError(codec: MediaCodec, e: CodecException) {
            if (SDK_INT >= 23) {
                viewModel.updateLog(("AudioEncoder error: ${e.errorCode} + ${e.diagnosticInfo}"))
            } else {
                viewModel.updateLog(("AudioEncoder error: ${e.message} + ${e.diagnosticInfo}"))
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            this.format = format
        }
    }

    inner class InputBufferIndex(val codec: MediaCodec, val index: Int)
    inner class MuxingBuffer(val buffer: ByteBuffer, val info: BufferInfo)
    inner class LedgerBuffer(val buffer: ByteBuffer, val info: BufferInfo, val frameNum: Int)
}