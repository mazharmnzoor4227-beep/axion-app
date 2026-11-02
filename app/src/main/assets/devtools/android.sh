# Android (Java/Kotlin): OpenJDK 17 + the Kotlin compiler. (Android SDK + Gradle for APK builds arrive with Build mode.)
KOTLIN_FALLBACK=2.2.21
kotlin_latest() {
  curl -fsSL --connect-timeout 15 https://api.github.com/repos/JetBrains/kotlin/releases/latest 2>/dev/null \
   | grep '"tag_name"' | head -1 | sed -E 's/.*"v([0-9.]+)".*/\1/'
}
do_install() {
  apt_install 10 "Installing Java (OpenJDK 17)" openjdk-17-jdk-headless
  local v; v="$(kotlin_latest)"; [ -n "$v" ] || v="$KOTLIN_FALLBACK"
  p 70 "Downloading Kotlin $v"
  mkdir -p /tmp/axion-dl && fetch "https://github.com/JetBrains/kotlin/releases/download/v$v/kotlin-compiler-$v.zip" /tmp/axion-dl/kotlin.zip || die "Kotlin download failed"
  p 85 "Unpacking Kotlin"
  rm -rf /opt/kotlinc && unzip -q /tmp/axion-dl/kotlin.zip -d /opt && rm -f /tmp/axion-dl/kotlin.zip
  for b in kotlin kotlinc; do ln -sf /opt/kotlinc/bin/$b /usr/local/bin/$b; done
}
do_version() { local j k; j="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9._]+)".*/\1/')"; k="$(cat /opt/kotlinc/build.txt 2>/dev/null | cut -d- -f1)"; [ -n "$j" ] && echo "Java $j · Kotlin ${k:-?}"; }
do_latest() { echo "Kotlin $(kotlin_latest)"; }
do_uninstall() { apt_purge openjdk-17-jdk-headless openjdk-17-jre-headless; rm -rf /opt/kotlinc /usr/local/bin/kotlin /usr/local/bin/kotlinc; }
do_size() { size_of /usr/lib/jvm /opt/kotlinc; }
