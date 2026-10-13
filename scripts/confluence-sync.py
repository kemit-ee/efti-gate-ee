#!/usr/bin/env python3
"""Publish repository Markdown to the EFTI Confluence space (wiki.kemit.ee).

The repository is the source of truth; the Confluence pages are generated from it. Edit the
Markdown here, then run this script — never edit the mapped pages in Confluence directly, the
next sync overwrites them.

What is synced is the PAGES table below: one entry per Confluence page, naming the Markdown
source. For each page the script

  * renders the Markdown (CommonMark + tables) into Confluence storage format,
  * drops the leading `# Title` (the page title is set from PAGES),
  * turns fenced code into the `code` macro, `> quote` blocks into the `info` macro, and
    `![..](images/x.png)` into an attached image (`x.png` is uploaded / replaced on that page),
  * rewrites repo-relative links to GitHub (`dev` branch),
  * keeps the page's existing navigation bar: everything before the first `<hr />` and after the
    last `<hr /><table>` of the current page body (the user-guide pages have prev/next tables).

Usage (from the repository root):

    python3 scripts/confluence-sync.py                   # dry run: writes build/confluence/*.html
    python3 scripts/confluence-sync.py --publish         # update every page in PAGES
    python3 scripts/confluence-sync.py --publish --only 346491698 346510186

Token: a Confluence Data Center personal access token in CONFLUENCE_TOKEN, or in an `env.local`
file (`CONFLUENCE_TOKEN=...`, git-ignored) at the repository root or its parent checkout.
Requires markdown-it-py (`pip install markdown-it-py==3.0.0`).
"""

import argparse
import html
import json
import os
import posixpath
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

WIKI = "https://wiki.kemit.ee"
GITHUB_BLOB = "https://github.com/kemit-ee/efti-gate-ee/blob/dev/"
SOURCE_NOTE = ("Allikas: <a href=\"{url}\">{path}</a> (eFTI Gate repositoorium). Muudatused tee "
               "repos; see leht on sellest genereeritud (<code>scripts/confluence-sync.py</code>).")

# page id -> (title, repository-relative Markdown source, add source note)
PAGES = {
    "346510186": ("eFTI värava paigaldusjuhend", "docs/specs/deploy/resource-and-secrets-requirements.md", True),
    "346491696": ("Kasutusjuhend", "docs/user-guide/src/index.md", False),
    "346491697": ("1. Sisselogimine ja navigeerimine", "docs/user-guide/src/login.md", False),
    "346491698": ("2. Väravad", "docs/user-guide/src/gates.md", False),
    "346491699": ("3. Platvormid", "docs/user-guide/src/platforms.md", False),
    "346491700": ("4. Pädevad asutused", "docs/user-guide/src/authorities.md", False),
    "346491701": ("5. Kasutajad", "docs/user-guide/src/users.md", False),
    "346491702": ("6. Saadetised", "docs/user-guide/src/consignments.md", False),
    "346491703": ("7. Veaolukorrad ja head tavad", "docs/user-guide/src/troubleshooting.md", False),
}


