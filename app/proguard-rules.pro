# kotlinx.serialization: Serializer-Klassen und Companion-Objekte erhalten
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.shelfwatch.**$$serializer { *; }
-keepclassmembers class dev.shelfwatch.** { *** Companion; }
-keepclasseswithmembers class dev.shelfwatch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
