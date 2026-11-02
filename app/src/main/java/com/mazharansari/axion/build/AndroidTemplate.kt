package com.mazharansari.axion.build

import java.io.File

/**
 * Starting point for "make me an app" projects: the smallest Android project that builds fast on a phone — Kotlin,
 * no AndroidX (no big dependency downloads), one Activity with a View layout. Versions match the APK builder toolchain
 * (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21; compileSdk 36 + build-tools 36.0.0 from the phone's SDK). Antigravity
 * edits it from there. This exact template was built to an APK on the test box (x86 SDK 35, -Paxion.compileSdk=35).
 */
object AndroidTemplate {
    fun pkgOf(slug: String) = "com.axion." + slug.replace(Regex("[^a-z0-9]"), "").ifBlank { "app" }.let { if (it.first().isDigit()) "a$it" else it }.take(30)

    fun files(appName: String, slug: String): Map<String, String> {
        val pkg = pkgOf(slug)
        val esc = appName.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("&", "&amp;").replace("<", "&lt;")
        return mapOf(
            "settings.gradle.kts" to """
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "$slug"
include(":app")
""".trimStart(),
            "build.gradle.kts" to """
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
""".trimStart(),
            "gradle.properties" to "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8\nandroid.useAndroidX=false\nkotlin.code.style=official\nandroid.suppressUnsupportedCompileSdk=36\n",
            "app/build.gradle.kts" to """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val axionCompileSdk = (findProperty("axion.compileSdk") as String?)?.toInt() ?: 36
val axionBuildTools = (findProperty("axion.buildTools") as String?) ?: "36.0.0"

android {
    namespace = "$pkg"
    compileSdk = axionCompileSdk
    buildToolsVersion = axionBuildTools
    defaultConfig {
        applicationId = "$pkg"
        minSdk = 24
        targetSdk = axionCompileSdk
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
""".trimStart(),
            "app/src/main/AndroidManifest.xml" to """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="@string/app_name" android:icon="@drawable/ic_launcher" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart(),
            "app/src/main/res/values/strings.xml" to "<resources>\n    <string name=\"app_name\">$esc</string>\n</resources>\n",
            "app/src/main/res/drawable/ic_launcher.xml" to """
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#111111" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFFFFF" android:pathData="M54,26 L80,82 L68,82 L54,50 L40,82 L28,82 Z" />
</vector>
""".trimStart(),
            "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt" to """
package $pkg

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = getString(R.string.app_name); textSize = 30f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        val sub = TextView(this).apply { text = "Made with Axion"; textSize = 16f; setTextColor(Color.parseColor("#9A9A9A")); gravity = Gravity.CENTER }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.parseColor("#0B0B0B"))
            addView(title); addView(sub)
        })
    }
}
""".trimStart(),
            ".gitignore" to "build/\n.gradle/\nlocal.properties\n",
        )
    }

    fun write(dir: File, appName: String, slug: String) {
        files(appName, slug).forEach { (rel, text) -> File(dir, rel).apply { parentFile?.mkdirs(); if (!exists()) writeText(text) } }
        File(dir, "local.properties").writeText("sdk.dir=/root/android-sdk\n")
    }

    /** Newest APK under app/build/outputs (debug first). */
    fun findApk(dir: File): File? = File(dir, "app/build/outputs/apk").walkTopDown().filter { it.isFile && it.extension == "apk" }.maxByOrNull { it.lastModified() }
}
