#!/bin/sh
# Install as root-owned /usr/local/sbin/campus-reload-nginx, mode 0755.
# Certbot runs this deploy hook only after successfully renewing a certificate.
set -eu
nginx -t
systemctl reload nginx
