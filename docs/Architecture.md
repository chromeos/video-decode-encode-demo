This project is designed as a proof-of-concept demo to illustrate the different building blocks a video editing app might need. Some design decisions were made to illustrate concepts that may not make sense for production apps, for example audio buffers are copied in order to illustrate how to separate them from the decode process and apply separate audio filters, etc - a production app may wish to avoid this copy.

Below is a brief overview of the architecture to help orient you exploring the code.

# Basic flow
<img alt="Basic demo architecture showing main, exoplayer, audio buffers, video frames, preview, and encoding." src="https://github.com/chromeos/video-decode-encode-demo/blob/master/docs/VideoDemo-02-Overview.png" />

This demo uses ExoPlayer to decode audio and video from source files. The resulting buffers and frames are then made available for live previews or processing/encoding.

# Detailed data flow
<img alt="Detailed demo architecture showing main, exoplayer, audio buffers, video frames, preview, and encoding with internal structures but no internal data flow." src="https://github.com/chromeos/video-decode-encode-demo/blob/master/docs/VideoDemo-03-NoData.png" />

<img alt="Detailed demo architecture showing main, exoplayer, audio buffers, video frames, preview, and encoding with internal structures including internal data flow." src="https://github.com/chromeos/video-decode-encode-demo/blob/master/docs/VideoDemo-04-Full.png" />

## ExoPlayer
ExoPlayer is used for decoding. It simplifies some of the decoding set-up and includes device specific workarounds that are convenient. Because the demo does more than simple playback, it overrides the default decoders to provide custom implementations. 

`MediaCodecAudioRenderer` is overridden to provide performance metrics and to pass decoded audio buffers to a custom audio sink: `CopyAndPlayAudioSink`, contained in the "Audio Buffers" box in the diagrams above. 

`MediaCodecVideoRenderer` is overridden to provide performance metrics and to connect the `VideoSurfaceManager` to the decoded frames. It's other principal task is provide a render lock that prevents frames being dropped by ensuring that each frame is rendered correctly before another is queued to the decoder. This render lock is engaged when the `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered()` callback is received by the `VideoFrameLedger` attached to the ExoPlayer `VideoComponent` in `VideoSurfaceManager`.

The ExoPlayer `SimpleExoPlayer` is used to process the input files, sending the audio buffers/video frames to the correct renderer.

## Audio Buffers
Decoded audio buffers are sent to the `CopyAndPlayAudioSink`.  As the name indicates, this audio sink both plays the audio out to the device speakers using a `MediaTrack` as well as copies them for effects or encoding. Audio buffers are copied into a buffer queue managed by `AudioBufferManager` which keep track of the correct presentation times, end-of-stream, etc.

The copy step could be removed if a lock was included to ensure the decoded audio buffer was not freed until encoding was complete. This would improve performance and memory usage at the cost of closely linking encoding with decoding and playback. Because video decoding and encoding is also happening simultaneously, clarity and modularization are more valuable than preventing the extra copying of audio buffers, which is only a part of the overall latency of the system.

## Video Frames
The `VideoSurfaceManager` manages the creation of internal surfaces needed for video decoding and previewing, as well as controls the flow of video frames. It also manages the render lock that prevents frames being dropped during decoding.

The render lock is engaged via the `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered()` callback received by the ExoPlayer `VideoComponent`. The render lock is released after `updateTexImage` / `onFrameAvailable` has been called on the decoder output surface and the next frame can be input. 

If encoding is requested, the `VideoSurfaceManager` gets the encoding surface from the `AudioVideoEncoder` and manages copying frames into it.

Video frames are copied to the preview surfaces and/or the encoder surface via OpenGL, with optional GL filters, using a `DrawFrameProcessor`.

Because encoded frames are just video frames copied onto a surface, metadata like correct presentation time needs to be preserved from the decoder (shown in the diagram as "metadata - us", here us = microseconds, the unit usually used for presentation time). This data is preserved in the `VideoFrameLedger`, along with some frame counting to ensure no frames are dropped and the atomic render lock.

## Encoding
The `AudioVideoEncoder` handles encoding and muxing audio buffers from the `AudioBufferManager` and video frames from the `VideoSurfaceManager`. This is done via [MediaCodec in asynchronous mode](https://developer.android.com/reference/android/media/MediaCodec#asynchronous-processing-using-buffers).

### Audio encoding
Decoded PCM audio is stored in the `AudioBufferManager`. For the first part of any encode, every time a new audio buffer is received by the manager, the `newAudioData()` callback is triggered and the encoder will try to encode it. In this way, the audio encoding process is "driven" by decoded audio arriving in the AudioBufferManager.

Audio encoding will be limited by the number of encoder input buffers available. Each time one of these becomes available, the `onInputBufferAvailable` is called and the index of the input buffer is queued - represented by the "IB" queue in the diagram. These buffers get used for encoding when more data arrives and the `newAudioData()` callback is triggered.

As the audio encoded process nears completion, all of the remaining audio data will now be stored in AudioBufferManager, waiting for input buffers to become available. This means `newAudioData()` will no longer be called. At this point, the audio encode process will be driven by new input buffers becoming available in `onInputBufferAvailable`.

### Video encoding
Video frames will be copied into the encoder's input surface by the `DrawFrameProcessor` managed by the `VideoFrameManager`. These will automatically be encoded by the encoder and encoded output buffers will appear in `onOutputBufferAvailable`.

The video frames will not have any metadata associated with them and need to be correlated with the metadata preserved in the `VideoFrameLedger`. Due to the concurrent/asynchronous nature of the pipeline, frames can be encoded before their decoded metadata is available in the `VideoFrameLedger`. In this case, the frames are held in the `ledgerQueue` until they can be muxed to file.

### Muxing
The audio and video data is muxed together and saved using `MediaMuxer`.  If audio or video frames are received before the muxer has been configured and started, they are queued in their respective `muxerQueue` until the muxer is started.

# Filters
<img alt="Detailed demo architecture showing places video and audio filters can be inserted." src="https://github.com/chromeos/video-decode-encode-demo/blob/master/docs/VideoDemo-05-Filters.png" />
This diagram indicates the easiest place in the architecture to insert video and audio effects. This could include providing multiple video decode surfaces to an OpenGL shader that could blend the multiple streams using multiple texture samplers or other OpenGL effects. The demo provides a simple, toggleable sepia filter as an example.


Raw PCM audio buffers pass through the `CopyAndPlayAudioSink` and audio could be filtered there before playback / being stored in the `AudioBufferManager`. Alternatively, buffers could be filtered in the `AudioBufferManager` prior to playback or encoder which could make more advanced effects possible.