package edelivery

data class MessageContext(val key: RequestKey, val xml: String)

interface MessageHandlers {
  val rootTags: Map<String, (MessageContext) -> String?>
}
