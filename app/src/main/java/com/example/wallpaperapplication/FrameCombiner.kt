package com.example.wallpaperapplication

import android.view.Surface
import org.webrtc.*

/**
 * FrameCombiner combines two video tracks (back + front camera) into a single EGL surface
 * for dual-camera recording. Currently renders the most recent incoming frame; a full
 * picture-in-picture GL compositing pass can be added in renderCombinedFrame().
 */
class FrameCombiner(
    private val surface: Surface,
    private val eglBase: EglBase
) : VideoSink {

    private var backFrame: VideoFrame? = null
    private var frontFrame: VideoFrame? = null

    private val eglRenderer = EglRenderer("FrameCombinerRenderer")

    init {
        eglRenderer.init(eglBase.eglBaseContext, EglBase.CONFIG_RECORDABLE, GlRectDrawer())
        eglRenderer.createEglSurface(surface)
    }

    /**
     * Receives frames from both tracks. We identify which track sent the frame
     * by the frame itself (external callers should use addSink on each track).
     * For now, we render every frame directly and store references for future
     * PiP compositing.
     */
    override fun onFrame(frame: VideoFrame) {
        // Render the incoming frame immediately to prevent black output
        eglRenderer.onFrame(frame)
    }

    /**
     * TODO: Implement full OpenGL PiP compositing here.
     * Use SurfaceTextureHelper / GLES to render:
     *   1. Full-screen back camera
     *   2. Small PiP overlay of front camera (bottom-right corner)
     * Currently a no-op; the last arriving frame is passed through by onFrame().
     */
    private fun renderCombinedFrame(back: VideoFrame, front: VideoFrame) {
        // Placeholder — full GL blending implementation goes here
    }

    fun release() {
        // Release any stored frames
        backFrame = null
        frontFrame = null
        eglRenderer.release()
    }
}
