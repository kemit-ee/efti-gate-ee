# PROMPT-05: Generate Data Transformations Specification for eFTI Gate v2.0

> [!IMPORTANT]
> **Background prompt — not authoritative.** See [`PROMPT-00-INDEX.md`](PROMPT-00-INDEX.md) for historical context, including how stack references here (Kotlin / Klite / Digilogistika Keskus PoC paths) relate to the v2 spec's stack-open position.

## Context

You are helping create a **complete data transformations specification** for eFTI Gate v2.0, a production system for electronic freight transport information exchange under EU Regulation 2024/2024.

The eFTI Gate handles multiple data formats and must transform between them:
- **XML (eFTI datasets)**: EU regulation-compliant consignment data in EU01-EU07 subset schemas
- **JSON (API contracts)**: RESTful API requests/responses
- **SOAP/AS4**: Gate-to-gate communication via eDelivery network
- **Database (PostgreSQL)**: Normalized storage of identifiers and metadata

This specification will be used by external developers during procurement to implement all data transformations consistently.

## Your Task

Generate a **complete data transformations specification document** (`specs/data-transformations.md`) that defines:
- XML → JSON transformations (30+ scenarios)
- JSON → XML transformations (dataset creation, updates)
- XML → Database extractions (identifier metadata, vehicle info, dangerous goods)
- SOAP/AS4 message wrapping/unwrapping
- Error handling for malformed data
- Performance requirements for large datasets

## Input Materials Required

Before starting, you must have access to:

1. **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
   - **XML handling**: `edelivery/src/edelivery/Xml.kt`
     - `dropXmlHeader()` helper function
     - `dropXmlRoot()` helper function
     - XML parsing patterns
   - **Consignment parsing**: `gate/src/efti/Consignment.kt`
     - How Current Gate extracts vehicle plate, country, transport mode
     - How dangerous goods are detected (UN numbers)
   - **SOAP message handling**: `edelivery/src/edelivery/SoapClient.kt`
     - AS4 message structure
     - How dataset XML is wrapped in SOAP envelope

2. **Epic Documentation**: `docs/epics/` (per-epic files)
   - Epic 1.1: Identifier search (how consignment XML is searched/filtered)
   - Epic 1.2: Dataset provision (how XML is returned to authorities)
   - Epic 1.5: Dataset management (how platforms upload/update XML)
   - All epics mentioning "EU01", "EU07", "dangerous goods", "vehicle"

3. **OpenAPI Specification**: `specs/openapi.yaml` (from PROMPT-01)
   - Request/response schemas for all endpoints
   - JSON structure for identifier registration, search results, dataset requests
   - Error response format (RFC 7807)

4. **Database Schema**: `specs/db/schema.sql` (from PROMPT-02)
   - `consignments` table (what metadata is extracted from XML)
   - `dangerous_goods` table (how UN numbers are stored)
   - `vehicles` table (if separate, how vehicle info is normalized)

5. **eFTI Regulation Schemas**:
   - EU Regulation 2024/2024 data subsets (EU01-EU07)
   - **Note**: If Askend doesn't have official XSD schemas, use Current Gate example XMLs from `gate/test/resources/` or document assumptions clearly

6. **Feedback Document**: `docs/Askend/feedback/CRITICAL-SPECIFICATION-GAPS.md`
   - Section 1.5: "Missing Specification File: Data Transformations"
   - Examples of required transformation documentation

## Specification Requirements

### 1. Transformation Categories

Your specification must cover:

#### A. XML → JSON (API Responses)
When Gate returns consignment data to authority or platform:
- Full dataset XML → JSON representation for API response
- Metadata extraction → JSON search result
- Error XML → JSON error response

#### B. JSON → XML (API Requests)
When platform provides dataset via API:
- JSON request body → eFTI-compliant XML dataset
- Validation: Ensure generated XML conforms to EU subset schemas

#### C. XML → Database (Metadata Extraction)
When identifier is registered:
- Extract vehicle plate, country, mode from XML
- Extract dangerous goods UN numbers (if present)
- Extract dataset type (EU01, EU07, etc.)
- Store in normalized database tables

#### D. Database → JSON (Search Results)
When search returns local results:
- Database row → JSON search result object
- Include: identifier ID, vehicle info, dangerous goods flag, dataset metadata

#### E. SOAP/AS4 Wrapping (Gate-to-Gate)
When broadcasting search or requesting dataset from another gate:
- JSON search request → SOAP envelope → AS4 message
- AS4 response → SOAP unwrapping → JSON result

