# Web: Node.js LTS + npm (official nodejs.org build for this CPU), plus pnpm/yarn via corepack.
NODE_FALLBACK=v24.11.1
node_latest() {
  curl -fsSL --connect-timeout 15 https://nodejs.org/dist/index.json 2>/dev/null \
   | tr '{' '\n' | grep '"lts":"' | head -1 | sed -E 's/.*"version":"(v[0-9.]+)".*/\1/'
}
do_install() {
  local v; v="$(node_latest)"; [ -n "$v" ] || v="$NODE_FALLBACK"
  local f="node-$v-linux-$(arch).tar.xz"
  apt_install 5 "Installing Node.js system libraries" libatomic1   # official arm64 builds need it (found in the arm64 test)
  p 10 "Downloading Node.js $v"
  mkdir -p /tmp/axion-dl && fetch "https://nodejs.org/dist/$v/$f" "/tmp/axion-dl/$f" || die "Node.js download failed"
  p 60 "Unpacking Node.js $v"
  rm -rf /opt/node && mkdir -p /opt/node
  tar -xJf "/tmp/axion-dl/$f" -C /opt/node --strip-components=1 || die "Node.js archive is damaged"
  rm -f "/tmp/axion-dl/$f"
  for b in /opt/node/bin/*; do ln -sf "$b" /usr/local/bin/; done
  hash -r
  p 85 "Adding pnpm and yarn"
  if [ -x /opt/node/bin/corepack ]; then corepack enable --install-directory /usr/local/bin >/dev/null 2>&1 || true
  else npm install -g --no-fund --no-audit pnpm yarn >/dev/null 2>&1 && for b in /opt/node/bin/*; do ln -sf "$b" /usr/local/bin/; done; fi
  npm config set fund false >/dev/null 2>&1; npm config set update-notifier false >/dev/null 2>&1
}
do_version() { /opt/node/bin/node --version 2>/dev/null | sed 's/^v//'; }
do_latest() { node_latest | sed 's/^v//'; }
do_uninstall() { rm -rf /opt/node /root/.npm; for b in node npm npx corepack pnpm pnpx yarn yarnpkg; do rm -f /usr/local/bin/$b; done; }
do_size() { size_of /opt/node /root/.npm; }
