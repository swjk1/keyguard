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
