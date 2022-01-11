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

import android.opengl.*
import com.google.android.exoplayer2.util.GlUtil
import com.google.android.exoplayer2.util.Util
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.Utils.GlUtils.createEglDisplay

/**
 * GlManager provides a common EGLDisplay/EGLContext/EGLConfig for all gl operators.
 *
 * Includes convenience methods for setting up these contexts
 */
class GlManager {
    val eglDisplay: EGLDisplay
    val eglContext: EGLContext
    val eglConfig: EGLConfig

    /** A runtime exception to be thrown if some EGL operations failed.  */
    class GlException constructor(msg: String) : RuntimeException(msg)

    init {
        eglDisplay = defaultDisplay
        eglConfig = chooseEGLConfig(eglDisplay)
        eglContext = createEGLContext(eglDisplay, eglConfig)
    }

    companion object {
        // For protected content, currently unused
        const val EGL_PROTECTED_CONTENT_EXT = 0x32C0

        // The following constants are not exposed in Java and thus have been copied from eglext.h:
        // https://www.khronos.org/registry/EGL/api/EGL/eglext.h
        private const val EGL_YUV_BUFFER_EXT = 0x3300
        private const val EGL_YUV_CSC_STANDARD_EXT = 0x330A
        private const val EGL_YUV_CSC_STANDARD_601_EXT = 0x330B
        private const val EGL_YUV_PLANE_BPP_EXT = 0x331A
        private const val  EGL_YUV_PLANE_BPP_0_EXT = 0x331B
        private const val  EGL_YUV_PLANE_BPP_8_EXT = 0x331C
        private const val  EGL_YUV_PLANE_BPP_10_EXT = 0x331D
        private const val EGL_YUV_ORDER_EXT = 0x3301
        private const val EGL_YUV_NUMBER_OF_PLANES_EXT = 0x3311
        private const val EGL_YUV_DEPTH_RANGE_EXT = 0x3317
        private const val EGL_YUV_DEPTH_RANGE_LIMITED_EXT = 0x3318
        private const val EGL_YUV_DEPTH_RANGE_FULL_EXT = 0x3319
        private const val EGL_YUV_SUBSAMPLE_EXT = 0x3312
        private const val EGL_YUV_SUBSAMPLE_4_2_0_EXT = 0x3313
        private const val EGL_YUV_SUBSAMPLE_4_2_2_EXT = 0x3314
        private const val EGL_YUV_SUBSAMPLE_4_4_4_EXT = 0x3315
        private const val EGL_YUV_ORDER_YUV_EXT = 0x3302
        private const val EGL_YUV_ORDER_YVU_EXT = 0x3303

        private val EGL_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_BUFFER_SIZE, 32,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_NONE,
            EGL14.EGL_SURFACE_TYPE,  EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        private val EGL_YUV_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_COLOR_BUFFER_TYPE, EGL_YUV_BUFFER_EXT,
            EGL_YUV_SUBSAMPLE_EXT, EGL_YUV_SUBSAMPLE_4_2_0_EXT,
            EGL_YUV_NUMBER_OF_PLANES_EXT, 2,
            EGL_YUV_ORDER_EXT, EGL_YUV_ORDER_YVU_EXT,
            // EGL_YUV_CSC_STANDARD_EXT, EGL_YUV_CSC_STANDARD_601_EXT,
            EGL_YUV_PLANE_BPP_EXT, EGL_YUV_PLANE_BPP_8_EXT,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_NONE,
            EGL14.EGL_SURFACE_TYPE,  EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        // From "Example Configuration for NV12" from eglext.h
        private val EGL_YUV_CONFIG_ATTRIBUTES2 = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE,  EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_COLOR_BUFFER_TYPE, EGL_YUV_BUFFER_EXT,
            EGL_YUV_ORDER_EXT,             EGL_YUV_ORDER_YUV_EXT,
            EGL_YUV_NUMBER_OF_PLANES_EXT,  2,
            EGL_YUV_SUBSAMPLE_EXT,         EGL_YUV_SUBSAMPLE_4_2_0_EXT,
            EGL_YUV_DEPTH_RANGE_EXT,       EGL_YUV_DEPTH_RANGE_LIMITED_EXT,
            EGL_YUV_CSC_STANDARD_EXT,      EGL_YUV_CSC_STANDARD_601_EXT,
            EGL_YUV_PLANE_BPP_EXT,         EGL_YUV_PLANE_BPP_8_EXT,
            EGL14.EGL_NONE
        )

        val defaultDisplay: EGLDisplay = createEglDisplay()

        fun chooseEGLConfig(display: EGLDisplay?): EGLConfig {
            val configs =
                arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)

            // Call getConfigs with null to see how many configs
            EGL14.eglGetConfigs(display, null, 0, 0, numConfigs, 0)
            MainActivity.logd("I have gotten ${numConfigs[0]} EGL Configs!")
            // Now actually fetch configs
            val deviceConfigs = arrayOfNulls<EGLConfig>(numConfigs[0])
            EGL14.eglGetConfigs(display, deviceConfigs, 0, numConfigs[0], numConfigs, 0)

