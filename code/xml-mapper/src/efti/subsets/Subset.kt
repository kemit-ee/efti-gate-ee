package efti.subsets

import klite.StringValue

class Subset(value: String): StringValue(value) {
  init {
    require(isFull || isIdentifier || countryCode != null) { "Subset '$value' should start with country code" }
  }

  val countryCode: CountryCode? get() = if (isFull || isIdentifier || value == null) null else runCatching { CountryCode.valueOf(value.substring(0, 2)) }.getOrNull()
  val isFull get() = value == "full"
  val isIdentifier get() = value == "identifier"
}
