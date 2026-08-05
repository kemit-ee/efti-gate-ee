package followup

import domain.UIL
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST

@Tag(name = "Follow-up request", description = "These routes are for mapping requests and responses for follow-up request.")
class FollowupRoutes {
    @Operation(description = "Map UIL and Message as JSON to FTI025LodgeFollowUpCommRequest as XML.")
    @POST("/request-to-xml") fun requestToXml(request: FollowupRequest): String {
        TODO("Implement")
    }

    @Operation(description = "Map FTI025LodgeFollowUpCommRequest as XML to UIL and Message as JSON.")
    @POST("/request-to-json") fun requestToJson(xml: String): FollowupRequest {
        TODO("Implement")
    }

    // do we need this at all?
    @Operation(description = "Map FTI030LodgeFollowUpCommResponse as XML to UIL as JSON.")
    @POST("/response-to-json") fun responseToJson(xml: String): UIL {
        TODO("Implement")
    }

    // do we need this at all?
    @Operation(description = "Map UIL as JSON to FTI030LodgeFollowUpCommResponse as XML.")
    @POST("/response-to-xml") fun responseToXml(uil: UIL): String {
        TODO("Implement")
    }
}

data class FollowupRequest(val uil: UIL, val message: String)