# Base tools every toolchain needs (installed once, right after the Ubuntu download).
do_install() {
  # v0.5.2: required tools first; procps/less/nano are optional and may be skipped
  apt_install 10 "Installing base tools (certificates, curl, git)" ca-certificates curl git xz-utils unzip
  p 80 "Installing small extras (procps, less, nano)"
  apt_optional procps less nano
  git config --system init.defaultBranch main 2>/dev/null || true
}
do_version() { git --version 2>/dev/null | awk '{print $3}'; }
do_latest() { apt-cache policy git 2>/dev/null | awk '/Candidate/ {print $2}'; }
do_uninstall() { :; }
do_size() { echo 0; }
