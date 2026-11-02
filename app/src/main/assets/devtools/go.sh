# Go: official tarball from go.dev for this CPU.
GO_FALLBACK=go1.25.3
go_latest() { curl -fsSL --connect-timeout 15 'https://go.dev/VERSION?m=text' 2>/dev/null | head -1; }
do_install() {
  local v; v="$(go_latest)"; [ -n "$v" ] || v="$GO_FALLBACK"
  local a; a="$(arch)"; [ "$a" = "x64" ] && a=amd64
  p 10 "Downloading $v"
  mkdir -p /tmp/axion-dl && fetch "https://go.dev/dl/$v.linux-$a.tar.gz" /tmp/axion-dl/go.tgz || die "Go download failed"
  p 70 "Unpacking Go"
  rm -rf /usr/local/go && tar -xzf /tmp/axion-dl/go.tgz -C /usr/local && rm -f /tmp/axion-dl/go.tgz
  ln -sf /usr/local/go/bin/go /usr/local/bin/go; ln -sf /usr/local/go/bin/gofmt /usr/local/bin/gofmt
}
do_version() { /usr/local/go/bin/go version 2>/dev/null | awk '{print $3}' | sed 's/^go//'; }
do_latest() { go_latest | sed 's/^go//'; }
do_uninstall() { rm -rf /usr/local/go /usr/local/bin/go /usr/local/bin/gofmt /root/go; }
do_size() { size_of /usr/local/go /root/go; }
