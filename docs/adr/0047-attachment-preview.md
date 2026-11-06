# ADR-0047: Preview of documents and files in the web app

- Status: Accepted
- Date: 2026-10-08
- Sources: the owner's request of 2026-10-08 (a preview for artifacts); PLATFORM.md §4.9 (documents), §9 (the work item page); CONVENTIONS §5 (the security headers), §11, §14.7 (attachments), §14.10 (`/output`); [ADR-0014](0014-task-attachments-and-upload-scan.md), [ADR-0021](0021-agent-attachments.md), [ADR-0039](0039-runner-protocol-v2-server-executed-tools.md); the threat model, rows 1.24 and 1.25
- Changes: ADR-0014's rule that the UI never renders an attachment inline, and the threat model's row 1.24, which stated it.

## Context

- A document is what an agent produced for people (PLATFORM.md §4.9): a report, a test plan, a CSV export, a chart, a log, a patch. It is an attachment of the work item, named in the turn's comment and listed in the side column, where a member could only download it and open it elsewhere.
- Members need to see a document where it is listed. Most documents are text: Markdown from `/output`, CSV, JSON, logs, code, diffs; the rest are images, PDFs and Office files.
- ADR-0014 decided that downloads are `Content-Disposition: attachment` with `nosniff` and the strict CSP, and that the UI never renders an attachment inline, because a malicious file could run script in the UI's origin (threat model 1.24). A document is an agent's output, and an agent can be prompt-injected (threat model 7.10, 9.5), so a document is as untrusted as a member's upload.
- The stored content type is the file name's extension and nothing else: the runner host's table for a file from `/output` or `save_artifact` (`application/octet-stream` for most code), the browser's type for a member's upload. Nothing sniffs the content.
- The web app already renders untrusted Markdown safely (threat model 1.25): raw HTML shown as text, links only of `http(s):`, `mailto:` and in-app paths, images turned into links, the result bound through Angular's sanitizer. The CSP allows images only from the app's own origin and `data:`.

## Decision

**The web app shows a file only in ways that interpret nothing of it as HTML or script, and downloads everything else.** The download endpoint and its headers do not change: every response stays `Content-Disposition: attachment` with `nosniff` and the strict CSP. The preview is in the web app only (`web/src/app/shared/files/attachment-preview.ts`, with the rules in `preview.ts`).

| Kind | Files | Shown as |
|---|---|---|
| Markdown | `.md`, `.markdown` and the like, or `text/markdown` | The renderer of `shared/markdown` (its rules of threat model 1.25), with one addition: a reference to another file of the same work item, relative to the document's own path (`chart.png`, `../data/q3.csv`), becomes an `img` of that file's same-origin download URL, or a link to it. Any other image stays a link. |
| Text | `.txt`, `.log`, `.rst`, `.adoc`, names like `README` or `LICENSE`, or any `text/*` type without a better rule | A `pre` element, wrapped, bound as text |
| Code | Source and configuration files by extension (`.py`, `.ts`, `.java`, `.sql`, `.yaml`, `.xml`, `.html`, `.css`, `.sh`, `.env`, `Makefile`, `Dockerfile` …), or a code content type | A `pre` element, unwrapped, bound as text. HTML is shown as its source, never rendered. |
| JSON | `.json`, `.geojson`, `.ipynb`, or `application/json` | Indented when it parses, is at most 256 KiB and every number literal survives the round trip (JavaScript rounds an integer beyond 2^53 and rewrites `1.0`), else as it is |
| CSV | `.csv`, `.tsv`, or `text/csv` | A table (RFC 4180: quoted fields, doubled quotes, line breaks in quotes; the delimiter guessed from the first line: comma, semicolon or tab). The first record is the header. At most 500 rows. |
| Diff | `.diff`, `.patch`, or `text/x-diff` | The lines coloured by kind: headers, hunks, added, removed, context |
| Image | `.png`, `.jpg`, `.gif`, `.webp`, `.svg`, `.bmp`, `.avif`, or an `image/*` type | An `img` element whose `src` is the download URL. The browser loads it with the session cookie under `img-src 'self'`, and runs no script and loads nothing external for an image, SVG included. The SVG markup is never put into the page. |
| Everything else | PDF, Office files, archives, unknown names | No preview; the name downloads the file |