#### F. Error Transformations
When validation fails or XML is malformed:
- XML parsing errors → RFC 7807 JSON error response
- Missing required fields → structured error with field path
- Invalid values → error with validation rules

### 2. Key Transformation Scenarios (30+ Required)

For each scenario, provide:
- **Input example** (realistic data, complete structure)
- **Output example** (complete, valid)
- **Transformation rules** (step-by-step logic)
- **Edge cases** (missing fields, invalid values, multiple elements)
- **Performance notes** (if applicable: large datasets, streaming)

#### Scenarios to Cover:

**XML → JSON**
1. Full EU07 dangerous goods dataset XML → JSON response
2. Full EU01 road transport dataset XML → JSON response
3. Consignment XML with multiple vehicles → JSON array
4. Consignment XML with missing optional fields → JSON with nulls
5. Malformed XML (unclosed tag) → JSON error response
6. XML with namespaces → JSON (how to handle namespace prefixes)

**JSON → XML**
7. JSON identifier registration request → EU07 XML dataset
8. JSON dataset update request → Modified XML (preserve structure)
9. JSON with special characters (quotes, ampersands) → Escaped XML
10. JSON with nested arrays → XML repeated elements

**XML → Database Extraction**
11. EU07 XML with single UN number → `dangerous_goods` table insert
12. EU07 XML with multiple UN numbers → Multiple `dangerous_goods` rows
13. EU01 XML with vehicle plate "123ABC" → `consignments.vehicle_plate`
14. XML with missing vehicle info → Database NULL values (allowed?)
15. XML with dataset type in metadata → `consignments.dataset_type`

**Database → JSON**
16. Database consignment row → JSON search result (identifier found)
17. Database row with dangerous goods → JSON with `dangerousGoods: true`
18. Database row with NULL vehicle plate → JSON with `vehiclePlate: null`

**SOAP/AS4 Transformations**
19. JSON search request → SOAP envelope → AS4 message (example: search for plate "123ABC")
20. AS4 search response → SOAP unwrap → JSON result array
21. Dataset request to remote gate → SOAP envelope with identifier ID
22. Dataset response from remote gate → Extract XML from SOAP body

**Error Handling**
23. XML parsing error (invalid character) → RFC 7807 JSON error
24. Missing required field in XML → Validation error with XPath
25. Invalid UN number (not 4 digits) → Validation error
26. XML too large (> 10MB) → Payload too large error
27. Unsupported dataset type → Error response

**Special Cases**
28. XML with CDATA sections → How to preserve/transform
29. XML with comments → Strip or preserve?
30. XML with processing instructions → Strip or preserve?
31. Streaming large XML (> 1MB) → Chunked processing
32. Concurrent transformations → Thread safety

### 3. XML Schema References

For each eFTI dataset type, document:
- **EU01 (Road Transport)**: Required elements, optional elements, structure
- **EU07 (Dangerous Goods)**: UN number location, packaging info, emergency contact
- **Other subsets** (EU02-EU06): High-level structure (if applicable)

If official XSD schemas are available, reference them. If not, document assumptions based on Current Gate examples.

### 4. Helper Functions from Current Gate

Document Current Gate XML helper functions and how they should be preserved:

**From `Xml.kt`**:
```kotlin
fun dropXmlHeader(xml: String): String
// Removes: <?xml version="1.0" encoding="UTF-8"?>
// Use case: When storing dataset in database (header not needed)

fun dropXmlRoot(xml: String): String
// Removes outer root element, keeps inner content
// Use case: When extracting specific sections from dataset
```

**Transformation rule**: v2.0 must implement equivalent functionality (language-agnostic, but same behavior).

### 5. Performance Requirements

Document performance targets:
- **Small datasets (< 100KB)**: Transformation latency < 10ms
- **Medium datasets (100KB - 1MB)**: Transformation latency < 100ms
- **Large datasets (1MB - 10MB)**: Streaming transformation, < 500ms
- **Concurrent transformations**: Support 100 concurrent transformations without degradation
- **Memory usage**: No more than 2x dataset size in memory (use streaming for large files)

### 6. Validation Rules

For each transformation, specify validation:
- **XML validation**: Must conform to eFTI subset XSD (if available) or documented structure
- **JSON validation**: Must conform to OpenAPI schema
- **Database validation**: Must satisfy database constraints (NOT NULL, foreign keys, check constraints)
- **Business validation**: Required fields present, valid values (e.g., country codes ISO 3166-1)

