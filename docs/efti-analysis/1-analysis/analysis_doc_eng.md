---
title: "Estonian eFTI Gate Analysis"
format:
  docx:
    reference-doc: ref.docx
    toc: false
    number-sections: false
---

## 1. Introduction
### 1.1 Goal
The primary objective of this project is to implement the Estonian national eFTI (Electronic Freight Transport Information) Gate, transitioning from a Proof of Concept (POC) to a live, production-ready infrastructure. The Estonian eFTI Gate is designed to serve as a central sovereign hub for the exchange of regulatory freight transport information between eFTI platforms used by economic operators, Estonian competent authorities and other EU Member State competent authorities through the eFTI exchange environment. The objective is to provide a high-performance, cost-efficient solution that ensures full compliance with EU Regulation 2020/1056 and helps reduce administrative burden through digitalization for Estonian competent authorities, logistics and transport sector.

### 1.2 Mission and Vision
The Estonian eFTI Gate aims to provide a seamless, digital-first infrastructure for freight transport information exchange that reduces administrative burden and enables real-time oversight for European member states Competent Authorities and economic operators.

### 1.3 Scope
- **EU Interoperability**: Full integration with the EU-wide eFTI exchange environment via secure eDelivery/AS4 protocols, ensuring seamless cross-border data access.
- **National Competent Authority Integration**: Providing secure, logged access to freight transport data for Estonian agencies via **X-Road (national data exchange layer)** and dedicated REST interfaces. X-Road serves as the primary backbone for inter-agency communication, ensuring non-repudiation and secure authentication for:
    - Estonian Tax and Customs Board (MTA)
    - Estonian Police and Border Guard Board (PPA)
    - Estonian Environmental Board
    - Estonian Transport Administration
- **eFTI Platform Connectivity**: Standardized REST APIs for certified eFTI Platforms to register identifiers (UIL) and provide authorized data subsets.
- **Data Governance & Retention**: Implementation of Registry of Identifiers (RoI) and subset-based filtering, with data auditing capabilities extending to the period required by the eFTI Regulation (at least 2 years) to meet legal requirements.
- **Performance & Reliability**: A lightweight JVM-based architecture designed to handle high volume of queries with sub-second response times and high availability (HA).

*Note: This document is prepared with development Epics in Jira, that describe full scope*

### 1.4 Key Terms
| Term | Description |
| :--- | :--- |
| **AAP** | Authority Access Point. |
| **ADN** | European Agreement concerning the International Carriage of Dangerous Goods by Inland Waterways |
| **ADR** | European Agreement concerning the International Carriage of Dangerous Goods by Road |
| **ANTS** | Automatic number plate recognition system. |
| **Apollo** | E-police information system. |
| **AS4** | AS4 (Applicability Statement 4) is an open standard for the secure and payload-agnostic exchange of Business-to-business documents using Web services. |
| **CA** | Competent Authority. |
| **CAB** | Conformity Assessment Body. |
| **CMDS** | Consignment Movement Dataset. A uniquely identified set of eFTI data that, together, constitutes the regulatory information on freight transport related to a specific consignment movement.| 
| **Competent Authority** | Means a public authority, agency or other body which is competent to perform tasks pursuant to the legal acts referred to in the eFTI regulation 2020/1056 Article 2(1) and for which access to regulatory information is necessary, such as checking, enforcing, validating or monitoring compliance on the territory of a Member State. |
| **Consignment ID** | The unique identifier of the consignment, as used in the eFTI data set and in the Registry of Identifiers search logic.|
| **DG** | Dangerous Goods. |
| **eAWB** | Electronic Air Waybill. |
| **eCIM** | Electronic rail consignment note based on the Contract for International Carriage of Goods by Rail  (used mainly in the COTIF rail area, including most EU countries) |
| **eCMR** | Electronic consignment note based on the CMR Convention for the international carriage of goods by road |
| **eDelivery** | EU secure messaging infrastructure. |
| **eFTI** | Electronic Freight Transport Information. |
| **eFTI Gate** | An information system, which is part of the eFTI exchange environment, used for the processing of requests for access to regulatory information and of the corresponding responses, including the processing of unique identifiers of eFTI datasets and corresponding UILs. |
| **eFTI Platform** | A solution based on information and communication technology (ICT), such as an operating system, an operating environment, a database or any other type of platform, intended to be used for the processing of eFTI CMDS. |
| **eIDAS** | Regulation (EU) No 910/2014 on electronic identification and trust services for electronic transactions in the internal market. |
| **EMCS** | Excise Movement and Control System. |
| **EO** | Economic Operator. |
| **eSMGS** | EElectronic rail consignment note based on the Agreement on International Goods Transport by Rail (used mainly in the OSJD rail area)  |
| **IAA** | Identification, Authentication, and Authorization. |
| **KeA** | Estonian Environmental Board. |
| **KOTKAS** | Environmental decision information system.|
| **LOIS2** | Aviation Safety Information System. |
| **MTA** | Estonian Tax and Customs Board. |
| **NES** | National Entry System. |
| **PISTRIK** | Environmental decision information system for domestic waste management. Is a subsystem of KOTKAS. |
| **PPA** | Estonian Police and Border Guard Board. |
| **QTSP** | Qualified Trust Service Provider. |
| **RIA** | Estonian Information System Authority |
| **RID** | Regulation concerning the International Carriage of Dangerous Goods by Rail |
| **RoI** | Registry of Identifiers (by eFTI Regulation). |
| **SADHES** | Electronic system used for managing excise goods |
| **TAF TSI** | Technical Specification for Interoperability relating to the telematics applications for freight subsystem of the rail system in the European Union. |
| **TGD Annex1** | Technical Assistance for Facilitating the Implementation of eFTI. Technical Guidance Document Annex 1 Detailed Data Structures Specifications v1.0.3 |
| **TGD Document** | Technical Assistance for Facilitating the Implementation of eFTI. Technical Guidance Document v1.0.5 |
| **TOTS2** | Customs Control Task Management System |
| **TRAM** | Estonian Transport Administration. |
| **UIC** | Rail wagon identification standard (also known as European Vehicle Number - EVN). |
| **UIL** | Unique Identifying Link (URI). |
| **X-Road** | Estonian data exchange layer (X-Tee). |

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 2. Estonian eFTI Gate Description
### 2.1 Strategic Objectives
The Estonian eFTI Gate is designed as a high-performance sovereign hub to enable the exchange of electronic freight transport regulatory information required under EU and national law. It prioritizes:
- **Digital Sovereignty**: Ensure uniform access by competent authorities to freight transport and consignment movement information intended for the performance of supervisory activities.
- **Administrative Efficiency**: Automating compliance checks and processes for Estonian competent authorities and other agencies to reduce roadside inspection times.
- **Regional Leadership**: Setting a technical benchmark for eFTI implementations within the EU through a lightweight, high-availability architecture.

### 2.2 eFTI Requirements
This section outlines the comprehensive technical requirements for the eFTI Gate, categorized by functional domain to support building a compliant system from scratch.

#### 2.2.1 Core Infrastructure & Protocol Requirements
| ID | Requirement | Specification |
| :--- | :--- | :--- |
| **REQ-CORE-01** | **Secure EU Interoperability** | Mandatory implementation of **eDelivery/AS4** protocol for eFTI gate-to-gate and eFTI gate-to-platform communication. |
| **REQ-CORE-02** | **Registry of Identifiers (RoI)** | Maintain a PostgreSQL database of Unique Identifying Links (UIL) mapping freight transport identifiers to eFTI platform endpoints. |
| **REQ-CORE-03** | **Global Broadcast Search** | If an identifier is not found locally, the eFTI Gate must broadcast a query to all other EU Member State eFTI gates via AS4. |
| **REQ-CORE-04** | **Data Persistence Policy** | Only identifiers and UIL are stored in Estonian eFTI Gate. Full CMDS are available only via eFTI platform queries. |
| **REQ-CORE-05** | **X.509 Certificate Management** | Automated handling of WS-Security and mTLS certificates for secure message signing and encryption. |
| **REQ-CORE-06** | **Performance Benchmark** | System must handle queries with <60s end-to-end response time (Commission Implementing Regulation (EU) 2024/1942). |
| **REQ-CORE-07** | **Throughput** | System must be scalable, initially handle high number of queries  (Estonian specific requirement). |

#### 2.2.2 eFTI CMDS Statuses
The eFTI Consignment Movement Dataset (CMDS) undergoes several status changes throughout its lifecycle, which determine its searchability in the Registry of Identifiers (RoI) and the corresponding access rights for Competent Authorities (CA) and Economic Operators (EO). Statuses are described via CMDS lifecycle on an eFTI Platform

| Status | Description | eFTI Gate Interaction | Access Rights |
|---|---|---|---|
| **Draft** | CMDS is being composed on the eFTI platform. Data elements are being entered but the dataset is not yet complete or validated. | None. FTI004 has not been sent. UIL is not yet registered in the eFTI Gate RoI. | **CA:** No access (dataset not visible to eFTI Gate). **EO:** Full create and edit rights on eFTI platform. |
| **Active** | CMDS is validated and complete. FTI004 has been sent to the eFTI Gate, registering the UIL and identifiers in the RoI. The transport operation is ongoing. | Corresponds to eFTI Gate status: Registered. UIL is active and searchable in RoI. | **CA:** Full access via eFTI Gate (search + subset retrieval). **EO:** Edit rights on eFTI platform; updates trigger new FTI004 to eFTI Gate. |
| **Completed** | Transport operation finished. Delivery date (eFTI188) has been recorded. No further edits permitted. eFTI Platform notifies eFTI Gate of completion. | Triggers eFTI Gate transition to Deactivated (extended period for for road transport). | **CA:** Via eFTI Gate (road); via direct UIL if authorized (other modes). **EO:** Read-only on eFTI platform. |
| **Ready to Archive** | CMDS has been completed and deactivated in the eFTI Gate. The dataset awaits the final archiving operation on the eFTI platform. | None. eFTI Gate has already removed UIL from RoI. | **CA:** Accessible via direct UIL for immediate post-transport checks if authorized. **EO:** Authorized to perform Archive CMDS operation. |
| **Archived** | CMDS has been archived on the eFTI platform for long-term retention in accordance with applicable transport legislation retention periods. | None. Dataset is retained on eFTI platform only. | **CA:** Accessible for retrospective audits and ex-post controls via direct eFTI platform access if authorized. Access can only be granted by EO. **EO:** Read-only for compliance and audit purposes. |
| **Cancelled** | CMDS was created in error or consignment voided. The dataset is cancelled on the eFTI platform with an audit trail preserved. Physical deletion is not permitted. | eFTI Platform sends cancellation signal to eFTI Gate. eFTI Gate removes UIL from RoI and logs the cancellation. | **CA:** Not accessible via eFTI Gate. Cancellation traceable via audit logs on both eFTI Gate and eFTI platform. **EO:** No further operations. Audit trail preserved. |

