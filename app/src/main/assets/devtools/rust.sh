# Rust: rustup with the minimal profile (rustc + cargo + std). Needs a C linker → installs gcc too.
do_install() {
  apt_install 10 "Installing the C linker Rust needs" gcc libc6-dev
  p 30 "Downloading rustup"
  curl -fsSL --connect-timeout 20 https://sh.rustup.rs -o /tmp/rustup.sh || die "rustup download failed"
  p 40 "Installing Rust (minimal)"
  RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo sh /tmp/rustup.sh -y --profile minimal --no-modify-path >/dev/null 2>&1 || die "Rust install failed"
  rm -f /tmp/rustup.sh
  for b in /opt/cargo/bin/*; do ln -sf "$b" /usr/local/bin/; done
  echo 'export RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo' > /etc/profile.d/axion-rust.sh
}
do_version() { RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo /opt/cargo/bin/rustc --version 2>/dev/null | awk '{print $2}'; }
do_latest() { echo stable; }
do_uninstall() { rm -rf /opt/rustup /opt/cargo /etc/profile.d/axion-rust.sh; for b in cargo rustc rustup rustdoc rust-gdb rust-lldb cargo-fmt cargo-clippy rustfmt clippy-driver; do rm -f /usr/local/bin/$b; done; }
do_size() { size_of /opt/rustup /opt/cargo; }
