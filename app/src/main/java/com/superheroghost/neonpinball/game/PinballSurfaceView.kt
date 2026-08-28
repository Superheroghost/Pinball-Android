package com.superheroghost.neonpinball.game

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent

/**
 * GL surface with pinball-optimised multitouch handling. Touch events are
 * translated to [InputState] flags immediately on ACTION_DOWN; the game thread
 * consumes them at its own rate, so input is never coupled to frame rate.
 *
 * Flippers only: the plunger is driven by the HUD's LAUNCH button.
 */
class PinballSurfaceView(
    context: Context,
    val input: InputState,
) : GLSurfaceView(context) {
    var rendererRef: PinballRenderer? = null

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
                }
                syncFlags()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                leftPointers.remove(id)
                rightPointers.remove(id)
                syncFlags()
            }
            MotionEvent.ACTION_CANCEL -> {
                leftPointers.clear()
                rightPointers.clear()
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
        input.leftFlipper = false
        input.rightFlipper = false
        input.plungerHeld = false
    }
}