#### 2.2.3 Freight Transport Mode Specific Requirements
The following tables outline the specific searchable identifiers and regulatory requirements for each transport mode supported by the eFTI Gate, in accordance with Article 11 of Implementing Regulation (EU) 2024/1942 and Section 2 of the Annex to Delegated Regulation (EU) 2024/2024.

##### Table 1: Road Transport Requirements (Mode 3)
| ID | eFTI Element | Requirement | Law / Regulation Reference |
| :--- | :--- | :--- | :--- |
| **REQ-ROAD-01** | **eFTI131** | Unique identifier of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-ROAD-02** | **eFTI617** | Identification of the type of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-ROAD-03** | **eFTI618** | Identification of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-ROAD-04** | **eFTI620** | Registration country of the means of transport | Art 11(3)(a)(ix) 2024/1942 |
| **REQ-ROAD-05** | **eFTI378** | Identification of the category of the transport equipment | Art 11(3)(a)(iv) 2024/1942 |
| **REQ-ROAD-06** | **eFTI374** | Identification of the transport equipment | Art 11(3)(a)(iii) 2024/1942 |
| **REQ-ROAD-07** | **eFTI578** | Registration country of the transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-ROAD-08** | **eFTI987** | Sequence number of the transport equipment | Art 11(3)(a)(x) 2024/1942 |
| **REQ-ROAD-09** | **eFTI450** | Identification of the category of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-ROAD-10** | **eFTI448** | Identification of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-ROAD-11** | **eFTI1857**| Registration country of the carried transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-ROAD-12** | **eFTI1000**| Sequence number of the carried transport equipment | Art 11(3)(a)(xi) 2024/1942 |
| **REQ-ROAD-13** | **eFTI39**   | Date and time of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-ROAD-14** | **eFTI146**  | Country of the place of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-ROAD-15** | **eFTI583**  | Date and time of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-ROAD-16** | **eFTI908**  | Country of the place of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-ROAD-17** | **eFTI188**  | Date and time of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-ROAD-18** | **eFTI162**  | Country of the place of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-ROAD-19** | **eFTI596**  | Date and time of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-ROAD-20** | **eFTI917**  | Country of the place of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-ROAD-21** | **eFTI581**  | Main transport mode | Art 11(3)(a)(vi) 2024/1942 |
| **REQ-ROAD-22** | **eFTI1855** | Dangerous goods indicator | Art 11(3)(b) 2024/1942 |
| **REQ-ROAD-23** | **eFTI1400** | UN number | Art 11(3)(b) 2024/1942 |
| **REQ-ROAD-24** | **eFTI1401** | Dangerous goods class | Art 11(3)(b) 2024/1942 |
| **REQ-ROAD-25** | **Mode 3** | UILs for road transport are deactivated only after the period defined in Art 8(2) of Reg 1072/2009 | Art 11(4) 2024/1942 |

##### Table 2: Rail Transport Requirements (Mode 2)
| ID | eFTI Element | Requirement | Law / Regulation Reference |
| :--- | :--- | :--- | :--- |
| **REQ-RAIL-01** | **eFTI131** | Unique identifier of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-RAIL-02** | **eFTI617** | Identification of the type of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-RAIL-03** | **eFTI618** | Identification of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-RAIL-04** | **eFTI620** | Registration country of the means of transport | Art 11(3)(a)(ix) 2024/1942 |
| **REQ-RAIL-05** | **eFTI378** | Identification of the category of the transport equipment | Art 11(3)(a)(iv) 2024/1942 |
| **REQ-RAIL-06** | **eFTI374** | Identification of the transport equipment | Art 11(3)(a)(iii) 2024/1942 |
| **REQ-RAIL-07** | **eFTI578** | Registration country of the transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-RAIL-08** | **eFTI987** | Sequence number of the transport equipment | Art 11(3)(a)(x) 2024/1942 |
| **REQ-RAIL-09** | **eFTI450** | Identification of the category of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-RAIL-10** | **eFTI448** | Identification of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-RAIL-11** | **eFTI1857**| Registration country of the carried transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-RAIL-12** | **eFTI1000**| Sequence number of the carried transport equipment | Art 11(3)(a)(xi) 2024/1942 |
| **REQ-RAIL-13** | **eFTI39**   | Date and time of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-RAIL-14** | **eFTI146**  | Country of the place of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-RAIL-15** | **eFTI583**  | Date and time of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-RAIL-16** | **eFTI908**  | Country of the place of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-RAIL-17** | **eFTI188**  | Date and time of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-RAIL-18** | **eFTI162**  | Country of the place of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-RAIL-19** | **eFTI596**  | Date and time of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-RAIL-20** | **eFTI917**  | Country of the place of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-RAIL-21** | **eFTI581**  | Main transport mode | Art 11(3)(a)(vi) 2024/1942 |
| **REQ-RAIL-22** | **eFTI1855** | Dangerous goods indicator | Art 11(3)(b) 2024/1942 |
| **REQ-RAIL-23** | **eFTI1400** | UN number | Art 11(3)(b) 2024/1942 |
| **REQ-RAIL-24** | **eFTI1401** | Dangerous goods class | Art 11(3)(b) 2024/1942 |

##### Table 3: Air Transport Requirements (Mode 4)
| ID | eFTI Element | Requirement | Law / Regulation Reference |
| :--- | :--- | :--- | :--- |
| **REQ-AIR-01** | **eFTI131** | Unique identifier of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-AIR-02** | **eFTI617** | Identification of the type of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-AIR-03** | **eFTI618** | Identification of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-AIR-04** | **eFTI620** | Registration country of the means of transport | Art 11(3)(a)(ix) 2024/1942 |
| **REQ-AIR-05** | **eFTI378** | Identification of the category of the transport equipment | Art 11(3)(a)(iv) 2024/1942 |
| **REQ-AIR-06** | **eFTI374** | Identification of the transport equipment | Art 11(3)(a)(iii) 2024/1942 |
| **REQ-AIR-07** | **eFTI578** | Registration country of the transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-AIR-08** | **eFTI987** | Sequence number of the transport equipment | Art 11(3)(a)(x) 2024/1942 |
| **REQ-AIR-09** | **eFTI450** | Identification of the category of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-AIR-10** | **eFTI448** | Identification of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-AIR-11** | **eFTI1857**| Registration country of the carried transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-AIR-12** | **eFTI1000**| Sequence number of the carried transport equipment | Art 11(3)(a)(xi) 2024/1942 |
| **REQ-AIR-13** | **eFTI39**   | Date and time of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-AIR-14** | **eFTI146**  | Country of the place of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-AIR-15** | **eFTI583**  | Date and time of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-AIR-16** | **eFTI908**  | Country of the place of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-AIR-17** | **eFTI188**  | Date and time of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-AIR-18** | **eFTI162**  | Country of the place of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-AIR-19** | **eFTI596**  | Date and time of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-AIR-20** | **eFTI917**  | Country of the place of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-AIR-21** | **eFTI581**  | Main transport mode | Art 11(3)(a)(vi) 2024/1942 |
| **REQ-AIR-22** | **eFTI1855** | Dangerous goods indicator | Art 11(3)(b) 2024/1942 |
| **REQ-AIR-23** | **eFTI1400** | UN number | Art 11(3)(b) 2024/1942 |
| **REQ-AIR-24** | **eFTI1401** | Dangerous goods class | Art 11(3)(b) 2024/1942 |

##### Table 4: Inland Waterways Requirements (Mode 8)
| ID | eFTI Element | Requirement | Law / Regulation Reference |
| :--- | :--- | :--- | :--- |
| **REQ-IWW-01** | **eFTI131** | Unique identifier of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-IWW-02** | **eFTI617** | Identification of the type of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-IWW-03** | **eFTI618** | Identification of the means of transport | Art 11(3)(a)(viii) 2024/1942 |
| **REQ-IWW-04** | **eFTI620** | Registration country of the means of transport | Art 11(3)(a)(ix) 2024/1942 |
| **REQ-IWW-05** | **eFTI378** | Identification of the category of the transport equipment | Art 11(3)(a)(iv) 2024/1942 |
| **REQ-IWW-06** | **eFTI374** | Identification of the transport equipment | Art 11(3)(a)(iii) 2024/1942 |
| **REQ-IWW-07** | **eFTI578** | Registration country of the transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-IWW-08** | **eFTI987** | Sequence number of the transport equipment | Art 11(3)(a)(x) 2024/1942 |
| **REQ-IWW-09** | **eFTI450** | Identification of the category of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-IWW-10** | **eFTI448** | Identification of the carried transport equipment | Art 11(3)(a)(v) 2024/1942 |
| **REQ-IWW-11** | **eFTI1857**| Registration country of the carried transport equipment | Art 11(3)(a)(vii) 2024/1942 |
| **REQ-IWW-12** | **eFTI1000**| Sequence number of the carried transport equipment | Art 11(3)(a)(xi) 2024/1942 |
| **REQ-IWW-13** | **eFTI39**   | Date and time of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-IWW-14** | **eFTI146**  | Country of the place of acceptance of the consignment | Art 11(3)(a)(i) 2024/1942 |
| **REQ-IWW-15** | **eFTI583**  | Date and time of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-IWW-16** | **eFTI908**  | Country of the place of loading | Art 11(3)(a)(xii) 2024/1942 |
| **REQ-IWW-17** | **eFTI188**  | Date and time of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-IWW-18** | **eFTI162**  | Country of the place of delivery | Art 11(3)(a)(ii) 2024/1942 |
| **REQ-IWW-19** | **eFTI596**  | Date and time of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-IWW-20** | **eFTI917**  | Country of the place of unloading | Art 11(3)(a)(xiii) 2024/1942 |
| **REQ-IWW-21** | **eFTI581**  | Main transport mode | Art 11(3)(a)(vi) 2024/1942 |
| **REQ-IWW-22** | **eFTI1855** | Dangerous goods indicator | Art 11(3)(b) 2024/1942 |
| **REQ-IWW-23** | **eFTI1400** | UN number | Art 11(3)(b) 2024/1942 |
| **REQ-IWW-24** | **eFTI1401** | Dangerous goods class | Art 11(3)(b) 2024/1942 |

#### 2.2.4 API Service Requirements
| Service | Endpoint | Functional Requirement |
| :--- | :--- | :--- |
| **Registration** | `POST /identifiers/:datasetId` | eFTI Platforms register freight transport CMDS UIL and identifiers. Must validate against `consignment-identifier.xsd`. |
| **Search** | `GET /identifiers/:identifier` | Competent Authorities search for freight transport information. The eFTI Gate must stream results via **SSE (Server-Sent Events)** for real-time responsiveness. |
| **Retrieval** | `GET /dataset/...` | Fetch XML from eFTI Platform for specific subsets (EU01-EU06) requested by the competent authority. |
| **Follow-up** | `POST /follow-up/...` | Communication from a competent authority to an economic operator or to an eFTI platform, following an initial request for information; may consist of a request for missing eFTI data or information in relation to follow-up action (Article 1(6) and 6(2)(c) of Implementing Regulation (EU) 2024/1942). |
| **Auth** | `/auth/token` | Bearer token issuance for eFTI Platforms and Competent Authorities via X-Road or internal access management. |

