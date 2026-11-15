# Release shrinking rules. Libraries ship their own consumer rules; only
# app-specific additions belong here.

# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class app.scanova.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OpenCV and SQLCipher call back into Java from native code.
-keep class org.opencv.** { *; }
-keep class net.zetetic.database.** { *; }