def load_token(root):
    if os.environ.get("CONFLUENCE_TOKEN"):
        return os.environ["CONFLUENCE_TOKEN"]
    for candidate in (root / "env.local", root.parent / "env.local", *[p / "env.local" for p in root.parents]):
        if candidate.is_file():
            for line in candidate.read_text().splitlines():
                if line.startswith("CONFLUENCE_TOKEN="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    sys.exit("confluence-sync: CONFLUENCE_TOKEN is not set and no env.local with it was found")


def render(md_text, md_path):
    """Markdown -> (storage-format XHTML, [image paths relative to the Markdown file])."""
    from markdown_it import MarkdownIt  # imported here so --help works without the dependency

    md_text = re.sub(r"\A# .*\n", "", md_text)
    body = MarkdownIt("commonmark", {"html": False}).enable("table").render(md_text)

    def code(m):
        lang = f'<ac:parameter ac:name="language">{m.group(1)}</ac:parameter>' if m.group(1) else ""
        src = html.unescape(m.group(2)).replace("]]>", "]]]]><![CDATA[>")
        return (f'<ac:structured-macro ac:name="code">{lang}'
                f'<ac:plain-text-body><![CDATA[{src}]]></ac:plain-text-body></ac:structured-macro>')
    body = re.sub(r'<pre><code(?: class="language-([\w-]+)")?>(.*?)</code></pre>', code, body, flags=re.S)

    images = []
    def image(m):
        images.append(html.unescape(m.group(1)))
        name = posixpath.basename(m.group(1))
        return f'<ac:image ac:align="center"><ri:attachment ri:filename="{name}" /></ac:image>'
    body = re.sub(r'<img src="([^"]+)" alt="[^"]*" ?/?>', image, body)

    base = posixpath.dirname(md_path)
    def link(m):
        href = html.unescape(m.group(1))
        if re.match(r"^(https?:|mailto:|#)", href):
            return m.group(0)
        target, _, fragment = href.partition("#")
        url = GITHUB_BLOB + posixpath.normpath(posixpath.join(base, target)) + (f"#{fragment}" if fragment else "")
        return f'href="{html.escape(url)}"'
    body = re.sub(r'href="([^"]+)"', link, body)

    body = re.sub(r"<blockquote>\s*(.*?)\s*</blockquote>",
                  r'<ac:structured-macro ac:name="info"><ac:rich-text-body>\1</ac:rich-text-body></ac:structured-macro>',
                  body, flags=re.S)
    return body, images


def keep_navigation(current, body):
    """Wrap body in the current page's navigation bars (before the first / after the last <hr />)."""
    if "<hr />" not in current:
        return body
    first = current.index("<hr />") + len("<hr />")
    prefix = current[:first]
    if not prefix.lstrip().startswith("<table>"):
        return body
    suffix = current[current.rindex("<hr /><table>"):] if "<hr /><table>" in current[first:] else ""
    return prefix + body + suffix


class Confluence:
    def __init__(self, token):
        self.token = token

    def call(self, method, path, data=None, headers=None):
        h = {"Authorization": f"Bearer {self.token}", "Accept": "application/json"}
        if data is not None and not isinstance(data, bytes):
            data = json.dumps(data).encode()
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        request = urllib.request.Request(WIKI + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(request) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            sys.exit(f"confluence-sync: {method} {path}: HTTP {e.code} {e.read()[:500]!r}")

    def page(self, page_id):
        return self.call("GET", f"/rest/api/content/{page_id}?expand=body.storage,version,space")

    def update(self, page_id, title, storage, message):
        current = self.page(page_id)
        return self.call("PUT", f"/rest/api/content/{page_id}", {
            "id": page_id, "type": "page", "title": title,
            "space": {"key": current["space"]["key"]},
            "version": {"number": current["version"]["number"] + 1, "message": message},
            "body": {"storage": {"value": storage, "representation": "storage"}},
        })

    def attach(self, page_id, file_path, comment):
        name = file_path.name
        existing = self.call("GET", f"/rest/api/content/{page_id}/child/attachment?filename={urllib.parse.quote(name)}")
        boundary = uuid.uuid4().hex
        mime = "image/png" if name.endswith(".png") else "application/octet-stream"
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                   f"Content-Type: {mime}\r\n\r\n").encode() + file_path.read_bytes() + \
                  (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="comment"\r\n\r\n{comment}'
                   f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="minorEdit"\r\n\r\ntrue'
                   f"\r\n--{boundary}--\r\n").encode()
        headers = {"X-Atlassian-Token": "nocheck", "Content-Type": f"multipart/form-data; boundary={boundary}"}
        suffix = f"/{existing['results'][0]['id']}/data" if existing["results"] else ""
        return self.call("POST", f"/rest/api/content/{page_id}/child/attachment{suffix}", payload, headers)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root (default: .)")
    parser.add_argument("--publish", action="store_true", help="update Confluence (default: dry run)")
    parser.add_argument("--only", nargs="+", metavar="PAGE_ID", help="limit to these page ids")
    parser.add_argument("--message", default="Sünkroonitud repositooriumist", help="Confluence version comment")
    parser.add_argument("--out", type=Path, default=Path("build/confluence"), help="dry-run output directory")
    args = parser.parse_args()

    root = args.root.resolve()
    selected = {pid: PAGES[pid] for pid in (args.only or PAGES) if pid in PAGES}
    unknown = sorted(set(args.only or []) - set(PAGES))
    if unknown:
        sys.exit(f"confluence-sync: unknown page id(s) {unknown}; known: {sorted(PAGES)}")

    confluence = Confluence(load_token(root))
    if not args.publish:
        args.out.mkdir(parents=True, exist_ok=True)

    for page_id, (title, md_path, note) in selected.items():
        source = root / md_path
        body, images = render(source.read_text(), md_path)
        if note:
            body = ('<ac:structured-macro ac:name="info"><ac:rich-text-body><p>'
                    + SOURCE_NOTE.format(url=GITHUB_BLOB + md_path, path=md_path)
                    + "</p></ac:rich-text-body></ac:structured-macro>" + body)
        storage = keep_navigation(confluence.page(page_id)["body"]["storage"]["value"], body)
        image_files = [(source.parent / img).resolve() for img in images]
        missing = [str(p) for p in image_files if not p.is_file()]
        if missing:
            sys.exit(f"confluence-sync: {md_path}: image(s) not found {missing}")

        if not args.publish:
            (args.out / f"{page_id}.html").write_text(storage)
            print(f"dry run  {page_id}  {title}  ({len(storage)} chars, {len(image_files)} image(s)) -> {args.out}/{page_id}.html")
            continue
        version = confluence.update(page_id, title, storage, args.message)["version"]["number"]
        for image_file in image_files:
            confluence.attach(page_id, image_file, args.message)
        print(f"updated  {page_id}  {title}  v{version}  ({len(image_files)} image(s))  {WIKI}/pages/viewpage.action?pageId={page_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
