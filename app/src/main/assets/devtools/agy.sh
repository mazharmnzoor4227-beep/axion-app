# Antigravity CLI (agy), Google's official Linux build, pinned to the version Kodex runs (1.1.27).
AGY_VERSION=1.1.27
agy_url() { case "$(arch)" in
  arm64) echo "https://storage.googleapis.com/antigravity-public/antigravity-cli/1.1.27-5211191891591168/linux-arm/cli_linux_arm64.tar.gz ed45f6930785aa4b42f14e07ace1c9d91a94fb76e760f54acbd7d3d3951e1f957fd456a0dae2a3124dd9a3b689bf7afb7c9303a3e4ba95037fc10063424d9bf9" ;;
  *) echo "${AGY_URL_X64:-} ${AGY_SHA512_X64:-}" ;;
esac; }
do_install() {
  set -- $(agy_url); local url="$1" sum="$2"
  [ -n "$url" ] || die "No Antigravity build for $(uname -m)"
  p 10 "Downloading Antigravity CLI $AGY_VERSION"
  mkdir -p /tmp/axion-dl && fetch "$url" /tmp/axion-dl/agy.tgz || die "Antigravity download failed"
  if [ -n "$sum" ]; then p 70 "Checking download"; echo "$sum  /tmp/axion-dl/agy.tgz" | sha512sum -c - >/dev/null || { rm -f /tmp/axion-dl/agy.tgz; die "Antigravity download is damaged (checksum)"; }; fi
  p 80 "Unpacking"
  mkdir -p /tmp/axion-dl/agy /root/.local/bin && tar -xzf /tmp/axion-dl/agy.tgz -C /tmp/axion-dl/agy || die "Antigravity archive is damaged"
  local b; b="$(find /tmp/axion-dl/agy -type f \( -name agy -o -name antigravity -o -name 'cli*' \) -perm -u+x | head -1)"
  [ -n "$b" ] || b="$(find /tmp/axion-dl/agy -type f -perm -u+x | head -1)"
  [ -n "$b" ] || die "agy binary not found in the archive"
  install -m 755 "$b" /root/.local/bin/agy && ln -sf /root/.local/bin/agy /usr/local/bin/agy
  echo "$AGY_VERSION" > /root/.local/bin/.axion-agy-version
  rm -rf /tmp/axion-dl/agy /tmp/axion-dl/agy.tgz
  mkdir -p /root/.gemini/antigravity-cli
}
do_version() { [ -x /root/.local/bin/agy ] && (/root/.local/bin/agy --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || cat /root/.local/bin/.axion-agy-version); }
do_latest() { curl -fsSL --connect-timeout 15 https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json 2>/dev/null | grep -oE '"version" *: *"[^"]+"' | head -1 | sed -E 's/.*"([^"]+)"$/\1/'; }
do_uninstall() { rm -f /root/.local/bin/agy /usr/local/bin/agy /root/.local/bin/.axion-agy-version; }
do_size() { size_of /root/.local/bin/agy; }
