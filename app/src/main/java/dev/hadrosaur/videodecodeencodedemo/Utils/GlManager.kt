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
    class GlException public constructor(msg: String) : RuntimeException(msg)

    init {
        eglDisplay = defaultDisplay
        eglConfig = chooseEGLConfig(eglDisplay)
        eglContext = createEGLContext(eglDisplay, eglConfig)
    }

    companion object {
        // For protected content, currently unused
        const val EGL_PROTECTED_CONTENT_EXT = 0x32C0

        val EGL_CONFIG_ATTRIBUTES = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_CONFIG_CAVEAT, EGL14.EGL_NONE,
            EGL14.EGL_SURFACE_TYPE,  EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        /* majorOffset= */  /* minorOffset= */
        val defaultDisplay: EGLDisplay = createEglDisplay()

        fun chooseEGLConfig(display: EGLDisplay?): EGLConfig {
            val configs =
                arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val success = EGL14.eglChooseConfig(
                display,
                EGL_CONFIG_ATTRIBUTES,  /* attrib_listOffset= */
                0,
                configs,  /* configsOffset= */
                0,  /* config_size= */
                1,
                numConfigs,  /* num_configOffset= */
                0
            )

            MainActivity.logd("In Choose EGLConfig. Configs 0 is null?: ${configs[0] == null}, success is ${success}")
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
            val glAttributes: IntArray
            glAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)

            return EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, glAttributes, 0)
                ?: throw GlException("eglCreateContext failed")
        }


        private const val EGL_SURFACE_WIDTH = 1
        private const val EGL_SURFACE_HEIGHT = 1

        fun createEGLSurface(display: EGLDisplay?, config: EGLConfig?, context: EGLContext?): EGLSurface? {
            val surface: EGLSurface?
            val pbufferAttributes: IntArray
            pbufferAttributes =
                intArrayOf(
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
            GLES20.glGenTextures( /* n= */1, textureIdHolder,  /* offset= */0)
            GlUtil.checkGlError()
            return textureIdHolder[0]
        }

        /**
         * Returns a new [EGLSurface] wrapping the specified `surface`.
         *
         * @param eglDisplay to attach the surface to
         * @param surface to wrap; must be a surface, surface texture or surface holder
         * @param recordable if the surface will be used to record from
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
            GLES20.glDeleteTextures(1, textures, 0)
        }
    }
}
