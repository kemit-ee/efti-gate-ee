# eFTI Gate v2 — Project Overview

Visual map of the 9 themes and 25 epics that make up the v2 spec. For artifacts and design rules, see [`docs/planning/SPEC-INDEX.md`](docs/planning/SPEC-INDEX.md).

```mermaid
graph TB
    subgraph T1["THEME 1 — Identity & Access"]
        direction TB
        E1[EPIC 1<br/>User Management & RBAC]
        E2[EPIC 2<br/>Authentication]
        E23[EPIC 23<br/>Auth & Access Flows]
    end

    subgraph T2["THEME 2 — Core Functionality"]
        direction TB
        E3[EPIC 3<br/>Identifier Management<br/><i>Platform API</i>]
        E4[EPIC 4<br/>Identifier Search<br/><i>Authority API</i>]
        E5[EPIC 5<br/>Dataset Retrieval<br/>& Follow-up]
        E24[EPIC 24<br/>Search & Retrieval Flows]
    end

    subgraph T3["THEME 3 — Registry Management"]
        direction TB
        E6[EPIC 6<br/>Gate Registry]
        E7[EPIC 7<br/>Platform Registry]
        E8[EPIC 8<br/>Authority Registry]
        E9[EPIC 9<br/>Consignment Management]
    end

    subgraph T4["THEME 4 — Integrations"]
        direction TB
        E10[EPIC 10<br/>eDelivery AS4]
        E11[EPIC 11<br/>X-Road<br/><i>EE extension</i>]
        E25[EPIC 25<br/>AS4 Message Flow]
    end

    subgraph T5["THEME 5 — Infrastructure"]
        direction TB
        E12[EPIC 12<br/>Scalability<br/>& Statelessness]
        E13[EPIC 13<br/>Health Checks<br/>& Graceful Shutdown]
    end

    subgraph T6["THEME 6 — Security & Compliance"]
        direction TB
        E14[EPIC 14<br/>Security]
        E15[EPIC 15<br/>Audit & GDPR]
    end

    subgraph T7["THEME 7 — Observability"]
        direction TB
        E16[EPIC 16<br/>Logging<br/>& Observability]
        E17[EPIC 17<br/>Monitoring<br/>& Alerting]
    end

    subgraph T8["THEME 8 — Software Quality"]
        direction TB
        E18[EPIC 18<br/>Test Coverage<br/>& Quality]
        E19[EPIC 19<br/>API Standardisation]
        E20[EPIC 20<br/>CI/CD<br/>& Supply Chain]
    end

    subgraph T9["THEME 9 — User Interfaces"]
        direction TB
        E21[EPIC 21<br/>Authority UI<br/><i>AAP — H2M</i>]
        E22[EPIC 22<br/>Admin UI]
    end

    classDef theme fill:#e8f4f8,stroke:#2b6cb0,stroke-width:2px
    classDef epic  fill:#fff,stroke:#4a5568,stroke-width:1px,cursor:pointer
    class T1,T2,T3,T4,T5,T6,T7,T8,T9 theme
    class E1,E2,E3,E4,E5,E6,E7,E8,E9,E10,E11,E12,E13,E14,E15,E16,E17,E18,E19,E20,E21,E22,E23,E24,E25 epic

    click E1  "docs/epics/epic_1_en.md"  "User Management & RBAC"
    click E2  "docs/epics/epic_2_en.md"  "Authentication"
    click E3  "docs/epics/epic_3_en.md"  "Identifier Management (Platform API)"
    click E4  "docs/epics/epic_4_en.md"  "Identifier Search (Authority API)"
    click E5  "docs/epics/epic_5_en.md"  "Dataset Retrieval & Follow-up"
    click E6  "docs/epics/epic_6_en.md"  "Gate Registry Management (Admin API)"
    click E7  "docs/epics/epic_7_en.md"  "Platform Registry Management (Admin API)"
    click E8  "docs/epics/epic_8_en.md"  "Authority Registry Management (Admin API)"
    click E9  "docs/epics/epic_9_en.md"  "Consignment Management (Admin API)"
    click E10 "docs/epics/epic_10_en.md" "eDelivery AS4 Integration"
    click E11 "docs/epics/epic_11_en.md" "X-Road Integration (EE extension)"
    click E12 "docs/epics/epic_12_en.md" "Scalability & Statelessness"
    click E13 "docs/epics/epic_13_en.md" "Health Checks & Graceful Shutdown"
    click E14 "docs/epics/epic_14_en.md" "Security"
    click E15 "docs/epics/epic_15_en.md" "Audit & GDPR Compliance"
    click E16 "docs/epics/epic_16_en.md" "Logging & Observability"
    click E17 "docs/epics/epic_17_en.md" "Monitoring & Alerting"
    click E18 "docs/epics/epic_18_en.md" "Test Coverage & Quality"
    click E19 "docs/epics/epic_19_en.md" "API Standardisation"
    click E20 "docs/epics/epic_20_en.md" "CI/CD & Supply Chain Security"
    click E21 "docs/epics/epic_21_en.md" "Authority UI (AAP — H2M)"
    click E22 "docs/epics/epic_22_en.md" "Admin UI"
    click E23 "docs/epics/epic_23_en.md" "Authentication & Access Flows"
    click E24 "docs/epics/epic_24_en.md" "Identifier Search & Dataset Retrieval Flows"
    click E25 "docs/epics/epic_25_en.md" "eDelivery AS4 Message Flow"
```

**Totals:** 9 themes, 25 epics. Per-epic acceptance criteria live in [`docs/epics/`](docs/epics/) (split files) and [`docs/efti_full_epics_en.md`](docs/efti_full_epics_en.md) (canonical).