**Available eFTI Subsets:**
- **EU01 (Full Dataset)**: All regulatory information requirements.
- **EU02 (Combined Transport)**: Information requirements for the verification of combined transport operations.
- **EU03 (Waste Transport)**: Information requirements for the shipment of waste.
- **EU04 (Food & Health)**: Information requirements for food safety, animal, and plant health.
- **EU05 (Dangerous Goods)**: Information requirements for the transport of dangerous goods (ADR/RID/ADN).
- **EU06 (Cabotage)**: Information requirements for the verification of cabotage compliance.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 2.3 Stakeholder Requirements
This section details specific functional and technical requirements from Estonian competent authorities (CA), categorized by their primary systems and query workflows.

#### 2.3.1 Summary of Competent Authority Requirements
| Competent Authority | Requirement |
| :--- | :--- |
| **MTA** | **Requirement 1**: MTA Related requirements are specified in section 13 |
| **PPA** | **Requirement 2**: The eFTI Gate must provide standardized interfaces for real-time access to regulatory information during roadside inspections. |
| **PPA** | **Requirement 3**: The eFTI Gate must provide real-time result streaming via Server-Sent Events (SSE) to field tools to minimize latency. |
| **PPA** | **Requirement 4**: The eFTI Gate must provide access to historical transport identifiers to enable verification of Union cabotage compliance. |
| **TRAM** | **Requirement 5**: The eFTI Gate must provide access to aviation and maritime regulatory information subsets for ramp and port inspections. |

#### 2.3.2 Detailed Functional Requirements of Estonian eFTI gate
| ID | Requirement | Description |
| :--- | :--- | :--- |
| **REQ-EST-01** | **Driver Identification** | Data subsets provided to MTA/PPA MUST include the driver's Estonian personal identification code and birth date (Note: driver data is not searchable in the RoI). |
| **REQ-EST-02** | **Post-Transport Data Access** | Competent Authorities MUST be able to query and retrieve data even after the transport operation has physically ended (up to the standard retention limit). |
| **REQ-EST-03** | **X-Road Integration** | All inter-agency queries MUST be routed through the national **X-Road (X-Tee)** infrastructure for non-repudiation and identity management. |
| **REQ-EST-04** | **Real-time SSE Streaming** | Search results MUST be streamed to field tools (like Apollo) via Server-Sent Events to minimize perceived latency for roadside checks. |
| **REQ-EST-05** | **Intermodal Transparency** | The system MUST achieve intermodal transparency by mapping sea waybills (eSW) and Bills of Lading (eBoL) in the Registry of Identifiers (RoI), allowing authorities to query maritime-linked consignments via standardized APIs. |

#### 2.3.3 Additional Authority Integrations and Push Services
For details on the suggested integration with the waste management systems, see section 12 of this document

For details on the suggested integration with the Estonian Tax and Customs Board (MTA) systems, see section 13 of this document

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 3. System Description and Architecture Overview
### 3.1 Design Principles
The eFTI Gate should be built on six core architectural pillars, aligned with Regulation (EU) 2020/1056, Implementing Regulation (EU) 2024/1942, Delegated Regulation (EU) 2024/2024, and TGD Annex 1 v1.0.3.
- **Lightweight Architecture**: A modular implementation that avoids heavy third-party Access Point middleware (e.g., Domibus), reducing deployment complexity, attack surface, and maintenance overhead. This direct approach reduces deployment complexity, minimizes the attack surface, and significantly lowers maintenance overhead by eliminating complex dependency trees.
- **High Concurrency & Scalability**: Optimized JVM implementation leveraging Virtual Threads (Project Loom), SAX-based XML streaming, and Server-Sent Events (SSE) to handle high volumes of concurrent requests with low, predictable latency.
- **Data Minimization & Privacy**: UILs And searchable identifiers are stored in PostgreSQL; freight transport payloads are processed ephemerally and never persisted, with configurable retention policies for stored records.
- **Security & Non-Repudiation**: Zero Trust architecture with mutual TLS, token-based access control (OAuth 2.0), eIDAS-compliant digital signatures/seals validation, and comprehensive tamper-evident audit logging ensuring full traceability and non-repudiation of all operations
- **Multi-Protocol Interoperability**: eDelivery (AS4) for cross-border gate-to-gate exchange, REST API (OpenAPI 3.1) for certified eFTI platform integration (FTI004), and X-Road (X-Tee) as the national integration layer for Estonian competent authorities.
- **Observability & Audit Compliance**: Structured logging, OpenTelemetry-based distributed tracing, circuit breaker resilience patterns, and a dedicated append-only regulatory audit trail satisfying the traceability requirements of Article 7 of Regulation (EU) 2020/1056.

### 3.2 Components Description
| Component | Technical Description & Protocols |
| :--- | :--- |
| **eFTI Gate Core** | Central orchestration engine managing the lifecycle of consignment identifiers and system entities. It exposes **REST APIs** (JSON) for administrative tasks and **Synchronous REST** (XML) for local platform registration (FTI004). Integrates with national authorities via **X-Road (REST/SOAP)**, ensuring secure identity propagation. Manages persistent metadata in PostgreSQL via **JDBC**. |
| **eDelivery Module** | A custom, lightweight implementation of the **AS4 (ebMS3)** protocol. It handles asynchronous message exchange with other EU Gates and remote eFTI platforms. Key technical features include **WS-Security** (X.509), **XML Canonicalization (C14N)**, and digital signature generation/verification (XMLDSig) to ensure non-repudiation in cross-border data flows. |
| **Subsetter Module** | High-performance data transformation engine used by the eFTI Gate to ensure retrieved datasets are filtered down to the specific subsets (EU01-EU06) requested by the competent authority. It applies a **SAX-based streaming filter** to minimize memory overhead when the source eFTI platform does not support native subsetting. |
| **Management UI** | A Reactive web interface built with **Svelte** and **Tailwind CSS**. It communicates with the eFTI Gate Core via a secured **REST API** (using Bearer tokens). It provides real-time monitoring of system health and enables configuration. |
| **Registry Services** | Internal logic layer responsible for managing connectivity parameters, security certificates (mTLS), and endpoint discovery. It utilizes **PostgreSQL** for state persistence and supports dynamic discovery updates via **SMP/SML** integration. |
| **Messaging Layer** | Handles the internal routing and delivery of eFTI messages (FTI001-FTI031). It supports **Server-Sent Events (SSE)** for real-time streaming of search results to field applications, reducing perceived latency for roadside inspections. |

### 3.3 System Architecture
The following diagram illustrates the interaction between the Estonian eFTI Gate, competent authorities, and the external eFTI ecosystem.

```{mermaid}
graph TD
    %% Top Level
    subgraph Authorities ["Estonian Competent Authorities (Non-comprehensive List)"]
        direction TB
        MTA["Estonian Tax and Customs Board (MTA)"]
        PPA["Estonian Police and Border Guard Board (PPA)"]
        KeA["Estonian Environmental Board (KeA)"]
        TRAM["Estonian Transport Administration (TRAM)"]
        Others["..."]
    end

    %% Middle Level
    subgraph Gate ["Estonian eFTI Gate"]
        direction TB
        UI["Svelte Admin/Auth UI"]
        Core["eFTI Gate Core"]
        Subsetter["Subsetter Engine"]
        DB[("PostgreSQL")]
        EDelivery["eDelivery/AS4 Module"]
        
        UI <--> Core
        Core <--> DB
        Core <--> Subsetter
        Core <--> EDelivery
    end

    %% Bottom Level
    subgraph ExternalNet ["External eFTI Ecosystem"]
        direction TB
        subgraph PlatformsSub ["Economic Operators"]
            Platforms["Certified eFTI Platforms"]
        end
        subgraph EUGateSub ["EU Gate Network"]
            OtherGates["EU Member State Gates"]
        end
    end

    %% Vertical connections to force layout
    Authorities --- Gate
    Gate --- ExternalNet

    %% Detailed Connections (Competent Authority to Core)
    MTA <-->|X-Road| Core
    PPA <-->|X-Road| Core
    KeA <-->|X-Road| Core
    TRAM <-->|X-Road| Core
    Others -.->|X-Road| Core

    %% Detailed Connections (Gate to External)
    Core <-->|"REST API / eDelivery"| Platforms
    EDelivery <-->|"AS4 / eDelivery"| OtherGates
```

