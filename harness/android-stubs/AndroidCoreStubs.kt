// Harness-only compile-time stand-ins for the android.* classes the app uses.
// They exist so the headless checks (build-app.sh via a stub android.jar, and
// the render harness) can build without an Android SDK. Method bodies are
// no-ops; only the API shapes matter and they mirror the platform signatures.
package android.annotation

annotation class SuppressLint(vararg val value: String)
