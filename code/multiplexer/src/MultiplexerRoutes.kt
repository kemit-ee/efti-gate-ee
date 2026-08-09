import io.swagger.v3.oas.annotations.Operation
import klite.annotations.GET
import klite.annotations.POST
import klite.annotations.PathParam
import java.util.*

class MultiplexerRoutes {
  @Operation(description = "Multiplex a search request to all gates. Returns first response as XML.")
  @POST("/first/:searchId") fun multiplex(xml: String, @PathParam searchId: UUID): String {
    TODO("Implement")
  }

  @Operation(description = "Returns rest of the received responses as XMLs with string delimiter. Can be polled.")
  @GET("/rest/:searchId") fun rest(@PathParam searchId: UUID): String {
    TODO("Implement")
  }
}
