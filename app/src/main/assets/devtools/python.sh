# Python 3 + pip + venv from Ubuntu. pip may install system-wide (it's a private sandbox).
do_install() {
  apt_install 15 "Installing Python, pip and venv" python3 python3-pip python3-venv python3-dev
  mkdir -p /etc/pip && printf '[global]\nbreak-system-packages = true\nroot-user-action = ignore\ndisable-pip-version-check = true\nno-cache-dir = true\n' > /etc/pip.conf
  ln -sf /usr/bin/python3 /usr/local/bin/python
  ln -sf /usr/bin/pip3 /usr/local/bin/pip
}
do_version() { python3 -c 'import sys,pip;print("%d.%d.%d"%sys.version_info[:3])' 2>/dev/null; }
do_latest() { apt-cache policy python3 2>/dev/null | awk '/Candidate/ {print $2}' | cut -d- -f1; }
do_uninstall() { apt_purge python3-pip python3-venv python3-dev; rm -f /usr/local/bin/python /usr/local/bin/pip /etc/pip.conf; rm -rf /root/.cache/pip; }
do_size() { size_of /usr/lib/python3 /usr/lib/python3.* /usr/local/lib/python3.* /root/.cache/pip; }
