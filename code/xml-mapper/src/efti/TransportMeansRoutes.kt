package efti

import efti.domain.TransportMeansConsignment
import efti.domain.TransportMeansRow
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.HttpExchange
import klite.MimeTypes
import klite.StatusCode.Companion.OK
import klite.annotations.POST
import klite.json.JsonMapper

@Tag(
  name = "Transport means",
  description = "Normalization for the X-Road transport-means lookup (scope: allgates)."
)
class TransportMeansRoutes {
  // Rendered with explicit nulls, unlike every other xml-mapper route: ReSql emits nulls for the
  // local slice, so the default omit-nulls rendering would give remote rows a smaller key set than
  // local ones — breaking the "identical shape across scopes" promise (ADR-007 variant A) exactly
  // on the sparse rows where it matters. Scoped to this route on purpose.
  private val json = JsonMapper(renderNulls = true)

  @Operation(description = "Normalize core authority/search results — raw local get_consignments " +
    "rows or remote ConsignmentRows alike — to the curated identifier-level projection that " +
    "scope: local returns (ADR-007 variant A). Drops the identifier XML blob and equipment " +
    "sequence numbers; nests gateId/platformId/datasetId as uil. Renders nulls explicitly so the " +
    "key set matches the local projection field for field.")
  @POST("/normalize") fun normalize(rows: List<TransportMeansRow>, e: HttpExchange) {
    e.send(OK, renderNormalized(rows), MimeTypes.json)
  }

  fun normalized(rows: List<TransportMeansRow>): List<TransportMeansConsignment> =
    rows.map { TransportMeansConsignment(it) }

  fun renderNormalized(rows: List<TransportMeansRow>): String = json.render(normalized(rows))
}
