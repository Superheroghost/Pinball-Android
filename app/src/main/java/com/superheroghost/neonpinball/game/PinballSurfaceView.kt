package com.superheroghost.neonpinball.game

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/**
 * GL surface with pinball-optimised multitouch handling. Touch events are
 * translated to [InputState] flags immediately on ACTION_DOWN; the game thread
 * consumes them at its own rate, so input is never coupled to frame rate.
 */
class PinballSurfaceView(
    context: Context,
    val input: InputState,
) : GLSurfaceView(context) {
    var rendererRef: PinballRenderer? = null

    /** Plunger drag tracking. */
    private var plungerPointerId = -1
    private var plungerStartY = 0f
    private var plungerStartPull = 0f

    /** Flipper pointer tracking: two slots. */
    private val leftPointers = HashSet<Int>(4)
    private val rightPointers = HashSet<Int>(4)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i) / width.coerceAtLeast(1)
                val y = event.getY(i) / height.coerceAtLeast(1)
                when (input.onTouchZones(x, y)) {
                    InputState.Zone.LEFT -> leftPointers.add(event.getPointerId(i))
                    InputState.Zone.RIGHT -> rightPointers.add(event.getPointerId(i))
                    InputState.Zone.PLUNGER -> {
                        plungerPointerId = event.getPointerId(i)
                        plungerStartY = event.getY(i)
                        plungerStartPull = input.plungerPull
                        input.plungerHeld = true
                    }
                }
                syncFlags()
            }
            MotionEvent.ACTION_MOVE -> {
                if (plungerPointerId != -1) {
                    val i = event.findPointerIndex(plungerPointerId)
                    if (i >= 0) {
                        val dy = (event.getY(i) - plungerStartY) / height.coerceAtLeast(1)
                        input.plungerPull = (plungerStartPull + dy * 2.6f).coerceIn(0f, 1f)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                leftPointers.remove(id)
                rightPointers.remove(id)
                if (id == plungerPointerId) {
                    plungerPointerId = -1
                    input.plungerHeld = false
                }
                syncFlags()
            }
            MotionEvent.ACTION_CANCEL -> {
                leftPointers.clear()
                rightPointers.clear()
                plungerPointerId = -1
                input.plungerHeld = false
                syncFlags()
            }
        }
        return true
    }

    private fun syncFlags() {
        input.leftFlipper = leftPointers.isNotEmpty()
        input.rightFlipper = rightPointers.isNotEmpty()
    }

    /** Release everything (used on pause so flippers don't stick). */
    fun releaseAll() {
        leftPointers.clear()
        rightPointers.clear()
        plungerPointerId = -1
        input.leftFlipper = false
        input.rightFlipper = false
        input.plungerHeld = false
    }
}
