# The Rust engine looks this class and method up by name through JNI.
-keep class com.foulfoxhacks.mellozone.core.NativeCore { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization ships its own consumer rules; keep generated
# serializers for the app's models as a belt-and-braces measure.
-keepclassmembers class com.foulfoxhacks.mellozone.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
