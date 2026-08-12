package domain

import klite.Converter

data class DangerousGoods(val code: String) {
  companion object {
    val EXTREME = DangerousGoods("0")
    val HIGH = DangerousGoods("1")
    val EXPLOSIVES = DangerousGoods("1a")
    val MEDIUM = DangerousGoods("2")
    val LOW = DangerousGoods("3")
    val PACKAGING = DangerousGoods("4")

    init {
      Converter.use { DangerousGoods(it) }
    }
  }

  override fun toString() = code
}
