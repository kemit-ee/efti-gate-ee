# eFTI Gate (EE) — Project Overview

Map of the 9 themes and 26 epics that make up the v2 spec. For artifacts and design rules, see [`README.md`](README.md).

| # | Theme | Epic |
|---|---|---|
| T1 | Identity & Access | [E1 User Management & RBAC](docs/cfr/identity-and-access/user_management_and_rbac.md) |
| | | [E2 Authentication](docs/cfr/identity-and-access/authentication.md) |
| | | [E23 Auth & Access Flows](docs/cfr/identity-and-access/authentication_and_access_flows.md) |
| T2 | Core Functionality | [E3 Identifier Management — Platform API](docs/cfr/core-functionality/identifier_management.md) |
| | | [E4 Identifier Search — Authority API](docs/cfr/core-functionality/identifier_search.md) |
| | | [E5 Dataset Retrieval & Follow-up](docs/cfr/core-functionality/dataset_retrieval_and_follow_up.md) |
| | | [E24 Search & Retrieval Flows](docs/cfr/core-functionality/search_and_retrieval_flows.md) |
| T3 | Registry Management | [E6 Gate Registry](docs/cfr/registry-management/gate_registry.md) |
| | | [E7 Platform Registry](docs/cfr/registry-management/platform_registry.md) |
| | | [E8 Authority Registry](docs/cfr/registry-management/authority_registry.md) |
| | | [E9 Consignment Management](docs/cfr/registry-management/consignment_management.md) |
| T4 | Integrations | [E10 eDelivery AS4](docs/cfr/integrations/edelivery_as4.md) |
| | | [E11 X-Road — EE extension](docs/cfr/integrations/x_road_integration.md) |
| | | [E25 AS4 Message Flow](docs/cfr/integrations/as4_message_flow.md) |
| T5 | Infrastructure | [E12 Scalability & Statelessness](docs/cfr/infrastructure/scalability_and_statelessness.md) |
| | | [E13 Health Checks & Graceful Shutdown](docs/cfr/infrastructure/health_checks_and_graceful_shutdown.md) |
| | | [E26 Append-Only Archival — via CronManager](docs/cfr/infrastructure/append_only_archival.md) |
| T6 | Security & Compliance | [E14 Security](docs/cfr/security-and-compliance/security.md) |
| | | [E15 Audit & GDPR](docs/cfr/security-and-compliance/audit_and_gdpr.md) |
| T7 | Observability | [E16 Logging & Observability](docs/cfr/observability/logging_and_observability.md) |
| | | [E17 Monitoring & Alerting](docs/cfr/observability/monitoring_and_alerting.md) |
| T8 | Software Quality | [E18 Test Coverage & Quality](docs/cfr/software-quality/test_coverage_and_quality.md) |
| | | [E19 API Standardisation](docs/cfr/software-quality/api_standardisation.md) |
| | | [E20 CI/CD & Supply Chain](docs/cfr/software-quality/ci_cd_and_supply_chain.md) |
| T9 | User Interfaces | [E21 Authority UI — AAP / H2M](docs/cfr/user-interfaces/authority_ui.md) |
| | | [E22 Admin UI](docs/cfr/user-interfaces/admin_ui.md) |

**Totals:** 9 themes, 26 epics. Canonical per-epic acceptance criteria live in [`docs/cfr/`](docs/cfr/).
