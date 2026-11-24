package com.cfop.lab.engine

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

@SuppressLint("ViewConstructor")
class CubeGLSurfaceView(
    context: Context,
    val renderer: CubeGLRenderer
) : GLSurfaceView(context) {

    private var prevX = 0f
    private var prevY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true; return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoomByFactor(detector.scaleFactor); return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                renderer.resetCamera(); return true
            }
        }
    )

    init {
        setEGLContextClientVersion(3)
        // 优先启用 4x MSAA 硬件抗锯齿，回退标准 8888+16bit 深度缓冲
        setEGLConfigChooser(MsaaConfigChooser())
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prevX = event.x; prevY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.pointerCount == 1) {
                    renderer.rotateByDrag(event.x - prevX, event.y - prevY)
                }
                prevX = event.x; prevY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remain = if (event.actionIndex == 0) 1 else 0
                if (remain < event.pointerCount) {
                    prevX = event.getX(remain); prevY = event.getY(remain)
                }
            }
        }
        return true
    }

    private class MsaaConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val msaaAttribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, msaaAttribs, configs, 1, num) && num[0] > 0 && configs[0] != null) {
                return configs[0]!!
            }
            val fallback = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE
            )
            egl.eglChooseConfig(display, fallback, configs, 1, num)
            return configs[0]!!
        }
    }
}