### 7. Error Handling Strategy

When transformation fails:
- **Return**: RFC 7807 JSON error response
- **Log**: Detailed error with input snippet (first 200 chars, sanitized)
- **Do NOT**: Return full input in error (may contain sensitive data)
- **HTTP status codes**:
  - 400: Validation error (missing field, invalid format)
  - 422: Business logic error (e.g., dataset type mismatch)
  - 413: Payload too large
  - 500: Unexpected transformation error (XML parser crash)

## Document Structure

Your generated `specs/data-transformations.md` should follow this structure:

```markdown
# eFTI Gate v2.0 Data Transformations Specification

**Version**: 1.0
**Date**: 2026-04-22
**Status**: Development-ready specification

## 1. Overview
- Purpose: Why transformations are needed
- Data formats involved: XML, JSON, SOAP, SQL
- High-level transformation flow diagram (Mermaid)

## 2. eFTI Dataset Schemas
### 2.1 EU01 - Road Transport Dataset
- Structure overview
- Required elements
- Optional elements
- Example XML (complete, realistic)

### 2.2 EU07 - Dangerous Goods Dataset
- Structure overview
- UN number location (XPath: `/consignment/dangerousGoods/unNumber`)
- Example XML with multiple UN numbers

### 2.3 Other Subsets (EU02-EU06)
- High-level structure (if applicable to Gate)

## 3. Transformation Scenarios

### 3.1 XML → JSON Transformations

#### 3.1.1 Full EU07 Dataset → JSON Response

**Use case**: Authority requests dataset, Gate returns as JSON

**Input XML** (example):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<consignment xmlns="urn:efti:eu:2024:dataset:eu07">
  <id>550e8400-e29b-41d4-a716-446655440000</id>
  <vehicle>
    <plate>123ABC</plate>
    <country>EE</country>
  </vehicle>
  <dangerousGoods>
    <unNumber>1203</unNumber>
    <properShippingName>Gasoline</properShippingName>
    <packagingGroup>II</packagingGroup>
  </dangerousGoods>
</consignment>
```

**Transformation rules**:
1. Parse XML using XML parser (validate against XSD if available)
2. Extract all elements, preserve structure
3. Convert XML elements to JSON keys (camelCase)
4. Convert XML attributes to JSON (prefix with `@` if needed, or flatten)
5. Handle namespaces: Strip `xmlns` in JSON
6. Result: Valid JSON matching OpenAPI schema

**Output JSON**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "vehicle": {
    "plate": "123ABC",
    "country": "EE"
  },
  "dangerousGoods": {
    "unNumber": "1203",
    "properShippingName": "Gasoline",
    "packagingGroup": "II"
  }
}
```

**Edge cases**:
- Missing optional fields: Omit from JSON (or include as `null` - specify policy)
- Multiple dangerous goods items: Return JSON array
- Invalid UN number: Validation error before transformation

**Performance**: < 10ms for typical EU07 dataset (< 50KB)

---

#### 3.1.2 Malformed XML → JSON Error Response

**Input XML**:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<consignment>
  <vehicle>
    <plate>123ABC</plate>
    <country>EE
  </vehicle>
</consignment>
```

**Transformation rules**:
1. Attempt XML parsing
2. Catch parsing exception (unclosed `<country>` tag)
3. Generate RFC 7807 error response

**Output JSON** (error):
```json
{
  "type": "https://api.efti.ee/errors/xml-parse-error",
  "title": "XML Parsing Error",
  "status": 400,
  "detail": "Unclosed tag: country at line 5, column 3",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_XML_MALFORMED",
  "timestamp": "2026-04-22T10:30:45.123Z"
}
```

**HTTP status**: 400 Bad Request

---

[Continue with remaining 30+ scenarios...]

### 3.2 JSON → XML Transformations
[Scenarios 7-10 with examples]

### 3.3 XML → Database Extractions
[Scenarios 11-15 with examples]

### 3.4 Database → JSON Transformations
[Scenarios 16-18 with examples]

### 3.5 SOAP/AS4 Transformations
[Scenarios 19-22 with examples]

### 3.6 Error Handling Scenarios
[Scenarios 23-27 with examples]

### 3.7 Special Cases
[Scenarios 28-32 with examples]

## 4. Helper Functions (From Current Gate)

### 4.1 dropXmlHeader()

**Purpose**: Remove XML declaration from dataset before storage

**Input**: `<?xml version="1.0" encoding="UTF-8"?>\n<consignment>...</consignment>`