### 3.4 Technical Stack
Estonian eFTI Gate leverages a modern, efficient stack designed for the JVM:
- **Language**: [Kotlin](https://kotlinlang.org/) (JVM 25) for concise, type-safe backend logic.
- **Framework**: [Klite](https://github.com/keksworks/klite) - a high-performance, lightweight HTTP framework with built-in support for JSON, XML, and OpenAPI.
- **Database**: [PostgreSQL](https://www.postgresql.org/) for reliable storage of identifiers and entity metadata.
- **Frontend**: [Svelte](https://svelte.dev/) with [Tailwind CSS](https://tailwindcss.com/) for a fast, responsive administrative interface.
- **Security**: Custom implementation of XMLDSig and mTLS for eDelivery; Bearer tokens and X-Road for API security. Authorization (RBAC) and authentication are handled by the system's security module.
- **Deployment**: [Docker](https://www.docker.com/) and [Kubernetes](https://kubernetes.io/) (via Helm charts) for scalable, containerized operations.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 4. Functional Requirements
### 4.1 Core eFTI Gate Functions and Use Cases
The Estonian eFTI Gate should implement four primary technical functions as defined in EU Regulation 2020/1056, enabling a wide range of functional domains for competent authorities.

#### 4.1.1 Core eFTI Gate Functions
- **Processing of unique identifiers of eFTI datasets and corresponding UILs**: Enables certified eFTI Platforms to register identifiers and UILs in the Registry of Identifiers (RoI) as defined in Article 11 of Implementing Regulation (EU) 2024/1942.
- **Processing of requests for access to regulatory information**: Allows competent authorities to perform real-time checks (local and EU-wide via AS4). Checks can be performed according to identifiers in the RoI (based on UILs registered in the RoI).
- **Processing of responses to requests for access to regulatory information**: Facilitates the fetching of XML datasets from eFTI platforms and ensures competent authorities only receive the specific data subsets they have requested through subset filtering.
- **Processing of follow-up communications**: In accordance with Article 1(6) of Implementing Regulation (EU) 2024/1942, provides a secure channel for authorities to request additional information or clarify data discrepancies directly with the eFTI Platform.

#### 4.1.2 Primary Functional Domains (Use Cases)
The system supports several primary domains:
- **General Road Freight Compliance**: Enables roadside inspectors to retrieve the full CMDS digitally during on-site checks.
- **Dangerous Goods (DG)**: Facilitates specialized checks for ADR/RID/ADN via dedicated subsets (EU05).
- **Cabotage Monitoring**: Enables competent authority to verify fulfillment of cabotage rules.
- **Combined Transport**: Streamlines regulatory checks for multi-modal transitions (e.g., Sea-Road) by supporting subset EU02 for authorized authorities.
- **Rail and Aviation Interoperability**: Allow competent authorities to link air, rail and inland waterways transport data to specific eFTI transport data.
- **Waste Shipment Compliance**: Supports cross-border waste transport verification per Regulation (EC) No 1013/2006, leveraging eFTI subsets for waste-specific regulatory data elements.
*Note: subject of further analysis, described in section 12 of this document* 

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 4.2 Message Catalog
All system interactions follow the FTI message standard. Every transaction is identified by a `MessageId` and associated with a `ConversationId`. 
**Note: these message IDs are recommended by the eFTI4EU project as the basis for technical interoperability.**

| Message ID | Name | Description | Response / Answer |
| :--- | :--- | :--- | :--- |
| **FTI001** | Login Request | EO user authentication request to the eFTI Platform. | `FTI002` |
| **FTI002** | Login Response | Status of the login process, providing a token (e.g., JWT) upon success. | N/A |
| **FTI003** | Upload Raw eFTI Data | EO Application uploads full or partial dataset elements to the eFTI Platform. | `FTI006` |
| **FTI004** | Upload Identifiers | Platform registers consignment metadata (identifiers + UIL) to the eFTI Gate. | `FTI029` |
| **FTI005** | Store Identifiers | eFTI Gate forwards identifiers to a Registry of Identifiers (RoI). | `FTI030` |
| **FTI006** | UIL Generation | Platform notifies the EO Application of the generated UIL and record UUID. | N/A |
| **FTI007** | Raw Dataset Request | UAP requests a dataset from the AAP using a UIL. | `FTI012` |
| **FTI008** | Fully Qualified Dataset Request | AAP enriches the request with CA identity and authorized subsets for the eFTI Gate. | `FTI011` |
| **FTI009** | Forward Dataset Request | eFTI Gate requests the dataset from the specific Platform via its endpoint. | `FTI010` |
| **FTI010** | Response Dataset | Platform returns the filtered XML payload to the requesting eFTI Gate. | N/A |
| **FTI011** | Forward Response (Gate) | Requesting eFTI Gate forwards the dataset payload back to the AAP. | N/A |
| **FTI012** | Forward Response (AAP) | AAP forwards the final authorized payload to the CA's User Application. | N/A |
| **FTI013** | Request Identifier Search | UAP initiates search (e.g., by License Plate) to the AAP. | `FTI018` |
| **FTI014** | Authorize Search | AAP validates search rights and forwards the query to the local eFTI Gate. | `FTI017` |
| **FTI015** | Identifiers' Search | eFTI Gate performs an internal lookup in its local RoI database. | `FTI016` |
| **FTI016** | Search Response (Local) | Local RoI returns matching UILs and identifiers to the eFTI Gate. | N/A |
| **FTI017** | Search Response (AAP) | eFTI Gate returns consolidated results (local + remote) to the AAP. | N/A |
| **FTI018** | Search Response (UAP) | AAP forwards the final list of matching UILs to the CA Officer's screen. | N/A |
| **FTI019** | Request Search (Cross-Gate) | Requesting eFTI Gate broadcasts the search query to other EU MS Gates via AS4. | `FTI021` |
| **FTI020** | Forward Request (Cross-Gate) | Requesting eFTI Gate forwards a dataset retrieval request to a remote EU Gate. | `FTI022` |
| **FTI021** | Forward Search Response | Receiving eFTI Gate returns its local RoI matches to the Requesting eFTI Gate via AS4. | N/A |
| **FTI022** | Forward Dataset Response | Receiving eFTI Gate forwards the eFTI Platform's dataset response back to the Requesting eFTI Gate. | N/A |
| **FTI023** | Follow-up by UAP | CA Officer adds notes/annotations to a CMDS. | `FTI028` (Timeout) |
| **FTI024** | Follow-up by AAP | AAP forwards the follow-up note to the eFTI Gate. | N/A |
| **FTI025** | Forward Follow-up (Gate) | eFTI Gate forwards the annotation to the eFTI Platform's follow-up endpoint. | `FTI030` (Ack) |
| **FTI026** | Forward Follow-up (Remote) | eFTI Gate forwards the note to another MS eFTI Gate if the eFTI platform is remote. | `FTI031` (Ack) |
| **FTI027** | No Response (Gate) | Signaled by the eFTI Gate to AAP when a 60s processing timeout is reached. | N/A |
| **FTI028** | No Response (AAP) | Signaled by AAP to UAP when the backend system fails to respond. | N/A |
| **FTI029** | Upload Response (Platform) | eFTI Gate confirms to Platform that identifiers were successfully stored in RoI. | N/A |
| **FTI030** | Upload Response (RoI) | External RoI confirms record creation/update status to the eFTI Gate. | N/A |
| **FTI031** | Follow-up Ack (Remote) | Remote eFTI Gate confirms receipt of follow-up communication via AS4. | N/A |

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 4.3 Registry of Identifiers (RoI)
In accordance with Article 1(12) and Article 11 of Commission Implementing Regulation (EU) 2024/1942, the registry of identifiers is the component of the eFTI gate that stores and provides access to unique identifiers of eFTI datasets and corresponding Unique Identifier Links (UILs).
The following identifiers are processed in the RoI for the purpose of search and identification of regulatory information as defined in Article 11(3) of Implementing Regulation (EU) 2024/1942:

| ID | Name | Description |
| :--- | :--- | :--- |
| **UIL**      | Unique Identifying Link | The Unique Identifier Link used to uniquely identify and fetch a dataset across the EU network. It consists of `gateId`, `platformId`, and `datasetId`. |
| **eFTI131**[*] | Unique identifier of the consignment | The unique identification of the consignment. |
| **eFTI617**[*] | Identification of the type of the means of transport | The code specifying the type of freight transport means used. |
| **eFTI618** | Identification of the means of transport | The identifier of the means of transport used in the main leg of transportation. |
| **eFTI620** | Registration country of the means of transport | The country code of the registration country of the transport means used in the main leg of transportation. |
| **eFTI378** | Identification of the category of the transport equipment | The code specifying the category for the used transport equipment. |
| **eFTI374** | Identification of the transport equipment | The identifier of the transport equipment used for transporting this consignment. |
| **eFTI578** | Registration country of the transport equipment | The code for the registration country of the used transport equipment. |
| **eFTI987** | Sequence number of the transport equipment | The sequence number differentiating this piece of transport equipment from others in a set of used transport equipment. |
| **eFTI450**[*] | Identification of the category of the carried transport equipment | The code specifying the category of the carried transport equipment. |
| **eFTI448** | Identification of the carried transport equipment | The identifier of the carried transport equipment. |
| **eFTI1857**[*] | Registration country of the carried transport equipment | The registration country for this carried transport equipment. |
| **eFTI1000**| Sequence number of the carried transport equipment | The sequence number differentiating this piece of transport equipment from others in a set of carried transport equipment. |
| **eFTI39**   | Date and time of acceptance of the consignment | The date, time, date time or other date time value when this supply chain consignment has been accepted by the carrier. |
| **eFTI146**[*]  | Country of the place of acceptance of the consignment | The country code of the carrier acceptance location. |
| **eFTI583**[*]  | Date and time of loading | The actual loading date/time. |
| **eFTI908**[*]  | Country of the place of loading | The country code of the loading event location. |
| **eFTI188**  | Date and time of delivery | The actual delivery date/time for this consignment. |
| **eFTI162**[*]  | Country of the place of delivery | The country code of the consignee receipt location. |
| **eFTI596**[*]  | Date and time of unloading | The actual unloading date/time. |
| **eFTI917**[*]  | Country of the place of unloading | The country code of the unloading event location. |
| **eFTI581**  | Main transport mode | The code specifying the mode of transport for the main leg of transport. |
| **eFTI1855** | Dangerous goods indicator | The indication of whether or not dangerous goods are carried. |
| **eFTI1400**[*] | UN number | The four-digit identification number of the substance or article taken from the UN Model Regulations. |
| **eFTI1401**[*] | Dangerous goods class | The class (and division, if applicable) assigned to the dangerous goods. |

[*] Not part of mandatory XSD

#### 4.3.1 Storage and Archiving Rules
- **Registration**: Created when `FTI004` is received from an eFTI Platform. 
- **Update**: Replaces existing record when a newer `FTI004` is received.
- **Deactivation**: Triggered by the processing of the date and time of delivery (`eFTI188`). To support cabotage control for road transport (eFTI581 = '3'), the UIL remains active in the Registry for the period defined in Article 8(2) of Regulation (EC) No 1072/2009, in accordance with Article 11(4) of Implementing Regulation (EU) 2024/1942.
- **Retention**: In accordance with Article 11(4) of Implementing Regulation (EU) 2024/1942, identifiers and UILs are deleted from the Registry upon deactivation. Long-term accessibility is ensured via eFTI platform directly. Upon deactivation, identifiers and UILs are deleted from the Registry, subject to the extended retention period for cabotage as described above.
- **Operations Log**: As per Article 9(1)(c) of Regulation (EU) 2020/1056 and Article 6 of Implementing Regulation (EU) 2024/1942, the eFTI Gate maintains a log for a period of at least two years for the purpose of ensuring compliance, including searchable metadata and transaction logs.
- **Error handling**: In case deactivated or deleted eFTI dataset (UIL + identifiers) is queried from Estonian eFTI Gate (FTI007 .. FTI0012), the answer must include a specific error message

### 4.4 Follow-up Communication
In accordance with Article 1(6) of Commission Implementing Regulation (EU) 2024/1942, follow-up communication means communication between competent authorities and the economic operators concerned on the information made available by the economic operators on an eFTI platform, following a compliance check by a competent authority officer of that information as provided in response to a request for access to eFTI data. Any such follow-up communication shall comply with applicable national legal requirements on follow-up actions to regulatory compliance checks, as stipulated in Article 6(4) of Implementing Regulation (EU) 2024/1942.

### Scope and Purpose
Follow-up communication is initiated where a competent authority officer establishes that the information retrieved from an eFTI platform is incomplete or otherwise fails to comply with the applicable regulatory information requirements. The purpose is to communicate those findings to the economic operator and, where necessary, to request clarification or additional information.

### Processing by the eFTI Gate
The eFTI Gate acts as a pass-through intermediary for follow-up communications. It does not interpret or modify the content of the communication but ensures secure routing and metadata logging. Specifically, the eFTI Gate performs the following:
- Receives the follow-up communication from the AAP component via the user application and routes it to the relevant eFTI platform via the established connection (AS4 for cross-border, REST API for domestic eFTI platform connections).
- Logs the metadata as required by Article 6(2)(c) of Implementing Regulation (EU) 2024/1942: the unique identification number of the follow-up communication, the identifier of the AAP or requesting eFTI Gate, and the precise date and time of receipt.
- Routes any response from the eFTI platform (on behalf of the economic operator) back to the originating AAP component.

### Content Requirements
In accordance with Article 6(4) of Implementing Regulation (EU) 2024/1942, the follow-up communication transmitted by a competent authority shall contain:
- The unique identification number of the original request for access to eFTI data to which the follow-up relates.
- A specification of the regulatory information requirements that were found to be incomplete or non-compliant.
- The nature of the follow-up action requested (e.g., clarification, correction, submission of additional data).

### Relationship to Compliance Operations
Follow-up communication is operationally linked to the "Verify eFTI CMDS" and "Record Inspection Result" operations. A follow-up is typically initiated after a verification operation has identified a discrepancy, and the inspection result may be updated upon receipt of the economic operator's response.


```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 5. Messages and Data Flow Description
This section details the primary system-to-system interactions within the eFTI ecosystem, mapping functional requirements to technical implementations.

### 5.1 Economic Operator Data Upload (Informative)
**Description**: The Economic Operator (EO) uploads freight transport data to their chosen certified eFTI Platform. This flow is **national** to the eFTI Platform's relationship with the EO but triggers the subsequent registration with the national eFTI Gate.
- **Messages**: `FTI003` (Upload Data), `FTI006` (UIL Generation).
- **Trigger**: New freight transport assignment or update in EO's internal system.

```{mermaid}
sequenceDiagram
    participant EO as Economic Operator
    participant P as eFTI Platform
    EO->>P: FTI001: Login Request
    P-->>EO: FTI002: Login Response (Token)
    EO->>P: FTI003: Upload eFTI Data
    Note over P: Validate & Store Dataset
    P-->>EO: FTI006: UIL Generation (Link)
```

### 5.2 Processing of Unique Identifiers and UILs (Registration)
**Description**: The eFTI Platform uploads the identifiers of the CMDS, together with its  UIL to the national eFTI Gate for registration in the RoI
- **Messages**: `FTI004` (Upload Identifiers), `FTI029` (Upload Response).
- **Storage**: Data is persisted in the Registry of Identifiers (RoI).

```{mermaid}
sequenceDiagram
    participant P as eFTI Platform
    participant G as National eFTI Gate
    participant RoI as Registry of Identifiers
    P->>G: POST /identifiers/:datasetId (FTI004)
    G->>G: Validate XSD (consignment-identifier)
    G->>RoI: Save metadata & UIL
    G-->>P: 200 OK (FTI029)
```

### 5.3 Processing of Requests for Regulatory Information (Search)
**Description**: Competent Authorities search for regulatory information by specified identifiers. Initial requests from Estonian competent authorities are **national** (via X-Road). If not found in the Estonian eFTI Gate, then it  broadcasts a **cross-border** search to **other Member State eFTI Gates** via the eDelivery AS4 network.
- **Messages**: `FTI014` (Search Request - AAP to Gate), `FTI019` (Cross-Gate Search), `FTI021` (Cross-Gate Response).
- **Feature**: Real-time result streaming using **Server-Sent Events (SSE)**.

```{mermaid}
sequenceDiagram
    participant AAP as Authority Access Point (AAP)
    participant G as National eFTI Gate
    participant RoI as Registry of Identifiers
    participant OG as Other MS eFTI Gate
    AAP->>G: GET /identifiers/:id (FTI014)
    par National Search
        G->>RoI: Query identifiers
        RoI-->>G: National UILs
        G-->>AAP: SSE: data (National Results)
    and Cross-border Broadcast (AS4)
        G->>OG: FTI019: Request Search (eDelivery)
        OG-->>G: FTI021: Forward Search Response
        G-->>AAP: SSE: data (Cross-border Results)
    end
    G-->>AAP: SSE: event: complete
```

### 5.4 Processing of Responses for Regulatory Information (Retrieval)
**Description**: Competent Authorities retrieve a specific authorized data subset using a UIL. The request is made by Estonian competent authority. The Estonian eFTI Gate proxies the request to the target eFTI Platform (national or cross-border).
- **Messages**: `FTI008` (Dataset Request - AAP to Gate), `FTI009` (Forward to Platform), `FTI020` (Forward to Remote eFTI Gate).
- **Processing**: Gate applies **Subset Filtering** before delivery.

```{mermaid}
sequenceDiagram
    participant AAP as Authority Access Point (AAP)
    participant G as National eFTI Gate
    participant P as eFTI Platform (National)
    participant OG as Other MS eFTI Gate
    AAP->>G: GET /dataset/... (FTI008)
    alt National eFTI Platform
        G->>P: GET /dataset (FTI009)
        P-->>G: Full XML (FTI010)
    else Other Member State Gate (Cross-border)
        G->>OG: FTI020: Forward Request (AS4)
        OG-->>G: FTI022: Forward Response (AS4)
    end
    G->>G: Apply XSLT Subset Filter
    G-->>AAP: Filtered Payload (FTI011/FTI012)
```

### 5.5 Processing of Follow-up Communications
**Description**: A competent authority representative sends follow-up communication regarding a retrieved dataset to the eFTI Platform. Estonian competent authority recieves information from other menber state eFTI Gate (not directly connected to Estonian eFTI Gate).
- **Messages**: `FTI024` (Follow-up Request - AAP to Gate), `FTI025` (Forward to Platform), `FTI026` (Forward to Remote eFTI Gate).
- **Trigger**: New freight transport assignment or update in EO's internal system.

```{mermaid}
sequenceDiagram
    participant AAP as Authority Access Point (AAP)
    participant G as National eFTI Gate
    participant P as eFTI Platform
    participant OG as Other MS eFTI Gate
    AAP->>G: POST /follow-up/... (FTI024)
    alt National eFTI Platform
        G->>P: POST /follow-up (FTI025)
        P-->>G: 200 OK
    else Other Member State Gate (Cross-border)
        G->>OG: FTI026: Forward Follow-up (AS4)
        OG-->>G: Ack (AS4)
    end
    G-->>AAP: 200 OK
```

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 6. User Roles and Privileges
### 6.1 Role Definitions
The eFTI Gate defines roles based on the underlying code implementation and the entities specified in EU Regulation 2020/1056. These roles are assigned to human users (using the Management UI) for system management and data access.

- **Gate Super Admin** (Code: `ADMIN`): Full system access without specific entity scoping. Responsible for high-level management, including creating other admins, managing system-wide certificates, and monitoring overall health.
- **Competent Authority** (Code: `AUTHORITY`): A public authority authorized to query and view regulatory freight transport information in accordance with its legal mandate. 
    - **CA Admin**: An administrative user (human) scoped to a specific `AuthorityId`. Manages user accounts and API keys for their authority.
    - **Inspector / Officer**: A human user performing real-time searches and retrievals through an authorized competent authority user interface.
- **eFTI Platform** (Code: `PLATFORM`): A certified ICT solution used by economic operators to store and process freight transport datasets.
    - **Platform Admin**: A human user managing credentials and monitoring registration success for a specific `PlatformId`.
- **EU Member State Gate** (Code: `GATE`): Represents a peer national gate in the EU-wide eFTI exchange environment, communicating via secure AS4/eDelivery.

### 6.2 Role User Rights
The following matrix defines the functional permissions for each role within the eFTI Gate ecosystem.

| Role | Search (UIL) | Retrieve (Dataset) | Register (UIL) | Follow-up | Manage Entities | Config System |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Super Admin** | [x] | [x] | [x] | [x] | [x] | [x] |
| **Competent Authority** | [x] | [x] | [ ] | [x] | [x]* | [ ] |
| **eFTI Platform** | [ ] | [ ] | [x] | [ ] | [x]* | [ ] |
| **EU Member State Gate** | [x] | [x] | [ ] | [x] | [ ] | [ ] |

*\*Note: Admins (CA/Platform) can only manage entities/users within their own scope.*

### 6.3 Authentication and Authorization
The eFTI Gate should implement a multi-tiered security model to ensure strict access control, data privacy, and non-repudiation across all communication channels, in accordance with Article 9 of Implementing Regulation (EU) 2024/1942.

#### 6.3.1 Authentication Methods
- **Management UI**: Email/password authentication with mandatory multi-factor authentication (MFA) for human administrators and managers. Production deployment shall enforce MFA in accordance with the security requirements of the national eFTI Gate operator.
- **External API (REST)**: OAuth 2.0 client credentials flow with short-lived bearer tokens for certified eFTI platforms and competent authorities. Tokens are scoped to the requesting entity and expire after a configurable time-to-live (default: 1 hour). Platform identity is verified against the Estonian eFTI Platform registry (as defined in Implementing Regulation (EU) 2025/2243) to confirm valid certification status before token issuance.
- **National Integration (X-Road)**: Mutual TLS between security servers with organisation-level identity headers. X-Road guarantees both server-level and organisation-level authentication, ensuring that the competent authority is cryptographically identified and that all exchanges are bilaterally logged for non-repudiation.
- **EU Interoperability (AS4)**: WS-Security with X.509 certificates providing mutual TLS and message-level XML signing in accordance with the eDelivery AS4 profile. Each Gate-to-Gate message is signed with the sending eFTI Gate's certificate, enabling the receiving eFTI Gate to verify origin and integrity.

#### 6.3.2 Authorization Logic
- **Role-Based Access Control (RBAC)**: The system defines the following principal roles, each with scoped permissions: 
**eFTI Gate Administrator**: Full system configuration, user and entity management, audit log access.
**eFTI Platform Operator**: UIL registration (FTI004), dataset update, and deactivation operations scoped to the operator's own PlatformId.
**CA Officer**: Read access to eFTI data, follow-up communication initiation, subset-based retrieval — scoped to the officer's AuthorityId and legal mandate.
**CA System (M2M)**: Machine-to-machine read access via X-Road or AS4, subject to the same scoping rules as CA Officer.
- **Entity Scoping**: Every authenticated principal is linked to a specific GateId, PlatformId, or AuthorityId. The system enforces that a principal can only register, modify, or query data connected to their own entity. 
- **Access Control**: When a competent authority requests eFTI data, the eFTI Gate validates if the competent authority is registred with Estonian eFTI Gate

#### 6.3.3 Non-Repudiation Mechanisms
Non-repudiation is ensured through the combination of the following technical measures, addressing the identity verification requirements identified in the eFTI XM schema review:
- **Signed requests and responses**: All AS4 messages are signed at the message level using the sending party's X.509 certificate. REST API requests include the OAuth 2.0 token binding the request to the authenticated entity. X-Road messages are signed by the security server with the organisation's certificate.
- **Timestamping**: All operations are timestamped with a trusted time source (UTC). For AS4 exchanges, timestamps are embedded in the ebMS3 message header. For REST and X-Road, timestamps are generated by the Gate at the point of receipt.
- **Immutable audit trail**: Every authenticated operation (registration, query, follow-up, deactivation) is recorded in an append-only audit log containing: principal identity, role, entity identifier, operation type, target UIL or resource, request/response hash, and timestamp. Audit records are retained for a minimum of two years in accordance with Article 9(1)(c) of Regulation (EU) 2020/1056 and Article 6 of Implementing Regulation (EU) 2024/1942.
- **Bilateral logging (X-Road)**: X-Road's built-in message log ensures that both the requesting and responding parties retain independent, tamper-evident records of every transaction, providing mutual non-repudiation without reliance on a single party's logs.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 7. Security and Certification Management
### 7.1 Description of eFTI Gate Security
The eFTI Gate implements technical and organizational measures to ensure the security, confidentiality, integrity, and availability of regulatory information exchanged within the eFTI exchange environment, in accordance with Regulation (EU) 2020/1056, Implementing Regulation (EU) 2024/1942, and Implementing Regulation (EU) 2025/2243.

- **Identification, Authentication, and Authorization (IAA)**:
    - **Identification and Authentication**: All access by competent authorities to the eFTI exchange environment is subject to duly identified and authenticated access. The eFTI Gate ensures the authentication of the competent authority or ensures that such authentication is performed by an Authority Access Point (AAP) or a peer eFTI Gate using appropriate electronic identification means and security keys.
    - **Authorization**: Access to eFTI data is allowed only following due authorization based on assigned access and processing rights. The eFTI Gate validates that each request for access to eFTI data includes references to the access and processing rights of the competent authority responsible for the request, as recorded in the authorization registry. Each competent authority is responsible to verify what users are allowed to access eFTI transport data.
- **Secure Communication and Message Exchange**:
    - **Standardized Formats**: All communication between eFTI Gates and between eFTI Gates and eFTI platforms is conducted using the XML format via secure and authenticated connections.
    - **eDelivery AS4**: Message exchanges between eFTI Gates comply with eDelivery message exchange specifications, using eDelivery Access Points and the eDelivery AS4 profile for cross-border interoperability.
    - **Security Certificates**: Member States issue security certificates through a certificate authority to eDelivery Access Points. The eFTI Gate ensures that private security keys are securely stored and that digital certificates are delivered and validated through secure mechanisms to ensure the authenticity and non-repudiation of all communication.
- **Data Protection and Confidentiality**:
    - **Confidential Commercial Information**: In accordance with Article 6 of Regulation (EU) 2020/1056, the eFTI Gate takes measures to ensure the confidentiality of commercial information. Access and processing of such information are allowed only when authorized and protected against corruption, theft, or unauthorized access.
    - **Metadata Processing**: The eFTI Gate does not store eFTI data, except for metadata connected to eFTI data processing, such as identifiers or operation logs, ensuring that freight transport payloads remain at their source on the eFTI platforms.
- **Operation Logs and Audit Trails**:
    - **Mandatory Recording**: All data processing is recorded in operation logs to allow the identification of each distinct processing operation, the legal person having made the operation, and the sequencing of the operations.
    - **Retention**: In accordance with Article 10(1)(d) of Regulation (EU) 2020/1056 and Article 6(2)(h) of Implementing Regulation (EU) 2024/1942, audit logs are archived and remain accessible for auditing purposes for a period of at least two years as required for the purpose of ensuring compliance.

#### 7.1.1 Audit Logs
The eFTI Gate maintains a comprehensive and immutable audit trail for all requests, responses, and follow-up communications to ensure accountability and provide a verifiable log of all data exchange activities.

| Event Category | Mandatory Log Content | Legal Reference |
| :--- | :--- | :--- |
| **Requests & Follow-ups** | • Unique identification number of the request or follow-up communication<br>• Identifier of the AAP or requesting eFTI Gate from which it received the request<br>• Searchable identifiers provided by the officer (e.g., license plate, wagon ID, AWB)<br>• Precise date and time of receipt | Art 6(2)(c) 2024/1942 |
| **Data Responses** | • UIL of the eFTI dataset received in response to the request<br>• Unique identifier of the eFTI Platform or receiving eFTI Gate from which it received the response<br>• Precise date and time of receipt<br>• Indication of "No Response" in case of timeouts | Art 6(2)(g) 2024/1942 |
| **Processing Events** | • Identification of each distinct processing operation<br>• The natural or legal person having made the operation<br>• The sequencing of the operations on each individual data element | Art 6(2)(h) 2024/1942 |

These logs should be stored using standard PostgreSQL; implementing tamper-evident mechanisms (e.g., hash chaining or WORM storage) to guarantee non-repudiation is a **Production Requirement**. Access to these logs is restricted to authorized administrative personnel and competent authorities during formal ex-post audits.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 7.2 Description of Suggested Deployment Security
To ensure the robustness of the eFTI infrastructure in a production environment, the following deployment security measures are recommended:

- **Infrastructure Orchestration**:
    - Deployment via **Kubernetes** using Helm charts to manage scaling, health checks, and secure networking.
    - Use of **Ingress Controllers** with automated TLS certificate management (e.g., Let's Encrypt or national PKI).
- **Secure Key Management**:
    - **Hardware Security Modules (HSM)** or **Trusted Platform Modules (TPM)** should be used for storing private keys for AS4 signing and mTLS.
    - In cloud environments where hardware modules are unavailable, strict IAM policies must limit access to the key management service and its associated PKCS12 keystores.
- **Network Isolation**:
    - The **Estonian eFTI Gate** and its **PostgreSQL** database should reside in a private subnet, with access restricted to authorized IP ranges (e.g., **X-Road Security Servers** and **EU Member State Gates**).
    - Regular vulnerability scanning of Docker images to identify and patch vulnerabilities in the **eFTI Gate Core** stack.
- **High Availability (HA)**:
    - Horizontal scaling of the **eFTI Gate Core** to prevent Denial of Service (DoS) through resource exhaustion.
    - **PostgreSQL** replication across multiple availability zones to ensure data persistence of identifiers.

### 7.3 Recommendations for Secure Administration
Secure administration is critical for maintaining the trust of the eFTI ecosystem.

- **Administrative RBAC**:
    - **Gate Super Admin**: Limited to a few high-level personnel for system-wide configuration and certificate management.
    - **Competent Authority/eFTI Platform Admin**: Empowered to manage users only within their specific scope, reducing the blast radius of a compromised account.
- **Credential Lifecycle**:
    - Enforce the "Show Once" policy for system-generated passwords, ensuring they are only visible to the user at the moment of creation.
    - Implement regular rotation of API Bearer tokens and administrative passwords.
- **Operational Monitoring**:
    - Real-time monitoring of the Operations Log for anomalous behavior (e.g., mass identifier registration or unusual query patterns).
    - Integration with centralized logging and alerting systems (e.g., ELK stack or Prometheus/Grafana).
- **Multi-Level Support**:
    - Implementation of the **eFTI Helpdesk Network** (level 1, level 2, level 3) to handle security incidents and operational issues within the mandated common availability window (10:00 - 16:00 CET) as per Art 8(3) of Implementing Regulation (EU) 2024/1942.

### 7.4 Cryptography
The eFTI Gate utilizes industry-standard cryptographic algorithms and protocols to ensure the confidentiality, integrity, and authenticity of freight transport data.

- **Protocol Stack**:
    - **TLS 1.2/1.3**: Mandatory for all transport-level communication between nodes.
    - **AS4 (ebMS3)**: Used for secure asynchronous message exchange between eFTI Gates.
- **Message Security**:
    - **XMLDSig (XML Digital Signatures)**: Used to sign AS4 message envelopes, ensuring the origin and integrity of the metadata.
    - **XML Canonicalization (C14N)**: Custom implementation to ensure consistent hashing of XML structures for digital signature verification.
- **Algorithms**:
    - **Asymmetric**: RSA (2048-bit minimum) or ECC (Elliptic Curve Cryptography) for key pairs.
    - **Hashing**: SHA-256 for message digests and password hashing.
    - **Symmetric**: AES-128/256 for payload encryption in AS4.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 7.5 Trust Framework and Compliance Management
The eFTI exchange environment relies on a structured trust framework to ensure the authenticity, integrity, and non-repudiation of electronic freight transport information between competent authorities, eFTI gates, and eFTI platforms.

#### 7.5.1 Trust Entities and Certifications
- **Conformity Assessment Body (CAB)**: An accredited body responsible for performing the conformity assessment of eFTI platforms and service providers to ensure they meet the requirements of the Regulation. (Ref: Article 11, Regulation (EU) 2020/1056; Regulation (EC) No 765/2008).
- **Qualified Trust Service Provider (QTSP)**: An entity providing qualified trust services (such as qualified electronic signatures or seals) that meet the high security standards required for legal recognition across the EU. (Ref: Regulation (EU) 910/2014).

| Trust / Certification Element | Regulatory Purpose | Legislative Basis | Governing Entity |
| :--- | :--- | :--- | :--- |
| **Security Certificate (X.509)** | Authentication and secure message exchange. | Art 10, Reg 2024/1942 | Trusted Certificate Authority (CA) |
| **eIDAS Certificates** | Qualified electronic signatures and seals for datasets. | Reg 910/2014 | Qualified Trust Service Provider (QTSP) |

#### 7.5.2 Update Routine and Compliance Strategy
- **Certificate Management**:
    - Security certificates must be managed and renewed to ensure continuous secure communication within the eFTI exchange environment, adhering to the technical specifications for identification and authentication. (Ref: Article 10, Implementing Regulation (EU) 2024/1942).
- **Compliance with the Registry of eFTI Platforms**:
    - **Registry Synchronization**: The eFTI gate must verify that every eFTI platform it interacts with is listed as active in the registry of eFTI platforms maintained by each Member State. (Ref: Article 11, Regulation (EU) 2020/1056; Article 6, Implementing Regulation (EU) 2024/1942).
    - **Qualified Trust Validation**: Electronic signatures and seals provided by eFTI platforms must be validated against the European Union Trusted Lists (EUTL) to ensure the qualified status of the issuing trust service provider. (Ref: Regulation (EU) 910/2014).
- **Dynamic Discovery and Interoperability**:
    - The eFTI gate must implement dynamic discovery of peer nodes (eFTI gates and eFTI platforms) using the common technical specifications adopted for the exchange environment, ensuring that updated security metadata and endpoints are propagated automatically. (Ref: Article 9, Implementing Regulation (EU) 2024/1942).
- **Revocation and Validity Checks**:
    - The eFTI gate must perform real-time validation of the status of digital certificates (e.g., via OCSP or CRL). Information exchange must be immediately suspended if a certificate is revoked, expired, or no longer meets the required security standards. (Ref: Article 10, Implementing Regulation (EU) 2024/1942).
- **Certification Maintenance**:
    - The active conformance status of eFTI platforms must be monitored. If a Conformity Assessment Body (CAB) withdraws a conformance certificate, the CAB or administrator must ensure that the platform's ability to provide regulatory information is revoked. (Ref: Article 12, Regulation (EU) 2020/1056).

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 8. Non-Functional Requirements (NFR)
### 8.1 Estonian Digital Government & KEMIT Standards
The eFTI Gate must comply with the overarching standards for Estonian state information systems:
- **Interoperability Framework**: Full compliance with the [Estonian Common Interoperability Framework (CFR)](https://koodivaramu.eesti.ee/e-gov/cfr) to ensure seamless data exchange between state information systems.
- **Cybersecurity**: Adherence to the [Estonian Cyber Security Standard (E-ITS)](https://eits.ria.ee/et) to ensure data integrity, confidentiality, and availability.
- **KEMIT Standards**: Implementation must align with [KEMIT Public Non-functional Requirements](https://www.kemit.ee/en/technology) for hosting and technology lifecycle management.

### 8.2 eFTI Gate Specific NFRs
- **High Throughput**: The system is designed to handle high load from Estonian competent authorities (MTA, PPA, KeA, etc.). The solution must be scalable.
- **Low Latency**: Lightweight stack ensures sub-second response times. 
- **Data Retention**: Audit trail for metadata and transaction logs for at least 2 years to meet eFTI standard requirements.
- **Statelessness**: The eFTI Gate core is horizontally scalable; all session and entity state is confined to the PostgreSQL database.
- **Auditability**: Comprehensive logging of all FTI message exchanges (FTI001-FTI031) as required by Article 10(1)(c) of EU Regulation 2020/1056 and Article 6(2) of Implementing Regulation (EU) 2024/1942.
- **Logging & Monitoring**:
    - **Operational Logs**: Real-time logging of system health, authentication events, and API usage.
    - **External Integration**: Support for centralized log aggregation (e.g., ELK stack) and performance monitoring (e.g., Prometheus/Grafana) to facilitate rapid incident response.
    - **Tamper-Evidence**: Transaction logs must be stored using mechanisms that ensure non-repudiation and integrity over the required retention period.

### 8.3 Server & Infrastructure Requirements (SLA & Capacity)
- **Service Level Agreement (SLA)**: Suggested **99.9% availability** during business hours (10:00 - 16:00 CET minimum) as per Art 5(3) and Art 8(3) of Implementing Regulation (EU) 2024/1942, supported by Kubernetes-based high availability.
- **Processing Capacity**: Capability to process **100+ requests per second (RPS)** per instance without performance degradation.
- **Software Stack**: Kotlin on JVM (running in Docker), PostgreSQL for persistence, and Caddy/Nginx for reverse proxying.
- **Scaling**: Native support for **Horizontal Pod Autoscaling (HPA)** based on CPU/Memory usage.

### 8.4 Disaster Recovery
The eFTI Gate should implement disaster recovery measures to ensure continuity of service and data integrity in the event of infrastructure failure, data corruption, or other disruptive incidents.
- **Recovery Time Objective (RTO)**: Target recovery time is 4 hours for critical components (search mechanism, RoI, AS4 endpoint) and 8 hours for non-critical components (Management UI, reporting). These targets apply during business hours; outside business hours, the emergency on-call service initiates recovery procedures with best-effort response time.
- **Recovery Point Objective (RPO)**: Maximum acceptable data loss is 1 hour for the Registry of Identifiers and operation logs. This is achieved through continuous PostgreSQL Write-Ahead Log (WAL) streaming to a standby replica or backup storage.
- **Failover Strategy**: In a Kubernetes-based deployment, pod-level failures are handled automatically through liveness probes and restart policies. For node-level or cluster-level failures, traffic is redirected to a standby instance or secondary cluster. Cross-border AS4 communication is designed to tolerate temporary unavailability through message queuing and retry mechanisms at the sending Gate.
- **Recovery Testing**: Disaster recovery procedures are tested at least annually through simulated failure scenarios. Test results are documented and reviewed as part of the operational audit trail.

### 8.5 Backup Policy
The eFTI Gate should maintain a structured backup regime for all persisted data, ensuring recoverability and compliance with regulatory retention requirements.
- **Scope**: Backups cover the PostgreSQL database (Registry of Identifiers, authorization configuration, operation logs), application configuration, and security certificates/keys.
- **Frequency**: 
**Continuous**: WAL streaming for point-in-time recovery capability.
**Daily**: Full automated database backup, scheduled during low-traffic periods.
**Weekly**: Full system-level snapshot including application configuration and certificate stores.
- **Storage**: Backups are encrypted at rest (AES-256) and stored in a geographically separate location from the primary deployment. Backup storage access is restricted to Gate Administrator role only.
- **Retention**: Daily backups are retained for 30 days. Weekly snapshots are retained for 12 months. Operation log backups are retained for at least two years.
- **Restoration Testing**: Backup restoration is tested quarterly to verify data integrity and recovery procedures. Restoration test results are documented in the operations log.

### 8.6 Monitoring and Observability
The eFTI Gate should implement comprehensive monitoring and observability to support operational awareness, performance management. This section complements the design principles defined in Section 3.1.6 (Observability & Audit Compliance).
- **Health Monitoring**: 
**Liveness probes**: Verify that the eFTI Gate process is running and responsive. Failed probes trigger automatic pod restart.
**Readiness probes**: Verify that the eFTI Gate is ready to accept traffic (database connection active, certificate store loaded, X-Road connection available). Failed probes remove the instance from the load balancer.
**Startup probes**: Allow sufficient time for initial certificate loading and registry synchronization before health checks begin.
- **Performance Metrics**: OpenTelemetry-based instrumentation captures request-level metrics across all protocol boundaries (AS4, REST, X-Road), including: request rate (RPS), response latency (p50, p95, p99), error rate by endpoint and protocol, active connection count, database connection pool utilization, and message queue depth for AS4 exchanges.
- **Alerting**: Threshold-based alerts are configured for critical conditions: sustained error rate above 5%, response latency exceeding defined thresholds, database connection pool exhaustion, certificate expiration within 30 days, disk usage above 80%, and failed health probes. Alerts are routed to the eFTI Gate operations team through the designated notification channel.
- **Distributed Tracing**: Each request receives a unique trace identifier that propagates across protocol boundaries, enabling end-to-end latency analysis for cross-border scenarios (e.g., CA query → Estonian eFTI Gate → AS4 → remote eFTI Gate → remote eFTI platform → response). Trace data is retained for 30 days for operational analysis.
- **Dashboard**: A centralized operational dashboard provides real-time visibility into eFTI Gate status, traffic patterns, error rates, and resource utilization. The dashboard is accessible to eFTI Gate Administrators as defined in Section 6.3.2.
- **Integration with National Infrastructure**: For X-Road-based communication, monitoring data is supplemented by the X-Road operational monitoring provided by RIA, enabling correlation of eFTI Gate-side metrics with national infrastructure health.

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 9. Logical and Physical Architecture
### 9.1 Logical Architecture
The logical architecture of the Estonian eFTI Gate is designed for modularity and high performance, separating concerns between the interface, business logic, and data persistence layers. The **eFTI Gate Core** acts as the central orchestrator, managing authentication through the **Access Checker**, metadata via the **Registry Services**, and data transformation through the **Subsetter Engine**. The custom **eDelivery Module** provides a high-performance implementation of the AS4 protocol, enabling seamless cross-border communication with other EU Member State FTI Gates and eFTI platforms.

```{mermaid}
graph TB
    subgraph "Estonian eFTI Gate (Logical Model)"
        direction TB
        
        subgraph "Interface Layer"
            UI["Management UI - Svelte"]
            AdminAPI["Admin API - REST"]
            PlatformAPI["Platform API - REST"]
            AuthAPI["Competent Authority API - REST/X-Road"]
            EDeliveryAPI["eDelivery Endpoints - AS4/ebMS3"]
        end

        subgraph "Service & Logic Layer"
            Core["Gate Core Engine - Orchestrator"]
            Access["Access Checker - RBAC/IAA"]
            Subsetter["Subsetter Engine - XSLT Filtering"]
            Registry["Registry Services - Platform/Gate/Competent Authority"]
            EDeliveryClient["eDelivery Client - AS4 Signing/C14N"]
        end

        subgraph "Data & Persistence Layer"
            DB[("PostgreSQL")]
            RoI["Registry of Identifiers - UIL Storage"]
            EntityStore["Entity Metadata - Configurations"]
            AuditLogs["Audit Trail - 2-Year Retention"]
        end

        %% Internal Orchestration
        UI <--> AdminAPI
        AdminAPI <--> Core
        PlatformAPI <--> Core
        AuthAPI <--> Core
        EDeliveryAPI <--> Core
        
        Core <--> Access
        Core <--> Subsetter
        Core <--> Registry
        Core <--> EDeliveryClient
        
        Registry <--> EntityStore
        Core <--> RoI
        Core <--> AuditLogs
        
        EntityStore --- DB
        RoI --- DB
        AuditLogs --- DB
    end
```

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

### 9.2 Physical Architecture
The physical deployment of the eFTI Gate leverages containerization and orchestration to ensure scalability, high availability, and secure communication. The system is deployed within a **Kubernetes** cluster, where the **eFTI Gate Core** is horizontally scalable to handle high volumes of concurrent requests. An **Ingress Controller** manages incoming traffic, providing SSL termination and routing. Sensitive cryptographic keys for eDelivery and mTLS are managed by a dedicated certificate storage, while the PostgreSQL database ensures persistent storage of identifiers and metadata with high availability through replication and persistent volume claims.

```{mermaid}
graph TB
    subgraph "Kubernetes Infrastructure (Production Cluster)"
        direction TB
        Ingress["Ingress Controller - Nginx/Caddy"]
        
        subgraph "Gate Core Pods (Scalable)"
            GateApp["Gate Core Container"]
            KeyStore["Certificate Storage - mTLS/AS4 Certificates"]
            Caches["In-Memory Caches"]
            
            GateApp --- KeyStore
            GateApp --- Caches
        end

        subgraph "Database Tier"
            PG[("PostgreSQL - Primary/Standby")]
            PVC["Persistent Volume Claim - Encrypted Storage"]
            PG --- PVC
        end

        %% Internal Traffic Flow
        Ingress -->|"HTTPS / mTLS"| GateApp
        GateApp <-->|"Internal JDBC"| PG
    end
```

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 10. Compliance with eFTI Regulation
The Estonian eFTI Gate is designed and operated in full compliance with the European regulatory framework for electronic freight transport information. This section summarises the applicable legislation, standards, and compliance obligations.

### 10.1 Applicable EU Legislation
- **Regulation (EU) 2020/1056 (eFTI Regulation)**: The base regulation establishing the legal framework for electronic freight transport information across all transport modes excluding Marintime. The eFTI Gate implements the requirements for Member State ICT systems as defined in Articles 8–12.
- **Implementing Regulation (EU) 2024/1942**: Common procedures and detailed rules for accessing and processing eFTI data by competent authorities, including functional and technical requirements for eFTI Gates, the Registry of Identifiers, search mechanisms, and follow-up communications.
- **Delegated Regulation (EU) 2024/2024**: Establishes the eFTI common data set and eFTI data subsets (EU01–EU06). The eFTI Gate validates and processes data exclusively in accordance with the structure, code lists, and business rules defined in this regulation.
- **Delegated Regulation (EU) 2024/2025**: Amends Part B of Annex I to Regulation (EU) 2020/1056, incorporating references to national regulatory information requirements notified by Member States.
- **Implementing Regulation (EU) 2025/2243**: Detailed specifications regarding the functional requirements for eFTI platforms, including platform-to-Gate communication, DIWASS integration, and certification requirements. The eFTI Gate implements the corresponding interface specifications to ensure interoperability with certified eFTI platforms.
- **Regulation (EU) No 910/2014 (eIDAS)**: Electronic identification, authentication, and trust services. The eFTI Gate relies on eIDAS-compliant advanced electronic signatures and qualified electronic seals for non-repudiation, and on electronic identification means for competent authority officer authentication.

### 10.2 Technical Standards and Specifications
- **eFTI Data Model**: Strict adherence to the eFTI XML Schema Definitions (XSDs), including consignment-common.xsd, consignment-identifier.xsd, and associated subset schemas. Schema validation is performed at ingestion for all incoming datasets.
- **Technical Guidance Document (TGD) Annex 1**: The eFTI Gate implements the operational and technical specifications defined in the TGD Document.
- **eDelivery AS4**: Cross-border Gate-to-Gate communication follows the eDelivery AS4 with static discovery, ensuring standardised message exchange across all EU Member States.
- **X-Road**: National integration follows the X-Road protocol specifications as maintained by RIA, ensuring compliance with Estonia's national interoperability framework.

### 10.3 Compliance Assurance
- **Schema validation**: All incoming eFTI data is validated against the official XSD schemas before processing. Non-conformant datasets are rejected with a structured error response.
- **eFTI Platform certification verification**: The eFTI Gate verifies the certification status of connecting eFTI platforms against the Estonian eFTI Platforms registry before accepting UIL registrations or responding to data requests (ref. Section 6.3.1).
- **Audit trail**: A complete, immutable audit trail of all operations supports regulatory compliance verification and supervisory inspections (ref. Section 7.1.1 Audit Logs).
- **Periodic review**: Regulatory compliance is reviewed upon adoption of new implementing or delegated acts, TGD updates, or changes to national legislation.

## 11. Integrated Systems Overview
The following table summarizes the key internal and external systems that must be integrated with the Estonian eFTI Gate to ensure full operational capacity and regulatory compliance.

| System Name | Responsible Organization | Integration Method | Description / Purpose |
| :--- | :--- | :--- | :--- |
| **X-Road (X-Tee)** | RIA (State Information System Authority) | REST / SOAP | National backbone for secure, authenticated data exchange between Estonian government agencies. |
| **eDelivery (AS4)** | EU Commission / Member States | AS4 / ebMS3 | Secure cross-border infrastructure for connecting eFTI Gates and remote platforms. |
| **MTA eFTI Inegration Layer** | MTA | REST API (via X-Road) | Centralized intergation layer for MTA-specific eFTI data use cases. Consolidates queries for key systems (ANTS, SADHES, EMCS, TOTS2) and supports long-term compliance and audit requirements. |
| **Apollo** | MTA / PPA / KeA | REST API (via X-Road) | Mobile/field application for officers to perform real-time roadside checks and searches. |
| **LOIS2** | TRAM  | REST API (via X-Road) | Aviation information system for controlling platforms and flight-based queries. |
| **eFTI Platforms** | Commercial | Synchronous REST | Certified ICT systems providing regulatory freight transport data to the eFTI Gate. |

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 12. Suggested Waste Management Integration (Annex 1)
**Note:** This suggested integration and technical implementation is not currently agreed with involved parties and is subject to additional analysis.
**Note:** Waste transport needs permits, this paragraph does not include information about permits and eFTI gate analysis does not change how these permits are handled

### 12.1 Solution Description
The proposed integration creates a unified digital environment for waste transport by utilizing Estonia's national systems—Kotkas (international waste) and Pistrik (local waste). This allows economic operators to manage both environmental manifests and freight transport data within a single workflow, reducing manual data entry and improving compliance.

The solution consists of 4 possible workflows for both local and international waste transportation:
- Workflow 1. Economic Operator uses only Pistrik system to manage domestic waste transport. In this case Pistrik functions similarly to a eFTI platform. 
- Workflow 2. Economic operator uses eFTI platform to manage domestic waste transport. eFTI Platform is integrated with Pistrik and environmental manifests are created from eFTI platform.
- Workflow 3. Economic operator does double data entry in case of international waste transport. All environmental documents are created in system Kotkas and all eFTI transport documents are created in an eFTI platform. Both document creation needs manual work
- Workflow 4. Economic operator uses eFTI platform to manage international waste transport. eFTI Platform is integrated with Kotkas and environmental manifests are created from eFTI platform. Freight transport documents are also created in eFTI platform according eFTI requirements.

### 12.2 Architecture
```{mermaid}
graph LR
    subgraph Economic Operators
        EO[Transporters / Receivers \n Use KeA web portal directly]
    end
    subgraph eFTI Platform
        PL[Certified eFTI platform \n with or without direct Kotkas/Pistrik int.]
    end

    subgraph State Infrastructure - Secured via X-Road
        Gate[Estonian eFTI Gate]
        App[Competent Authority]
        Kotkas[Kotkas]
        Pistrik[Pistrik]
    end

    subgraph EU Waste Management Infrastructure
        DIWASS[ DIWASS \n EU Waste management central IT system]
    end

    %% B2G Interaction
    EO -- "Requests permit / Creates Waste transport notification in Kotkas UI (workflow 3)" --> Kotkas
    EO -- "Creates Waste doc. and/or Transport doc. in Pistrik UI (workflow 1)" --> Pistrik
    EO -- "Creates Transport doc. and Estonian Waste doc. in eFTI Platform (workflow 2)" --> PL
    EO -- "Creates Transport doc. and International Waste doc. in eFTI Platform (workflow 3 and 4)" --> PL
    PL -. "Optional: Waste doc. sent directly from eFTI platform (workflow 4)" .-> Kotkas
    PL -. "Optional: Waste doc. sent directly from eFTI platform (workflow 2)" .-> Pistrik
    Kotkas -- "Standard Environmental data exchange" --> DIWASS
    Gate -. "Potential future Push service for Environmental information exchange" .-> Kotkas



    %% Platform to Gate connection
    Pistrik <-- "UIL & eFTI Dataset" --> Gate
    PL <-- "UIL & eFTI Dataset" --> Gate
    
    %% Inspection Flow
    App <== "Standard eFTI Query" ==> Gate

    linkStyle 0 stroke:#2e7d32, stroke-width:2px; 
    linkStyle 1 stroke:#e65100, stroke-width:2px;
    linkStyle 2 stroke:#e65100, stroke-width:2px;
    linkStyle 3 stroke:#2e7d32, stroke-width:2px; 
    linkStyle 4 stroke:#2e7d32, stroke-width:2px;
    linkStyle 5 stroke:#e65100, stroke-width:2px;
    linkStyle 7 stroke:#2e7d32, stroke-width:2px;
```
Legend: 
Orange line - Estonian domestic Waste transport
Green line - International waste transport

```{=openxml}
<w:p><w:r><w:br w:type="page"/></w:r></w:p>
```

## 13. Suggested MTA Integration (Annex 2)
**Note:** This suggested integration and technical implementation is not currently agreed with the MTA and is subject to further analysis and additional agreements between KEMIT and the eFTI Gate development team.

### 13.1 Solution Description
Suggestion of this analysis is for MTA to build a new centralized eFTI integration layer for MTA-specific freight transport data use cases. Alternative for new centralized integration layer would be to use one of the existing systems like TOTS2 to perform integration functions. Final agreement has to be made with MTA. By consolidating queries, the MTA can leverage a unified internal interface to access eFTI data across key systems. This will avoid delays in eFTI gate development project and be more cost effective. Latter comes from development needs inside MTA infrastructure only

This architectural approach ensures that the Estonian eFTI Gate only needs to interface with MTA eFTI integration layer (or single chosen system) for these specific MTA workflows, simplifying identity management and supporting high-volume processing. In later phases eFTI gate does not need to deal with different system integration for additional MTA functionality that requires freight transport information.

The solution addresses specific MTA requirements, such as the need for 7-year data retention for customs audits, by bridging the gap between the eFTI Gate's CMDS handling and MTA's long-term compliance needs. MTA will have to review on additional data saving needs to have the capability of long term document retention.

In the ANTS use case, the system assists officials by providing vehicle identification, but the actual eFTI data query is initiated by the MTA official. ANTS will be officially ready for further analysis and development during 2027

### 13.2 Visual representation

*Note: Architecture is a subject to further analysis*

```{mermaid}
flowchart TB
    classDef gateway fill:#0e62a1,color:#fff,stroke:#09436e,stroke-width:2px;
    classDef system fill:#e1f0fa,color:#333,stroke:#0e62a1,stroke-width:1.5px;
    classDef db fill:#fef3c7,color:#333,stroke:#d97706,stroke-width:1.5px;

    subgraph eFTI ["eFTI Infrastructure"]
        Gate["Estonian eFTI Gate"]
    end

    subgraph MTA [MTA Enterprise Architecture]
        
        subgraph IntegrationLayer [Integration Layer]
            MTAGW["MTA eFTI Integration layer/ Single System"]:::gateway
        end

        subgraph AppLayer [Application Layer / Use Cases]
            ANTS["1. ANTS System\n(Assisted Border/Road Check)"]:::system
            SADHES["2. SADHES / EMCS\n(Excise Movement Verification)"]:::system
            AUDIT["3. Audit Toolset\n(Retrospective Customs Audit)"]:::system
            NES["4. NES\n(Third-Country Transport Control)"]:::system
        end

    end

    %% Data Flow / API Connections
    Gate <==> |CA Queries \n X-Road| MTAGW
    
    ANTS --> |Real-time eFTI Data Query| MTAGW
    SADHES --> |Document Request on customer approval| MTAGW
    AUDIT --> |Historical 7-year Data Access| MTAGW
    NES --> |Document Request on customer approval| MTAGW
```

### 13.3 Possible core Use Cases

*Note: Use cases and examples and need to be further identified and analysed*

| Use Case | Primary Actor | Context / Trigger | Description |
| :--- | :--- | :--- | :--- |
| **1. Assisted Border/Road Check (ANTS)** | MTA (Officer) | A vehicle passes an ANTS camera at a border crossing or highway. | The ANTS system captures the license plate, and based on this detection, an MTA official initiates a real-time query via **MTA eFTI Intergation Layer** / **Single system** to verify the existence and content of the eFTI CMDS from the Estonian eFTI Gate. |
| **2. Excise Movement Verification (SADHES/EMCS)** | EO | A user is filing or auditing excise documents in SADHES or EMCS. | **MTA eFTI Intergation Layer** / **Single system** provides the underlying transport documentation (CMR/Waybill) to support excise declarations, ensuring that the physical movement of goods matches the registered transport data. Documents can only be provided via EO request |
| **3. Retrospective Customs Audit** | MTA (Auditor) | An auditor reviews a past import/export declaration (up to 7 years old). | The auditor uses **MTA eFTI Intergation Layer** / **Single system** to retrieve historical eFTI data associated with customs declarations. MTA eFTI Integration Layer facilitates access to relevant eFTI platform data or archived metadata for the required 7-year audit period. |
| **4. Third-Country Transport Control** | MTA (Officer) | A truck from a non-EU country enters or operates within Estonia. | MTA uses **NES** and **MTA eFTI Integration layer** / **Single system** as a gateway to verify transport documentation for third-country vehicles, ensuring compliance with national and international carriage regulations through the standardized eFTI interface. |
