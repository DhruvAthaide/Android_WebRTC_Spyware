package com.example.wallpaperapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.YuvImage
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.webrtc.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraManager(private val context: Context, private val eglBase: EglBase) {

    private var factory: PeerConnectionFactory? = null
    private var backCapturer: CameraVideoCapturer? = null
    private var frontCapturer: CameraVideoCapturer? = null
    private var backHelper: SurfaceTextureHelper? = null
    private var frontHelper: SurfaceTextureHelper? = null
    private var backSource: VideoSource? = null
    private var frontSource: VideoSource? = null

    private var backTrack: VideoTrack? = null
    private var frontTrack: VideoTrack? = null

    fun initialize(factory: PeerConnectionFactory) {
        this.factory = factory
        setupCapturers()
    }

    private fun setupCapturers() {
        val f = factory ?: return
        val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(true)
        }
        val deviceNames = enumerator.deviceNames

        // Setup Back Camera
        for (name in deviceNames) {
            if (enumerator.isBackFacing(name)) {
                try {
                    backCapturer = enumerator.createCapturer(name, null)
                    backHelper = SurfaceTextureHelper.create("BackVideoThread", eglBase.eglBaseContext)
                    backSource = f.createVideoSource(false)
                    backSource?.let {
                        backCapturer?.initialize(backHelper, context.applicationContext, it.capturerObserver)
                        backTrack = f.createVideoTrack("back_video", it)
                    }
                    Log.d("CameraManager", "Back camera initialized: $name")
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error setting up back camera", e)
                }
                break
            }
        }

        // Setup Front Camera
        for (name in deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                try {
                    frontCapturer = enumerator.createCapturer(name, null)
                    frontHelper = SurfaceTextureHelper.create("FrontVideoThread", eglBase.eglBaseContext)
                    frontSource = f.createVideoSource(false)
                    frontSource?.let {
                        frontCapturer?.initialize(frontHelper, context.applicationContext, it.capturerObserver)
                        frontTrack = f.createVideoTrack("front_video", it)
                    }
                    Log.d("CameraManager", "Front camera initialized: $name")
                } catch (e: Exception) {
                    Log.e("CameraManager", "Error setting up front camera", e)
                }
                break
            }
        }
    }

    fun startBackCamera() {
        try {
            backCapturer?.startCapture(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT, Constants.VIDEO_FPS)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to start back camera", e)
        }
    }

    fun startFrontCamera() {
        try {
            frontCapturer?.startCapture(Constants.VIDEO_WIDTH, Constants.VIDEO_HEIGHT, Constants.VIDEO_FPS)
        } catch (e: Exception) {
            Log.e("CameraManager", "Failed to start front camera", e)
        }
    }

    fun changeResolution(width: Int, height: Int, fps: Int) {
        Log.d("CameraManager", "Changing capturing resolution to: ${width}x${height} @ ${fps}fps")
        try {
            // Stop active capturers
            stopCapturers()
            // Small delay to let camera hardware release
            Thread.sleep(200)
            // Re-start with new parameters
            backCapturer?.startCapture(width, height, fps)
            frontCapturer?.startCapture(width, height, fps)
        } catch (e: Exception) {
            Log.e("CameraManager", "Error changing camera capturing parameters", e)
        }
    }

    fun stopCapturers() {
        try {
            backCapturer?.stopCapture()
            frontCapturer?.stopCapture()
        } catch (e: Exception) {
            Log.e("CameraManager", "Error stopping capturers", e)
        }
    }

    /**
     * Checks if the device supports concurrent camera streaming (API 30+).
     * On API < 30, always returns false.
     */
    fun isConcurrentStreamingSupported(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager
                return manager.concurrentCameraIds.isNotEmpty()
            } catch (e: Exception) {
                Log.e("CameraManager", "Error checking concurrent camera support", e)
            }
        }
        return false
    }

    fun dispose() {
        // Run disposal on a background thread to prevent ANR on main thread
        Thread {
            try {
                stopCapturers()
                backCapturer?.dispose()
                frontCapturer?.dispose()
                backHelper?.dispose()
                frontHelper?.dispose()
                backTrack?.dispose()
                frontTrack?.dispose()
                backSource?.dispose()
                frontSource?.dispose()
            } catch (e: Exception) {
                Log.e("CameraManager", "Disposal error", e)
            } finally {
                backCapturer = null
                frontCapturer = null
                backTrack = null
                frontTrack = null
            }
        }.start()
    }

    fun getBackTrack(): VideoTrack? = backTrack
    fun getFrontTrack(): VideoTrack? = frontTrack
    fun hasBackCamera(): Boolean = backTrack != null
    fun hasFrontCamera(): Boolean = frontTrack != null

    /**
     * Captures a snapshot from the existing WebRTC video track instead of
     * opening a conflicting second Camera2 session. Grabs the next available
     * frame, converts it to JPEG, and returns as base64.
     */
    fun captureSnapshot(useFrontCamera: Boolean, callback: (String) -> Unit) {
        val track = if (useFrontCamera) frontTrack else backTrack
        if (track == null) {
            Log.e("CameraManager", "No ${if (useFrontCamera) "front" else "back"} track available for snapshot")
            return
        }

        val sink = object : VideoSink {
            @Volatile
            var captured = false

            override fun onFrame(frame: VideoFrame) {
                if (captured) return
                captured = true

                // Retain frame so it isn't recycled before we finish processing
                frame.retain()

                try {
                    val buffer = frame.buffer
                    val i420 = buffer.toI420()
                    if (i420 != null) {
                        val width = i420.width
                        val height = i420.height

                        // Convert I420 to NV21 for YuvImage
                        val nv21 = i420ToNv21(i420, width, height)
                        i420.release()

                        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                        val baos = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, baos)
                        val jpegBytes = baos.toByteArray()
                        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                        callback(base64)
                    } else {
                        Log.e("CameraManager", "Failed to convert frame to I420")
                    }
                } catch (e: Exception) {
                    Log.e("CameraManager", "Snapshot frame processing error", e)
                } finally {
                    frame.release()
                    // Remove this one-shot sink from the track on the main thread
                    Handler(Looper.getMainLooper()).post {
                        try { track.removeSink(this) } catch (_: Exception) {}
                    }
                }
            }
        }

        track.addSink(sink)
    }

    /**
     * Converts a WebRTC I420Buffer to NV21 byte array for use with Android's YuvImage.
     */
    private fun i420ToNv21(i420: VideoFrame.I420Buffer, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane
        val yBuffer = i420.dataY
        val yStride = i420.strideY
        for (row in 0 until height) {
            yBuffer.position(row * yStride)
            yBuffer.get(nv21, row * width, width)
        }

        // Interleave V and U planes into NV21 format
        val uBuffer = i420.dataU
        val vBuffer = i420.dataV
        val uStride = i420.strideU
        val vStride = i420.strideV
        val halfWidth = width / 2
        val halfHeight = height / 2

        var offset = ySize
        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                vBuffer.position(row * vStride + col)
                nv21[offset++] = vBuffer.get()
                uBuffer.position(row * uStride + col)
                nv21[offset++] = uBuffer.get()
            }
        }

        return nv21
    }
}