**Output**: `<consignment>...</consignment>`

**Implementation notes**:
- Remove first line if it starts with `<?xml`
- Preserve all other content
- v2.0 must implement equivalent (language-agnostic)

### 4.2 dropXmlRoot()

**Purpose**: Extract inner content from root element

**Input**: `<root><child>Content</child></root>`

**Output**: `<child>Content</child>`

**Implementation notes**:
- Remove outer `<root>...</root>` tags
- Preserve all inner content including attributes, namespaces
- Use case: Extract specific sections from complex XML

## 5. Validation Rules

### 5.1 XML Validation
- Must conform to eFTI subset XSD (EU01, EU07, etc.) - if schemas provided
- If no XSD: Validate against documented structure (Section 2)
- Required fields must be present (document for each subset)
- Data types must match (e.g., UN number is 4-digit integer)

### 5.2 JSON Validation
- Must conform to OpenAPI schema (`specs/openapi.yaml`)
- Use JSON Schema validator
- Return 400 with field-level errors if validation fails

### 5.3 Database Validation
- Extracted values must satisfy database constraints
- Example: `consignments.vehicle_country` must be ISO 3166-1 alpha-2 code
- Foreign keys must exist (e.g., platform_id references platforms.id)

### 5.4 Business Validation
- Vehicle plate format: Country-specific rules (Estonia: 3-digit + 3-letter, or other format)
- UN numbers: Must be valid (1000-9999 range, cross-check with UN dangerous goods list if available)
- Dataset type: Must be one of EU01-EU07

## 6. Performance Requirements

| Dataset Size | Transformation Type | Max Latency | Notes |
|--------------|---------------------|-------------|-------|
| < 100KB | XML → JSON | 10ms | Typical consignment |
| 100KB - 1MB | XML → JSON | 100ms | Large consignment with multiple vehicles |
| 1MB - 10MB | XML → JSON | 500ms | Use streaming parser |
| Any size | JSON → XML | 2x of XML → JSON | Generation faster than parsing |
| Concurrent | 100 transformations | No degradation | Thread-safe transformations |

**Memory usage**:
- Small datasets (< 100KB): In-memory transformation (2x size)
- Large datasets (> 1MB): Streaming transformation (SAX parser for XML, streaming JSON writer)

## 7. Error Handling

### 7.1 Error Response Format

All transformation errors return RFC 7807 JSON:
```json
{
  "type": "https://api.efti.ee/errors/{error-type}",
  "title": "Human-readable title",
  "status": 400,
  "detail": "Specific error details",
  "instance": "/v1/platform/identifiers",
  "errorCode": "ERR_CODE_FROM_CATALOG",
  "timestamp": "2026-04-22T10:30:45.123Z",
  "validationErrors": [
    {
      "field": "vehicle.country",
      "message": "Must be ISO 3166-1 alpha-2 code",
      "rejectedValue": "Estonia"
    }
  ]
}
```

### 7.2 Error Scenarios

| Error Type | HTTP Status | Error Code | Example |
|------------|-------------|------------|---------|
| XML parsing error | 400 | ERR_XML_MALFORMED | Unclosed tag |
| Missing required field | 400 | ERR_VALIDATION_FAILED | Vehicle plate missing |
| Invalid value | 400 | ERR_INVALID_VALUE | Country code "XX" invalid |
| Unsupported dataset type | 422 | ERR_UNSUPPORTED_DATASET_TYPE | Dataset type "EU99" |
| Payload too large | 413 | ERR_PAYLOAD_TOO_LARGE | Dataset > 10MB |
| Unexpected error | 500 | ERR_TRANSFORMATION_FAILED | XML parser crash |

### 7.3 Logging

When transformation fails:
- **Log level**: WARN (client error) or ERROR (server error)
- **Include**: Error code, error message, input size (bytes), first 200 chars of input (sanitized)
- **Do NOT log**: Full dataset (may contain sensitive data), credentials, certificates

## 8. Security Considerations

### 8.1 XML External Entity (XXE) Prevention
- **Disable external entities** in XML parser
- Configuration example (Java):
  ```java
  factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
  factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
  ```

### 8.2 XML Bomb Prevention
- **Limit XML size**: Max 10MB
- **Limit XML depth**: Max 20 levels deep
- **Timeout**: XML parsing must complete within 5 seconds

### 8.3 JSON Injection Prevention
- **Escape special characters** when converting XML → JSON
- **Validate JSON** before returning to client

