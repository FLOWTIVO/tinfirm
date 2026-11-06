# Error reports: what Tinfirm captures of its own errors, and sending them to the vendor

The public guide is [Error reports](../../docs-site/content/operator-guide/error-reports.md) (https://docs.tinfirm.com/operator-guide/error-reports/), for operators and instance admins; the **Errors** page is also introduced in [The Instance admin pages](../../docs-site/content/operator-guide/instance-admin.md) (https://docs.tinfirm.com/operator-guide/instance-admin/). This page keeps what the people who build Tinfirm need beyond them: the page's route and audit event, which replica delivers, and the vendor's side, receiving the reports. The design is [ADR-0070](../adr/0070-error-reports.md); the exact rules, with the tables, the endpoints and the scheduled job's lock, are [CONVENTIONS §15.21](../CONVENTIONS.md#1521-error-reports-adr-0070).

## 1. The page *Errors*

The page is `/admin/errors`, for instance admins.

## 2. The modes

When `TINFIRM_ERROR_REPORTS` is unset, an instance admin's choice of **Send automatically** is audited in the instance log as `error_reporting.changed`.

## 3. Delivery

A `worker` replica delivers, one at a time across the replicas, every 15 minutes (the first time two minutes after it starts).

## 4. For the vendor: receiving the reports

Run a tracker that speaks Sentry's protocol and make a project per installation; its DSN is what the installation's operator sets. Each event's fingerprint is the report's, so the tracker groups as the installation does, and its id follows from the report and its count, so a resend is not counted twice.

A downloaded file holds the events under `events`; each can be sent to the project's envelope endpoint as the delivery does:

```sh
jq -c '.events[]' tinfirm-error-reports-<id>.json | while read -r event; do
  id=$(jq -r .event_id <<<"$event")
  printf '{"event_id":"%s"}\n{"type":"event"}\n%s\n' "$id" "$event" |
    curl -sS -H 'Content-Type: application/x-sentry-envelope' \
      -H "X-Sentry-Auth: Sentry sentry_version=7, sentry_key=<key>" \
      --data-binary @- 'https://errors.example.com/api/<project id>/envelope/'
done
```

The web app's frames are minified until the source maps of each release are uploaded to the tracker.
