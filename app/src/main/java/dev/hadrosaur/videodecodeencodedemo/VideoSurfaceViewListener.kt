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

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.TextureView
import dev.hadrosaur.videodecodeencodedemo.MainActivity.Companion.logd
import kotlinx.android.synthetic.main.activity_main.*

class VideoSurfaceViewListener(val mainActivity: MainActivity, val surfaceNumber: Int) : SurfaceHolder.Callback {
    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) {
        mainActivity.surfaceReleased()
    }

    override fun surfaceCreated(holder: SurfaceHolder?) {
        mainActivity.surfaceAvailable()
    }
}
