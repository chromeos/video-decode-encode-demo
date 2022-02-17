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

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.KeyEvent.*
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.coroutineScope
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer
import dev.hadrosaur.videodecodeencodedemo.AudioHelpers.AudioBufferManager
import dev.hadrosaur.videodecodeencodedemo.Utils.*
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.VideoSurfaceManager
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val NUMBER_OF_STREAMS = 10

// TODO: replace this with proper file loading
const val VIDEO_RES_1 = R.raw.paris_01_1080p
const val VIDEO_RES_2 = R.raw.paris_02_1080p
const val VIDEO_RES_3 = R.raw.paris_03_1080p
const val VIDEO_RES_4 = R.raw.paris_04_1080p
const val VIDEO_RES_5 = R.raw.paris_05_1080p
const val VIDEO_RES_6 = R.raw.paris_06_1080p
const val VIDEO_RES_7 = R.raw.paris_07_1080p
const val VIDEO_RES_8 = R.raw.paris_08_1080p
const val VIDEO_RES_9 = R.raw.paris_01_720p
const val VIDEO_RES_10 = R.raw.video_1080x1920_30fps

class MainActivity : AppCompatActivity() {
    // Preview surfaces
    // TODO: Revisit the "To Delete" logic and make sure it is still necessary. Possibly remove it.
    private var previewSurfaceViews = ArrayList<SurfaceView>()
    private var previewSurfaceViewsToDelete = ArrayList<SurfaceView>()

    // Counter to track if all surfaces are ready
    private var numberOfReadySurfaces = 0
    private var activeDecodes = 0
    private var activeEncodes = 0

    // Managers for internal surfaces
    private var videoSurfaceManagers = ArrayList<VideoSurfaceManager>()
    private var videoSurfaceManagersToDelete = ArrayList<VideoSurfaceManager>()

    // Managers for audio buffers used for encoding
    private var audioBufferManagers = ArrayList<AudioBufferManager>()

    // Audio / Video encoders
    var audioVideoEncoders = ArrayList<AudioVideoEncoder>()

    // The GlManager manages the eglcontext for all renders and filters
    private val glManager = GlManager()

    val viewModel: MainViewModel by viewModels()

    companion object {
        const val LOG_VIDEO_EVERY_N_FRAMES = 300 // Log dropped frames and fps every N frames
        const val LOG_AUDIO_EVERY_N_FRAMES = 500 // Log dropped frames and fps every N frames
        const val MIN_DECODE_BUFFER_MS = 68 // Roughly 2 frames at 30fps.
        const val LOG_TAG = "VideoDemo"
        const val FILE_PREFIX = "VideoDemo"
        var CAN_WRITE_FILES = false // Used to check if file write permissions have been granted

        // Convenience logging function
        fun logd(message: String) {
            Log.d(LOG_TAG, message)
        }
    }

    private fun initializeEncoders() {
        // Free up old encoders and audio buffer managers
        releaseEncoders()

        // Stream 1
        audioBufferManagers.add(AudioBufferManager())
        audioVideoEncoders.add(AudioVideoEncoder(viewModel, videoSurfaceManagers[0].renderer.frameLedger, audioBufferManagers[0]))

        // Stream 2 - 4 not currently permitted to encode

        // Stream 2
        // audioBufferManagers.add(AudioBufferManager())
        // audioVideoEncoders.add(AudioVideoEncoder(viewModel, videoSurfaceManagers[1].renderer.frameLedger, audioBufferManagers[1]))

        // Stream 3
        // audioBufferManagers.add(AudioBufferManager())
        // audioVideoEncoders.add(AudioVideoEncoder(viewModel, videoSurfaceManagers[2].renderer.frameLedger, audioBufferManagers[2]))

        // Stream 4
        // audioBufferManagers.add(AudioBufferManager())
        // audioVideoEncoders.add(AudioVideoEncoder(viewModel, videoSurfaceManagers[3].renderer.frameLedger, audioBufferManagers[3]))
    }

