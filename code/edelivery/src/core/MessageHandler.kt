package core

interface MessageHandler {
  fun response(requestKey: RequestKey, xml: String): String?
}
