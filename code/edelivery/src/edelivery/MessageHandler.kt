package edelivery

data class MessageContext(val key: RequestKey, val xml: String)

interface MessageHandler {
  val handlers: Map<String, (MessageContext) -> String?>
}
