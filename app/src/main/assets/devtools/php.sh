# PHP + Composer. Tries PHP 8.4 (ondrej PPA), falls back to Ubuntu's own PHP.
do_install() {
  local codename; codename="$(. /etc/os-release; echo $VERSION_CODENAME)"
  local pk="php-cli php-mbstring php-xml php-curl php-zip"
  if [ ! -f /etc/apt/sources.list.d/ondrej-php.list ]; then
    p 8 "Adding PHP 8.4 repository"
    apt_install 8 "Preparing" gnupg
    if curl -fsSL --connect-timeout 15 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x14AA40EC0831756756D7F66C4F4EA0AAE5267A6C' | gpg --dearmor > /usr/share/keyrings/ondrej-php.gpg 2>/dev/null \
       && echo "deb [signed-by=/usr/share/keyrings/ondrej-php.gpg] https://ppa.launchpadcontent.net/ondrej/php/ubuntu $codename main" > /etc/apt/sources.list.d/ondrej-php.list; then
      rm -f /var/lib/apt/lists/.axion-updated
    else rm -f /etc/apt/sources.list.d/ondrej-php.list; fi
  fi
  if [ -f /etc/apt/sources.list.d/ondrej-php.list ] && apt_ready 20 && apt-cache show php8.4-cli >/dev/null 2>&1; then pk="php8.4-cli php8.4-mbstring php8.4-xml php8.4-curl php8.4-zip"; fi
  ( apt_install 30 "Installing PHP and common extensions" $pk unzip ) || {
    rm -f /etc/apt/sources.list.d/ondrej-php.list /var/lib/apt/lists/.axion-updated
    apt_install 30 "Installing PHP (Ubuntu repository)" php-cli php-mbstring php-xml php-curl php-zip unzip; }
  p 80 "Installing Composer"
  fetch https://getcomposer.org/download/latest-stable/composer.phar /usr/local/bin/composer && chmod 755 /usr/local/bin/composer
}
do_version() { local v c; v="$(php -r 'echo PHP_VERSION;' 2>/dev/null)"; [ -n "$v" ] || return; c="$(COMPOSER_ALLOW_SUPERUSER=1 composer --version 2>/dev/null | awk '{print $3}')"; echo "$v${c:+ · Composer $c}"; }
do_latest() { apt-cache policy php8.4-cli php-cli 2>/dev/null | awk '/Candidate/ && $2!="(none)" {print $2; exit}' | cut -d- -f1 | sed 's/^[0-9]://'; }
do_uninstall() { apt_purge 'php8.4*' 'php8.3*' php-cli php-common; rm -f /usr/local/bin/composer /etc/apt/sources.list.d/ondrej-php.list; rm -rf /root/.composer /root/.cache/composer; }
do_size() { size_of /usr/lib/php /usr/share/php* /etc/php /usr/bin/php8.* /usr/local/bin/composer; }
