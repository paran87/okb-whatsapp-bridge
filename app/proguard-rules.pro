# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.okb.whatsappbridge.**$$serializer { *; }
-keepclassmembers class com.okb.whatsappbridge.** { *** Companion; }
-keepclasseswithmembers class com.okb.whatsappbridge.** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager instantiates workers through our WorkerFactory by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
