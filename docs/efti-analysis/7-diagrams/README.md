# Diagrammid

> **v2.0 spetsifikatsioon:** [`../../specs/diagrams/`](../../specs/diagrams/README.md) — 25 Mermaid diagrammi (15 järjestusdiagrammi, 5 olekudiagrammi, 3 voodiagrammi, 2 arhitektuuridiagrammi).

## Arhitektuur
Kuna eFTI värav ei ole oma olemuselt keerukas süsteem, siis ei pea olema arhitektuur ka keerukas.
eFTI värav võiks järgida põhimõtteid, mis vähendavad süsteemi keerukust.   
- Ei kasutata väliseid rakendusservereid ega raskekaalulisi raamistikke.
- Puhas ja modulaarne koodibaas, mida on lihtne mõista ja kohandada.
- Lihtsustatud liidesed platvormide ja asutustega integreerimiseks.

```mermaid
graph TD
  subgraph Gate
    Core-->DB[(DB)]
  end
  Platforms <-->|REST API \n eDelivery| Gate
  Gates[Other Gates] <-->|eDelivery| Gate
  Authorities[Local Authorities] -->|REST API| Gate
```

#### 4. Andmevood:

**Identifikaatorite otsing**
```mermaid
sequenceDiagram
  participant CA as Competent Authority
  participant Gate as eFTI Gate
  participant db as Database
  participant OtherGates as Connected eFTI Gates

  CA->>Gate: Identifier query
  Gate->>db: Find identifiers
  db-->>Gate: Results (may be empty)

  Gate->>OtherGates: Query if no results found
  OtherGates-->>Gate: Responses

  Gate-->>CA: Aggregated results
```

**andmeseti päring**
```mermaid
sequenceDiagram
  participant CA as Competent Authority
  participant Gate as eFTI Gate
  participant Source as External Gate or <br> Local Platform

  CA->>Gate: Dataset request (UIL)
  Gate->>Source: Dataset query
  Source-->>Gate: Dataset response
  Gate-->>CA: Dataset response
```

**Järelpäringu sõnum**
```mermaid
sequenceDiagram
  participant CA as Competent Authority
  participant Gate as eFTI Gate
  participant Source as External Gate or <br> Local Platform

  CA->>Gate: Follow-up query <br> (Message, UIL, Request ID)
  Gate->>Source: Forward follow-up query
```