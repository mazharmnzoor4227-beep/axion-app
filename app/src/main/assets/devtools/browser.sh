# Browser automation: Chromium (Playwright's build — Ubuntu's own chromium is a snap and can't run here) + puppeteer-core + playwright-core.
export PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright
do_install() {
  command -v node >/dev/null || die "Install Web (Node.js) first — browser automation runs on Node"
  p 5 "Installing puppeteer-core and playwright-core"
  npm install -g --no-fund --no-audit puppeteer-core playwright-core >/dev/null || die "npm install failed"
  p 20 "Installing Chromium's system libraries"
  apt_ready 20
  local nodeglobal; nodeglobal="$(npm root -g)"
  # Same dependency list Playwright uses for this Ubuntu version; fonts so pages render text.
  (cd "$nodeglobal/playwright-core" && node cli.js install-deps chromium) || die "Could not install Chromium's libraries"
  apt_install 50 "Installing fonts" fonts-liberation fonts-noto-color-emoji fonts-noto-core
  p 60 "Downloading Chromium"
  (cd "$nodeglobal/playwright-core" && node cli.js install chromium-headless-shell) || die "Chromium download failed"
  local bin; bin="$(find /opt/ms-playwright -maxdepth 3 -type f \( -name chrome-headless-shell -o -name headless_shell \) -perm -u+x 2>/dev/null | head -1)"
  [ -x "$bin" ] || die "Chromium binary missing after download"
  printf '#!/bin/sh\nexec "%s" --no-sandbox --disable-gpu --disable-dev-shm-usage "$@"\n' "$bin" > /usr/local/bin/chromium
  chmod 755 /usr/local/bin/chromium
  echo "export PUPPETEER_EXECUTABLE_PATH=/usr/local/bin/chromium PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright" > /etc/profile.d/axion-browser.sh
}
do_version() { chromium --version 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -1; }
do_latest() { npm view playwright-core version 2>/dev/null | sed 's/^/playwright /'; }
do_uninstall() { npm uninstall -g puppeteer-core playwright-core >/dev/null 2>&1; rm -rf /opt/ms-playwright /usr/local/bin/chromium /etc/profile.d/axion-browser.sh; }
do_size() { size_of /opt/ms-playwright "$(npm root -g 2>/dev/null)/puppeteer-core" "$(npm root -g 2>/dev/null)/playwright-core"; }
