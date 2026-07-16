package com.example.wallpaperapplication

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.webrtc.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * LocalRecorder saves WebRTC VideoTracks to an MP4 file on-device using MediaRecorder.
 *
 * Three recording modes:
 *   - startSingle(track)          → single camera or screen track
 *   - startDual(back, front)      → both cameras via FrameCombiner PiP
 *   - startScreenWithAudio(track) → screen share track with mic audio
 *
 * Output path: getExternalFilesDir(MOVIES)/Recordings/REC_yyyyMMdd_HHmmss.mp4
 */
class LocalRecorder(
    private val context: Context,
    private val eglBase: EglBase
) {

    private var mediaRecorder: MediaRecorder? = null
    private var surface: Surface? = null
    @Volatile
    private var isRecording = false
    @Volatile
    private var isStopping = false

    private var videoFilePath: String? = null

    // Rendering pipeline
    private var videoSink: VideoSink? = null
    private var combiner: FrameCombiner? = null
    private var eglRenderer: EglRenderer? = null

    private var activeTrack: VideoTrack? = null
    private var activeTrack2: VideoTrack? = null

    private var frameCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    interface RecordingCallback {
        fun onRecordingStopped(path: String?)
    }

    fun isRecording(): Boolean = isRecording || isStopping

    // ─────────────────────────────────────────────────────────────
    // Public start methods
    // ─────────────────────────────────────────────────────────────

    fun start(track: VideoTrack, isScreenSharing: Boolean): String? {
        Log.d(TAG, "start() requested. isScreenSharing: $isScreenSharing")
        return if (isScreenSharing) {
            startScreenWithAudio(track)
        } else {
            startSingle(track)
        }
    }

    fun startSingle(track: VideoTrack): String? {
        if (isRecording || isStopping) return null
        return try {
            if (!setupRecorder()) return null

            val renderer = EglRenderer("SingleRecorderRenderer")
            renderer.init(eglBase.eglBaseContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
            renderer.createEglSurface(surface!!)
            eglRenderer = renderer

            frameCount = 0
            val sink = VideoSink { frame ->
                frameCount++
                renderer.onFrame(frame)
            }
            videoSink = sink
            activeTrack = track
            track.addSink(sink)

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Single recording started: $videoFilePath")
            videoFilePath
        } catch (e: Exception) {
            Log.e(TAG, "startSingle failed", e)
            cleanup()
            null
        }
    }

    fun startDual(back: VideoTrack, front: VideoTrack): String? {
        if (isRecording || isStopping) return null
        return try {
            if (!setupRecorder()) return null

            val combinerSink = FrameCombiner(surface!!, eglBase)
            this.combiner = combinerSink

            frameCount = 0
            val sink = VideoSink { frame ->
                frameCount++
                combinerSink.onFrame(frame)
            }
            videoSink = sink
            activeTrack = back
            activeTrack2 = front
            back.addSink(sink)
            front.addSink(sink)

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Dual recording started: $videoFilePath")
            videoFilePath
        } catch (e: Exception) {
            Log.e(TAG, "startDual failed", e)
            cleanup()
            null
        }
    }

    fun startScreenWithAudio(screenTrack: VideoTrack): String? {
        if (isRecording || isStopping) return null
        return try {
            if (!setupRecorder()) return null

            val renderer = EglRenderer("ScreenRecorderRenderer")
            renderer.init(eglBase.eglBaseContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
            renderer.createEglSurface(surface!!)
            eglRenderer = renderer

            frameCount = 0
            val sink = VideoSink { frame ->
                frameCount++
                renderer.onFrame(frame)
            }
            videoSink = sink
            activeTrack = screenTrack
            screenTrack.addSink(sink)

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Screen recording started: $videoFilePath")
            videoFilePath
        } catch (e: Exception) {
            Log.e(TAG, "startScreenWithAudio failed", e)
            cleanup()
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Stop & Cleanup
    // ─────────────────────────────────────────────────────────────

    fun stop(callback: RecordingCallback?) {
        if (!isRecording || isStopping) {
            callback?.onRecordingStopped(null)
            return
        }
        isRecording = false
        isStopping = true
        Log.d(TAG, "Stopping recording... Total frames: $frameCount")

        val recorderToStop = mediaRecorder
        val path = videoFilePath

        Thread {
            try {
                // Wait for encoder to flush remaining frames
                val waitMs = if (frameCount < 30) 1500L else 800L
                Thread.sleep(waitMs)

                try {
                    recorderToStop?.stop()
                    Log.d(TAG, "MediaRecorder stopped successfully")
                } catch (e: RuntimeException) {
                    Log.e(TAG, "MediaRecorder.stop() failed — deleting incomplete file", e)
                    path?.let { File(it).delete() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during stop wait", e)
            } finally {
                mainHandler.post {
                    cleanup()
                    isStopping = false
                    val finalPath = if (path != null && File(path).exists()) path else null
                    callback?.onRecordingStopped(finalPath)
                }
            }
        }.start()
    }

    /**
     * Switches the active recording track without stopping (e.g., on camera switch).
     */
    fun updateTrack(newTrack: VideoTrack) {
        if (!isRecording || isStopping) return
        val sink = videoSink ?: return
        try {
            activeTrack?.removeSink(sink)
            activeTrack = newTrack
            activeTrack?.addSink(sink)
        } catch (e: Exception) {
            Log.e(TAG, "updateTrack failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Internal setup
    // ─────────────────────────────────────────────────────────────

    private fun setupRecorder(): Boolean {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Recordings")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create recordings directory: ${dir.absolutePath}")
                return false
            }
            // Check external storage is available
            val state = Environment.getExternalStorageState()
            if (state != Environment.MEDIA_MOUNTED) {
                Log.e(TAG, "External storage not mounted: $state")
                return false
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            videoFilePath = File(dir, "REC_$timestamp.mp4").absolutePath

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // MIC may be busy during active WebRTC streaming — fall back to video-only gracefully
            // IMPORTANT: AudioSource must be set BEFORE VideoSource per MediaRecorder state machine
            var audioInitialized = false
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                audioInitialized = true
            } catch (e: Exception) {
                Log.w(TAG, "MIC busy or unavailable — recording video only")
            }

            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(videoFilePath)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (audioInitialized) {
                try {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(128_000)
                    recorder.setAudioSamplingRate(44_100)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not configure audio encoder")
                }
            }

            recorder.setVideoEncodingBitRate(Constants.REC_VIDEO_BITRATE)
            recorder.setVideoFrameRate(Constants.REC_VIDEO_FPS)
            recorder.setVideoSize(Constants.REC_VIDEO_WIDTH, Constants.REC_VIDEO_HEIGHT)

            recorder.prepare()
            mediaRecorder = recorder
            this.surface = recorder.surface
            true
        } catch (e: Exception) {
            Log.e(TAG, "setupRecorder failed", e)
            false
        }
    }

    private fun cleanup() {
        try {
            val sink = videoSink
            if (sink != null) {
                activeTrack?.removeSink(sink)
                activeTrack2?.removeSink(sink)
            }
            eglRenderer?.release()
            eglRenderer = null
            combiner?.release()
            combiner = null
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error", e)
        } finally {
            mediaRecorder = null
            surface = null
            videoSink = null
            activeTrack = null
            activeTrack2 = null
        }
    }

    companion object {
        private const val TAG = "LocalRecorder"
    }
}
