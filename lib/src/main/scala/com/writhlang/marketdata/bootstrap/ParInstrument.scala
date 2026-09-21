package com.writhlang.marketdata.bootstrap

import com.writhlang.risk.Tenor

/**
  * A quoted market instrument used to bootstrap a discount curve.
  *
  * Each par instrument carries a quoted rate (a decimal, so `0.04` = 4%) and a
  * maturity. The bootstrapper walks these in maturity order, solving for one
  * discount factor per instrument. `rate` is the *observable market quote* —
  * this is what a sensitivity bump perturbs (par-conversion), never an
  * interpolated curve point.
  */
sealed trait ParInstrument {
  /** The quoted rate (decimal). */
  def rate: Double

  /** The tenor the quote is notionally observed at (for shock shapes). */
  def tenorYears: Double

  /** The maturity at which this instrument pins a discount factor. */
  def maturityYears: Double

  /** A copy with the quoted rate replaced (used when applying a shock). */
  def withRate(newRate: Double): ParInstrument
}

/**
  * A cash deposit: lends money to `tenor`, earning the quoted rate with
  * continuous compounding. It pins `df(tenor)` directly:
  * `df(T) = e^(-rate · T)`.
  */
final case class Deposit(tenor: Tenor, rate: Double) extends ParInstrument {
  def tenorYears: Double    = tenor.years
  def maturityYears: Double = tenor.years
  def withRate(newRate: Double): Deposit = copy(rate = newRate)
}

/**
  * An interest-rate future / FRA: a forward rate over `[start, end]`. The quoted
  * `rate` is a continuously-compounded forward rate; it implies the discount
  * factor at `end` from the factor at `start`:
  * `df(end) = df(start) · e^(-rate · (end - start))`.
  */
final case class Future(start: Tenor, end: Tenor, rate: Double) extends ParInstrument {
  def tenorYears: Double    = start.years
  def maturityYears: Double = end.years
  def withRate(newRate: Double): Future = copy(rate = newRate)
}

/**
  * A par interest-rate swap: pays the fixed `rate` `freq` times a year to
  * `tenor`. At par the fixed-leg value equals the floating-leg value, which
  * pins the final discount factor via the par equation
  * `df(T) = (1 - rate · Σ accrual·df(tᵢ)) / (1 + rate · accrual)`.
  */
final case class Swap(tenor: Tenor, rate: Double, freq: Int = 1) extends ParInstrument {
  def tenorYears: Double    = tenor.years
  def maturityYears: Double = tenor.years
  def withRate(newRate: Double): Swap = copy(rate = newRate)
}
