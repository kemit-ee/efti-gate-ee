package core

import klite.info
import klite.jdbc.*
import klite.toValuesSkipping
import javax.sql.DataSource
import kotlin.concurrent.thread

class MultiNodeAsyncResponseProvider(private val db: DataSource): SingleNodeAsyncResponseProvider() {
  private val table = "async_responses"
  init {
    thread(name = this::class.simpleName, isDaemon = true) {
      db.consumeNotifications(listOf(table)) {
        if (it.name == table) pendingResponses[RequestKey(it.parameter)]?.offer("")
      }
    }
  }

  override fun waitForResponse(key: RequestKey): String {
    log.info("Waiting for response for $key")
    var body = super.waitForResponse(key)
    if (body.isEmpty()) {
      val where = listOf(RequestKey::receiverId to key.receiverId, RequestKey::requestId to key.requestId)
      body = db.select(table, where) { getString("body") }.first()
      db.delete(table, where)
    }
    return body
  }

  override fun provideResponse(key: RequestKey, payload: String): Boolean {
    if (super.provideResponse(key, payload)) return true
    sendUpdateToOtherNodes(key, payload)
    return true
  }

  fun sendUpdateToOtherNodes(requestKey: RequestKey, payload: String) {
    log.info("Inserting response for $requestKey")
    db.insert(table, requestKey.toValuesSkipping(RequestKey::senderId) + ("body" to payload))
    db.notify(table, requestKey.toString())
    Transaction.current()?.commit()
  }
}
