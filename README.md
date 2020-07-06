# VideoDecodeEncodeDemo

## This is not an officially supported Google product
This is a proof-of-concept demo. This code should not be used in production.

## About
The fastest way to decode media using MediaCodec is via an internal Surface. This can lead to
dropped frames pre-Android 10 (when the [allow-frame-drop](https://developer.android.com/reference/android/media/MediaCodec#using-an-output-surface)
flag was introduced).

This project demostrates how to use internal Surfaces for fast decoding using a SurfaceTexture while
not losing frames using an Atomic lock.

ExoPlayer is used to simplify the decoding. MediaCodec/MediaMuxer is used for encoding.

An option is included to pass streams through a simple OpenGL sepia filter to demonstrate where to
insert custom filters in the pipeline.

<img alt="Screenshot of VideoDecodeEncodeDemo" src="https://github.com/chromeos/video-decode-encode-demo/blob/master/VideoDecodeEncodeDemo-Screenshot.png" width="200" />

## Usage
* Click decode to simultaneously decode all selected video streams
* Toggle the sepia filter flag to test using a GL filter
* Turning on the "Encode 1st Stream" switch before beginning a decode will take the decoded frames
from the first surface and re-encode them

## Notes

* Only tested on a handful of devices
* Do not use this code in production as is
* 4 - 1080p mp4 files from a Pixel 4 phone are included for testing in the raw directory
* Encoded files are found in the sdcard/Android/data/dev.hadrosaur.videodecodeencodedemo directory
* Encoded media settings are not ideal and currently do produce the proper frame delay

## LICENSE

***

Copyright 2020 Google LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.