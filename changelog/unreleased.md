# Unreleased

Notes for the next release. Nothing is published from this file; copy it to
`vX.Y.Z.md` when cutting a release. See [README.md](README.md).

## Added

- Email alerting alongside the existing webhook: `GATEWAY_ALERT_EMAIL_TO` plus
  `SPRING_MAIL_*`, one message per alert or per deduplicated budget event.
- `POST /admin/alerts/test` sends a test alert and reports which channels are configured.
- Mail settings editable from the admin console: `GET/PUT /admin/mail-settings` stores SMTP
  host, port, credentials, from and recipients (password encrypted, write-only), taking
  precedence over `SPRING_MAIL_*`.
- `PUT` added to the CORS methods; without it the console's settings save failed in the
  browser while working from curl.

## Changed

- Nothing yet.

## Fixed

- Nothing yet.
