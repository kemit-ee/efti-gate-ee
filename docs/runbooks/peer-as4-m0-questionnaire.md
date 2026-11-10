# Peer AS4 questionnaire (M0a-M0d)

Send this to the operator of a peer gate before the first AS4 exchange with it. The answers decide which of
the conditional legacy-mode fixes in issue #392 are needed (S2 Service/Action, S3 verbatim reference splice,
BinarySecurityToken). The connection test itself is in [external-gate-interop-test.md](external-gate-interop-test.md).

Our side: gate id `EU-EE`, AS4 endpoint `https://<our-domain>/services/msh`, SOAP 1.2, WS-Security 1.1
(XML Signature RSA-SHA256 with exclusive c14n, SHA-256 digests; XML Encryption AES-128-GCM; key transport RSA-OAEP, xmlenc11, with SHA-256 digest and MGF1-SHA256).

## M0a - Registering the peer (what we need from you)

1. Your **gate id** (the AS4 PartyId you put in `eb:From`, exactly as you will send it).
2. Your **eDelivery URL** (the HTTPS endpoint that accepts our AS4 messages).
3. Your **public AS4 signing/encryption certificate** (PEM). We use it to verify your signatures and to encrypt
   what we send you.

We register these in our gate registry. Until the peer is registered every inbound message from
it is answered with `OTHER` / EBMS:0004, whatever else is correct.
We send you our public certificate and AS4 endpoint separately.

## M0b - A sample signed envelope

Please send one real signed AS4 UserMessage envelope your gate produces (the SOAP part; the payload may be a dummy).
From it we read:
- how many `ds:Reference` elements you sign and in which order (we answer with a receipt that acknowledges every one);
- which `ds:Transform` algorithms and `InclusiveNamespaces/@PrefixList` each reference carries, and the `DigestMethod`;
- whether your `eb:ConversationId` is a UUID or another string (we accept any string up to 255 characters);
- the `eb:Service` / `eb:Action` literals you use (M0c);
- how your signer certificate is referenced (M0d).

## M0c - eb:Service and eb:Action

Our current defaults are `eb:Service` = `bdx:noprocess` with type `tc1`, and `eb:Action` = `eftiGateAction` for every
message type. The target profile (`docs/specs/non-functional.md`) names six Action literals under the service
namespace `https://api.efti.ee/services/`: `identifierQuery`, `identifierResponse`, `uilQuery`, `uilResponse`,
`postFollowUpRequest`, `followUpResponse`.
Please confirm:
1. Does your PMode accept our defaults, or does it require the Service/Action literals above (strict)?
2. The exact `eb:Service` value **and its `type` attribute** (we send `tc1`).
3. Which Action you send for each of dataset query/response, identifier search/response, follow-up/response, upload/response.

## M0d - How you resolve our signer certificate

We reference our signing certificate in `wsse:SecurityTokenReference` by SKI (`wsse:KeyIdentifier`, ValueType
X509SubjectKeyIdentifier), and do not send a `wsse:BinarySecurityToken`.
Please confirm whether your resolver binds our certificate by SKI against the certificate you registered, or whether you
require a `wsse:BinarySecurityToken` (X509v3) in the message. For the messages you send us: we take your signing certificate from our registry by your `eb:From` PartyId (a certificate in the message is not used), and your `xenc:EncryptedKey` must reference our certificate either by SKI (`wsse:KeyIdentifier`) or by X509 serial number, matching the certificate we sent you.
