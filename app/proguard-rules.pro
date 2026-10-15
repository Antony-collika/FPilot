# Default ProGuard rules
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable *; }

# SLF4J's optional JVM binding is not bundled on Android; logging can use its fallback.
-dontwarn org.slf4j.impl.StaticLoggerBinder
