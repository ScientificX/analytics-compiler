package com.writhlang.marketdata

/**
  * A spot market quote — the current price/rate of a traded asset (e.g. an FX
  * spot rate). Unlike curves and surfaces it is a single number, so a shock is
  * either a relative move (multiply by 1 + amount, the standard FX convention)
  * or an absolute move (add amount).
  */
final case class SpotQuote(value: Double) {
  /** Relative shock: S' = S * (1 + amount). `amount` is a decimal (0.01 == +1%). */
  def shiftedRelative(amount: Double): SpotQuote =
    SpotQuote(value * (1.0 + amount))

  /** Absolute shock: S' = S + amount (in the quote's own units). */
  def shiftedAbsolute(amount: Double): SpotQuote =
    SpotQuote(value + amount)
}
