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

import android.media.AudioFormat
import android.media.AudioFormat.CHANNEL_OUT_STEREO
import android.media.AudioTrack
import com.google.android.exoplayer2.C.ENCODING_PCM_16BIT
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.audio.AudioSink
import com.google.android.exoplayer2.audio.AuxEffectInfo
import com.google.android.exoplayer2.audio.DefaultAudioSink.OUTPUT_MODE_OFFLOAD
import com.google.android.exoplayer2.audio.DefaultAudioSink.OUTPUT_MODE_PCM
import com.google.android.exoplayer2.audio.DefaultAudioTrackBufferSizeProvider
import com.google.android.exoplayer2.util.MimeTypes
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.nio.ByteBuffer

/**
 * An AudioSink for ExoPlayer that copies out audio buffers to an encode queue and optionally plays
 * back audio buffers as they are received.
 *
 * @param viewModel Main view model for logging and UI variables - including audio playback toggle
 * @param streamNum Stream number, used for logging only
 * @param audioBufferManager If not null, audio buffers will be copied out to this
 * AudioBufferManager for encode. If null, do not encode.
 *
 * Note: Playback will only work for SDK 23 and higher
 */
class CopyAndPlayAudioSink(
    private val viewModel: MainViewModel,
    private val streamNum: Int = 0,
    private val audioMixTrack: AudioMixTrack,
    private val audioBufferManager: AudioBufferManager? = null
): AudioSink {
    private var handledEndOfStream = false

    private var pbParameters = PlaybackParameters.DEFAULT
    private var shouldSkipSilence = false
    private var sinkAudioAttributes = AudioAttributes.DEFAULT
    private var sinkAudioSessionId = -1
    private var sinkAuxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0F)

    private var inputFormat: Format = Format.Builder().build()
    private var sinkVolume = 100F
    
    private var numBuffersHandled = 0
    private var lastPosition = 0L

    override fun setListener(listener: AudioSink.Listener) {
        // No listener needed
    }

    // This is used by MediaCodecAudioRenderer to determine if ExoPlayer needs to decode
    // audio before sending it to this AudioSink. This sink only excepts PCM 16-bit data
    // and requires everything else to be decoded first. Return false if not raw 16-bit PCM.
    // Note: on devices where PCM_FLOAT is supported, it *could* be supported here, but would require
    // the encoder to be configured correctly. For simplicity, this demo just requires 16-bit PCM.
    override fun supportsFormat(format: Format): Boolean {
        return MimeTypes.AUDIO_RAW == format.sampleMimeType
                && format.pcmEncoding == ENCODING_PCM_16BIT
    }

    // The only audio input this sink supports directly is raw 16-bit PCM, see supportsFormat
    override fun getFormatSupport(format: Format): Int {
        return if (MimeTypes.AUDIO_RAW == format.sampleMimeType
            && format.pcmEncoding == ENCODING_PCM_16BIT) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
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
        // Exo adds an offset of 1000000000000 to prevent -'ve timestamps. Subtract this for encode
        val hackPresentationTime = presentationTimeUs - 1000000000000;

        // Calculate clock time duration of the buffer
        val bufferLengthUs = bytesToDurationUs(
            buffer.remaining(),
            inputFormat.channelCount,
            2, // 2 bytes per frame for 16-bit PCM,
            inputFormat.sampleRate
        )

        // If buffer is needed for encoding, copy it out
        // buffer will be freed after this call so a deep copy is needed for encode
        if (audioBufferManager != null) {
            val encodeBuffer = cloneByteBuffer(buffer)
            audioBufferManager.addData(
                AudioBuffer(
                    encodeBuffer,
                    numBuffersHandled + 1,
                    hackPresentationTime,
                    bufferLengthUs,
                    buffer.remaining()
                )
            )
        }

        // TODO: add some sort of underrun detection

        // If the play audio toggle is enabled, copy this buffer to the mix track for playback
        // buffer will be freed after this call so a copy is needed
//        if (viewModel.getPlayAudioVal()) {
            audioMixTrack.addAudioChunk(
                AudioBuffer(
                    cloneByteBuffer(buffer),
                    numBuffersHandled + 1,
                    presentationTimeUs,
                    bufferLengthUs,
                    buffer.remaining()
                )
            )
//        }

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
        this.inputFormat = inputFormat
    }

    override fun play() {
        // No-op
    }

    override fun handleDiscontinuity() {
        viewModel.updateLog("CopyAndPlayAudioSink: audio buffer discontinuity received, not processing.")
    }

    // If an internal buffer is implemented, make sure to drain internal buffers.
    // Currently there are no internal buffers. However, leverage this call to insert a fake
    // audio buffer for the encoder to know when the end of stream (EOS) is
    override fun playToEndOfStream() {
        if (!handledEndOfStream && drainToEndOfStream()) {
            handledEndOfStream = true
            playPendingData()

            // Stream is ended, include a fake EOS buffer as a flag for the audio encoder
            if (audioBufferManager != null) {
                audioBufferManager.addData(AudioBuffer(ByteBuffer.allocate(1), numBuffersHandled + 1, lastPosition, 0, 0, true))
                audioBufferManager.audioDecodeComplete = true
            }

            // viewModel.updateLog("All audio buffers handled for Stream ${streamNum + 1}. # == ${numBuffersHandled}")
        }
    }

    private fun playPendingData() {
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
        // viewModel.updateLog("setPlaybackParameters called on audio sink. Params: ${playbackParameters}")
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
        // viewModel.updateLog("setAudioAttributes called on audio sink. Attributes: ${audioAttributes}")
        sinkAudioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes {
        return sinkAudioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        sinkAudioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // viewModel.updateLog("setAuxEffectInfo called on audio sink. Effect info: ${auxEffectInfo}")
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

    private fun setVolumeInternal() {
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