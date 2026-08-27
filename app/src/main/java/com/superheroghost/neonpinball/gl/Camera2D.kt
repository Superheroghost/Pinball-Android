package com.superheroghost.neonpinball.gl

import android.opengl.GLES20
import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.min

/**
 * Orthographic camera mapping table space (metres, +Y up) to clip space with
 * letterboxing-free "fit" scaling. Also carries camera shake and zoom.
 */
class Camera2D {
    /** World-space view rect (computed on update). */
    var worldLeft = 0f
    var worldRight = 0f
    var worldBottom = 0f
    var worldTop = 0f
        private set

    /** Design world rect we must always show (table + margins). */
    var baseLeft = -0.03f
    var baseRight = 0.544f
    var baseBottom = -0.055f
    var baseTop = 1.09f

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val tmpMatrix = FloatArray(16)

    var screenWidth = 1
        private set
    var screenHeight = 1
        private set

    /** Extra zoom (1 = none). */
    var zoom = 1f

    /** Shake offset in metres. */
    var shakeX = 0f
    var shakeY = 0f

    private var shakeTime = 0f
    private var shakeDuration = 0f
    private var shakeMagnitude = 0f
    private var shakeAngle = 0f

    fun onSurfaceChanged(w: Int, h: Int) {
        screenWidth = max(1, w)
        screenHeight = max(1, h)
    }

    /** Trigger a camera shake; magnitude in metres, duration seconds. */
    fun shake(magnitude: Float, duration: Float) {
        if (magnitude <= shakeMagnitude || shakeTime <= 0f) {
            shakeMagnitude = magnitude
            shakeDuration = duration
            shakeTime = duration
            shakeAngle = (Math.random() * Math.PI * 2).toFloat()
        }
    }

    fun update(dt: Float) {
        if (shakeTime > 0f) {
            shakeTime -= dt
            val k = (shakeTime / shakeDuration).coerceIn(0f, 1f)
            val m = shakeMagnitude * k * k
            // Orbit the shake direction so it feels like a physical jolt.
            shakeAngle += dt * 46f
            shakeX = m * kotlin.math.cos(shakeAngle)
            shakeY = m * kotlin.math.sin(shakeAngle) * 0.6f
            if (shakeTime <= 0f) {
                shakeMagnitude = 0f
                shakeX = 0f
                shakeY = 0f
            }
        }
    }

    /** Rebuild matrices; call once per frame before rendering. */
    fun apply() {
        val designW = baseRight - baseLeft
        val designH = baseTop - baseBottom
        val aspect = screenWidth.toFloat() / screenHeight.toFloat()
        val designAspect = designW / designH

        var w = designW
        var h = designH
        if (aspect > designAspect) {
            // Screen wider than design: match height, extend width.
            h = designH
            w = h * aspect
        } else {
            w = designW
            h = w / aspect
        }
        // Slight zoom-out headroom factor.
        w /= zoom
        h /= zoom

        val cx = (baseLeft + baseRight) * 0.5f + shakeX
        val cy = (baseBottom + baseTop) * 0.5f + shakeY

        worldLeft = cx - w * 0.5f
        worldRight = cx + w * 0.5f
        worldBottom = cy - h * 0.5f
        worldTop = cy + h * 0.5f

        // Projection maps world Y-down (we flip Y here so world +Y up works).
        Matrix.orthoM(projMatrix, 0, worldLeft, worldRight, worldBottom, worldTop, -1f, 1f)
        Matrix.setIdentityM(viewMatrix, 0)
        System.arraycopy(projMatrix, 0, viewProj, 0, 16)
        tmpMatrix // unused placeholder to keep import used
        Matrix.multiplyMM(viewProj, 0, projMatrix, 0, viewMatrix, 0)
    }

    fun worldToScreenX(x: Float): Float = (x - worldLeft) / (worldRight - worldLeft) * screenWidth
    fun worldToScreenY(y: Float): Float = (1f - (y - worldBottom) / (worldTop - worldBottom)) * screenHeight

    fun screenToWorldX(sx: Float): Float = worldLeft + sx / screenWidth * (worldRight - worldLeft)
    fun screenToWorldY(sy: Float): Float = worldTop - sy / screenHeight * (worldTop - worldBottom)

    fun viewProjMatrix(): FloatArray = viewProj
}
