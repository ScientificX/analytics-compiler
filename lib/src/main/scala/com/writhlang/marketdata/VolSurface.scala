package com.writhlang.marketdata

/**
  * An implied-volatility surface, addressed by (expiry, strike) in years and rate
  * respectively. Volatility is annualised and stored as a decimal (0.20 == 20%).
  *
  * Stage 1 uses a flat surface (one volatility for every expiry/strike). The
  * two-argument signature is kept now so that a real expiry×strike grid can slot
  * in later without changing the pricing call sites.
  */
final case class VolSurface(volAt: (Double, Double) => Double) {
  /** Annualised implied volatility (decimal) at an expiry (years) and strike. */
  def volatility(expiryYears: Double, strike: Double): Double =
    volAt(expiryYears, strike)

  /**
    * Add `delta(expiry)` to the surface. Stage 1 shifts are term-only (the strike
    * dimension is ignored until a real grid lands); the additive shift keeps the
    * legacy flat-vol behaviour.
    */
  def shifted(delta: Double => Double): VolSurface =
    VolSurface((expiry, strike) => volAt(expiry, strike) + delta(expiry))
}

object VolSurface {
  /** A flat volatility surface at a single annualised volatility (decimal). */
  def flat(volatility: Double): VolSurface = VolSurface((_, _) => volatility)
}
