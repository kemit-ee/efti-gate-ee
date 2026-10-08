# ADR-016: Registri tarne — YAML allikas, JSON build-ajal, staatiline nginx

**Otsus (08.10.2026, Anton Keks):** [ADR-015](015-registry-as-file-server.md) otsus (register ei ole
andmebaasis, vaid failides) jääb kehtima, aga **tarneviis muutub**: allikas on **YAML**, see
konverteeritakse **JSON-iks image'i build-ajal** ja tulemust serveerib **staatiline nginx**. Erinev
python-teenus (`docker/registry/serve.py`) ja **runtime-mount kustutatakse**.

## Kontekst

ADR-015 tegi `registry/**` failidest ainsa tõeallika, aga serveeris neid oma python-teenusega, mis
luges mount'itud kataloogi **käivitusel** ja hoidis sisu mälus. See tähendas:

- rakenduskoodi, mida oli vaja ainult staatilise sisu serveerimiseks;
- runtime-mount'i, mis on kohustuslik (ilma selleta konteiner ei käivitu), seega iga keskkond peab
  kataloogi kuidagi kohale toimetama (K8s Secret/ConfigMap, bind mount);
- YAML/JSON küsimust, mida ADR-015 ei käsitlenud: failid olid JSON, kuigi kõik ülejäänud
  konfiguratsioon selles repos on YAML.

## Otsus

1. **Allikas on YAML.** `registry/{gates,platforms,authorities}/<id>.yml`, failinimi = `id`.
   Sertifikaadid on literaalsed plokid (`|`), nii et need on ülevaatusel loetavad.
   **Puuduvad ja `null`-väärtusega valikulised võtmed jäetakse välja** — neid ei kirjutata JSON-i
   `null`-ina. Tühjad kollektsioonid ei ole "puuduvad": `subsets: []` (õiguseta asutus) ja
   `headers: {}` säilivad.

2. **Konverter on ka valideerija.** `scripts/registry-to-json.py` (PyYAML) kontrollib sama, mida
   varem `serve.py`: kohustuslikud väljad, `id` vs failinimi, `status`-enum, `countryCode`, PEM-kuju,
   `subsets` koodid, tundmatud võtmed. Viga = mittenulliline väljumine.

3. **Konverteerimine käib image'i build-ajal.** `docker/registry/Dockerfile` on kaheastmeline:
   `python:3.13-alpine` + PyYAML konverteerib `registry/` → `/srv/registry`, seejärel
   `nginx:stable-alpine` serveerib neid `/usr/share/nginx/html`-ist. **Rakenduskoodi ei ole**;
   `docker/registry/serve.py` kustutati.
   Genereeritud failid: `/<tüüp>.json` (kogu register massiivina), `/<tüüp>/<id>.json` (üks kirje),
   `health.json`.

4. **Mount'i ei ole.** Image sisaldab oma registrit. Iga keskkond, kellel on oma väravad,
   platvormid ja asutused, **buildib oma image'e**; `registry/**` asendatakse enne `docker build`-i
   (haru, või CI samm, mis kirjutab kataloogi üle).

5. **Fail-closed nihkus build-ajale.** Varem peatas vigane register pinu käivitumise; nüüd ei lase
   see `docker compose build`-il õnnestuda — veelgi varasem ja tugevam värav. Lisaks jooksutab CI
   konverterit ilma `--out`-ita (`.github/workflows/e2e.yml`, `.gitlab-ci.yml`), seega vigane
   register kukub läbi ka ilma image'i buildimiseta.

6. **Id-d on failinimedes väiketähtedega.** Staatiline failiserver on tõstutundlik, aga id-d olid
   varem `CITEXT` (tõstutundetud). Seetõttu **DSL väiketähestab id enne päringut** kõigis seitsmes
   koha-järgi päringus (`toLowerCase()`), nii et `EU-EE`, `eu-ee` ja `Eu-Ee` käituvad endiselt
   ühtemoodi. See on ainus viis tõstutundetust staatilise serveriga säilitada.

