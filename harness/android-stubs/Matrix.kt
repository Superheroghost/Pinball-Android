package android.opengl

/**
 * Harness-only stand-in for android.opengl.Matrix: the three helpers the
 * camera uses, implemented exactly as the platform does (column-major).
 */
object Matrix {

    fun orthoM(m: FloatArray, mOffset: Int, left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float) {
        if (left == right) throw IllegalArgumentException("left == right")
        if (bottom == top) throw IllegalArgumentException("bottom == top")
        if (near == far) throw IllegalArgumentException("near == far")

        val rWidth = 1.0f / (right - left)
        val rHeight = 1.0f / (top - bottom)
        val rDepth = 1.0f / (far - near)
        val x = 2.0f * rWidth
        val y = 2.0f * rHeight
        val z = -2.0f * rDepth
        val tx = -(right + left) * rWidth
        val ty = -(top + bottom) * rHeight
        val tz = -(far + near) * rDepth
        m[mOffset + 0] = x
        m[mOffset + 1] = 0.0f
        m[mOffset + 2] = 0.0f
        m[mOffset + 3] = 0.0f
        m[mOffset + 4] = 0.0f
        m[mOffset + 5] = y
        m[mOffset + 6] = 0.0f
        m[mOffset + 7] = 0.0f
        m[mOffset + 8] = 0.0f
        m[mOffset + 9] = 0.0f
        m[mOffset + 10] = z
        m[mOffset + 11] = 0.0f
        m[mOffset + 12] = tx
        m[mOffset + 13] = ty
        m[mOffset + 14] = tz
        m[mOffset + 15] = 1.0f
    }

    fun setIdentityM(sm: FloatArray, smOffset: Int) {
        for (i in 0..15) {
            sm[smOffset + i] = 0f
        }
        for (i in 0..15 step 5) {
            sm[smOffset + i] = 1.0f
        }
    }

    /** result = lhs x rhs, matrices stored column-major (as Android does). */
    fun multiplyMM(result: FloatArray, resultOffset: Int, lhs: FloatArray, lhsOffset: Int, rhs: FloatArray, rhsOffset: Int) {
        val tmp = FloatArray(16)
        for (col in 0..3) {
            for (row in 0..3) {
                var sum = 0f
                for (k in 0..3) {
                    sum += lhs[lhsOffset + k * 4 + row] * rhs[rhsOffset + col * 4 + k]
                }
                tmp[col * 4 + row] = sum
            }
        }
        for (i in 0..15) result[resultOffset + i] = tmp[i]
    }
}
