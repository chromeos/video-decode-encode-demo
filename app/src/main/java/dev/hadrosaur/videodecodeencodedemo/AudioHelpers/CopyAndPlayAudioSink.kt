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

import android.media.AudioTrack
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.AuxEffectInfo
import com.google.android.exoplayer2.util.MimeTypes
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer

/**
 * And AudioSink for ExoPlayer that copies out audio buffers to a concurrent queue and can play
 * back audio as buffers are received.
 *
 * If audioBufferManager is null, do not encode
 *
 * Note: for this simple demo, playback control from the GUI is passed through the MainActivity's
 * view model.
 */
class CopyAndPlayAudioSink(
    val viewModel: MainViewModel,
    private val streamNum: Int,
    val audioBufferManager: AudioBufferManager? = null
): AudioSink {

    private var isSinkInitialized = false
    private var handledEndOfStream = false

    private var pbParameters = PlaybackParameters.DEFAULT
    private var shouldSkipSilence = false
    private var sinkAudioAttributes = AudioAttributes.DEFAULT
    private var sinkAudioSessionId = -1
    private var sinkAuxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0F)

    private var inputFormat: Format = Format.Builder().build()
    private var sinkVolume = 100F

    val enableFloatOutput = false // Make this configurable if desired

    private var numBuffersHandled = 0
    private var lastPosition = 0L

    private val audioTrack: AudioTrack

    init {
        // Set up audio track for playback
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun setListener(listener: AudioSink.Listener) {
        // No listener needed
    }

    // This is used by MediaCodecAudioRenderer to determine if it needs to decode
    // audio before sending it to this AudioSink. This sink only excepts raw PCM data and requires
    // everything else to be decoded first. Return false if not raw PCM.
    override fun supportsFormat(format: Format): Boolean {
        return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
    }

    // The only audio input this sink supports directly is raw 16-bit PCM
    override fun getFormatSupport(format: Format): Int {
        if (MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    /**
     * This is where the audio data is handled.
     *
     * The sound is copied into to concurrent queue for this stream for encode.
     * If requested, sound is played out
     */
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        // buffer will be freed after this call so a deep copy is needed for encode
        val soundBuffer = cloneByteBuffer(buffer)

        // Calculate clock time duration of processed buffer
        val bufferLengthUs = getBufferDurationUs(
            buffer.remaining(),
            inputFormat.channelCount * 2, // 2 bytes per frame for 16-bit PCM,
            inputFormat.sampleRate
        )

        // viewModel.updateLog("Buffer info: pos: ${buffer.position()}, limit: ${buffer.position()}, rem: ${buffer.remaining()}, order: ${buffer.order()}")

        val expecting = (lastPosition / 1000).toInt()
        val got = (presentationTimeUs / 1000).toInt()
        val missing = got - expecting
        val message = if (missing > 0) "(MISSING ${missing} ms)" else "(Correct)"
        // viewModel.updateLog("Presentation time: ${presentationTimeUs / 1000}ms, duration: ${bufferLengthUs / 1000}ms, bytes: ${buffer.remaining()}. I was expecting pres time of ${expecting}ms ${message}")

        // If buffer is needed for encoding, copy it out
        if (audioBufferManager != null) {
            audioBufferManager.addData(
                AudioBuffer(
                    soundBuffer,
                    numBuffersHandled + 1,
                    presentationTimeUs,
                    bufferLengthUs,
                    buffer.remaining()
                )
            )
        }

        // Play audio buffer through speakers
        // This will be chunky and crackly with no buffering
        if (viewModel.getPlayAudioVal()) {
            val playBuffer = buffer.asReadOnlyBuffer()
            val audioTrackBufferSize = audioTrack.bufferSizeInFrames
            var bytesToPlay = 0

            // The AudioTrack may have a smaller buffer size than the bytes to play out. Play out one
            // chunk at a time.
            while (playBuffer.remaining() > 0) {
                bytesToPlay = playBuffer.remaining().coerceAtMost(audioTrackBufferSize) // Same as Math.min

                // Write sound and auto-advance position
                val bytesPlayed = audioTrack.write(playBuffer, bytesToPlay, AudioTrack.WRITE_BLOCKING)

                // If AudioTrack.write did not succeed, playBuffer.position will not be auto-advanced
                // and this loop can get stuck. This can happen if a malformed buffer arrives. To
                // prevent and endless loop, just exit.
                // If correct playback is required, do something more intelligent here
                if (bytesPlayed <= 0) {
                    break
                }
            }

            // If the AudioTrack is not playing, begin playback
            if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.play()
            }
        }

        // Update last position
        lastPosition = presentationTimeUs + bufferLengthUs

        // Advance buffer position to the end to indicate the whole buffer was handled
        buffer.position(buffer.limit())
        numBuffersHandled++

        // Tell ExoPlayer the entire buffer was handled
        return true
    }


    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // viewModel.updateLog("AudioSink: getCurrentPositionUs is called @ ${lastPosition}")
        return lastPosition
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        // viewModel.updateLog("AudioSink format: ${inputFormat}, buf size: ${specifiedBufferSize}, output channels: ${outputChannels}")
        this.inputFormat = inputFormat
        isSinkInitialized = true
    }

    override fun play() {
        // No-op
    }

    override fun handleDiscontinuity() {
        viewModel.updateLog("CopyAndPlayAudioSink: audio buffer discontinuity received, not processing.")
    }


    // If an internal buffer is implemented, make sure to drain internal buffers.
    // Currently no internal buffers
    override fun playToEndOfStream() {
        if (!handledEndOfStream && isSinkInitialized && drainToEndOfStream()) {
            handledEndOfStream = true
            playPendingData()

            // Stream is ended, include a fake EOS buffer for the audio encoder
            if (audioBufferManager != null) {
                audioBufferManager.addData(AudioBuffer(ByteBuffer.allocate(1), numBuffersHandled + 1, lastPosition, 0, 0, true))
                audioBufferManager.audioDecodeComplete = true
            }

            viewModel.updateLog("All audio buffers handled for Stream ${streamNum}. # == ${numBuffersHandled}")
        }
    }

    fun playPendingData() {
        // No internal buffer queues
    }

    private fun drainToEndOfStream(): Boolean {
        // No need internal buffer queues, just return true
        return true
    }


    override fun isEnded(): Boolean {
        return handledEndOfStream
    }

    // Always return true here to keep pipeline moving
    override fun hasPendingData(): Boolean {
        return true
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        pbParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return pbParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        shouldSkipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return shouldSkipSilence
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        sinkAudioAttributes = audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        sinkAudioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        sinkAuxEffectInfo = auxEffectInfo
    }

    override fun enableTunnelingV21() {
        // No-op
    }

    override fun disableTunneling() {
    }

    override fun setVolume(volume: Float) {
        if (sinkVolume != volume) {
            sinkVolume = volume
            setVolumeInternal()
        }
    }

    fun setVolumeInternal() {
        // Do nothing
    }
    override fun pause() {
        // Do nothing
    }

    override fun flush() {
        // Do nothing, no seeking
    }

    override fun experimentalFlushWithoutAudioTrackRelease() {
        // Do nothing
    }

    override fun reset() {
        // Release everything
    }
}