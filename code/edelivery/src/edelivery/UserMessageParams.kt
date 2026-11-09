package edelivery

data class UserMessageParams(
  val requestKey: RequestKey,
  val action: String = "eftiGateAction",
  val service: String = "bdx:noprocess",
  val serviceType: String? = "tc1",
  /** The peer's own ConversationId to echo; null means the request key's UUID. */
  val conversationId: String? = null,
)
