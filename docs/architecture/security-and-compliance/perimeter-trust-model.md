# Perimeter trust model: edelivery, Turvis and the XML that flows between them

Status: written for #307 (steps 1-4). Nothing here is **accepted** until #309 (mTLS mesh, NetworkPolicy) is enforced and probed; the Turvis integration itself ships dark (`TURVIS_INTEGRATION_MODE=legacy` by default) and must not be switched on before real-service interoperability (#308). Contract: [`docs/specs/turvis/SPEC.md`](../../specs/turvis/SPEC.md) (§2, §7.2-7.4, §9, §13). Machine check: `scripts/validate-xml-surface.py` with `scripts/xml-surface-allowlist.txt`.

## 1. Two trust boundaries

| Boundary | What it covers | Parser posture |
|---|---|---|
| **edelivery-authored XML** | the SOAP envelope and receipt edelivery templates itself (`EDeliveryMessageGenerator`), built from trusted values (own config, ids it generated; the received `MessageId` is XML-escaped) | trusted input; an unhardened parser would be acceptable, and edelivery has none on this path |
| **peer-authored bytes** | the AS4 multipart a peer posts to `/services/msh`: envelope, WS-Security block, encrypted attachment | in `turvis` mode edelivery runs **no XML parser** on them: it MIME-splits (`MimeSplitter`, SPEC 9.3) and **Turvis is the sole parser** of the envelope. edelivery does apply regular expressions in two places: the root tag of the verified, decrypted payload (`rootTagRegex`) and the fault text of a peer's unverified HTTP answer to an outbound send (`EDeliveryClient`); both are in the audit sheet (6.1). In `legacy`/`shadow` mode the in-process legacy path still parses them (see section 5) until #312 removes it |

mTLS and NetworkPolicy authenticate *who* talks. They never sanitise *content*: a signature proves the signer, not that the business XML inside is safe. Nothing in this document claims otherwise.

## 2. Traffic model (single source of truth for #307 and #309)

