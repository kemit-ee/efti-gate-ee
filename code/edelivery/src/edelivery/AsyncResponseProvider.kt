package edelivery

import klite.Config
import java.util.UUID.randomUUID

interface AsyncResponseProvider {
  fun register(key: RequestKey)
  fun waitForResponse(key: RequestKey): String
  fun provideResponse(key: RequestKey, payload: String): Boolean
}

data class RequestKey(val receiverId: PartyId, val requestId: String = randomUUID().toString(), val senderId: PartyId = Config.partyId) {
  constructor(s: String): this(PartyId(s.substringBefore(':')), s.substringAfter(':'))
  override fun toString() = "$receiverId:$requestId"
}