- **The kind comes from the file name first**, from the extension of the last path segment, because the content type is the extension too and says `application/octet-stream` for most code. The content type, without its parameters, decides only for a name without a known extension; an `image/*` type the app does not name is an image, a `text/*` type is text. The lookup never reads a property of `Object.prototype`.
- **Text is read through the API** (`downloadAttachment` as a blob) up to 1 MiB and decoded as UTF-8; a longer file shows its first 1 MiB cut at a whole line, with a notice and the download. Markdown above the renderer's own limit (200 000 characters) is shown as text by the renderer's existing rule. An image is not read by the app at all.
- **Where it shows.** In the side column of the work item, a document's title and a file's name open the preview when the app can show the file; a download button stands beside them. A file the app cannot show keeps its name as the download link. The preview is a dialog with the name, the size, the content type and who added the file, the content, the notice when only the start is shown, and Download. A document's own images and links are resolved against the work item's files, so a report from `/output` with `reports/q3.md` and `reports/chart.png` shows its chart.
- **What stays out.** HTML is not rendered, even in a sandboxed frame; PDF is not rendered, neither by the browser's viewer nor by a library; Office files are not converted. Each of these is its own decision with its own endpoint, library or CSP change, and none is needed for the files agents produce most.

## Consequences

- Members read a document where it is listed, in the app, as agents' reports are meant to be read. Nothing of a file reaches the page as markup: text goes through Angular's bindings, Markdown through the renderer and the sanitizer, images through `img`.
- The attack surface moves by one element: an `img` of a same-origin file, which browsers treat as an image whatever the stored content type says, and `img-src 'self'` already allowed it. The threat model's row 1.24 now says this.
- The content type of a file is still never checked against its bytes. A file named `x.png` that is not an image fails to load, and the preview says so; a file named `x.md` that is binary shows as garbled text. Neither runs anything.
- A document's relative reference can point only at a file of the same work item, with the path normalised and never above the files; the URL is always the app's own download URL. A reference that resolves to nothing keeps today's rule.
- The web app reads a text file in full to show its first 1 MiB (a file is at most 25 MiB, ADR-0014); a range request would be an API change, not made now.
- A preview of HTML, PDF or Office files, if wanted, is a later ADR: HTML needs an inline endpoint with `Content-Security-Policy: sandbox` in a sandboxed frame; PDF needs pdf.js (and a CSP token for its WebAssembly decoders) or the browser's viewer through an inline endpoint; Office files need a converter.

## Alternatives considered

- **An inline download endpoint** (`Content-Disposition: inline` for an allowlist of types) in a frame. Not chosen now: a frame renders the browser's own interpretation of the file, which is the surface ADR-0014 closed; text and images need no frame; HTML and PDF are the decision deferred above.
- **Sniff the content** (magic bytes) to decide the kind. Not chosen: the kinds the app shows are harmless whatever the bytes are, and sniffing would only move a `.png` that is text into the text preview. The server may check magic bytes if it ever serves a file inline.
- **Render SVG as markup** so its text is selectable. Rejected: SVG markup holds scripts, event handlers, `foreignObject` and external references; an `img` runs none of them.
- **Syntax highlighting** for code. Not chosen now: a library of several hundred kilobytes for a nicety; the `pre` element is enough to read a file.
- **Open a document in a new tab** instead of a dialog. Not chosen: the download endpoint forces a download on navigation, which is right for the endpoint (ADR-0014); a page of the app for one file is the dialog with a route, and can come later.
