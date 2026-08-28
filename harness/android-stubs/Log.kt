package android.util

/** Harness-only stand-in for android.util.Log; prints to stderr. */
object Log {
    val lines = ArrayList<String>()

    private fun record(level: String, tag: String, msg: String): Int {
        val line = "$level/$tag: $msg"
        lines.add(line)
        System.err.println(line)
        return msg.length
    }

    fun e(tag: String, msg: String): Int = record("E", tag, msg)
    fun w(tag: String, msg: String): Int = record("W", tag, msg)
    fun i(tag: String, msg: String): Int = record("I", tag, msg)
    fun d(tag: String, msg: String): Int = record("D", tag, msg)
    fun v(tag: String, msg: String): Int = record("V", tag, msg)
}
