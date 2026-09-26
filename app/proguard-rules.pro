# The rule pack is deserialized reflectively by kotlinx.serialization, so the generated
# serializers and the data classes they describe must survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.keyguard.detect.** {
    *** Companion;
}
-keepclasseswithmembers class com.keyguard.detect.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.keyguard.detect.**$$serializer { *; }

# The IME service and its activities are entry points named from the manifest.
-keep class com.keyguard.app.KeyguardInputMethodService { *; }
-keep class com.keyguard.app.SetupActivity { *; }
-keep class com.keyguard.app.OnboardingActivity { *; }

# The model contract, thresholds and rule table are deserialized the same way, so the
# :infer serializers need the same treatment as :detect's.
-keepclassmembers class com.keyguard.infer.** {
    *** Companion;
}
-keepclasseswithmembers class com.keyguard.infer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.keyguard.infer.**$$serializer { *; }

# ONNX Runtime is a JNI library: the native side looks its Java classes up by name and
# constructs them reflectively, so nothing here can be renamed or stripped. This failure
# only appears in a release build — the one nobody runs until the end — and it surfaces as
# an UnsatisfiedLinkError or a null tensor rather than as anything that names the cause.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames class ai.onnxruntime.** {
    native <methods>;
}
-dontwarn ai.onnxruntime.**