    private fun initializeSurfaces() {
        numberOfReadySurfaces = 0

        // Create the preview surfaces
        for (n in 0..NUMBER_OF_STREAMS) {
            previewSurfaceViews.add(SurfaceView(this))
        }

        // Setup surface listeners to indicate when surfaces have been created/destroyed
        for (n in 0..NUMBER_OF_STREAMS) {
            previewSurfaceViews[n].holder.addCallback(VideoSurfaceViewListener(this))
        }

        // Create the internal SurfaceTextures that will be used for decoding
        initializeInternalSurfaces()

        // Add the preview surfaces to the UI
        frame_one.addView(previewSurfaceViews[0])
        frame_two.addView(previewSurfaceViews[1])
        frame_three.addView(previewSurfaceViews[2])
        frame_four.addView(previewSurfaceViews[3])
        frame_five.addView(previewSurfaceViews[4])
        frame_six.addView(previewSurfaceViews[5])
        frame_seven.addView(previewSurfaceViews[6])
        frame_eight.addView(previewSurfaceViews[7])
        frame_nine.addView(previewSurfaceViews[8])
        frame_ten.addView(previewSurfaceViews[9])
    }

    // Create the internal SurfaceTextures that will be used for decoding
    private fun initializeInternalSurfaces() {
        // Clean-up any surfaces that need deletion
        releaseSurfacesMarkedForDeletion()

        // Empty and free current arrays of surface managers
        videoSurfaceManagers.clear()

        for (n in 0..NUMBER_OF_STREAMS) {
            videoSurfaceManagers.add(VideoSurfaceManager(viewModel, glManager, previewSurfaceViews[n]))
        }
    }

    /**
     * To start a new test, fresh surfaces are needed but do not garbage collect them until the next
     * time "Decode" is pressed.
     */
    private fun markSurfacesForDeletion() {
        // Point deletion handles to the old arrays of managers/views
        previewSurfaceViewsToDelete = previewSurfaceViews
        videoSurfaceManagersToDelete = videoSurfaceManagers

        // Set up new arrays for the fresh managers/views
        previewSurfaceViews = ArrayList()
        videoSurfaceManagers = ArrayList()
    }

    /**
     * Clear any surfaces/managers marked for deletion
     */
    private fun releaseSurfacesMarkedForDeletion() {
        previewSurfaceViewsToDelete.clear()
        previewSurfaceViewsToDelete = ArrayList()

        for (manager in videoSurfaceManagersToDelete) {
            manager.release()
        }
        videoSurfaceManagersToDelete.clear()
        videoSurfaceManagersToDelete = ArrayList()
    }

    // Empty and free current arrays encoders and audio managers
    private fun releaseEncoders() {
        audioBufferManagers.clear()
        for (encoder in audioVideoEncoders) {
            encoder.release()
        }
        audioVideoEncoders.clear()
    }

