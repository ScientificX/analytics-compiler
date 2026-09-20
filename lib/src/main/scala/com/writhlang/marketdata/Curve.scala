package com.writhlang.marketdata

/**
  * A term structure of continuously-compounded zero rates.
  *
  * `rateAt(t)` returns the annualised zero rate (as a decimal, so 0.04 == 4%)
  * at tenor `t` measured in years. The discount factor is derived as
  * `df(t) = e^(-rateAt(t) * t)` (continuous compounding).
  *
  * Stage 1 represents a curve as a pure function of tenor. This is deliberately
  * general so that flat, bucket, twist and sine shocks are all just "add a
  * function of tenor" — no shape-specific code lives here. Stage 2 replaces the
  * function with bootstrapped pillar points; the `rateAt`/`df` interface stays.
  *
  * Note: a function-valued field means two `Curve`s are not meaningfully
  * compared by `==`; the DAG never compares ops, so this is acceptable.
  */
final case class Curve(rateAt: Double => Double) {
  /** Discount factor DF(t) = e^(-r·t) for a cashflow at tenor t (years). */
  def df(tenorYears: Double): Double =
    math.exp(-rateAt(tenorYears) * tenorYears)

  /** Return a new curve with an additive shift `delta(t)` added to every rate. */
  def shifted(delta: Double => Double): Curve =
    Curve(t => rateAt(t) + delta(t))

  /** Return the pointwise sum of this curve and another (rate + rate). */
  def plus(other: Curve): Curve =
    Curve(t => rateAt(t) + other.rateAt(t))
}

object Curve {
  /** A flat curve at a single annualised rate (decimal). */
  def flat(rate: Double): Curve = Curve(_ => rate)
}
