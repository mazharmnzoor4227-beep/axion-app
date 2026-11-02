#!/bin/bash
# Axion developer tools — shared helpers. Runs INSIDE the PRoot Ubuntu guest as root.
# Protocol read by the app (DevTools.kt): "@@P <0-100> <message>", "@@V <version>", "@@L <latest>", "@@S <kilobytes>".
set -o pipefail
export DEBIAN_FRONTEND=noninteractive
export LANG=C.UTF-8
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
MARK=/opt/axion/state
mkdir -p "$MARK"
# PRoot fakes root; apt's _apt privilege drop then breaks gpgv ("NO_PUBKEY"). Run apt's helpers as root, like proot-distro does.
# v0.5.2 (real-phone failure "Could not install: ca-certificates curl git …"): retries + timeouts for mobile data, no prompts from dpkg.
cat > /etc/apt/apt.conf.d/99axion <<'EOF'
APT::Sandbox::User "root";
APT::Sandbox::Seccomp "false";
APT::Install-Recommends "0";
Dpkg::Use-Pty "0";
Acquire::Languages "none";
Acquire::Retries "5";
Acquire::http::Timeout "60";
Acquire::https::Timeout "60";
Dpkg::Options { "--force-confdef"; "--force-confnew"; };
EOF
p() { echo "@@P $1 $2"; }
die() { echo "ERROR: $*" >&2; exit 1; }
arch() { case "$(uname -m)" in aarch64|arm64) echo arm64;; x86_64|amd64) echo x64;; *) uname -m;; esac; }
debarch() { dpkg --print-architecture 2>/dev/null || echo arm64; }
# v0.5.2 (real phone: "Bad system call apt-get"): apt's own seccomp sandbox off, on every call, besides the conf file above.
apt_get() { apt-get -o APT::Sandbox::Seccomp=false -o APT::Sandbox::User=root -o DPkg::Lock::Timeout=120 "$@"; }

# Package mirrors, tried in order. https needs a CA bundle: the app writes Android's system CAs to
# /etc/ssl/certs/ca-certificates.crt before ca-certificates itself is installed. Plain http (last) can be broken by
# carrier proxies, so it is only the last resort.
AXION_MIRRORS="${AXION_MIRRORS:-https://ports.ubuntu.com/ubuntu-ports https://mirror.sg.gs/ubuntu-ports https://mirrors.ustc.edu.cn/ubuntu-ports https://mirrors.aliyun.com/ubuntu-ports http://ports.ubuntu.com/ubuntu-ports}"
use_mirror() {   # point apt at one mirror (deb822 file Ubuntu 24.04 ships, plus the old sources.list if present)
  local m="$1"
  if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    sed -i -E "s#^URIs:.*#URIs: $m/#" /etc/apt/sources.list.d/ubuntu.sources
  else
    printf 'deb %s/ noble main restricted universe multiverse\ndeb %s/ noble-updates main restricted universe multiverse\ndeb %s/ noble-security main restricted universe multiverse\n' "$m" "$m" "$m" > /etc/apt/sources.list
  fi
  echo "$m" > /var/lib/apt/.axion-mirror
}
net_report() {   # what the log needs when the network is the problem
  echo "--- network check ---"
  echo "resolv.conf: $(grep -h nameserver /etc/resolv.conf 2>/dev/null | tr '\n' ' ')"
  for h in ports.ubuntu.com mirror.sg.gs; do echo "lookup $h: $(getent hosts $h 2>/dev/null | head -1 || true)"; done
  command -v curl >/dev/null && curl -sS -o /dev/null -m 15 -w 'https test: %{http_code}\n' https://ports.ubuntu.com/ 2>&1 | tail -1
  echo "---------------------"
}
lists_ok() { ls /var/lib/apt/lists/ 2>/dev/null | grep -q '_noble_main_binary-.*_Packages'; }
apt_ready() {
  if [ -f /var/lib/apt/lists/.axion-updated ] && lists_ok && [ -z "$(find /var/lib/apt/lists/.axion-updated -mmin +720 2>/dev/null)" ]; then return 0; fi
  p "${1:-5}" "Refreshing package list"
  dpkg --configure -a >/dev/null 2>&1 || true
  rm -f /var/lib/apt/lists/.axion-updated
  local tried="" m host
  # the mirror that worked last time goes first
  local last; last="$(cat /var/lib/apt/.axion-mirror 2>/dev/null)"
  for m in $last $AXION_MIRRORS; do
    case " $tried " in *" $m "*) continue;; esac
    tried="$tried $m"
    case "$m" in https:*) [ -s /etc/ssl/certs/ca-certificates.crt ] || { echo "skip $m (no CA bundle yet)"; continue; };; esac
    host="${m#*://}"; host="${host%%/*}"
    getent hosts "$host" >/dev/null 2>&1 || { echo "skip $m (cannot resolve $host)"; continue; }
    use_mirror "$m"
    echo "apt-get update from $m"
    # --error-on=any: a failed download is an error (plain apt-get update exits 0 and the install fails later with
    # "Unable to locate package" — exactly the real-phone report). Two tries per mirror.
    # quick try per mirror (a dead mirror costs ~40 s, not minutes); full retries only in the last pass below
    if apt_get -o Acquire::Retries=1 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 update --error-on=any && lists_ok; then
      touch /var/lib/apt/lists/.axion-updated; return 0
    fi
    echo "mirror $m failed, trying the next one"
  done
  # last pass: the first usable mirror again, patiently (slow mobile data)
  for m in $AXION_MIRRORS; do
    case "$m" in https:*) [ -s /etc/ssl/certs/ca-certificates.crt ] || continue;; esac
    host="${m#*://}"; host="${host%%/*}"; getent hosts "$host" >/dev/null 2>&1 || continue
    use_mirror "$m"; echo "apt-get update from $m (patient retry)"
    if apt_get update --error-on=any && lists_ok; then touch /var/lib/apt/lists/.axion-updated; return 0; fi
    break
  done
  net_report
  die "Could not reach the Ubuntu package servers (internet or DNS problem inside Linux). Tap View log for details, then Retry."
}
apt_install() {   # apt_install <progress> <message> pkgs...   (all required)
  local at="$1" msg="$2"; shift 2
  apt_ready "$at"
  p "$at" "$msg"
  if apt_get install -y --no-install-recommends "$@"; then apt-get clean; return 0; fi
  # one at a time, so one bad package doesn't take the rest down
  sleep 2; dpkg --configure -a || true; apt_get -f install -y || true
  local failed="" x
  for x in "$@"; do apt_get install -y --no-install-recommends "$x" || { sleep 2; apt_get install -y --no-install-recommends "$x"; } || failed="$failed $x"; done
  apt-get clean
  [ -z "$failed" ] || die "Could not install:$failed. Tap View log for the exact error, then Retry."
}
apt_optional() {   # apt_optional pkgs...   (nice to have: a failure is logged and skipped)
  local x
  for x in "$@"; do apt_get install -y --no-install-recommends "$x" || echo "Skipped optional package $x (install failed)"; done
  apt-get clean
}
apt_purge() { apt_get purge -y "$@" >/dev/null 2>&1 || true; apt_get autoremove -y >/dev/null 2>&1 || true; apt-get clean; }
# fetch <url> <out>  — curl with resume + retries.
fetch() { curl -fL --retry 4 --retry-delay 2 -C - --connect-timeout 20 -o "$2" "$1" || curl -fL --retry 2 -o "$2" "$1"; }
size_of() { du -sk "$@" 2>/dev/null | awk '{s+=$1} END {print s+0}'; }
ver_cmp_newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$2" ]; }
