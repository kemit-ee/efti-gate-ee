# Integration quick start

This is the short, example-heavy entry point for integrating with the eFTI Gate. It complements, and
doesn't replace, the detailed reference guides:

- **eFTI platforms (REST):** [`docs/developer/platform_api_integration_guide.md`](developer/platform_api_integration_guide.md)
  (Estonian) is the full reference — message flows, headers, XML shapes, error codes, onboarding
  checklist. This guide pulls the same three operations into curl/Python/Java snippets so you can see
  working code before reading the full spec.
- **Competent authorities (X-Road):** [`docs/developer/x_road_authority_integration_guide.md`](developer/x_road_authority_integration_guide.md)
  and [`docs/developer/x_road_openapi.yaml`](developer/x_road_openapi.yaml) — a separate integration
  surface, authenticated via the X-Road Security Server, not covered by the code examples below.
- **Full machine-readable contracts:** [`docs/specs/openapi.yaml`](specs/openapi.yaml) (core/platform/
  admin surface) and [`docs/developer/x_road_openapi.yaml`](developer/x_road_openapi.yaml) (X-Road).
  There is no hosted interactive Swagger UI — load either file into your own OpenAPI tooling
  (Swagger Editor, Postman, Insomnia) if you want one.

## Who authenticates how

There is no single "JWT for everyone" flow — the gate has three distinct identities, each with its own
credential:

| Caller | Credential | Where it's checked |
|---|---|---|
| **eFTI platform** (you, uploading identifiers) | `X-Api-Key` header, issued by your gate operator when your platform is registered | `platforms/.guard.yml` |
| **Competent authority** (via X-Road) | X-Road Security Server client cert + `X-Road-Client` member code | `xroad/.guard.yml` |
| **Human admin user** (TARA login, admin UI only) | TARA-issued, TIM-minted JWT bearer token | `admin/.guard.yml` |

If you're a platform integrator, you only ever need the `X-Api-Key` your gate operator gives you — you
never see or need a JWT.

## 1. Upload a consignment identifier (platform → gate)

`POST /platforms/v1/consignments/{datasetId}` with a bare `<ParameterIDSetCriteria>` XML body (the
"short form" — see the full guide for the alternative full-FTI004 form used over eDelivery/AS4).

### curl

```sh
curl -X POST "https://<your-gate-host>/platforms/v1/consignments/b0284c81-a79d-11f1-931a-4ca954ebfb9f" \
  -H "X-Api-Key: <your-api-key>" \
  -H "Content-Type: text/xml" \
  --data-binary @- <<'XML'
<ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <CarrierAcceptanceDateParameterScope>
    <SpecifiedDateTime><udt:DateTimeString format="102">20260924</udt:DateTimeString></SpecifiedDateTime>
  </CarrierAcceptanceDateParameterScope>
  <CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>
  <MainCarriageTransportMeansIDParameterScope><ID>MOCK-123</ID></MainCarriageTransportMeansIDParameterScope>
  <MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope>
</ParameterIDSetCriteria>
XML
```

A 201 response body looks like:

```json
{"rowId": "...", "datasetId": "b0284c81-...", "platformId": "your-platform-id",
 "gateId": "EU-EE", "status": "ACTIVE", "createdAt": "2026-09-24T..."}
```

### Python

```python
import requests

xml_body = """<ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
  <CarrierAcceptanceDateParameterScope>
    <SpecifiedDateTime><udt:DateTimeString format="102">20260924</udt:DateTimeString></SpecifiedDateTime>
  </CarrierAcceptanceDateParameterScope>
  <CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>
  <MainCarriageTransportMeansIDParameterScope><ID>MOCK-123</ID></MainCarriageTransportMeansIDParameterScope>
  <MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope>
</ParameterIDSetCriteria>"""

response = requests.post(
    "https://<your-gate-host>/platforms/v1/consignments/b0284c81-a79d-11f1-931a-4ca954ebfb9f",
    headers={"X-Api-Key": "<your-api-key>", "Content-Type": "text/xml"},
    data=xml_body.encode("utf-8"),
)
response.raise_for_status()
print(response.json())
```

### Java

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

var xmlBody = """
    <ParameterIDSetCriteria xmlns:udt="urn:eu:move:eFTI:data:standard:UnqualifiedDataType:34">
      <CarrierAcceptanceDateParameterScope>
        <SpecifiedDateTime><udt:DateTimeString format="102">20260924</udt:DateTimeString></SpecifiedDateTime>
      </CarrierAcceptanceDateParameterScope>
      <CarrierAcceptanceCountryParameterScope><CountryID>DE</CountryID></CarrierAcceptanceCountryParameterScope>
      <MainCarriageTransportMeansIDParameterScope><ID>MOCK-123</ID></MainCarriageTransportMeansIDParameterScope>
      <MainCarriageModeCodeParameterScope><TransportModeParameterCode>3</TransportModeParameterCode></MainCarriageModeCodeParameterScope>
    </ParameterIDSetCriteria>""";

var client = HttpClient.newHttpClient();
var request = HttpRequest.newBuilder()
    .uri(URI.create("https://<your-gate-host>/platforms/v1/consignments/b0284c81-a79d-11f1-931a-4ca954ebfb9f"))
    .header("X-Api-Key", "<your-api-key>")
    .header("Content-Type", "text/xml")
    .POST(HttpRequest.BodyPublishers.ofString(xmlBody))
    .build();

HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode() + " " + response.body());
```

## 2. Serve dataset requests (gate → your platform)

When a competent authority requests the full dataset, the gate calls **your** service —
`GET {baseUrl}/v1/dataset/{datasetId}?subsets=EU01,EU02,...` — with whatever outgoing headers your
gate operator configured for your platform record. You must respond `200 OK`,
`Content-Type: text/xml`, with a bare `<rsm:SpecifiedSupplyChainConsignment>` element (no envelope —
the gate adds that). See
[`docs/developer/examples/platforms/dataset-response.xml`](developer/examples/platforms/dataset-response.xml)
for the full element shape.

### Python (Flask sketch)

```python
from flask import Flask, request, Response

app = Flask(__name__)

@app.route("/v1/dataset/<dataset_id>", methods=["GET"])
def dataset(dataset_id):
    subsets = request.args.get("subsets", "").split(",")
    xml = render_dataset_xml(dataset_id, subsets)  # your own lookup + rendering
    return Response(xml, mimetype="text/xml")
```

### Java (minimal servlet sketch)

```java
@WebServlet("/v1/dataset/*")
public class DatasetServlet extends HttpServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String datasetId = req.getPathInfo().substring(1);
    String subsets = req.getParameter("subsets");
    String xml = renderDatasetXml(datasetId, subsets); // your own lookup + rendering
    resp.setContentType("text/xml");
    resp.getWriter().write(xml);
  }
}
```

A non-200, unreachable, or malformed response here surfaces to the requesting authority as a
**502 Bad Gateway** — your service's availability directly affects their workflow.

## 3. Accept follow-up messages (gate → your platform)

`POST {baseUrl}/v1/dataset/{datasetId}/follow-up`, JSON body:

```json
{
  "uil": {"gateId": "EU-EE", "platformId": "your-platform-id", "datasetId": "..."},
  "referenceIds": ["073b32bc-..."],
  "message": "Please send supporting documents for this consignment.",
  "files": [{"fileName": "document.pdf", "base64Content": "...", "mimeType": "application/pdf"}]
}
```

Respond `201` — the gate passes your status straight back to the requesting authority. `files` is
optional.

## Error handling

Guard-level failures (missing/invalid `X-Api-Key`, etc.) and DSL-level error responses are
[RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) Problem Details objects:
`{type, title, status, detail, code}`, served with `Content-Type: application/problem+json` on the
guard/auth boundary. The full catalog of error `code`s, their HTTP status, and example payloads is in
[`docs/specs/errors.json`](specs/errors.json) — look up the `code` field from a response there for the
exact meaning and whether it's retryable (`retryable: true/false`).

Common ones you'll hit as a platform integrator:

| HTTP status | `code` | Meaning |
|---|---|---|
| 401 | `UNAUTHORIZED` | `X-Api-Key` missing or doesn't match a registered, `ONLINE` platform |
| 403 | — (platform ownership) | The `PlatformID`/`GateID` encoded in your XML doesn't match your authenticated identity |
| 400 | `BAD_REQUEST_GENERAL` | Malformed XML — check namespaces and required fields against the XSD |
| 502 | `GATEWAY_UNAVAILABLE` | The gate couldn't reach your `baseUrl` (dataset/follow-up), or (on upload) a downstream failure |

## FAQ

**Do I need a JWT?** No — platforms authenticate with a plain `X-Api-Key`, issued by your gate
operator. JWTs are only for human admin-UI logins (TARA) and aren't part of the platform integration
surface at all.

**Can I use eDelivery/AS4 instead of REST?** Yes, as an alternative to the `baseUrl` REST callback
model, if your platform already runs an eDelivery access point. This needs a separate certificate
exchange with your gate operator — see §4 of the full platform guide.

**What's the difference between `/platforms/v1/consignments` and `/platforms/v1/consignments/{id}`?**
The first form (no path id) expects a full FTI004 document (used when connecting over eDelivery/AS4);
the second (with a path `datasetId`) expects just the `<ParameterIDSetCriteria>` element, for direct
REST integrations. Use the second form unless you specifically need AS4.

**My dataset callback is slow / times out — what happens?** The requesting authority sees a 502. There
is no retry built into the gate for this path today; make your `baseUrl` endpoint fast and reliable, or
talk to your gate operator about timeout tuning.

**Is there a test platform I can integrate against before going live?** Yes — ask your gate operator
for access to the `mock` test platform (`X-Api-Key: mock-secret-key` in a dev/staging environment) to
exercise the full upload → dataset-request → follow-up cycle end to end.

**Where do I report a bug or ask a question not covered here?** Your gate operator's contact is in the
full platform guide's footer.

## Not done (tracked in Jira as a known gap)

This guide, the full platform/X-Road guides, and the OpenAPI specs exist and are what partners actually
integrate against today. Two items from the original ticket weren't done and are intentionally left
open rather than faked: external-partner review of this specific document, and a hosted interactive
Swagger UI (Ruuter's built-in one is intentionally admin-gated — see the API epic).
