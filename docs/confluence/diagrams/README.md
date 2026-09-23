# Confluence'i lehtede diagrammid

Mermaid-lähtefailid diagrammidele, mis on Confluence'i lehtedel (wiki.kemit.ee, ruum `EFTI`)
PNG-manustena. Faili nimi = manuse nimi (`<nimi>.png`).

| Fail | Leht | Sisu |
|---|---|---|
| [varav-ylevaade.mmd](varav-ylevaade.mmd) | [eFTI värav](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500285) | Osapooled ja põhivood |
| [varav-ylesehitus.mmd](varav-ylesehitus.mmd) | [eFTI värav](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500285) | Värava komponendid |
| [ed-valik.mmd](ed-valik.mmd) | [Platvormi eDelivery (AS4) liidestumine](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500286) | REST vs AS4 valik platvormi kirje järgi |
| [ed-registreerimine.mmd](ed-registreerimine.mmd) | [Platvormi eDelivery (AS4) liidestumine](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500286) | FTI004 → FTI029 |
| [ed-andmestik.mmd](ed-andmestik.mmd) | [Platvormi eDelivery (AS4) liidestumine](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500286) | FTI009 → FTI010 |
| [ed-jarelparimine.mmd](ed-jarelparimine.mmd) | [Platvormi eDelivery (AS4) liidestumine](https://wiki.kemit.ee/pages/viewpage.action?pageId=346500286) | FTI025 → FTI030 |

## PNG genereerimine

```sh
npx -p @mermaid-js/mermaid-cli mmdc -i varav-ylevaade.mmd -o varav-ylevaade.png -s 2 -b white
```

Seejärel laadi PNG Confluence'i lehele sama nimega manusena üles (asendab eelmise versiooni).
