# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep class/method names and line numbers so stack traces in crash reports
# (crash/CrashReporter) are readable without a mapping file. The code is open
# source, so obfuscation protects nothing; shrinking and optimisation stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# ONNX Runtime - keep all classes and native JNI bindings so R8 does not
# strip the symbols that onnxruntime-android.aar calls via JNI.
# ---------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
