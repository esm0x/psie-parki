# kotlinx.serialization keeps serializers in companion objects R8 cannot see used; a
# shrunk build would deserialize parks.json into an empty list without an error.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.adminstack.psieparki.**$$serializer { *; }
-keepclassmembers class com.adminstack.psieparki.** { *** Companion; }
-keepclasseswithmembers class com.adminstack.psieparki.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp's optional platform hooks.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
