# Security Policy

## Reporting a vulnerability

Report vulnerabilities privately. Use GitHub's private vulnerability reporting: open the repository's
**Security** tab and choose **Report a vulnerability**. Do not open a public issue or pull request for a
security problem.

Include the affected version (the image tag or commit), the steps to reproduce, and the impact you expect.
Fixes land on `main`, and the next image build ships them.

## Supported versions

Only the latest image built from `main` is supported. Older tags receive no fixes. Upgrade with
`scripts/upgrade.sh`, which keeps a backup and rolls back automatically if the new version fails its health check.

## Deployment security model

Centry is designed for a home LAN or a private tailnet. It is not designed to face the internet.

- **Do not expose port 8080 to the internet.** Do not forward it on your router.
- **Use HTTPS for remote access**, for example Tailscale `serve` or Caddy. Set
  `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` once you do.
- **Public endpoints:** `/actuator/health` (status only, no details), the login page, static assets, and the PWA
  manifest. Everything else requires a signed-in user, or HTTP Basic credentials for `/api/**`. Prometheus and
  other actuator endpoints are not exposed.
- **Authentication:** passwords are stored as BCrypt hashes. Browser sessions use a form login with CSRF
  protection and a 30-day remember-me cookie. Members without a password cannot sign in.
- **Roles:** admins manage members and budgets. Members can record expenses, and they can edit or delete only
  expenses they created.
- **Kafka** (optional): the broker is bound to `127.0.0.1` and has no authentication. Do not change the binding
  to a LAN address unless you add authentication.
- **Notifications:** an ntfy topic that is public on a shared server can be read by anyone who knows its name.
  Choose a long random topic, or use a token.
- **Secrets** live in `.env`. Keep that file readable only by its owner (`chmod 600 .env`), and never commit it.
  `CENTRY_ADMIN_PASSWORD` is read only while no users exist, so changing it later does not change the account.
- **Container:** Centry runs as uid and gid 10001, not root. The data directory `/opt/centry/data` must be owned
  by 10001, and it holds the database and backups, so restrict it to that user.
