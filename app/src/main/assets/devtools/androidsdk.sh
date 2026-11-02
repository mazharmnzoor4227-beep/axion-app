# Android APK builder: Android SDK (arm64 build tools — aapt2, d8, zipalign, apksigner — from the same bundle Kodex uses)
# + Gradle 8.11.1 (official). Java 17 comes from the "android" toolchain. Maven libraries download on the first build.
SDK_URL=https://appdevforall.org/dev-assets/debug/android-sdk-arm64-v8a.zip     # 77 MB, platform 36 + build-tools 36.0.0 (arm64)
GRADLE_V=8.11.1
GRADLE_URL=https://services.gradle.org/distributions/gradle-$GRADLE_V-bin.zip   # 130 MB
do_install() {
  command -v java >/dev/null || die "Install Android (Java/Kotlin) first — APK builds need Java 17"
  mkdir -p /tmp/axion-dl
  if [ "$(arch)" = "arm64" ]; then
    p 5 "Downloading Android SDK (77 MB)"
    fetch "$SDK_URL" /tmp/axion-dl/sdk.zip || die "Android SDK download failed"
    p 35 "Unpacking Android SDK"
    rm -rf /root/android-sdk && mkdir -p /root/android-sdk && unzip -q /tmp/axion-dl/sdk.zip -d /root/android-sdk || die "Android SDK archive is damaged"
    rm -f /tmp/axion-dl/sdk.zip
    [ -f /root/android-sdk/platforms/android-36/android.jar ] || die "Android SDK is missing android.jar"
    chmod -R 755 /root/android-sdk/build-tools /root/android-sdk/platform-tools 2>/dev/null
  else
    p 5 "x86 test box: Android SDK must be provided at /root/android-sdk"
    [ -d /root/android-sdk/platforms ] || die "No Android SDK for this CPU"
  fi
  p 50 "Downloading Gradle $GRADLE_V (130 MB)"
  fetch "$GRADLE_URL" /tmp/axion-dl/gradle.zip || die "Gradle download failed"
  p 80 "Unpacking Gradle"
  rm -rf /opt/gradle && mkdir -p /opt/gradle && unzip -q /tmp/axion-dl/gradle.zip -d /opt/gradle && rm -f /tmp/axion-dl/gradle.zip
  ln -sf /opt/gradle/gradle-$GRADLE_V/bin/gradle /usr/local/bin/gradle
  bt="$(ls -d /root/android-sdk/build-tools/* 2>/dev/null | sort -V | tail -1)"
  mkdir -p /root/.gradle
  {
    echo "org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8"
    echo "org.gradle.daemon=true"
    echo "org.gradle.workers.max=2"
    echo "android.useAndroidX=true"
    echo "android.suppressUnsupportedCompileSdk=36"
    [ "$(arch)" = "arm64" ] && echo "android.aapt2FromMavenOverride=$bt/aapt2"
  } > /root/.gradle/gradle.properties
  printf 'export ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk GRADLE_USER_HOME=/root/.gradle\nexport PATH=/opt/gradle/gradle-%s/bin:%s:$PATH\n' "$GRADLE_V" "$bt" > /etc/profile.d/axion-android.sh
  echo "sdk.dir=/root/android-sdk" > /root/android-sdk/.local.properties
}
do_version() { [ -x /opt/gradle/gradle-$GRADLE_V/bin/gradle ] && [ -d /root/android-sdk/platforms ] && echo "SDK $(ls /root/android-sdk/platforms | sed 's/android-//' | sort -n | tail -1) · Gradle $GRADLE_V"; }
do_latest() { echo "Gradle $GRADLE_V"; }
do_uninstall() { rm -rf /root/android-sdk /opt/gradle /root/.gradle /usr/local/bin/gradle /etc/profile.d/axion-android.sh; }
do_size() { size_of /root/android-sdk /opt/gradle /root/.gradle; }
