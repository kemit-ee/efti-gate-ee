# LLM Prompt: Generate Error Catalog for eFTI Gate v2.0

> [!IMPORTANT]
> **Background prompt — not authoritative.** See [`PROMPT-00-INDEX.md`](PROMPT-00-INDEX.md) for historical context, including how stack references here (Kotlin / Klite / Digilogistika Keskus PoC paths) relate to the v2 spec's stack-open position.

## Task
Create `errors.json` (300-500 lines) - Structured error catalog with 30+ error scenarios

## Required Input Materials - CHECKLIST

**⚠️ BEFORE STARTING**: Verify you have ALL required inputs below. If any are missing, **STOP** and request them.

### ✅ Mandatory Inputs - Verify Each One

- [ ] **OpenAPI Specification**: `specs/openapi.yaml` (from PROMPT-01)
  - **Purpose**: Extract all error responses (400, 401, 403, 404, 409, 429, 500, 502, 503, 504)
  - **Must include**: All endpoint error definitions
  - **What to extract**: Error scenarios, error response examples, which endpoints return which errors

- [ ] **Current Gate Source Code**: `{CURRENT_GATE_SOURCE}/`
  - **Purpose**: Understand error handling patterns
  - **What to check**: Error handling in `gate/src/*/Routes.kt`, exception types
  - **What to extract**: Exception-to-error-code mappings, error messages

- [ ] **Epic Documentation**: `efti_full_epics_en.md`
  - **Purpose**: Business error scenarios
  - **What to extract**: Error conditions from acceptance criteria

- [ ] **Technical Analysis Documents**:
  - [ ] `efti-gate-deep-dive-analysis.md` - Error handling patterns
  - [ ] `gap-analysis-askend-vs-my-analysis.md` - Missing error handling

- [ ] **Feedback Document**: `./CRITICAL-SPECIFICATION-GAPS.md`
  - **Purpose**: Complete error catalog example
  - **Section**: 1.3 - Error Catalog example

### ⚠️ Cross-Prompt Dependencies

- **CRITICAL**: This prompt DEPENDS on PROMPT-01 (OpenAPI spec)
- You MUST complete PROMPT-01 first and validate the OpenAPI spec
- Extract all error responses from `specs/openapi.yaml` before starting

### ❌ If Missing Inputs

**DO NOT PROCEED** if OpenAPI spec is not ready. The error catalog will be incomplete.

**Action Required**:
1. Complete PROMPT-01 first
2. Validate OpenAPI spec contains all error responses
3. Then generate error catalog

## Required Structure

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "eFTI Gate Error Catalog",
  "version": "2.0.0",
  "errors": {
    "BAD_REQUEST_GENERAL": {
      "code": "BAD_REQUEST_GENERAL",
      "httpStatus": 400,
      "title": "Bad Request",
      "description": "Request contains invalid parameters or malformed data",
      "type": "https://api.efti.ee/errors/bad-request",
      "examples": [
        {
          "scenario": "Missing required header",
          "request": "GET /v1/identifiers/123ABC (no X-Request-ID header)",
          "response": {
            "type": "https://api.efti.ee/errors/bad-request",
            "title": "Bad Request",
            "status": 400,
            "detail": "Missing required header: X-Request-ID",
            "instance": "/v1/identifiers/123ABC"
          }
        }
      ],
      "logLevel": "WARN",
      "retryable": false
    }
    // ... 29+ more error definitions
  },
  "errorMapping": {
    "ValidationException": "INVALID_XML",
    // ... exception class -> error code mappings
  }
}
```

## Minimum Required Errors (30+)
- BAD_REQUEST_GENERAL (400)
- INVALID_XML (400)
- INVALID_REQUEST_ID (400)
- DUPLICATE_REQUEST_ID (409)
- MISSING_REQUIRED_HEADER (400)
- UNAUTHORIZED (401)
- TOKEN_EXPIRED (401)
- TOKEN_INVALID (401)
- FORBIDDEN (403)
- FORBIDDEN_SUBSET (403)
- NOT_FOUND (404)
- CONSIGNMENT_NOT_FOUND (404)
- GATE_NOT_FOUND (404)
- CONFLICT (409)
- RATE_LIMIT_EXCEEDED (429)
- INTERNAL_ERROR (500)
- DATABASE_ERROR (500)
- GATEWAY_UNAVAILABLE (502)
- PLATFORM_UNAVAILABLE (502)
- SERVICE_UNAVAILABLE (503)
- PLATFORM_TIMEOUT (504)

Each error MUST include: code, httpStatus, title, description, type (RFC 7807 URI), examples (realistic scenarios), logLevel, retryable

Reference: `./CRITICAL-SPECIFICATION-GAPS.md` Section 1.3 for complete example

Generate now.
