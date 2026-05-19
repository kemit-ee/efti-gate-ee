# Security Policy

This repository contains the **specification corpus** for the **eFTI Gate (EE)** — the Estonian national node in the EU eFTI network. It is not a running deployment. Bugs and vulnerabilities in any live gate deployment must be reported to the operator running that deployment (in Estonia: the operator of the gate built against this spec; in other Member States: their respective national gate operator).

## Reporting a weakness in the specification

If you believe a specification artifact in this repository contains a security weakness — for example, an unsafe API contract, a permissions-matrix gap, a logging spec that prescribes recording sensitive data, an authentication flow that lets a credential escape its intended scope — please report it privately rather than via a public issue:

- **Primary:** [@turnerrainer](https://github.com/turnerrainer) on GitHub (DM / mention in a private security advisory).
- **Fallback:** `help@kemit.ee`.

KeMIT will acknowledge within 5 working days and coordinate a fix or clarification.

For sensitive reports, prefer GitHub's [private security advisory](https://docs.github.com/en/code-security/security-advisories) flow on this repository rather than email.

## Out of scope

- Bugs in any production deployment running an implementation of this spec — those belong to the operator of that deployment.
- Weaknesses in upstream EU-level protocols (eDelivery AS4, TARA OIDC, mTLS, X-Road) — report upstream to the relevant maintainer.
- Implementation choices that the spec leaves open by design (e.g. concrete Helm/k8s manifests, threat model, on-call runbook) — see the "Open issues" section of [`README.md`](README.md).

## Disclosure

KeMIT prefers coordinated disclosure. We will work with you on a timeline that balances downstream operator-protection (Member-State gate deployments may need lead time) with the public interest in transparency.
