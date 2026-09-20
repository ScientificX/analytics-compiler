package com.writhlang.risk

/**
  * Compatibility layer between the legacy DSL shock words and the new
  * [[RiskFactorKey]] taxonomy.
  *
  * The five DSL words have fixed, distinct keys (default currency EUR):
  *
  * | Legacy DSL word | RiskFactorKey                  |
  * | --------------- | ------------------------------ |
  * | `rate`          | `DiscountCurve:EUR`            |
  * | `spread`        | `CreditCurve:EUR`              |
  * | `prepay`        | `Prepay:EUR` (vector-typed key)|
  * | `volatility`    | `SwaptionVolatility:EUR`       |
  * | `fx`            | `FxSpot:EURUSD` (placeholder)  |
  *
  * `wordFor` is the inverse mapping, used by `DslCompiler` to decide which keys
  * are present in a given shock (whose scalar moves are still stored under the
  * legacy words).
  */
object LegacyRiskFactors {
  val rate: RiskFactorKey = RiskFactorKey(KeyType.DiscountCurve, "EUR")
  val spread: RiskFactorKey = RiskFactorKey(KeyType.CreditCurve, "EUR")
  val prepay: RiskFactorKey = RiskFactorKey(KeyType.Prepay, "EUR")
  val volatility: RiskFactorKey = RiskFactorKey(KeyType.SwaptionVolatility, "EUR")
  val fx: RiskFactorKey = RiskFactorKey(KeyType.FxSpot, "EURUSD")

  val all: List[RiskFactorKey] = List(rate, spread, prepay, volatility, fx)

  def fromWord(word: String): Option[RiskFactorKey] = word match {
    case "rate"       => Some(rate)
    case "spread"     => Some(spread)
    case "prepay"     => Some(prepay)
    case "volatility" => Some(volatility)
    case "fx"         => Some(fx)
    case _            => None
  }

  def wordFor(key: RiskFactorKey): Option[String] = key match {
    case `rate`       => Some("rate")
    case `spread`     => Some("spread")
    case `prepay`     => Some("prepay")
    case `volatility` => Some("volatility")
    case `fx`         => Some("fx")
    case _            => None
  }
}
