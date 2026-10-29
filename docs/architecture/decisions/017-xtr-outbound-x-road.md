# ADR-017: Väljaminev X-Tee REST — XTR vahekomponendina

**Otsus (29.10.2026):** Värav lisab valmiskomponendi **XTR** (`turnerrainer/xtr`, pinnitud `0.5.1-rc`),
mille kaudu liiguvad kõik **väljaminevad** X-Tee REST päringud: Ruuter → XTR (tavaline HTTP sisevõrgus) →
turvaserver (mTLS) → teenusepakkuja. Sissetulev suund (turvaserver → `ruuter-xroad` `/xroad/**`, [ADR-006](006-xroad-identity-and-subsets.md))
jääb muutmata ega käi XTR-i kaudu.

## Kontekst

Värav oli X-Tees ainult teenusepakkuja: turvaserver kutsub `xroad` projekti, väljaspoole X-Tee kaudu
ei pöördutud. Väljaminevaks suunaks on vaja mTLS-i turvaserveriga ja X-Road REST protokolli päiseid
(`X-Road-Client`, `X-Road-Id`), mida Ruuter ise ei oska (kliendisertifikaat, URL-i kokkupanek
`/r1/<instance>/<class>/<code>/<subsystem>/<service>`).

## Otsus

1. **XTR on ainus mTLS-identiteedi hoidja turvaserveri suunas.** Kliendi PKCS12 ja turvaserveri CA
   (`xtr/ssl`, ei kuulu repo ega image'i kihtidesse) on ainult XTR-il; sertifikaadi roteerimine puudutab ainult teda.
2. **`X-Road-Client` seatakse alati XTR-i konfist** (`xtr/xtr.yaml` `client_data` + `xroad_instance`);
   kutsuja antud väärtus eemaldatakse. `X-Road-Id` edastatakse, kui kutsuja selle annab, muidu genereerib XTR UUID-i.
   Keha, päised ja query lähevad muutmata edasi (REST passthrough).
3. **Üks teenus = üks DSL-fail** (`xtr/DSL/<group>/<service>.yml`, `kind: rest`); prefiksi- ega wildcard-läbilasku pole.
   Kuvatav URL XTR-is on `/<group>/<service>`. Config ja DSL loetakse boot'il, muutus vajab `docker compose up --build xtr`.
4. **Ainult sisevõrk.** XTR-il pole avaldatud porti (dev: ainult `127.0.0.1`). Ruuter kutsub teda
   `Authorization: Bearer XTR_INTER_SERVICE_TOKEN`-iga (`constants.ini`); `/health` ja `/api` on tokenist vabad.
5. **Dev ja test kasutavad mõlemad `ee-test` instantsi** (puhast dev-turvaserverit ei ole); `client_data` ja
   turvaserveri URL on seni kohahoidjad (TODO enne esimest kasutust).
6. **Vead.** XTR-i enda vead on `{"error", "message"}` (404 `template_not_found`, 405, 413, 502 `upstream_body_too_large`,
   504 `upstream_timeout`; võrgutõrge turvaserveri poole on **500** `internal_error`); teenusepakkuja ja turvaserveri
   4xx/5xx lähevad **muutmata** läbi (`X-Road-Error` päisega). Ruuteri väljaminev DSL peab need ise `errors.json` koodidele
   kaardistama (`GATEWAY_UNAVAILABLE` 502); `ruuter.yaml` `http_codes_allow_list` peab sisaldama kõiki edasi antavaid staatuseid.

## Piirangud

- **Sissetulev REST puudub XTR-is.** XTR 0.5.x sissetulev lane on ainult SOAP 1.1 (`/soap-in/`); REST on dokumenteeritud
  ainult väljaminevana. Turvaserver → XTR → Ruuter ei ole võimalik; selleks on vaja upstreami tuge.
- Keystore peab olema PKCS12 3DES/SHA1 (`openssl pkcs12 -export -legacy` või `-certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1`);
  OpenSSL 3 vaikimisi AES-iga fail annab käivitusel `keystore load failed: ... builder error`.
  `trust_ca_path` fail sisaldab **täpselt ühe** sertifikaadi (XTR loeb ainult esimese).
- Metrics puudub (`/health`, access-log `traceparent`-iga); Prometheus ei kraabi XTR-i.
- Tarbija ees ei autendi XTR kutsujat peale jagatud tokeni; seega kasutab seda ainult Ruuter.

## Seos teiste otsustega

[ADR-006](006-xroad-identity-and-subsets.md) turvanõue (`/xroad/**` ainult turvaserverile) ei muutu. Esimene iteratsioon sisaldab
infrastruktuuri, näide-DSL-i `xtr/DSL/example/echo.yml` ja Ruuteri proovi `GET /efti/api/v1/xtr/ready`; päris teenused lisatakse eraldi.
