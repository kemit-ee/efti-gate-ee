# xtr/ssl

Mounted read-only at `/app/ssl` in the `xtr` container. Nothing here except this file is committed
(`.gitignore`), and none of it enters an image layer (`.dockerignore`).

| File | What |
|------|------|
| `xtr-client.p12` | PKCS12 information-system client key (`sign` + `auth`) exported from the Security Server admin UI, **3DES/SHA1** (`openssl pkcs12 -export -legacy ...`; the OpenSSL 3 default AES format fails with `keystore load failed`). Password in `XTR_KEYSTORE_PASSWORD`. |
| `xroad-ca.pem` | The Security Server's TLS certificate or its CA, **exactly one certificate**. |
