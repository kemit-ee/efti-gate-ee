package efti.domain

import klite.Converter

data class Mode(val code: String) {
  companion object {
    val MARINE = Mode("1")
    val RAIL = Mode("2")
    val ROAD = Mode("3")
    val AIR = Mode("4")
    val WATERWAY = Mode("8")

    init {
      Converter.use { Mode(it) }
    }
  }

  override fun toString() = code
}
