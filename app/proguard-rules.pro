# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,EnclosingMethod,InnerClasses

# Preserve ONNX Runtime Android classes, JNI bindings, and tensor reflection
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Preserve Qualcomm QNN Execution Provider classes
-keep class com.qualcomm.qti.** { *; }
-dontwarn com.qualcomm.qti.**

# Preserve native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

