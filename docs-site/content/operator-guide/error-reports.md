# Error reports

Tinfirm keeps the errors of its own server and web app as error reports, scrubbed of the companies' data when they happen. The instance admins see them on the **Errors** page, and, if you choose, Tinfirm sends them to the vendor's error tracker so that FLOWTIVO can fix what went wrong. This page is for the operators who deploy Tinfirm and the instance admins who look after it.

Nothing leaves the installation unless an operator or an instance admin turns delivery on, and Tinfirm sends no other telemetry.

## What is captured

| Source | What becomes a report |
|---|---|
| The server | Every line it logs at `ERROR`, among them a request that failed with an unexpected exception |
| The web app | Every error the app does not handle in a person's browser, except a failed request (the server captured that itself) and a script that went missing after an upgrade (the page reloads instead). At most 10 a page load, the same error at most once a minute. |

One kind of error is one report: the same error from many requests, people or replicas adds to its count. A report keeps its first and last time and version, and its newest sample. A report not seen for 90 days is deleted. At most 1000 of the server's reports and 250 of the web app's are kept, each apart: beyond, the one of that kind seen least recently goes, so that the web app's reports, which any member can send, never push out the server's. From one person, the server takes at most 20 web errors a minute and at most 50 different kinds of web error an hour.

The server's own log still holds every error in full.

## What a report keeps

A report keeps only what describes the code, never what describes the company or its people:

| Kept | Never kept |
|---|---|
| The exception's type and its causes', with their stack frames | The request's URL, headers, cookies or body |
| The logger, and the log line's template, such as `Sync of {} failed`, without what filled it | The values that filled the template |
| The route's pattern, such as `GET /api/companies/{companyId}/conversations` | The route's actual ids |
| The version and commit of Tinfirm, the Java version, or the browser's name and major version | The user, the company, the IP address |

In an exception's message, URLs, email addresses, UUIDs, quoted strings, IP addresses, file paths, hexadecimal values, tokens and keys, and numbers of 4 digits or more are masked (`Issue <str> at <url>`), and the message is cut to 300 characters. Of the web app's frames, only Tinfirm's own scripts are kept; a browser extension's script shows as `<external>`.

## The Errors page

Instance admins open **Errors** in the sidebar's **Instance admin** section. The page says what it holds: "what you see here is what would be sent, and nothing more."

**Sending errors to the vendor** shows how reports are handled:

| Item | What it says |
|---|---|
| **Send automatically** | The switch for delivery: on, "Sent to" the tracker's host; off, "Kept on this installation; nothing is sent." Greyed out, with the reason, when the operator set the mode, or when there is no destination. |
| **Installation** | The installation's id, made at random at its first start: the vendor knows your installation by it |
| **Name in reports** | The label the operator gave the installation, if any |
| **Waiting to be sent** | The reports with occurrences not sent yet |
| **Last delivery**, **Last failure** | When reports were last delivered, and why the last delivery failed |

Below, the reports, the most recently seen first, with **Error**, **Where** (**Server** or **Web app**), **Count**, **Last seen** and **Version**. Choose a report to open its sample: how often and since when it happened, the logger, and each exception with its frames, Tinfirm's own code apart from the libraries'.

- :lucide-trash-2: deletes a report once you understand it. If the error happens again, it starts again as a new report.
- **Download** saves every report as one JSON file, `tinfirm-error-reports-<installation id>.json`, in exactly the form delivery sends. It is everything that would leave the installation, and works in every mode.

Each change of **Send automatically** is recorded in the instance audit log.

## Choose the mode

The operator sets the mode with `TINFIRM_ERROR_REPORTS`, or leaves it to the instance admins:

| `TINFIRM_ERROR_REPORTS` | What happens |
|---|---|
| unset, the default | The instance admins choose on the **Errors** page: **Send automatically** off (the default) or on |
| `off` | Nothing is captured. The page says "Error reporting is off: nothing is captured." |
| `local` | Captured and shown, never sent. The page cannot turn delivery on. |
| `automatic` | Captured, shown and sent. The page cannot turn it off. |

**Send automatically** also needs a destination, `TINFIRM_ERROR_REPORTS_DSN`. Without one the switch stays off, and the page says the operator sets it.

## Send reports to the vendor

Delivery speaks Sentry's protocol, so the destination is any tracker that does, such as Sentry, GlitchTip or Bugsink. The vendor gives you the DSN to use for your installation.

| Variable | Meaning |
|---|---|
| `TINFIRM_ERROR_REPORTS_DSN` | **Secret.** The destination, `https://<public key>@<host>/<project id>`. `https` only, or `http` to a loopback address. Without it, nothing is sent. |
| `TINFIRM_ERROR_REPORTS_LABEL` | The installation's name in its reports, such as `acme-prod`: 1 to 64 letters, digits, dots, dashes or underscores. Without it, only the installation's id names it. |
| `TINFIRM_ERROR_REPORTS_PROXY` | `http://host:port` of an HTTP proxy, for an installation that reaches the internet only through one. Without it, the JVM's proxy settings apply. |

The server reads them when it starts, and a malformed DSN, label or proxy stops it with the reason, never with the key. Restart the server after you change one.

How delivery works:

- Every 15 minutes, starting 2 minutes after it starts, the server sends each report that has occurrences not sent yet, at most 100 at a time, each as one event. A report is sent again only when it happens again.
- An event holds the report's fields above, the installation's id and label, and the release, `tinfirm@<version>`. Download the file to read exactly what is sent.
- A failure ends the pass, the page shows why, and the next pass tries again. When the tracker asks to wait, every pass waits as long as it asks.
- The server must reach the tracker on port 443. The Helm chart's default network policy allows https to public addresses; a tracker inside your network needs a rule of its own: [What the server calls out to](helm.md#what-the-server-calls-out-to).

=== "Helm"

    ```yaml
    errorReports:
      mode: automatic             # empty lets the instance admins choose
      dsnSecret:
        name: tinfirm-error-reports
        key: dsn
      label: acme-prod
      proxy: ""                   # for example http://proxy.example.com:3128
    ```

    ```sh
    kubectl -n tinfirm create secret generic tinfirm-error-reports \
      --from-literal=dsn='https://<public key>@errors.example.com/<project id>'
    ```

    The Secret is optional: without it, the server starts and sends nothing, with a warning in its log.

=== "Docker Compose"

    ```sh title="deploy/compose/.env"
    TINFIRM_ERROR_REPORTS=automatic
    TINFIRM_ERROR_REPORTS_DSN=https://<public key>@errors.example.com/<project id>
    TINFIRM_ERROR_REPORTS_LABEL=acme-prod
    ```

## Without internet

An installation that cannot reach the vendor keeps `local`, or leaves the mode to the instance admins with **Send automatically** off. When FLOWTIVO asks for the reports, an instance admin chooses **Download** on the **Errors** page and sends the file through your support channel. The file holds nothing that delivery would not send.
