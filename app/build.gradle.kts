import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")   // v0.5.3: reads app/google-services.json (axion-d5461). No Crashlytics, no FCM.
}

// Signing secrets live outside the source tree (../keys/keystore.properties) or AXION_KEYSTORE_PROPERTIES.
val keyPropsFile: File = (System.getenv("AXION_KEYSTORE_PROPERTIES")?.let { File(it) } ?: rootProject.file("../keys/keystore.properties"))
val keyProps = Properties().apply { if (keyPropsFile.exists()) keyPropsFile.inputStream().use { load(it) } }

android {
    namespace = "com.mazharansari.axion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mazharansari.axion"
        minSdk = 26
        targetSdk = 28   // v0.4: PRoot must exec Ubuntu binaries from app storage (W^X applies from 29), same as Kodex
        versionCode = 32
        versionName = "0.8.9"
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")   // v0.5.3: all UI text is English; drops library translations (smaller APK)
        ndk { abiFilters += "arm64-v8a" }
    }
    ndkVersion = "26.1.10909125"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }

    signingConfigs {
        create("release") {
            if (keyProps.getProperty("storeFile") != null) {
                storeFile = keyPropsFile.parentFile.resolve(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keyProps.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // v0.5.3 smaller APK: BouncyCastle (pulled in by pdfbox-android for encrypted PDFs) ships 8 MB of post-quantum
            // lookup tables (picnic/sike .properties) and German messages as Java resources. Axion never uses them.
            excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties", "META-INF/versions/9/OSGI-INF/**",
                "META-INF/*.version", "META-INF/**/*.kotlin_module.bak", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
        }
        jniLibs {
            useLegacyPackaging = true   // libproot.so etc. must be extracted to nativeLibraryDir so they can be exec'd
            // v0.5.3 smaller APK: keep 3 of llama.cpp's 7 CPU variants — baseline armv8.0, armv8.2 dotprod+fp16, armv8.6 i8mm.
            // ggml still picks the best one the phone supports; SVE/SME (v9) builds were not faster on phones.
            excludes += listOf("**/libggml-cpu-android_armv8.2_1.so", "**/libggml-cpu-android_armv9.0_1.so",
                "**/libggml-cpu-android_armv9.2_1.so", "**/libggml-cpu-android_armv9.2_2.so")
        }   // libproot.so etc. must be extracted to nativeLibraryDir so they can be exec'd
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true; isReturnDefaultValues = true
            all { t ->
                t.systemProperty("roborazzi.test.record", "true")
                t.systemProperty("robolectric.graphicsMode", "NATIVE")
                t.maxHeapSize = "3g"
                // v0.4 renders: one JVM per test class run with -PforkEach (Robolectric kept earlier tests' windows alive)
                if (project.hasProperty("forkEach")) { t.forkEvery = 1; t.maxParallelForks = 4 }
                t.testLogging { showStandardStreams = true; events("passed", "failed", "skipped") }
            }
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

// v0.5.3 smaller APK: pdfbox-android ships 3.3 MB of Chinese/Japanese/Korean predefined CMaps. Axion only reads PDFs into
// Skills; Identity-H/V (all modern embedded fonts) stay, so only old non-embedded CJK PDFs lose text. Removed after the merge.
val cjkCmaps = Regex("^(Adobe-(CNS1|GB1|Japan1|Japan2|Korea1)-.*|Uni(CNS|GB|JIS|KS)-.*|.*-RKSJ-.*|83pv-.*|90.*|Add-.*|B5pc-.*|CNS-EUC-.*|ETen.*|EUC-[HV]|Ext-.*|GB.*|HKscs-.*|KSC.*|[HV])$")
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        afterEvaluate {
            tasks.findByName("merge${name}Assets")?.doLast {
                outputs.files.forEach { out ->
                    out.walkTopDown().filter { it.isFile && it.parentFile?.name == "cmap" && it.path.contains("fontbox") && cjkCmaps.matches(it.name) }.forEach { it.delete() }
                }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("dev.chrisbanes.haze:haze-android:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")   // v0.3: PDF → skill text
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.apache.commons:commons-compress:1.27.1")   // v0.4: Ubuntu base .tar.gz
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // v0.12 scheduler: WorkManager wakes the app for scheduled tasks (inexact on purpose — no exact-alarm permission).
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // v0.5.3 sign-in: Firebase Authentication only (Google via Credential Manager, Email/Password, GitHub provider).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.40.1")
}
