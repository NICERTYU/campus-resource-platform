"""Guard the public TLS edge and execute the certificate reload hook in isolation."""
import configparser
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HTTPS = ROOT / "deploy/https"


def blocks(config, directive):
    """Read balanced Nginx blocks, including nested location directives."""
    result = []
    for match in re.finditer(r"(?m)^\s*" + directive + r"\s*\{", config):
        start = match.end()
        depth = 1
        for offset in range(start, len(config)):
            if config[offset] == "{":
                depth += 1
            elif config[offset] == "}":
                depth -= 1
                if depth == 0:
                    result.append(config[start:offset])
                    break
        else:
            raise AssertionError("Unclosed Nginx block")
    return result


class HttpsDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.config = (HTTPS / "nginx.conf.template").read_text()
        self.servers = blocks(self.config, "server")

    def test_http_serves_only_challenges_and_fixed_https_redirects(self):
        self.assertEqual(len(self.servers), 2)
        http = self.servers[0]
        self.assertIn("listen 80 default_server;", http)
        challenge = blocks(http, r"location \^~ /\.well-known/acme-challenge/")
        self.assertEqual(len(challenge), 1)
        self.assertIn("root /var/lib/letsencrypt;", challenge[0])
        self.assertIn("try_files $uri =404;", challenge[0])
        redirect = blocks(http, r"location /")
        self.assertEqual(len(redirect), 1)
        self.assertIn("return 308 https://${PUBLIC_HOST}$request_uri;", redirect[0])
        self.assertNotIn("proxy_pass", http)
        self.assertNotIn("$host", http, "Redirect destinations must not trust Host headers")

    def test_https_uses_managed_certificate_and_modern_protocols(self):
        tls = self.servers[1]
        self.assertIn("listen 443 ssl default_server;", tls)
        self.assertIn("ssl_certificate /etc/letsencrypt/live/${ACME_CERT_NAME}/fullchain.pem;", tls)
        self.assertIn("ssl_certificate_key /etc/letsencrypt/live/${ACME_CERT_NAME}/privkey.pem;", tls)
        self.assertRegex(tls, r"ssl_protocols\s+TLSv1\.2\s+TLSv1\.3\s*;")

    def test_tls_edge_overwrites_untrusted_proxy_headers_and_uses_loopback(self):
        tls = self.servers[1]
        self.assertIn("proxy_pass http://127.0.0.1:8080;", tls)
        for header, value in (("Host", "${PUBLIC_HOST}"),
                              ("X-Forwarded-Host", "${PUBLIC_HOST}"),
                              ("X-Forwarded-Proto", "https"),
                              ("X-Forwarded-Port", "443"),
                              ("X-Real-IP", "$remote_addr"),
                              ("X-Forwarded-For", "$remote_addr")):
            with self.subTest(header=header):
                self.assertIn("proxy_set_header " + header + " " + value + ";", tls)
        self.assertNotIn("$http_x_forwarded", tls)
        self.assertNotIn("$proxy_add_x_forwarded_for", tls)

    def test_template_has_only_operator_supplied_host_and_certificate_name(self):
        self.assertEqual(set(re.findall(r"\$\{([^}]+)\}", self.config)),
                         {"PUBLIC_HOST", "ACME_CERT_NAME"})
        self.assertNotRegex(self.config, r"https://\d+\.\d+\.\d+\.\d+")

    def test_renewal_uses_deploy_hook_and_persistent_six_hour_timer(self):
        service = configparser.ConfigParser(interpolation=None)
        service.read(HTTPS / "campus-certbot-renew.service")
        self.assertEqual(service["Service"]["Type"], "oneshot")
        self.assertEqual(service["Service"]["ExecStart"],
                         "/opt/certbot/bin/certbot renew --quiet "
                         "--deploy-hook /usr/local/sbin/campus-reload-nginx")
        self.assertNotIn("--force-renewal", service["Service"]["ExecStart"])
        timer = configparser.ConfigParser(interpolation=None)
        timer.read(HTTPS / "campus-certbot-renew.timer")
        self.assertEqual(timer["Timer"]["OnCalendar"], "*-*-* 00,06,12,18:00:00")
        self.assertEqual(timer["Timer"]["Unit"], "campus-certbot-renew.service")
        self.assertTrue(timer["Timer"].getboolean("Persistent"))
        self.assertEqual(timer["Timer"]["RandomizedDelaySec"], "10m")
        self.assertEqual(timer["Install"]["WantedBy"], "timers.target")

    def run_reload_hook(self, nginx_status):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            log = directory / "commands"
            for command, status in (("nginx", nginx_status), ("systemctl", 0)):
                executable = directory / command
                executable.write_text("#!/bin/sh\nprintf '%s\\n' '" + command +
                                      " '\"$*\" >> \"$HOOK_TEST_LOG\"\nexit " + str(status) + "\n")
                executable.chmod(0o755)
            result = subprocess.run(["sh", str(HTTPS / "reload-nginx.sh")],
                                    env=dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                                             HOOK_TEST_LOG=str(log)), capture_output=True, text=True)
            return result, log.read_text().splitlines()

    def test_successful_config_check_reloads_nginx_without_restart(self):
        result, commands = self.run_reload_hook(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(commands, ["nginx -t", "systemctl reload nginx"])

    def test_invalid_config_stops_before_reload(self):
        result, commands = self.run_reload_hook(7)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(commands, ["nginx -t"])


if __name__ == "__main__":
    unittest.main()