| # | Path | Carries | Trust basis |
|---|---|---|---|
| 1 | peer gate -> ingress / Ruuter (A8) -> edelivery `/services/msh` | raw peer-authored AS4 bytes | A8 envelope/size checks only; in `turvis` mode edelivery MIME-splits and does not parse the SOAP envelope |
| 2 | edelivery -> Turvis (`/turvis/v1/{decryptions,verifications}/...`, `{encryptions,signatures}`) | peer bytes in, JSON verdicts out | interim: dedicated `TURVIS_INTERNAL_TOKEN` (SPEC 11.2); target: mTLS mesh identity (#309). Turvis is the sole parser of peer-authored XML |
| 3 | Turvis response consumed as-is | `signer`, `ebms_routing`, `signature_digests` (+ verbatim `ds:Reference`), decrypted bytes | origin authenticated by the mesh and egress policy (#309); **edelivery re-verifies nothing**. Until #309 is verified this trust is not justified |
| 4 | edelivery -> peer gates (AS4 send) | Turvis-signed bytes, sent as-is | egress must allow the registered peer URLs from `registry/`, not only Turvis |
| 5 | edelivery -> `registry` / `resql` / `ruuter` / `xml-mapper` / DB | internal | existing internal network |
| 6 | edelivery/Ruuter -> xml-mapper | **decrypted, signature-verified business XML** (FTIxxx) | the signature proves the signer, not that the XML is safe: xml-mapper's parser is an explicit audit item (section 4, decision proposed in 4.2) |
| 7 | Turvis -> KMS; Turvis <- trust-view artifact (SPEC 7.2) | key use; public identity data | Turvis has **no** access to the `registry` service (it holds plaintext platform `apiKey`s, ADR-011 section 5) |

Consequences:
- edelivery ingress is **not** restricted to Turvis. It is restricted to the A8 ingress/Ruuter and the documented internal callers; Turvis only answers requests.
- edelivery egress is **not** restricted to Turvis. It is default-deny with explicit allows for Turvis, the internal services and the registered peer gate URLs (path 4).
- Path 3 cannot be relied on before #309 is verified. The code comments at each Turvis call site say so.

## 3. Signer trust (SPEC 7.4, 9.4)

- A signer is accepted only if its certificate satisfies the party's trust mode (pinning to `cert_pem` by default, or chain to one of the party's `allowed_chain_anchors`), its `verified_identity` equals a `parties[]` entry of the trust view, and that party is `ONLINE`. Failure is `SIGNER_UNTRUSTED`.
- Business authorisation uses `signer.verified_identity`, never the envelope `From` (SPEC 13.9): `TurvisInbound` additionally requires envelope `From` == `verified_identity`.
- **Deny wins** across edelivery's registry view and Turvis's trust view (SPEC 7.4): a party is usable only if both say `ONLINE`; generations may differ briefly (5-minute alert) and neither side widens the other's decision.
- `registry/` is the only hand-edited source; Turvis receives a generated, allowlist-filtered `turvis-trust.json` without `apiKey` (#315/#316, ADR-011 amendment). Phase-2 integrity (signed manifest, SPEC 7.2) is open.

## 4. Path 6: the xml-mapper parser

### 4.1 What was found (audit, 2026-10-22)

xml-mapper is the only **server-side** place that parses eFTI business XML (`code/xml-mapper/src/efti/xml/fti/FTIMessages.kt`, klite `XmlParser`). klite-xml 2.1.0 builds its StAX factory through `secureXMLInputFactory` (`javax.xml.stream.supportDTD=false`, `isSupportingExternalEntities=false`). DTDs are therefore **not processed** (a DOCTYPE with declarations but no reference parses and expands nothing), and an entity reference is then undeclared and refused with an `XMLStreamException`, so nothing is expanded: no XXE and no entity bomb. The XML a peer controls reaches the parser through the `*-to-json` endpoints (callers in 6.3). `XmlParserHardeningTest` pins this, including the refusal's exception type. Business XML is also read outside xml-mapper in the browser (admin UI `XmlViewer.svelte`, `shared/xmlParser.ts`: `DOMParser`, rendered with `textContent` only, so no HTML injection) and by Python dev tooling (generators and tests, not deployed); both are listed or named here so the claim "xml-mapper is the only server-side parser" stays checkable. The legacy edelivery parser (`SignatureValidator`) is hardened as well (`disallow-doctype-decl`, no external DTD/schema); SPEC 9.3's wording "explicitly not hardened" describes the design assumption, not the legacy code.

Residual points an audit must not skip: there is no XSD validation in the mapping path (schema enforcement is F-PV-5, out of scope); element-depth/size limits are the HTTP body caps (klite `bodySizeLimit`, Ruuter 16 MiB) and the Turvis payload budget (SPEC 13.8), not parser settings.

### 4.2 Decision (proposed, needs sign-off)

Keep the klite parser as the path-6 parser, because it already refuses DTDs, with these guards: `XmlParserHardeningTest` in CI; the allowlist check (section 7) so a second parser or a new caller cannot appear unseen; the body-size caps above. Revisit only if SPEC grows a hardened-parse endpoint in Turvis (then peer-authored business XML would be parsed there and xml-mapper would see Turvis's output). **Not recorded as decided until the owners approve it**; #307's path-6 acceptance criterion stays open until then.

## 5. Residual legacy paths (until #312)

`legacy` (default) and `shadow` modes keep the in-process path: `EDeliveryRoutes.msh` parses the peer envelope with klite `XmlParser` and `SignatureValidator` verifies the XMLDSig with a DOM parser; `KeyManager.ownPrivateKey` is the in-process key. Both are superseded by Turvis in `turvis` mode and deleted by #312, which is not started and must not start before the #313 GO. Comments at those sites point here.

## 6. Audit sheet

Regenerate the raw lists with `python3 scripts/validate-xml-surface.py --print`. The allowlist file is the reviewed, machine-checked form of this sheet.

### 6.1 XML parser and signature sites in code

| Site | What it parses | Trust chain | Mode |
|---|---|---|---|
| `EDeliveryRoutes.kt` `XmlParser()` | peer ebMS header | peer bytes, unverified until `SignatureValidator` | legacy/shadow only |
| `SignatureValidator.kt` `DocumentBuilderFactory`, `XMLSignatureFactory` | peer envelope (XMLDSig verify) | peer bytes; hardened DOM | legacy/shadow only |
| `xml-mapper FTIMessages.kt` `XmlParser` | eFTI FTI004/009/010/019/021/025/029/030 | after decrypt+verify (peer gates, also FTI004 uploads that arrive over AS4) or platform API key (direct uploads) | all modes |
| `EDeliveryClient.kt` two regexes (`faultReasonRegex`, `errorDetailRegex`) | fault text of a peer's **unverified** HTTP answer to an outbound send | peer bytes, no verification; only a name the gate knows (`FaultReason`, as its own `soapFault` writes it) and an ebMS code from the `errorCode` attribute are logged and put into the `IOException`, anything else is `unrecognised`, the scan covers the first 64 KiB with non-backtracking regexes, and the answer itself is represented by `body_sha256` and `body_size_bytes` (#322 follow-up) | all modes |
| `ReferenceXml.kt` two regexes | shape check of the `ds:Reference` fragment Turvis returns for the receipt (not a parser; well-formedness rests on Turvis's `sign`, SPEC 4.2) | Turvis's answer, itself derived from the verified peer envelope | turvis |
| `EDeliveryRoutes.kt` `rootTagRegex` | root tag of the decrypted payload | after verify (turvis) or legacy verify | all modes |
| admin UI `XmlViewer.svelte`, `shared/xmlParser.ts` `DOMParser` | stored consignment XML for display | admin user's browser, `textContent` rendering | UI |

### 6.2 Endpoints that receive XML

| Endpoint | Sender | Parses? |
|---|---|---|
| edelivery `POST /services/msh` | peer gates (via A8) | legacy: yes; turvis: no (MIME split only) |
| edelivery internal send (`InternalRoutes`) | Ruuter/xml-mapper output (gate-authored) | no |
| Ruuter `efti/POST/api/v1/{dataset,follow-up}-xml`, `consignments/search-xml` | edelivery dispatch of a verified peer message | no (forwards to xml-mapper) |
| Ruuter `platforms/POST/v1/consignments` | platform (API key) | no (forwards to xml-mapper) |
| Ruuter `platforms/POST/v1/consignments-xml` | edelivery dispatch of a verified peer FTI004 upload (`EftiMessageHandlers`, `RuuterClient.saveConsignment`) | no (templates `consignments`, which forwards to xml-mapper) |
| xml-mapper `*/request-to-json`, `*/response-to-json` | Ruuter | **yes** (path 6) |

### 6.3 xml-mapper callers (Ruuter DSL)

Listed with their reason in `scripts/xml-surface-allowlist.txt` (kind `xml-mapper-call`): 14 DSL files, of which those calling a `*-to-json` endpoint parse XML (authority dataset/follow-up/search replies from peers, G2G inbound requests, the platform upload) and the rest only build XML or normalise JSON.

### 6.4 Turvis call sites

| Call site | SPEC endpoint | Policy (SPEC 9.2 / 13.5) |
|---|---|---|
| `TurvisInbound` decrypt | 4.4 `decryptions/xmlenc`, no routing requested | the received envelope and attachment bytes, verbatim |
| `TurvisInbound` verify | 4.1 `verifications/xmldsig` | `//eb:Messaging`, `//env:Body`, `cid:message`; `signer`, `ebms_routing`, `signature_digests`, matched lists |
| `TurvisOutbound` encrypt | 4.3 `encryptions/xmlenc`, profile `wss-encrypted` | gzip(payload) for the receiver |
| `TurvisOutbound` sign (request) | 4.2 `signatures/xmldsig` | `//eb:Messaging`, `//env:Body`, `cid:message` with the gzip bytes as attachment |
| `TurvisOutbound` sign (receipt) | 4.2 `signatures/xmldsig` | `//eb:Messaging`, `//env:Body`, no attachment |
| `TurvisClient` | all four URL literals | transport, retries, response validation |

## 7. CI governance

`scripts/validate-xml-surface.py` (unit tests: `tests/xmlsurface/test_validate_xml_surface.py`) is **a tripwire for the common forms; code review remains the control**. It greps `code/` (Kotlin, Java, `.kts`, UI TypeScript/Svelte; tests, `node_modules` and build output excluded) and the Ruuter DSL on every CI run (GitHub `dsl-validate`, step "XML surface governance (#307)"; GitLab `validate:dsl`, which runs on the default branch and `release/*` only, so the pull-request gate is the GitHub step) and fails with `file:line` when it finds, without an entry in `scripts/xml-surface-allowlist.txt`: an XML parser, factory, validator or signature API, an import alias of one, a regex over XML tags, a DI-injected parser; a `.decrypt(`/`.verify(`/`.encrypt(`/`.sign(` call or a `/turvis/v1` URL in code, or a `/turvis/v1` URL in a Ruuter DSL (the mock excepted); or a Ruuter DSL that calls xml-mapper (constant, host or port). Each entry records the hit count and a fingerprint of the hit lines' text, so a moved line passes but one added and one removed in the same file, a changed count or a stale entry fails. It does **not** see reflection, a parser inside a dependency, Python tooling or any other language; it is separate from the SPEC-vs-DSL drift check of #306.

## 8. What is still open

- Step 5 of #307 (A3 verification) and every finding closure (F-PV-3, F-XM-1, F-XM-2) need #309's actual delivery and probes in both directions; the mock closes none of them.
- The path-6 decision (4.2) needs the owners' approval.