### 8.4 SQL Injection Prevention
- **Use parameterized queries** when inserting extracted values
- **Never concatenate** extracted XML values into SQL strings

## 9. Testing Strategy

### 9.1 Unit Tests
- Test each transformation scenario independently
- Input: Example XML/JSON
- Expected output: Exact match or schema validation
- Edge cases: Missing fields, invalid values, empty elements

### 9.2 Integration Tests
- End-to-end: HTTP request → transformation → database → response
- Example: POST /v1/platform/identifiers with XML → verify database row → verify JSON response

### 9.3 Performance Tests
- Large dataset transformation (5MB XML → JSON)
- Concurrent transformations (100 parallel)
- Memory profiling (no memory leaks)

### 9.4 Validation Tests
- Invalid XML → expect 400 error
- Missing required field → expect validation error with field name
- Unsupported dataset type → expect 422 error

## 10. Migration from Current Gate

### 10.1 Current Gate Implementation
- Language: Kotlin
- XML library: Kotlin's built-in XML parsing (based on Java SAX/DOM)
- Helper functions: `dropXmlHeader()`, `dropXmlRoot()` in `Xml.kt`

### 10.2 v2.0 Implementation
- Language: TBD by development partner (Java/Kotlin/Go/etc.)
- XML library: Must support streaming for large files
- Helper functions: Implement equivalent behavior

