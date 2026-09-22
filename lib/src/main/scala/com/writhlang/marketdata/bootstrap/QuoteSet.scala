package com.writhlang.marketdata.bootstrap

/**
  * The ordered set of par instruments that define one curve. This is the *market
  * input*: a curve is bootstrapped from it, and a shock transforms the quotes
  * here (then re-bootstraps) rather than mutating the derived curve.
  *
  * A shock is applied to the quotes, not the curve, so the whole curve moves
  * consistently (a bump to the 2Y swap propagates to later maturities through
  * the bootstrap).
  */
final case class QuoteSet(instruments: List[ParInstrument]) {

  /** Instruments in ascending maturity order (the order the bootstrapper uses). */
  def sorted: List[ParInstrument] = instruments.sortBy(_.maturityYears)

  /** Add `delta(tenorYears)` to each instrument's quoted rate. */
  def shifted(delta: Double => Double): QuoteSet =
    QuoteSet(instruments.map(i => i.withRate(i.rate + delta(i.tenorYears))))

  /** Add `amount` to the single instrument whose tenor equals `tenorYears`. */
  def shiftOne(tenorYears: Double, amount: Double): QuoteSet =
    QuoteSet(instruments.map { i =>
      if (math.abs(i.tenorYears - tenorYears) < 1e-12) i.withRate(i.rate + amount)
      else i
    })
}