7. **Serveerimine.** nginx `listen 8080` (hoidmaks `[#REGISTRY_URL]` muutumatuna), `try_files $uri =404`
   (kataloogide loendamist ei ole), `Cache-Control: no-store` (dokumendid sisaldavad elavaid
   võtmeid) ja JSON-kujuline 404. `health.json` + nginx healthcheck hoiavad `depends_on:
   service_healthy` ahela töös.

## Põrjus

- **Vähem liikuvaid osi.** Üks konteiner, milles ei ole ühtegi rida meie koodi; konverter on
  build-samm, mitte teenus.
- **YAML on repo konventsioon** ja seda on lihtsam üle vaadata kui JSON-i (eriti PEM-e).
- **Vigane register ei jõua kunagi käivitusse** — see ei ole ainult käivitusaegne kontroll, vaid
  build-värav.
- **Puuduvad võtmed kaovad** andmetest, mitte ei reisi `null`-idena läbi kogu ahela.

## Tagajärjed ja hinnad

- **Sellest repos builditud image serveerib dev-fixture'id.** `registry/` sisaldab `EU-EE`,
  `EU-MOCK`, `mock` (võti `mock-secret-key`) ja `auth-*` testandmeid. Tootmiskeskkond, mis kasutab
  CI-builditud image'it muutmata kujul, saab **dev-registri koos avatekstilise testvõtmega**.
  Iga päris paigaldus peab buildima oma image'e oma `registry/`-ga.
- **Platvormi API-võtmed on image'i kihtides** (ja seega image-registris, SBOM-is ja trivy
  skaneeringutes). ADR-015 nõudis mount'i Secret'ist; see nõue kaob, sest runtime-mount'i ei ole.
  See on teadlik hind: register on konfiguratsioon, mis kuulub image'i, aga keskkonna-saladuste
  jaoks ei ole selles repos enam eraldi kohta.
- **Registri muudatus nõuab image'i uuesti buildimist** (`docker compose up --build registry`).
  `develop.watch` ei aita, sest mount'i ei ole; dev-keskkonnas on see aeglasem kui ADR-015 ajal.
- **Runtime-valideerimist ei ole.** nginx ei kontrolli midagi; kogu kontroll on build-ajal. Kui
  keegi õnnestub serveerida katkise image'iga, ei ütle miski midagi — samas ei saa katkist image'it
  buildida.
- **`gates/<id>.json` suurtähtedega 404-b**, kuigi dokumentides on `id` endiselt `EU-EE`. Ops
  peab käsitsi pärimisel kasutama väiketähti.

## Rakendatav changeset

- `registry/**/*.json` → `registry/**/*.yml` (14 faili), `registry/README.md`
- `scripts/registry-to-json.py` (uus; konverter + valideerija + CI kontroll)
- `docker/registry/{Dockerfile,nginx.conf}` (uus), `docker/registry/serve.py` kustutatud
- `compose.yml` (mount ja env välja, healthcheck `health.json`-ile), `compose.override.yml`
- `DSL/Ruuter/**`: id väiketähestamine seitsmes koha-järgi päringus
- `.github/workflows/e2e.yml`, `.gitlab-ci.yml` (konverteri valideerimissamm)
- ADR-015 märgistatud täiendatuks

## Lahtised punktid

- **Devops.** `services/efti/devops` wrapper chart peab teadma, et `registry` on nüüd tavaline
  nginx-image: **mount'i ei tohi olla** ja porti ei tohi avaldada. Keskkonna register tuleb image'i
  buildimise ajal (CI peab kataloogi üle kirjutama), mitte Secret'iga.
- **Allkiri** (ADR-011) jääb tegemata; nüüd oleks allkirja loomulik koht build-samm.
- **Dokumentatsiooni võlg** — vt ADR-015 lahtised punktid; lisandub `registry`-teenuse kirjeldus
  kõikjal, kus räägiti mount'itud kataloogist.

## Seotud

- [ADR-015](015-registry-as-file-server.md) — **täiendatud**: otsus (register failides, mitte
  andmebaasis) kehtib, tarneviis (python-teenus + mount) asendatakse build-aegse konverteriga
- [ADR-014](014-registry-as-git-folder.md) — asendatud (andmebaasi vahekiht)
- [ADR-011](011-registries-as-signed-config.md), [ADR-004](004-platform-api-key.md)
