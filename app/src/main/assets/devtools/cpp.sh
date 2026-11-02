# C/C++: gcc, g++, make, cmake.
do_install() { apt_install 10 "Installing C/C++ compilers and build tools" build-essential cmake; }
do_version() { gcc -dumpfullversion 2>/dev/null; }
do_latest() { apt-cache policy gcc 2>/dev/null | awk '/Candidate/ {print $2}'; }
do_uninstall() { apt_purge build-essential cmake g++ gcc make cpp; }
do_size() { size_of /usr/lib/gcc /usr/libexec/gcc /usr/include /usr/share/cmake-*; }
