# Tinfirm docs site

The manuals of Tinfirm, for the people who use it, the Owners and Admins who set a company up in it, and the operators who install it, public at <https://docs.tinfirm.com>. It is built with [Zensical](https://zensical.org) from the Markdown files in `content/`.

This site is not `docs/`. `docs/` holds the engineering record: the design, the conventions, the ADRs, the reviews and the threat model, written for the people who build Tinfirm. This site explains the product to the people who use and run it, and links to nothing in the repository: it documents the product, not its source.

## Licence

This site's content is under the MIT licence, as is Tinfirm's code outside `ee/` ([ADR-0077](../docs/adr/0077-licence-editions-and-seats.md) decision 1); the footer of every page says so. A page that documents a paid feature of `ee/` grants no right to use it. The licence's text is the root `LICENSE`.

## Preview and build

You need Python 3.11 or newer, with `venv` (on Debian and Ubuntu, the `python3-venv` package).

| Command | What it does |
|---|---|
| `make docs-serve` | Serves the site on <http://localhost:8000> and rebuilds it on every save |
| `make docs-build` | Builds the site into `docs-site/site/` in strict mode: a link to a page or an anchor that does not exist fails the build |
| `docker build docs-site` | The same strict build, then the site's image, the way CI builds them (`ci.yml`, job `docs`) |
| `make docs-lock` | Locks `requirements.in` into `requirements.txt` with hashes, after you change the Zensical version; needs [uv](https://docs.astral.sh/uv/) |

The first `make docs-serve` or `make docs-build` creates `docs-site/.venv` and installs the locked packages into it.

## Publishing

The site's image follows `main`. Once a pull request that changes `docs-site/` is merged, `ci.yml`'s `publish-docs` job publishes the image `ghcr.io/flowtivo/tinfirm-docs:sha-<commit>` and moves the tag `main` to it. A push that changes nothing here publishes nothing. Preview a change with `make docs-serve` before you merge it.

To take a change back, revert it here: the push of the revert publishes the site's image without it.

## Layout

| Path | Contents |
|---|---|
| `zensical.toml` | The site's settings and its navigation (`nav`) |
| `content/index.md`, `content/concepts.md` | The home page, and the concepts every guide relies on |
| `content/user-guide/` | For every member: conversations, the inbox, accounts, AI clients |
| `content/admin-guide/` | For a company's Owners and Admins: the Company pages and roles |
| `content/operator-guide/` | For operators: install, upgrade, back up, run runner hosts |
| `content/reference/` | Exact lists to look things up in |
| `content/assets/` | The logo, the fonts, `tinfirm.css` with the brand colours, and images |
| `includes/abbreviations.md` | Abbreviations that show a tooltip on every page |
| `includes/*.svg` | Diagrams, drawn as SVG and pulled into a page with a snippet (below) |
| `Dockerfile`, `nginx.conf` | The image: the strict build, then nginx serving it on port 8080 (CONVENTIONS §12) |

## Add a page

1. Write it in the folder of its audience, with one `#` heading: the page's title.
2. Add it to `nav` in `zensical.toml`, under its guide. The navigation takes the title from the heading.
3. Link it from its guide's `index.md`, under "In this guide".
4. Run `make docs-build` and fix every warning.

## How to write

The writing rules of [CONTRIBUTING.md](../CONTRIBUTING.md#7-writing-rules-for-documentation) apply, written for the reader of each guide rather than for engineers:

- **Write for one reader.** A page belongs to one guide. A member does not need to know how the vault works; an operator does.
- **Start from the task.** Name the page after what the reader wants to do ("Work with Tinfirm from your AI client"), then give the steps in order.
- **Say "you".** Use short sentences in the active voice.
- **Use the app's words.** Name a page, button or field exactly as the web app shows it, in bold: **Runner hosts**, **Register a runner host**. One term for one thing, as [Concepts](content/concepts.md) names it. When the app renames something, rename it here too.
- **Write what exists.** Mark what does not exist yet as planned. Check each claim against the app or the code; the design documents in `docs/` can be ahead of or behind it.
- **No real data.** Use `tinfirm.example.com`, `Acme`, `alice@example.com`, and placeholders such as `sk-ant-…` for secrets.
- **Icons, not emoji.** The app uses Lucide icons only: write `:lucide-inbox:`.
- **Screenshots** go in `content/assets/images/<guide>/` as PNG, of the light theme, cropped to what the step is about. A screenshot ages with every UI change, so use one only where words are not enough.

Markdown beyond the basics, all set up in `zensical.toml`:

| Element | Syntax |
|---|---|
| Note, tip, warning, danger | `!!! warning "Title"`, then the text indented by four spaces |
| Collapsible note | `??? note "Title"` |
| Tabs | `=== "Claude Code"`, then the content indented by four spaces |
| Code block with a file name | ` ```json title="model.json" ` |
| Keys | `++ctrl+k++` |
| Cards | `<div class="grid cards" markdown>`, as on the home page |
| Diagram | `<figure class="tf-diagram">`, then `--8<-- "<name>.svg"` on a line of its own, then a `<figcaption>`, as on [Architecture](content/operator-guide/architecture.md) |

The full reference is Zensical's documentation: <https://zensical.org/docs/>.

**Diagrams** are inline SVG, not Mermaid: Zensical loads Mermaid from a CDN, and the site must load nothing from another origin. Draw the SVG by hand in `includes/`, with the classes of `tinfirm.css` (`tf-box`, `tf-box--key`, `tf-zone`, `tf-band`, `tf-edge`, `tf-title`, `tf-note`) instead of colours, so that it follows the light and dark schemes; give it a `<title>` and a `<desc>` that says in words what it shows.

## What to write next

Each feature has a page in its guide, checked against the web app and the server when it was written. What is left:

| Guide | Page or change | Source in the repository |
|---|---|---|
| Operator guide | Switch a fleet of runner hosts from protocol v1 to v2 | `docs/operations/runner-protocol-v2.md` |
| Every guide | Screenshots, where words are not enough | The web app |

The auto-deploy timer (`docs/operations/auto-deploy.md`), which deploys every commit of `main`, and `make deploy-host`, which deploys a working copy, serve the people who build Tinfirm: leave them out of the site.

## The guides in `docs/operations/`

A feature's how-to for customers lives here, once. `docs/operations/` keeps, for each guide, only what the people who build Tinfirm need beyond the site: the rationale with its ADRs and contracts, endpoints, codes and audit events, worked examples of an operator's setup, and development setups. Each such guide opens with a link to its page here. When you change a feature, change its page here; change the guide in `docs/operations/` only for what it keeps. When a guide is left with nothing of its own, keep its headings that other documents link to, each with a pointer to the page here, so that those links keep working.