            // Log attributes we care about
            val attributeValue = IntArray(1)
            var configNum = 0 // Counter
            var isYUVConfig = false
            for (deviceConfig in deviceConfigs) {
                val attributeCheckFailed = EGL14.eglGetConfigAttrib(display, deviceConfig, EGL14.EGL_COLOR_BUFFER_TYPE, attributeValue, 0)
                if (!attributeCheckFailed) {
                    MainActivity.logd("Failed getting Color Buffer Type for Config #${configNum+1}")
                }

                val colorBufferType = when (attributeValue[0]) { EGL14.EGL_RGB_BUFFER -> "RGB"; EGL14.EGL_LUMINANCE_BUFFER -> "YUV"; else -> "Unknown buffer type" }
                MainActivity.logd("Config #${configNum+1}: Color Buffer Type: ${colorBufferType}")
                if (attributeValue[0] == EGL14.EGL_LUMINANCE_BUFFER) {
                    isYUVConfig = true
                }
                configNum++
            }

            // Choose a YUV config if one exists for this device
            var success = false
            if (isYUVConfig) {
                MainActivity.logd("This device has a YUV configs! Select it.")
                success = EGL14.eglChooseConfig(
                    display,
//                EGL_YUV_CONFIG_ATTRIBUTES,  /* attrib_listOffset= */
                    EGL_YUV_CONFIG_ATTRIBUTES2,  /* attrib_listOffset= */
                    0,
                    configs,  /* configsOffset= */
                    0,  /* config_size= */
                    1,
                    numConfigs,  /* num_configOffset= */
                    0
                )
            } else {
                MainActivity.logd("This device has NO YUV configs. Try an RGB config.")
                success = EGL14.eglChooseConfig(
                    display,
                    EGL_CONFIG_ATTRIBUTES,  /* attrib_listOffset= */
                    0,
                    configs,  /* configsOffset= */
                    0,  /* config_size= */
                    1,
                    numConfigs,  /* num_configOffset= */
                    0
                )
            }

            MainActivity.logd("In Choose EGLConfig. Configs 0 is null?: ${configs[0] == null}, success is ${success}. numConfigs[0] = ${numConfigs[0]}")
            if (!success || numConfigs[0] <= 0 || configs[0] == null) {
                throw GlException(
                    Util.formatInvariant( /* format= */
                        "eglChooseConfig failed: success=%b, numConfigs[0]=%d,",
                        success, numConfigs[0]
                    )
                )
            }

            return configs[0]!!
        }

        fun createEGLContext(display: EGLDisplay?, config: EGLConfig?): EGLContext {
            val glAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)

            return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, glAttributes, 0)
                ?: throw GlException("eglCreateContext failed")
        }


        private const val EGL_SURFACE_WIDTH = 1
        private const val EGL_SURFACE_HEIGHT = 1

        fun createEGLSurface(display: EGLDisplay?, config: EGLConfig?, context: EGLContext?): EGLSurface {
            val surface: EGLSurface?
            val pbufferAttributes = intArrayOf(
                    EGL14.EGL_WIDTH,
                    EGL_SURFACE_WIDTH,
                    EGL14.EGL_HEIGHT,
                    EGL_SURFACE_HEIGHT,
                    EGL14.EGL_NONE
                )

            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                pbufferAttributes,  /* offset= */
                0
            )
            if (surface == null) {
                throw GlException("eglCreatePbufferSurface failed")
            }

            val eglMadeCurrent =
                EGL14.eglMakeCurrent(display,  /* draw= */surface,  /* read= */surface, context)
            if (!eglMadeCurrent) {
                throw GlException("eglMakeCurrent failed")
            }
            return surface
        }

        fun generateTextureIds() : Int {
            val textureIdHolder = IntArray(1)
            GLES31.glGenTextures( /* n= */1, textureIdHolder,  /* offset= */0)
            GlUtil.checkGlError()
            return textureIdHolder[0]
        }

        /**
         * Returns a new [EGLSurface] wrapping the specified `surface`.
         *
         */
        fun getEglSurface(eglConfig: EGLConfig?, eglDisplay: EGLDisplay?, surface: Any?): EGLSurface? {
            return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig,
                surface, intArrayOf(EGL14.EGL_NONE),
                0
            )
        }

        /**
         * Deletes a GL texture.
         *
         * @param texId of the texture to delete
         */
        fun deleteTexture(texId: Int) {
            val textures = intArrayOf(texId)
            GLES31.glDeleteTextures(1, textures, 0)
        }

        /**
         * Destroys the GL context identifier by `eglDisplay` and `eglContext`.
         */
        fun destroyEglContext(
            eglDisplay: EGLDisplay?,
            eglContext: EGLContext?
        ) {
            if (eglDisplay == null) {
                return
            }
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            var error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw RuntimeException("error releasing context: $error")
            }
            if (eglContext != null) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                error = EGL14.eglGetError()
                if (error != EGL14.EGL_SUCCESS) {
                    throw RuntimeException("error destroying context: $error")
                }
            }
            EGL14.eglReleaseThread()
            error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw RuntimeException("error releasing thread: $error")
            }
            EGL14.eglTerminate(eglDisplay)
            error = EGL14.eglGetError()
            if (error != EGL14.EGL_SUCCESS) {
                throw RuntimeException("error terminating display: $error")
            }
        }

    }

    fun release() {
        destroyEglContext(eglDisplay, eglContext)

    }
}
