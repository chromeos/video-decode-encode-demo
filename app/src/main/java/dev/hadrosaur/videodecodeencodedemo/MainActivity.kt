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
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.PlaybackParameters
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.CustomExoRenderersFactory
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.InternalSurfaceTextureComponent
import dev.hadrosaur.videodecodeencodedemo.VideoHelpers.buildExoMediaSource
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {
    // Preview surfaces
    lateinit var previewSurfaceViewOne: SurfaceView
    lateinit var previewSurfaceViewTwo: SurfaceView
    lateinit var previewSurfaceViewThree: SurfaceView
    lateinit var previewSurfaceViewFour: SurfaceView
    var previewSurfaceViewOneToDelete: SurfaceView? = null
    var previewSurfaceViewTwoToDelete: SurfaceView? = null
    var previewSurfaceViewThreeToDelete: SurfaceView? = null
    var previewSurfaceViewFourToDelete: SurfaceView? = null

    // Internal decoding surfaces
    lateinit var internalSurfaceTextureComponentOne: InternalSurfaceTextureComponent
    lateinit var internalSurfaceTextureComponentTwo: InternalSurfaceTextureComponent
    lateinit var internalSurfaceTextureComponentThree: InternalSurfaceTextureComponent
    lateinit var internalSurfaceTextureComponentFour: InternalSurfaceTextureComponent
    var internalSurfaceTextureComponentOneToDelete: InternalSurfaceTextureComponent? = null
    var internalSurfaceTextureComponentTwoToDelete: InternalSurfaceTextureComponent? = null
    var internalSurfaceTextureComponentThreeToDelete: InternalSurfaceTextureComponent? = null
    var internalSurfaceTextureComponentFourToDelete: InternalSurfaceTextureComponent? = null

    // The GlManager manages the eglcontext for all renders and filters
    val glManager = GlManager()

    // Counter to track if all surfaces are ready
    val NUMBER_OF_PREVIEW_SURFACES = 4
    var numberOfReadySurfaces = 0
    var activeDecodes = 0
    var stream1DecodeFinished = false // keep track of stream 1 decode for encoder

    var encodeFileOriginalRawFileId = R.raw.paris_01_1080p // Hack to easily pass original file info down to encoder

    val viewModel: VideoVideoModel by viewModels()

    companion object {
        const val LOG_EVERY_N_FRAMES = 200 // Log dropped frames and fps every N frames
        val MIN_DECODE_BUFFER_MS = 68 // Roughly 2 frames at 30fps.
        val LOG_TAG = "VideoDemo"
        val FILE_PREFIX = "VideoDemo"
        var CAN_WRITE_FILES = false

        // Convenience logging function
        fun logd(message: String) {
            Log.d(LOG_TAG, message)
        }
    }

    fun initializeSurfaces() {
        numberOfReadySurfaces = 0

        // Create the preview surfaces
        previewSurfaceViewOne = SurfaceView(this)
        previewSurfaceViewTwo = SurfaceView(this)
        previewSurfaceViewThree = SurfaceView(this)
        previewSurfaceViewFour = SurfaceView(this)

        // Setup surface listeners to indicate when surfaces have been created/destroyed
        previewSurfaceViewOne.holder.addCallback(VideoSurfaceViewListener(this))
        previewSurfaceViewTwo.holder.addCallback(VideoSurfaceViewListener(this))
        previewSurfaceViewThree.holder.addCallback(VideoSurfaceViewListener(this))
        previewSurfaceViewFour.holder.addCallback(VideoSurfaceViewListener(this))

        // Create the internal SurfaceTextures that will be used for decoding
        initializeInternalSurfaces()

        // Add the preview surfaces to the UI
        frame_one.addView(previewSurfaceViewOne)
        frame_two.addView(previewSurfaceViewTwo)
        frame_three.addView(previewSurfaceViewThree)
        frame_four.addView(previewSurfaceViewFour)
    }

    // Create the internal SurfaceTextures that will be used for decoding
    fun initializeInternalSurfaces() {
        internalSurfaceTextureComponentOne = InternalSurfaceTextureComponent(this, glManager, previewSurfaceViewOne)
        internalSurfaceTextureComponentTwo = InternalSurfaceTextureComponent(this, glManager, previewSurfaceViewTwo)
        internalSurfaceTextureComponentThree = InternalSurfaceTextureComponent(this, glManager, previewSurfaceViewThree)
        internalSurfaceTextureComponentFour = InternalSurfaceTextureComponent(this, glManager, previewSurfaceViewFour)
    }

    /**
     * To start a new test, fresh surfaces are desired, but the encoder or decoder may
     * still be finishing up. We leave these surfaces around until the next time the "go" button
     * is pressed.
     */
    fun markSurfacesForDeletion() {
        previewSurfaceViewOneToDelete = previewSurfaceViewOne
        previewSurfaceViewTwoToDelete = previewSurfaceViewTwo
        previewSurfaceViewThreeToDelete = previewSurfaceViewThree
        previewSurfaceViewFourToDelete = previewSurfaceViewFour

        internalSurfaceTextureComponentOneToDelete = internalSurfaceTextureComponentOne
        internalSurfaceTextureComponentTwoToDelete = internalSurfaceTextureComponentTwo
        internalSurfaceTextureComponentThreeToDelete = internalSurfaceTextureComponentThree
        internalSurfaceTextureComponentFourToDelete = internalSurfaceTextureComponentFour
    }

    fun releaseSurfacesMarkedForDeletion() {
        previewSurfaceViewOneToDelete = null
        previewSurfaceViewTwoToDelete = null
        previewSurfaceViewThreeToDelete = null
        previewSurfaceViewFourToDelete = null

        internalSurfaceTextureComponentOneToDelete?.release()
        internalSurfaceTextureComponentTwoToDelete?.release()
        internalSurfaceTextureComponentThreeToDelete?.release()
        internalSurfaceTextureComponentFourToDelete?.release()
        internalSurfaceTextureComponentOneToDelete = null
        internalSurfaceTextureComponentTwoToDelete = null
        internalSurfaceTextureComponentThreeToDelete = null
        internalSurfaceTextureComponentFourToDelete = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeSurfaces()

        // Request file read/write permissions need to save encodes.
        if (checkPermissions()) {
            CAN_WRITE_FILES = true
        } else {
            updateLog("File permissions not granted. Encoding will not save to file.")
        }

        // Setup seekbar
        seek_framedelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                viewModel.setPreviewFrameFrequency(progress)
                runOnUiThread {
                    text_frame_delay.setText("Preview every ${progress} frames")
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
        viewModel.getPreviewFrameFrequency().observe(this, Observer<Int>{ frameFrequency ->
            seek_framedelay.progress = frameFrequency
        })

        // Set up decode checkboxes. Stream 1 will always decode but set up properly anyway
        checkbox_decode_stream1.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream1(isChecked) }
        viewModel.getDecodeStream1().observe(this, Observer<Boolean> {
                isChecked -> checkbox_decode_stream1.isSelected = isChecked })
        checkbox_decode_stream2.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream2(isChecked) }
        viewModel.getDecodeStream2().observe(this, Observer<Boolean> {
                isChecked -> checkbox_decode_stream2.isSelected = isChecked })
        checkbox_decode_stream3.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream3(isChecked) }
        viewModel.getDecodeStream3().observe(this, Observer<Boolean> {
                isChecked -> checkbox_decode_stream3.isSelected = isChecked })
        checkbox_decode_stream4.setOnCheckedChangeListener{
                _, isChecked -> viewModel.setDecodeStream4(isChecked) }
        viewModel.getDecodeStream4().observe(this, Observer<Boolean> {
                isChecked -> checkbox_decode_stream4.isSelected = isChecked })

        // Set up toggle switches to auto track with the ViewModel
        switch_filter.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setApplyGlFilter(isChecked) }
        viewModel.getApplyGlFilter().observe(this, Observer<Boolean> {
                applyFilter -> switch_filter.isSelected = applyFilter })
        switch_encode.setOnCheckedChangeListener {
                _, isChecked -> viewModel.setEncodeStream1(isChecked) }
        viewModel.getEncodeStream1().observe(this, Observer<Boolean> {
                encodeStream -> switch_encode.isSelected = encodeStream })

        // Set up decode button
        button_start_decode.setOnClickListener {
            button_start_decode.isEnabled = false
            releaseSurfacesMarkedForDeletion()

            encodeFileOriginalRawFileId = R.raw.paris_01_1080p
            internalSurfaceTextureComponentOne.shouldEncode(viewModel.getEncodeStream1Val()) // When decode button is clicked, set if encoding should happen or not

            if (viewModel.getDecodeStream1Val()) {
                beginVideoDecode(R.raw.paris_01_1080p, internalSurfaceTextureComponentOne, 1)
            }
            if (viewModel.getDecodeStream2Val()) {
                beginVideoDecode(R.raw.paris_02_1080p, internalSurfaceTextureComponentTwo, 2)
            }
            if (viewModel.getDecodeStream3Val()) {
                beginVideoDecode(R.raw.paris_03_1080p, internalSurfaceTextureComponentThree, 3)
            }
            if (viewModel.getDecodeStream4Val()) {
                beginVideoDecode(R.raw.paris_04_1080p, internalSurfaceTextureComponentFour, 4)
            }
        }
    }

    // Set up an Exoplayer decode for the given video file and InternalSurfaceTextureComponent,
    // Note: the InternalSurfaceTextureComponent knows it's own preview SurfaceView
    fun beginVideoDecode(inputVideoRawId: Int, internalSurfaceTextureComponent: InternalSurfaceTextureComponent, streamNumber: Int) {
        activeDecodes++

        // Keep track of stream 1 decode to signal encoder
        if (streamNumber == 1) {
            stream1DecodeFinished = false
        }

        // Setup custom video and audio renderers
        val renderersFactory = CustomExoRenderersFactory(this@MainActivity, internalSurfaceTextureComponent, streamNumber)

        // Reduce default buffering to MIN_DECODE_BUFFER_MS to prevent over allocation when processing multiple large streams
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(MIN_DECODE_BUFFER_MS, MIN_DECODE_BUFFER_MS * 2, MIN_DECODE_BUFFER_MS, MIN_DECODE_BUFFER_MS).createDefaultLoadControl()
        val player: SimpleExoPlayer = SimpleExoPlayer.Builder(this@MainActivity, renderersFactory).setLoadControl(loadControl).build()

        if (player.videoComponent != null) {
            internalSurfaceTextureComponent.initialize(player.videoComponent as Player.VideoComponent)
        } else {
            updateLog("Player video component is NULL, this will not work")
        }

        // Note: the decoder uses a custom MediaClock that goes as fast as possible so this speed
        // value is not used, but required by the ExoPlayer API
        player.setPlaybackParameters(PlaybackParameters(1f))

        // Add a listener for when the video is done
        player.addListener(object: Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean , playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (streamNumber == 1) {
                        stream1DecodeFinished = true
                    }
//                    player.clearVideoSurface()
//                    player.release()
                    this@MainActivity.decodeFinished()
                }
            }
        })

        val videoSource = buildExoMediaSource(this, inputVideoRawId)
        if (null != videoSource) {
            player.prepare(videoSource)
            player.playWhenReady = true
        }
    }

    // Indicate one more preview surface is available
    // This is a primitive way to attempt to prevent a decode stream beginning when preview surfaces
    // are not yet ready
    fun surfaceAvailable() {
        numberOfReadySurfaces++

        if (numberOfReadySurfaces >= NUMBER_OF_PREVIEW_SURFACES) {
            runOnUiThread {
                button_start_decode.isEnabled = true
            }
        }
    }

    // Indicate one more preview surface has been released
    fun surfaceReleased() {
        numberOfReadySurfaces--

        if (numberOfReadySurfaces < NUMBER_OF_PREVIEW_SURFACES) {
            runOnUiThread {
                button_start_decode.isEnabled = false
            }
        }
    }

    // Called for each completed decode. When there are no more on-going decodes, re-enable the
    // decode button.
    fun decodeFinished() {
        activeDecodes--

        if (activeDecodes == 0) {
            runOnUiThread {
                if (!viewModel.getEncodeStream1Val()) {
                    markSurfacesForDeletion()
                    initializeSurfaces()
                }
            }
        }
    }

    fun encodeFinished() {
        markSurfacesForDeletion()
        initializeSurfaces()
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
    fun checkPermissions(): Boolean {
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
            scroll_log.post(Runnable { scroll_log.fullScroll(View.FOCUS_DOWN) })
        }
    }
}