    private fun release() {
        releaseSurfacesMarkedForDeletion()
        releaseEncoders()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up encoder default formats
        setDefaultEncoderFormats(this, viewModel)

        // Set up output directory for muxer
        viewModel.encodeOutputDir = getAppSpecificVideoStorageDir(this, viewModel, FILE_PREFIX)

        // Set up preview surfaces as well as internal, non-visible decoding surfaces
        initializeSurfaces()

        // Show max codec instances
        updateLog(getMaxInstancesString())

        // Request file read/write permissions need to save encodes.
        if (checkPermissions()) {
            CAN_WRITE_FILES = true
        } else {
            updateLog("File permissions not granted. Encoding will not save to file.")
        }

        // Set up preview frame frequency seekbar
        seek_framedelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setPreviewFrameFrequency(progress)
                runOnUiThread {
                    text_frame_delay.text = "Preview every ${progress} frames"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
        viewModel.getPreviewFrameFrequency().observe(this, { frameFrequency ->
            seek_framedelay.progress = frameFrequency
        })

        // Set up on-screen log
        viewModel.getLogText().observe(this, { logText ->
            runOnUiThread {
                updateLog(logText)
            }
        })

        // Set up decode checkboxes
        checkbox_decode_stream1.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream1(isChecked) }
        viewModel.getDecodeStream1().observe(this, {
                isChecked -> checkbox_decode_stream1.isSelected = isChecked })
        checkbox_decode_stream2.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream2(isChecked) }
        viewModel.getDecodeStream2().observe(this, {
                isChecked -> checkbox_decode_stream2.isSelected = isChecked })
        checkbox_decode_stream3.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream3(isChecked) }
        viewModel.getDecodeStream3().observe(this, {
                isChecked -> checkbox_decode_stream3.isSelected = isChecked })
        checkbox_decode_stream4.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream4(isChecked) }
        viewModel.getDecodeStream4().observe(this, {
                isChecked -> checkbox_decode_stream4.isSelected = isChecked })
        checkbox_decode_stream5.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream5(isChecked) }
        viewModel.getDecodeStream5().observe(this, {
                isChecked -> checkbox_decode_stream5.isSelected = isChecked })
        checkbox_decode_stream6.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream6(isChecked) }
        viewModel.getDecodeStream6().observe(this, {
                isChecked -> checkbox_decode_stream6.isSelected = isChecked })
        checkbox_decode_stream7.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream7(isChecked) }
        viewModel.getDecodeStream7().observe(this, {
                isChecked -> checkbox_decode_stream7.isSelected = isChecked })
        checkbox_decode_stream8.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream8(isChecked) }
        viewModel.getDecodeStream8().observe(this, {
                isChecked -> checkbox_decode_stream8.isSelected = isChecked })
        checkbox_decode_stream9.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream9(isChecked) }
        viewModel.getDecodeStream9().observe(this, {
                isChecked -> checkbox_decode_stream9.isSelected = isChecked })
        checkbox_decode_stream10.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream10(isChecked) }
        viewModel.getDecodeStream10().observe(this, {
                isChecked -> checkbox_decode_stream10.isSelected = isChecked })

        // Set up toggle switches for encode, audio, filters, etc.
        switch_filter.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setApplyGlFilter(isChecked) }
        viewModel.getApplyGlFilter().observe(this, {
                applyFilter -> switch_filter.isSelected = applyFilter })
        switch_encode.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setEncodeStream1(isChecked) }
        viewModel.getEncodeStream1().observe(this, {
                encodeStream -> switch_encode.isSelected = encodeStream })
        switch_audio.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setPlayAudio(isChecked) }
        viewModel.getPlayAudio().observe(this, {
                playAudio -> switch_audio.isSelected = playAudio })
        switch_software.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setSoftware(isChecked) }
        viewModel.getSoftware().observe(this, {
                software -> switch_software.isSelected = software })
        switch_seek.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setDoSeeks(isChecked) }
        viewModel.getDoSeeks().observe(this, {
                doSeeks -> switch_seek.isSelected = doSeeks })

        // Set up decode button
        button_start_decode.setOnClickListener {
            button_start_decode.isEnabled = false

            // Release any old surfaces marked for deletion
            releaseSurfacesMarkedForDeletion()

            // Stream 1
            if (viewModel.getDecodeStream1Val()) {
                // Are we encoding this run?
                if (viewModel.getEncodeStream1Val()) {
                    // Set-up an observer so the AudioVideoEncoder can signal the encode is complete
                    // through the view model
                    viewModel.getEncodingInProgress().value = true // Set this directly (not post) so the observer doesn't trigger immediately
                    viewModel.getEncodingInProgress().observe(this, {
                            inProgress -> if(inProgress == false) encodeFinished() })
                    activeEncodes++

                    // Set up encoder and audio buffer manager
                    initializeEncoders()

                    // Decode and Encode
                    beginVideoDecode(VIDEO_RES_1, videoSurfaceManagers[0], 0,
                        audioVideoEncoders[0], audioBufferManagers[0])
                } else {
                    // Decode only
                    beginVideoDecode(VIDEO_RES_1, videoSurfaceManagers[0], 0)
                }
            }

            // Stream 2
            if (viewModel.getDecodeStream2Val()) {
                beginVideoDecode(VIDEO_RES_2, videoSurfaceManagers[1], 1)
            }

            // Stream 3
            if (viewModel.getDecodeStream3Val()) {
                beginVideoDecode(VIDEO_RES_3, videoSurfaceManagers[2], 2)
            }

            // Stream 4
            if (viewModel.getDecodeStream4Val()) {
                beginVideoDecode(VIDEO_RES_4, videoSurfaceManagers[3], 3)
            }

            // Stream 5
            if (viewModel.getDecodeStream5Val()) {
                beginVideoDecode(VIDEO_RES_5, videoSurfaceManagers[4], 4)
            }

            // Stream 6
            if (viewModel.getDecodeStream6Val()) {
                beginVideoDecode(VIDEO_RES_6, videoSurfaceManagers[5], 5)
            }

            // Stream 7
            if (viewModel.getDecodeStream7Val()) {
                beginVideoDecode(VIDEO_RES_7, videoSurfaceManagers[6], 6)
            }

            // Stream 8
            if (viewModel.getDecodeStream8Val()) {
                beginVideoDecode(VIDEO_RES_8, videoSurfaceManagers[7], 7)
            }

            // Stream 9
            if (viewModel.getDecodeStream9Val()) {
                beginVideoDecode(VIDEO_RES_9, videoSurfaceManagers[8], 8)
            }

            // Stream 10
            if (viewModel.getDecodeStream10Val()) {
                beginVideoDecode(VIDEO_RES_10, videoSurfaceManagers[9], 9)
            }

                /*            val breakCodec5 = MediaCodec.createDecoderByType("video/avc")
            breakCodec5.configure(format, null, null, 0)
            breakCodec5.start()
            val breakCodec6 = MediaCodec.createDecoderByType("video/avc")
            breakCodec6.configure(format, null, null, 0)
            breakCodec6.start()*/
        }



        // TODO: Add option to swap out input video files
        // TODO: Add checkboxes to allow encodes for all streams
    }

    override fun onStop() {
        release()
        super.onStop()
    }

    override fun onDestroy() {
        glManager.release()
        super.onDestroy()
    }

    /**
     * Handy keyboard shortcuts
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // 1 - 4 : Toggle decode checkboxes
            KEYCODE_1 -> { checkbox_decode_stream1.isChecked = ! checkbox_decode_stream1.isChecked; checkbox_decode_stream1.clearFocus(); return true }
            KEYCODE_2 -> { checkbox_decode_stream2.isChecked = ! checkbox_decode_stream2.isChecked; checkbox_decode_stream2.clearFocus(); return true }
            KEYCODE_3 -> { checkbox_decode_stream3.isChecked = ! checkbox_decode_stream3.isChecked; checkbox_decode_stream3.clearFocus(); return true }
            KEYCODE_4 -> { checkbox_decode_stream4.isChecked = ! checkbox_decode_stream4.isChecked; checkbox_decode_stream4.clearFocus(); return true }
            KEYCODE_5 -> { checkbox_decode_stream5.isChecked = ! checkbox_decode_stream5.isChecked; checkbox_decode_stream5.clearFocus(); return true }
            KEYCODE_6 -> { checkbox_decode_stream6.isChecked = ! checkbox_decode_stream6.isChecked; checkbox_decode_stream6.clearFocus(); return true }
            KEYCODE_7 -> { checkbox_decode_stream7.isChecked = ! checkbox_decode_stream7.isChecked; checkbox_decode_stream7.clearFocus(); return true }
            KEYCODE_8 -> { checkbox_decode_stream8.isChecked = ! checkbox_decode_stream8.isChecked; checkbox_decode_stream8.clearFocus(); return true }
            KEYCODE_9 -> { checkbox_decode_stream9.isChecked = ! checkbox_decode_stream9.isChecked; checkbox_decode_stream9.clearFocus(); return true }
            KEYCODE_0 -> { checkbox_decode_stream10.isChecked = ! checkbox_decode_stream10.isChecked; checkbox_decode_stream10.clearFocus(); return true }

            // E, F, A, S, K : Toggle switches
            KEYCODE_E -> { switch_encode.isChecked = ! switch_encode.isChecked; switch_encode.clearFocus(); return true }
            KEYCODE_F -> { switch_filter.isChecked = ! switch_filter.isChecked; switch_filter.clearFocus(); return true }
            KEYCODE_A -> { switch_audio.isChecked = ! switch_audio.isChecked; switch_audio.clearFocus(); return true }
            KEYCODE_S -> { switch_software.isChecked = ! switch_software.isChecked; switch_software.clearFocus(); return true }
            KEYCODE_K -> { switch_seek.isChecked = ! switch_seek.isChecked; switch_seek.clearFocus(); return true }

            // D : Start decode
            KEYCODE_D -> {
                if (button_start_decode.isEnabled) {
                    button_start_decode.performClick()
                    return true
                }
            }
        }

        // Pass up any unused keystrokes
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Set up ExoPlayer and begin a decode/encode run
     *
     * @param inputVideoRawId The raw resource ID for the video file to decode
     * @param videoSurfaceManager The VideoSurfaceManger to use
     * internal decoding surfaces
     * @param streamNumber Stream number (0 - 3)
     * @param audioVideoEncoder Optional AudioVideoEncoder (if encoding is desired)
     * @param audioBufferManager Optional AudioBufferManager (if encoding is desired)
     */
    // ,
    // TODO: clean up line wrapping
    private fun beginVideoDecode(inputVideoRawId: Int,
                         videoSurfaceManager: VideoSurfaceManager,
                         streamNumber: Int,
                         audioVideoEncoder: AudioVideoEncoder? = null,
                         audioBufferManager: AudioBufferManager? = null) {
        // Keep track of active decodes to know when to re-enable decode button/clean up surfaces
        activeDecodes++

        // Setup custom video and audio renderers
        val renderersFactory = CustomExoRenderersFactory(this@MainActivity, viewModel,
            videoSurfaceManager, streamNumber, audioBufferManager, viewModel.getSoftwareVal())


        // Reduce default buffering to MIN_DECODE_BUFFER_MS to prevent over allocation
        // when processing multiple large streams
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_DECODE_BUFFER_MS, MIN_DECODE_BUFFER_MS * 2, MIN_DECODE_BUFFER_MS, MIN_DECODE_BUFFER_MS)
            .createDefaultLoadControl()
        val player: ExoPlayer = ExoPlayer.Builder(this@MainActivity, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        if (audioVideoEncoder != null) {
            // Set up encode and decode surfaces
            videoSurfaceManager.initialize(player,
                audioVideoEncoder.videoEncoderInputSurface,
                audioVideoEncoder.encoderWidth,
                audioVideoEncoder.encoderHeight)

            // Start the encoder
            audioVideoEncoder.startEncode()
        } else {
            // Only set up decode surfaces
            videoSurfaceManager.initialize(player)
        }

        // Note: the decoder uses a custom MediaClock that goes as fast as possible so this speed
        // value is not used, but required by the ExoPlayer API
        player.setPlaybackParameters(PlaybackParameters(1f))

        // Add a listener for when the video is done or error occurs
        player.addListener(object : Player.Listener {
            override fun onPlayerStateChanged(playWhenReady: Boolean , playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    audioVideoEncoder?.signalDecodingComplete()
                    player.release()
                    this@MainActivity.decodeFinished()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                updateLog("Decoder init error stream #${streamNumber}: ${error.message}")
                activeDecodes--
                super.onPlayerError(error)
            }
        })

        // Set up video source and start the player
        val videoSource = buildExoMediaSource(this, inputVideoRawId)
        player.setMediaSource(videoSource)
        player.prepare()
        player.playWhenReady = true

        if (viewModel.getDoSeeksVal()) {
            // Do a few seeks while decoding process. Jump forward seekJump ms and then back
            val numSeeks = 3
            val delayTimeMs = 1000L
            val seekJumpMs = 1000L
            lifecycle.coroutineScope.launch {
                delay(delayTimeMs)
                for (i in 1..numSeeks) {
                    player.pause()
                    val oldPos = player.currentPosition
                    val pos = oldPos + seekJumpMs
                    updateLog("Stream #${streamNumber+1}: Jumping to pos: ${pos}")
                    player.seekTo(pos)
                    player.prepare()
                    updateLog("Stream #${streamNumber+1}: Jumping back to pos: ${oldPos+1}")
                    player.seekTo(oldPos+1)
                    player.prepare()
                    player.playWhenReady = true
                    delay(delayTimeMs)
                }
            }
        }
    }

    // Indicate one more preview surface is available
    // This is used to only enable the decode button only after all the decode surfaces are ready
    fun surfaceAvailable() {
        numberOfReadySurfaces++

        if (numberOfReadySurfaces >= NUMBER_OF_STREAMS) {
            runOnUiThread {
                button_start_decode.isEnabled = true
            }
        }
    }

    // Indicate one more preview surface has been released
    fun surfaceReleased() {
        numberOfReadySurfaces--

        if (numberOfReadySurfaces < NUMBER_OF_STREAMS) {
            runOnUiThread {
                button_start_decode.isEnabled = false
            }
        }
    }

    // Called for each completed decode. When there are no more on-going decodes or encodes, mark
    // old surfaces for deletion and set-up fresh ones.
    private fun decodeFinished() {
        activeDecodes--
        if (activeDecodes <= 0 && activeEncodes <= 0) {
            runOnUiThread {
                markSurfacesForDeletion()
                initializeSurfaces()
            }
        }
    }

    // Called for each completed encode. When there are no more on-going decodes or encodes, mark
    // old surfaces for deletion and set-up fresh ones.
    private fun encodeFinished() {
        activeEncodes--
        viewModel.getEncodingInProgress().removeObservers(this)
        if (activeDecodes == 0 && activeEncodes == 0) {
            runOnUiThread {
                markSurfacesForDeletion()
                initializeSurfaces()
            }
        }
    }

    /**
     * Check if this device is a Chrome OS device
     */
    fun isArc(): Boolean {
        return packageManager.hasSystemFeature("org.chromium.arc")
    }

    private val requestPermission = registerForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            // Permission granted, restart the app
            updateLog("Permissions granted! Encoding will save to file. Restarting app...")
            val intent = this.intent
            finish()
            startActivity(intent)
        } else {
            // updateLog("File permissions not granted. Encoding will not save to file.")
        }
    }

    /**
     * Check for required app permissions and request them from user if necessary
     */
    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // Launch the permission request for WRITE_EXTERNAL_STORAGE
                requestPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return false
        }
        return true
    }

    // Convenience function to write to on-screen log and logcat log simultaneously
    fun updateLog(message: String, clear: Boolean = false) {
        logd(message)

        runOnUiThread {
            if (clear)
                text_log.text = ""
            else
                text_log.append("\n")

            text_log.append(message)
            scroll_log.post { scroll_log.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * Set up options menu to allow debug logging and clearing cache'd data
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater: MenuInflater = menuInflater
        inflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /**
     * Handle menu presses
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_delete_exports -> {
                deleteEncodes(this, viewModel, FILE_PREFIX)
                true
            }
            //R.id.menu_delete_logs -> {
            //    deleteLogs(this)
            //    true
            //}
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun getMaxInstancesString() : String {
        var outputString = " \n"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val allCodecs = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in allCodecs.getCodecInfos()) {
                if (info.isEncoder) {
                    continue
                }
                val types = info.getSupportedTypes()
                for (type in types) {
                    val caps: MediaCodecInfo.CodecCapabilities = info.getCapabilitiesForType(type)
                    val maxInstances = caps.maxSupportedInstances
                    var decoderType = ""
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        decoderType = if (info.isHardwareAccelerated) "HW decoder" else "SW decoder"
                    }
                    when (type) {
                        "video/avc" -> outputString += "Max instance for ${type} : ${maxInstances} with info : ${info.name}. ${decoderType}\n"
                        else -> {}
                    }
                }
            }
        }
        return outputString
    }
}