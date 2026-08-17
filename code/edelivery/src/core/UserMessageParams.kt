package core

data class UserMessageParams(
  val requestKey: RequestKey,
  val action: String = "eftiGateAction",
  val service: String = "bdx:noprocess",
  val serviceType: String? = "tc1",
)
