# Confluence (wiki.kemit.ee, ruum `EFTI`)

Need lehed on **genereeritud repo Markdownist** — muuda allikat siin ja käivita sünkroonimine; ära
muuda lehti otse Confluence'is, järgmine sünk kirjutab need üle.

| Leht | Allikas |
|---|---|
| [eFTI värava paigaldusjuhend](https://wiki.kemit.ee/pages/viewpage.action?pageId=346510186) | [`docs/specs/deploy/resource-and-secrets-requirements.md`](../specs/deploy/resource-and-secrets-requirements.md) |
| [Kasutusjuhend](https://wiki.kemit.ee/pages/viewpage.action?pageId=346491696) ja alamlehed 1–7 | [`docs/user-guide/src/*.md`](../user-guide/src/) (pildid `images/` manustena) |

Leht ↔ fail vastavus on `scripts/confluence-sync.py` tabelis `PAGES`.

```sh
pip install markdown-it-py==3.0.0
python3 scripts/confluence-sync.py                 # kuivjooks → build/confluence/<pageId>.html
python3 scripts/confluence-sync.py --publish       # uuenda kõik lehed
python3 scripts/confluence-sync.py --publish --only 346491698 --message "Väravate peatükk"
```

Token: Confluence'i isiklik juurdepääsutoken muutujas `CONFLUENCE_TOKEN` või git-ignoreeritud
failis `env.local` (`CONFLUENCE_TOKEN=...`) repo juures. Uue lehe lisamiseks loo leht Confluence'is
(õige vanema alla), lisa selle id ja allikafail `PAGES`-i ning käivita `--publish`.

Diagrammide lähtefailid: [`diagrams/`](diagrams/README.md).
