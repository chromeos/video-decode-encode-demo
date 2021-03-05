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

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import dev.hadrosaur.videodecodeencodedemo.MainActivity
import dev.hadrosaur.videodecodeencodedemo.MainViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
fun getAppSpecificVideoStorageDir(mainActivity: MainActivity, viewModel: MainViewModel, prefix: String): File {
    val file = File(mainActivity.getExternalFilesDir(
        Environment.DIRECTORY_MOVIES), prefix)

    // Make the directory if it does not exist yet
    if (!file.exists()) {
        if (!file.mkdirs()) {
            viewModel.updateLog("Error creating encoding directory: ${file.absolutePath}")
        }
    }
    return file
}

/**
 * Delete all previously encoded files
 */
fun deleteEncodes(mainActivity: MainActivity, viewModel: MainViewModel, prefix: String) {
    val encodesDir = getAppSpecificVideoStorageDir(mainActivity, viewModel, prefix)

    if (encodesDir.exists()) {
        var numFilesDeleted = 0
        var bytesFreed = 0L

        val fileList = encodesDir.listFiles()
        if (fileList != null) {
            for (file in fileList) {
                bytesFreed += file.length()
                file.delete()
                numFilesDeleted++
            }
        }

        // Files are deleted, let media scanner know
        // val scannerIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        //scannerIntent.data = Uri.fromFile(encodesDir)
        // activity.sendBroadcast(scannerIntent)

        mainActivity.runOnUiThread {
            mainActivity.updateLog("${numFilesDeleted} encoded files deleted, ${bytesFreed / 1000000}MB freed.")
        }
    }
}