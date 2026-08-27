# Default ProGuard rules for Neon Nexus Pinball.
# jBox2D is vendored and contains no reflection, but keep line numbers for
# readable crash traces, and keep the physics library untouched to guarantee
# numerically identical behaviour in release builds.
-keep class org.jbox2d.** { *; }
-keepattributes SourceFile,LineNumberTable

# Kotlin
-dontwarn kotlin.**
