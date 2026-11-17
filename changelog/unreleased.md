# Unreleased

Notes for the next release. Nothing is published from this file; copy it to
`vX.Y.Z.md` when cutting a release. See [README.md](README.md).

## Added

- Email alerting alongside the existing webhook: `GATEWAY_ALERT_EMAIL_TO` plus
  `SPRING_MAIL_*`, one message per alert or per deduplicated budget event.
- `POST /admin/alerts/test` sends a test alert and reports which channels are configured.

## Changed

- Nothing yet.

## Fixed

- Nothing yet.
