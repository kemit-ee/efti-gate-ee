# Sertide juhend: oma värav ja partnerid

Kõik, mida AS4 (`edelivery`) sertide kohta vajab, ühes kohas. Kolm ülesannet:

| Ülesanne | Millal |
|---|---|
| [A. Oma värava sert](#a-oma-värava-sert-ownp12) | esmakordne paigaldus, aegumine, võtme leke |
| [B. Partnervärava (või platvormi) sert](#b-partnervärava-või-platvormi-sert-registrisse) | uus partner, partneri sert vahetus |
| [C. Sertide kontroll ja aegumine](#c-kontroll-ja-aegumine) | pärast iga muudatust |

## Põhimõte

AS4 usaldus põhineb **sertifikaadi kinnitamisel registris** (pinning), mitte CA-ahelal:

- **Oma privaatvõti** on `own.p12` (Kubernetes Secret), kunagi mitte registris ega image'is.
- **Oma avalik sert** (`own.crt`) läheb **kahte kohta**: oma `registry/gates/<OWN_GATE_ID>.yml` väljale
  `eDeliveryCert` ja **igale partnerile**, kes paneb selle oma registrisse.
- **Partneri avalik sert** läheb meie `registry/gates/<partneri-id>.yml` väljale `eDeliveryCert`.
  Selle vastu kontrollime partneri allkirja ja selle võtmega krüpteerime talle saadetava.
- Registri muudatus = commit + `registry` (ja `turvis-trust`) image'i uuesti ehitamine. Runtime'is
  midagi ümber ei laeta.

```
  meie own.p12 (Secret)  ──allkirjastab/dekrüpteerib──▶  edelivery
  meie own.crt  ──▶ meie registry/gates/<MEIE-ID>.yml  +  partneritele (nende registrisse)
  partneri .crt ◀── partnerilt ──▶ meie registry/gates/<PARTNERI-ID>.yml
```

---

## A. Oma värava sert (`own.p12`)

### A1. Genereeri võtmepaar ja `own.p12`

Tee seda turvalises keskkonnas (mitte jagatud arendajamasinas). Dev-võti (`code/certs/own.p12`,
`CN=EU-EE`, parool `changeit`) on **ainult** `compose.yml` jaoks.

```bash
OWN_GATE_ID=EU-EE                      # selle keskkonna värava id (= OWN_GATE_ID)
KEYSTORE_PASSWORD='<tugev juhuslik parool>'

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out own.key

# a) isesigneeritud (sobib tehniliselt, sest partnerid kinnitavad serdi registris)
openssl req -new -x509 -key own.key -out own.crt -days 730 -subj "/CN=$OWN_GATE_ID"

# b) või CA kaudu, kui poliitika seda nõuab: saada own.csr CA-le, saad tagasi own.crt (PEM)
# openssl req -new -key own.key -out own.csr -subj "/CN=$OWN_GATE_ID"

openssl pkcs12 -export -out own.p12 -inkey own.key -in own.crt \
  -name "$OWN_GATE_ID" -passout pass:"$KEYSTORE_PASSWORD"
```

Nõuded, mille rikkumisel `edelivery` ei käivitu või partner lükkab sõnumid tagasi:

- PKCS#12 sisaldab **ühte** kirjet: privaatvõti + X.509 (RSA).
- **Alias (`-name`) = `OWN_GATE_ID`.**
- Privaatvõtme parool = võtmehoidla parool = `KEYSTORE_PASSWORD`.
- Sert on kehtiv (`notAfter` tulevikus).

Kontroll:

```bash
openssl pkcs12 -in own.p12 -passin pass:"$KEYSTORE_PASSWORD" -nokeys -info 2>/dev/null | grep friendlyName
openssl x509 -in own.crt -noout -subject -enddate -serial
```

Pärast paigaldust (A2) **hävita lahtine `own.key`**; privaatvõti jääb ainult `own.p12`-sse.

### A2. Pane `own.p12` Secretisse ja mount'i `edelivery`-sse

1. **Secret** (`efti-edelivery-keystore`, võtmed `own.p12` ja `KEYSTORE_PASSWORD`):
   - tavapäraselt SSM `/efti/<env>/edelivery/keystore` (fail base64-na) ja
     `/efti/<env>/edelivery/keystore-password` → `ExternalSecret` (`decodingStrategy: Base64`);
   - käsitsi testkeskkonnas:
     ```bash
     kubectl -n efti create secret generic efti-edelivery-keystore \
       --from-file=own.p12=./own.p12 --from-literal=KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD"
     ```
2. **`edelivery` Deployment** (devops-repo wrapper-chart):
   ```yaml
   env:
     - name: KEYSTORE_DIR
       value: /app/certs
     - name: KEYSTORE_PASSWORD
       valueFrom:
         secretKeyRef: {name: efti-edelivery-keystore, key: KEYSTORE_PASSWORD}
   volumeMounts:
     - {name: as4-keystore, mountPath: /app/certs, readOnly: true}
   volumes:
     - name: as4-keystore
       secret:
         secretName: efti-edelivery-keystore
         items: [{key: own.p12, path: own.p12}]
         defaultMode: 0444          # konteiner jookseb mitte-root kasutajana
   ```
   Image ei sisalda võtmehoidlat; ilma mount'ita `edelivery` ei käivitu. `KEYSTORE_PASSWORD` sea
   alati, koodi vaikeväärtus on `changeit`.
3. Lokaalselt (compose) mount'ib `./code/certs:/app/certs:ro`; uue dev-võtme teeb
   `cd code/certs && ./generate.sh`.

### A3. Avalda oma sert

1. Pane `own.crt` PEM oma `registry/gates/<OWN_GATE_ID>.yml` väljale `eDeliveryCert` (YAML literaalplokk `|`):
   ```yaml
   eDeliveryCert: |
     -----BEGIN CERTIFICATE-----
     ...
     -----END CERTIFICATE-----
   ```
2. **Saada sama `own.crt` igale partnervärava operaatorile**, et nad lisaksid selle oma registrisse
   (jaotis B nende poolel). Ilma selleta ei saa nad meie allkirja kontrollida ega meile krüpteerida.
3. Ehita registry uuesti (jaotis C).

### A4. Rotatsioon (aegumine või võtme leke)

Partnerid kontrollivad meid **oma registris oleva serdi** järgi, seega vahetus on kooskõlastatud:

1. Tee uus `own.p12` (A1) hiljemalt 30 päeva enne aegumist.
2. Saada uus `own.crt` partneritele ja lepi kokku vahetuse aeg.
3. Vahetuse hetkel samaaegselt: uuenda Secret → uuenda oma `eDeliveryCert` registris → ehita ja
   paigalda `registry` + `turvis-trust` → taaskäivita `edelivery`.
4. Partnerid uuendavad samal ajal oma registri.

Kuni partner pole oma koopiat uuendanud, ebaõnnestuvad sõnumid temaga. Võtme lekke korral on see aken paratamatu.

---

## B. Partnervärava (või platvormi) sert registrisse

### B1. Uus partnervärava

1. Küsi partneri operaatorilt tema **avalik** AS4 sert PEM-na (`.crt`/`.pem`). Privaatvõtit ei küsi ega saada keegi.
2. Kontrolli seda enne:
   ```bash
   openssl x509 -in partner.crt -noout -subject -issuer -enddate -serial
   ```
   Peab olema kehtiv ja tulema partneri enda aadressilt/kanalist, mitte avalikult lehelt.
3. Loo `registry/gates/<partneri-id>.yml` (faili nimi = `id`, ASCII, kõik väärtused stringid):
   ```yaml
   id: EU-LV
   countryCode: LV
   eDeliveryUrl: https://gate.example.lv/services/msh
   status: ONLINE
   eDeliveryCert: |
     -----BEGIN CERTIFICATE-----
     ...
     -----END CERTIFICATE-----
   # valikuline, kui partner kasutab HTTPS-i privaatse/isesigneeritud serdiga:
   # tlsCert: |
   #   -----BEGIN CERTIFICATE-----
   #   ...
   ```
4. Anna partnerile meie `own.crt` (A3 samm 2) ja `eDeliveryUrl`, et ta lisaks meid oma registrisse.
5. Ehita registry uuesti (jaotis C). `edelivery` võtab partneri üles `REGISTRY_REFRESH_SECONDS` jooksul (vaikimisi 10 s).

### B2. Partneri sert vahetub

Muuda ainult tema `eDeliveryCert` oma failis, ehita uuesti. Vahetuse aeg lepi partneriga kokku (vt A4 – sama
kooskõlastus teistpidi). Aegunud serdiga partner ei tööta.

### B3. Platvormi AS4 sert

`registry/platforms/<id>.yml` väli `eDeliveryCert` (valikuline). Platvorm ilma selleta ei ole AS4 osapool, ainult REST.
Menetlus sama mis B1/B2.

### B4. Väljad, mida pole vaja segi ajada

| Väli | Mis see on | Kasutab |
|---|---|---|
| `eDeliveryCert` | AS4 allkirja/krüpteerimise avalik sert | `edelivery` (ja Turvis) |
| `tlsCert` | partneri HTTPS-lõpp-punkti sert | `edelivery` väljuv TLS: JVM-i vaike-CA-d + kõik registri `tlsCert`-id. Vaja ainult siis, kui sert ei ole avalikult usaldatud CA-st |
| `allowedChainAnchors` + `trust-anchors/<alias>.pem` | CA-ahela usaldus (ainult Turvis); puudub = pinning | `turvis-trust` |

Turvis `turvis`-režiimis: kui partner kasutab CA-serti, lisa CA avalik sert `registry/trust-anchors/<alias>.pem`
(peab olema CA, `CA:TRUE`) ja partneri failis `allowedChainAnchors: [<alias>]`. Muidu kehtib pinning.

---

## C. Kontroll ja aegumine

### Registri ehitus

Konverter valideerib kõik sertid (PEM kuju, parsitav, kehtiv, mitte veel-mitte-kehtiv) ning
**ebaõnnestumine blokeerib ehituse**.

```bash
python3 scripts/registry-to-json.py                 # ainult valideeri, ei kirjuta midagi
docker compose up --build registry turvis-trust     # ehita mõlemad image'id
```

- **Aegunud või veel mittekehtiv sert (ka `DISABLED` värava oma) peatab mõlema image'i ehituse**, ka siis, kui
  deploy'itakse hoopis muud muudatust. Lahendus: asenda sert või kustuta värava fail.
- Sert, mis on kahe värava vahel jagatud, annab hoiatuse (`WARNING`); tootmisregistris seda ei tohi olla.
  Dev `EU-EE`/`EU-MOCK` jagavad sama serti ja see **aegub 2027-08-25**, pärast seda CI kukub kuni asendamiseni.
- Ilma `eDeliveryCert`-ita värav jääb AS4 osapoolte ja Turvis-vaate hulgast välja.

### Pärast paigaldust

- `edelivery` logib käivitusel `partyId: <OWN_GATE_ID>, KeyIdentifier/SKI: …, SerialNumber: …`. `SerialNumber`
  on kümnendkujul; `openssl x509 -in own.crt -noout -serial` annab selle heksana. Peavad vastama.
- Kui rida puudub ja pod jääb taaskäivitustsüklisse: fail puudub, vale `KEYSTORE_PASSWORD` või alias ≠ `OWN_GATE_ID`.
- Partneri poolel: sõnumid lähevad läbi, kui nad on meie `own.crt` oma registrisse pannud.

### Aegumise jälgimine

Sertifikaadi aegumise metrikat ei ole. Pane kalendrisse tähtaeg 30 päeva enne iga serdi `notAfter`
(oma sert ja kõik partnerite serdid):

```bash
openssl x509 -in registry-cert.crt -noout -enddate
```

Veaotsing: [`docs/runbooks/certificate-expiry.md`](../../runbooks/certificate-expiry.md),
[`docs/runbooks/edelivery-communication-failure.md`](../../runbooks/edelivery-communication-failure.md).

## Viited

- Registri väljad ja ehitus: [`registry/README.md`](../../../registry/README.md); otsus:
  [ADR-011](../../architecture/decisions/011-registries-as-signed-config.md)
- Kõik teised saladused ja Secretite haldus: [`resource-and-secrets-requirements.md`](resource-and-secrets-requirements.md)
- Turvis usaldusvaade: [`docs/specs/turvis/SPEC.md`](../turvis/SPEC.md) §7.2–7.4
