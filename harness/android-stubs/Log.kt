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

    fun e(tag: String, msg: String, tr: Throwable): Int =
        record("E", tag, msg + "\n" + stackTraceToString(tr))
    fun w(tag: String, msg: String, tr: Throwable): Int =
        record("W", tag, msg + "\n" + stackTraceToString(tr))
    fun i(tag: String, msg: String, tr: Throwable): Int =
        record("I", tag, msg + "\n" + stackTraceToString(tr))
    fun d(tag: String, msg: String, tr: Throwable): Int =
        record("D", tag, msg + "\n" + stackTraceToString(tr))
    fun v(tag: String, msg: String, tr: Throwable): Int =
        record("V", tag, msg + "\n" + stackTraceToString(tr))

    private fun stackTraceToString(tr: Throwable): String {
        val sw = java.io.StringWriter()
        tr.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}
