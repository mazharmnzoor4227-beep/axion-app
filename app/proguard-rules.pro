# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.mazharansari.axion.**$$serializer { *; }
-keepclassmembers class com.mazharansari.axion.** { *** Companion; }
-keepclasseswithmembers class com.mazharansari.axion.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.re2j.**

# v0.3 PDF → skill (pdfbox-android): optional JPEG2000 codec absent; keep fonts/encodings it loads by name.
-dontwarn com.gemalto.jp2.**
# v0.5.3 smaller APK: PDF → skill (pdfbox-android) keeps all its classes, as in v0.3.
# (Narrowing the keep saved ~0.4 MB but couldn't be proven safe for every PDF without a phone, so it stays whole.)
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }

# v0.5.3 Firebase Auth / Credential Manager (their AARs carry their own consumer rules; these are belt and braces)
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.playservices.** { *; }
-dontwarn com.google.android.recaptcha.**

# v0.5 llama.cpp JNI: native method names and the token callback the C++ side calls by name
-keep class com.mazharansari.axion.local.LlamaNative { *; }
-keep interface com.mazharansari.axion.local.LlamaNative$TokenCallback { *; }
-keepclassmembers class * implements com.mazharansari.axion.local.LlamaNative$TokenCallback { boolean onToken(java.lang.String); }

# v0.5.3 security: no debug/info logging in release builds (nothing about the account can reach logcat)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
