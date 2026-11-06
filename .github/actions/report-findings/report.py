#!/usr/bin/env python3
"""Turn a scanner's JSON report into things a reviewer actually sees.

Two modes:
  annotate  — emit GitHub workflow commands so each finding appears ON THE DIFF
              in Files changed, and append a table to the job summary.
  comment   — render every report into one sticky pull-request comment.

Log output is not a review surface: nobody opens a job log for a check that
passed, and on a check that failed they read the last screenful. Annotations put
the finding on the line that caused it; the sticky comment puts the totals where
the conversation is.
"""
import json
import os
import sys
import urllib.error
import urllib.request

MARKER = "<!-- tinfirm-security-report -->"
# Rendered per tool: (heading, column titles).
TOOLS = {
    "semgrep": ("SAST · semgrep", ("Severity", "Rule", "Location", "Message")),
    "gitleaks": ("Secrets · gitleaks", ("Rule", "Location", "Description")),
    "trivy": ("Dependencies · trivy", ("Severity", "CVE", "Package", "Fixed in")),
}


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        # A scanner that died before writing its report is reported by its own
        # job's exit code; this must not also crash the reporter.
        return None


def parse_semgrep(doc):
    out = []
    for r in doc.get("results", []) if isinstance(doc, dict) else []:
        extra = r.get("extra", {})
        out.append({
            "severity": extra.get("severity", "WARNING"),
            "rule": r.get("check_id", "?"),
            "file": r.get("path", "?"),
            "line": r.get("start", {}).get("line", 0),
            "col": r.get("start", {}).get("col", 0),
            "message": " ".join((extra.get("message") or "").split()),
        })
    return out


def parse_gitleaks(doc):
    out = []
    for r in doc if isinstance(doc, list) else []:
        out.append({
            "severity": "ERROR",
            "rule": r.get("RuleID", "?"),
            "file": r.get("File", "?"),
            "line": r.get("StartLine", 0),
            "col": 0,
            # Never the secret itself, even redacted — the description is enough
            # to find it, and a build log is not a place to reprint a credential.
            "message": r.get("Description", "potential secret"),
        })
    return out


def parse_trivy(doc):
    out = []
    for res in (doc or {}).get("Results", []) if isinstance(doc, dict) else []:
        for v in res.get("Vulnerabilities", []) or []:
            out.append({
                "severity": v.get("Severity", "UNKNOWN"),
                "rule": v.get("VulnerabilityID", "?"),
                "file": res.get("Target", "?"),
                "line": 0,
                "col": 0,
                "package": f"{v.get('PkgName','?')} {v.get('InstalledVersion','')}".strip(),
                "fixed": v.get("FixedVersion", "—"),
                "message": " ".join((v.get("Title") or "").split()),
            })
    return out


PARSERS = {"semgrep": parse_semgrep, "gitleaks": parse_gitleaks, "trivy": parse_trivy}


def escape_data(value):
    """A workflow command's message, as GitHub's toolkit escapes it (escapeData)."""
    return str(value).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value):
    """A workflow command's property value (file, title), as the toolkit escapes it (escapeProperty).

    Paths, rule ids and messages come from the scanners' output over the pull request's own files: a path
    with a comma, a colon or a line break must not end the property or the command and start another.
    """
    return escape_data(value).replace(":", "%3A").replace(",", "%2C")


def annotate(tool, findings):
    """Emit one workflow command per finding.

    `::error file=…,line=…::` is what puts a marker on the diff itself. Every tool
    blocks, trivy's dependency scan included, so every finding is an error. trivy's
    findings carry no line, so the marker lands at the top of the manifest rather than
    on a changed line; that is the cost of annotating a lockfile finding at all, and it
    beats a notice the reader reads as advisory.
    """
    level = "error"
    for f in findings:
        title = escape_property(f"{tool}: {f['rule']}")
        loc = f"file={escape_property(f['file'])}"
        if f.get("line"):
            loc += f",line={escape_property(f['line'])}"
        if f.get("col"):
            loc += f",col={escape_property(f['col'])}"
        msg = escape_data(f["message"] or f["rule"])
        print(f"::{level} {loc},title={title}::{msg}")