### 10.3 Compatibility
- **XML structure**: Identical to Current Gate (no changes to eFTI schemas)
- **JSON structure**: Defined by OpenAPI spec (may differ from Current Gate if Current Gate doesn't have JSON API)

## Appendix A: Complete eFTI Dataset Examples

### A.1 EU01 - Road Transport (Complete Example)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<consignment xmlns="urn:efti:eu:2024:dataset:eu01">
  <id>40a2af73-c502-48f7-a400-964bf61f164e</id>
  <vehicle>
    <plate>456XYZ</plate>
    <country>FI</country>
  </vehicle>
  <mode>ROAD</mode>
  <departureDate>2026-04-20T08:00:00Z</departureDate>
  <arrivalDate>2026-04-22T16:00:00Z</arrivalDate>
</consignment>
```

### A.2 EU07 - Dangerous Goods (Multiple UN Numbers)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<consignment xmlns="urn:efti:eu:2024:dataset:eu07">
  <id>3df2c1eb-7abc-4d8e-9f01-2a3b4c5d6e7f</id>
  <vehicle>
    <plate>789DEF</plate>
    <country>DE</country>
  </vehicle>
  <dangerousGoods>
    <unNumber>1203</unNumber>
    <properShippingName>Gasoline</properShippingName>
    <packagingGroup>II</packagingGroup>
  </dangerousGoods>
  <dangerousGoods>
    <unNumber>1950</unNumber>
    <properShippingName>Aerosols</properShippingName>
    <packagingGroup>III</packagingGroup>
  </dangerousGoods>
</consignment>
```

## Appendix B: XPath Reference

Common XPath expressions for extracting data:

| Data Element | XPath | Example Value |
|--------------|-------|---------------|
| Consignment ID | `/consignment/id` | 550e8400-... |
| Vehicle plate | `/consignment/vehicle/plate` | 123ABC |
| Vehicle country | `/consignment/vehicle/country` | EE |
| Transport mode | `/consignment/mode` | ROAD |
| UN number | `/consignment/dangerousGoods/unNumber` | 1203 |
| Dataset type | Namespace URI | eu01, eu07 |

## Appendix C: Code Examples (Reference Implementation)

### C.1 XML → JSON (Kotlin Example from Current Gate)
```kotlin
fun parseConsignment(xml: String): Consignment {
  val doc = parseXml(xml)
  return Consignment(
    id = doc.selectText("//consignment/id"),
    vehiclePlate = doc.selectText("//vehicle/plate"),
    vehicleCountry = doc.selectText("//vehicle/country"),
    mode = doc.selectText("//mode"),
    dangerousGoods = doc.selectAll("//dangerousGoods/unNumber").map { it.text }
  )
}
```

### C.2 JSON → XML (Pseudocode)
```
function createXmlDataset(json: Object, datasetType: String): String {
  xmlBuilder = new XmlBuilder()
  xmlBuilder.declaration("1.0", "UTF-8")
  xmlBuilder.element("consignment", xmlns=datasetType) {
    element("id", json.id)
    element("vehicle") {
      element("plate", json.vehicle.plate)
      element("country", json.vehicle.country)
    }
    if (json.dangerousGoods) {
      for (dg in json.dangerousGoods) {
        element("dangerousGoods") {
          element("unNumber", dg.unNumber)
          element("properShippingName", dg.name)
        }
      }
    }
  }
  return xmlBuilder.toString()
}
```

---

**Document complete**. External developers can implement all transformations using this specification.
```

## Quality Requirements

### Zero Tolerance
- ❌ No placeholders: "TBD", "TODO", "example value", "fill in later"
- ❌ No generic examples: "test@example.com", "example.com", "ABC123"
- ❌ No incomplete transformations: All 30+ scenarios must have complete input/output examples

### Realistic Data Requirements
- **XML namespaces**: Use realistic eFTI URNs (e.g., `urn:efti:eu:2024:dataset:eu07`)
- **Vehicle plates**: Estonian format "123ABC", Finnish "456XYZ", German "789DEF"
- **UN numbers**: Real dangerous goods codes (1203, 1950, 1965, 1072, 1075)
- **UUIDs**: Valid v4 format from `uuidgen`
- **Timestamps**: ISO 8601 format "2026-04-22T10:15:30Z"
- **Country codes**: ISO 3166-1 alpha-2 (EE, FI, DE, SE)

### Language Requirements
- **Unambiguous**: "10ms latency" not "fast transformation"
- **With units**: "10MB maximum" not "large file limit"
- **With rationale**: "Use streaming parser for files > 1MB to avoid memory exhaustion"

### Consistency Requirements
- **Terminology**: Use exact terms from OpenAPI (dataset, identifier, consignment, vehicle)
- **Error codes**: Match error catalog (ERR_XML_MALFORMED, ERR_VALIDATION_FAILED)
- **Field names**: Match OpenAPI schema (vehiclePlate, not vehicle_plate or VehiclePlate)

### Completeness Requirements
- ✅ All 30+ transformation scenarios with complete input/output
- ✅ All XML examples are valid, well-formed XML
- ✅ All JSON examples are valid JSON (can be parsed)
- ✅ All transformations reference OpenAPI schema fields
- ✅ External developer can implement by copy-pasting examples

## Validation Criteria

Before submitting `data-transformations.md`:

### 1. XML Validity
```bash
# Extract all XML blocks and validate
grep -A 20 '```xml' specs/data-transformations.md | xmllint --noout -
# Must succeed with no errors
```

### 2. JSON Validity
```bash
# Extract all JSON blocks and validate
grep -A 30 '```json' specs/data-transformations.md | jq . > /dev/null
# Must succeed with no errors
```

### 3. Completeness Check
- [ ] All 30+ scenarios documented with input/output examples
- [ ] All transformation rules specified (step-by-step)
- [ ] All edge cases documented
- [ ] All error scenarios covered

### 4. Cross-Reference Validation
- [ ] JSON field names match OpenAPI schema
- [ ] Error codes match error catalog
- [ ] Database table/column names match schema.sql
- [ ] XPath expressions are valid

### 5. Performance Requirements
- [ ] Latency targets specified for all dataset sizes
- [ ] Memory usage documented
- [ ] Streaming strategy documented for large files

### 6. Security Validation
- [ ] XXE prevention documented
- [ ] XML bomb prevention documented
- [ ] Input size limits specified
- [ ] SQL injection prevention documented

## Output Format

**File**: `specs/data-transformations.md`

**Expected size**: 30-40 pages (A4)

**Format**: GitHub-flavored Markdown with:
- Code blocks for XML (use ```xml)
- Code blocks for JSON (use ```json)
- Code blocks for code examples (use ```kotlin, ```java, or ```pseudocode)
- Tables for transformation rules
- Mermaid diagrams (optional, for transformation flow)

## Success Criteria

Your generated specification is complete when:

✅ **All 30+ transformation scenarios** documented with complete input/output examples
✅ **Zero placeholders** (TBD, TODO, example)
✅ **All XML validates** (well-formed, realistic namespaces)
✅ **All JSON validates** (can be parsed, matches OpenAPI schema)
✅ **Realistic data** (Estonian plates, real UN numbers, valid UUIDs)
✅ **Cross-references correct** (OpenAPI fields, error codes, DB schema)
✅ **Implementable** (external developer can copy-paste examples and start coding)
✅ **Security documented** (XXE prevention, XML bombs, input limits)
✅ **Performance documented** (latency targets, streaming strategy)

---

**Ready to generate?** Provide the input materials and start creating the specification.
