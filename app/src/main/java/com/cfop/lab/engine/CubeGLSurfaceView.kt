package com.cfop.lab.engine

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

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
                isScaling = true
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoomByFactor(detector.scaleFactor)
                return true
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
                renderer.resetCamera()
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prevX = event.x
                prevY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && event.pointerCount == 1) {
                    val dx = event.x - prevX
                    val dy = event.y - prevY
                    renderer.rotateByDrag(dx, dy)
                }
                prevX = event.x
                prevY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remain = if (event.actionIndex == 0) 1 else 0
                if (remain < event.pointerCount) {
                    prevX = event.getX(remain)
                    prevY = event.getY(remain)
                }
            }
        }
        return true
    }
}