def table(tool, findings, limit=25):
    heading, cols = TOOLS[tool]
    if not findings:
        return [f"### {heading}", "", "No findings.", ""]
    rows = [f"### {heading} — {len(findings)} finding(s)", "",
            "| " + " | ".join(cols) + " |",
            "|" + "|".join("---" for _ in cols) + "|"]
    for f in findings[:limit]:
        loc = f["file"] + (f":{f['line']}" if f.get("line") else "")
        if tool == "trivy":
            cells = [f["severity"], f["rule"], f.get("package", "—"), f.get("fixed", "—")]
        elif tool == "gitleaks":
            cells = [f["rule"], loc, f["message"]]
        else:
            msg = f["message"]
            # Mark the cut. A message trimmed mid-sentence with no ellipsis reads
            # as though that is the whole finding.
            cells = [f["severity"], f"`{f['rule'].split('.')[-1]}`", loc,
                     msg if len(msg) <= 160 else msg[:159].rstrip() + "…"]
        rows.append("| " + " | ".join(str(c).replace("|", "\\|") for c in cells) + " |")
    if len(findings) > limit:
        # Never truncate silently — a trimmed table reads as "that was all of it".
        rows.append(f"| … | … | … | {len(findings) - limit} more, see the job log | "
                    if len(cols) == 4 else f"| … | … | {len(findings) - limit} more |")
    rows.append("")
    return rows


def summary(lines):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def api(method, url, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "tinfirm-ci")
    if data:
        req.add_header("Content-Type", "application/json")
    # The URL is always https://api.github.com/repos/<this repository>/..., never caller input.
    with urllib.request.urlopen(req) as resp:  # nosemgrep: python.lang.security.audit.dynamic-urllib-use-detected.dynamic-urllib-use-detected
        return json.loads(resp.read() or "null")


def comment(reports, token, repo, pr):
    """One sticky comment, updated in place.

    Updating beats posting: a scanner that appends a fresh comment on every push
    buries the conversation, and reviewers start ignoring it.
    """
    body = [MARKER, "## Security scan", ""]
    total_blocking = 0
    for tool, path in reports:
        doc = load(path)
        findings = PARSERS[tool](doc) if doc is not None else []
        # Every tool here counts, trivy included: its dependency scan blocks too, and
        # leaving it out would give a comment reading "Blocking: none" next to a red,
        # merge-blocking check whenever trivy is the only tool with findings — misleading
        # at exactly the moment the reader needs it. trivy is scanned with
        # `--severity HIGH,CRITICAL --ignore-unfixed`, so what it reports here is
        # precisely what its job exits 1 on.
        total_blocking += len(findings)
        body += table(tool, findings)
    verdict = ("**Blocking: none.**"
               if total_blocking == 0 else
               f"**Blocking: {total_blocking} finding(s)** must be fixed or explicitly accepted.")
    body.insert(3, verdict)
    body.insert(4, "")
    payload = {"body": "\n".join(body)}

    try:
        existing = api("GET", f"https://api.github.com/repos/{repo}/issues/{pr}/comments?per_page=100", token)
        for c in existing or []:
            if MARKER in (c.get("body") or ""):
                api("PATCH", f"https://api.github.com/repos/{repo}/issues/comments/{c['id']}", token, payload)
                print(f"::notice::Updated the security report comment on #{pr}")
                return
        api("POST", f"https://api.github.com/repos/{repo}/issues/{pr}/comments", token, payload)
        print(f"::notice::Posted the security report comment on #{pr}")
    except urllib.error.HTTPError as e:
        # A missing `pull-requests: write` grant must not fail the gate — the
        # annotations and the job summary already carry the findings.
        print(f"::warning::Could not write the report comment ({e.code}). "
              f"Does the calling job grant `pull-requests: write`?")


def main():
    mode = sys.argv[1]
    reports = [a.split("=", 1) for a in sys.argv[2:]]
    if mode == "annotate":
        for tool, path in reports:
            doc = load(path)
            findings = PARSERS[tool](doc) if doc is not None else []
            annotate(tool, findings)
            summary(table(tool, findings))
    elif mode == "comment":
        token, repo, pr = os.environ["GH_TOKEN"], os.environ["GH_REPO"], os.environ.get("PR_NUMBER")
        if not pr:
            print("::notice::Not a pull request; skipping the report comment.")
            return
        comment(reports, token, repo, pr)
    else:
        raise SystemExit(f"unknown mode: {mode}")


if __name__ == "__main__":
    main()
