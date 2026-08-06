import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import klite.annotations.POST
import klite.annotations.PathParam

@Tag(name = "Internal routes", description = "Meant for Ruuter and Multiplexer.")
class InternalRoutes {
    @Operation(description = "Send eDelivery message to given Party.")
    @POST("/send/:partyId") fun send(xml: String, @PathParam partyId: String) {
        TODO("Implement")
    }
}