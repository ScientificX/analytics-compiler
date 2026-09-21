package com.writhlang.marketdata

/**
  * A term structure of continuously-compounded zero rates: a function mapping a
  * tenor (years) to an annualised zero rate (decimal, `0.04` = 4%) and the
  * derived discount factor `df(t) = e^(-rateAt(t)·t)`.
  *
  * Stage 2 turns this into a sealed trait with two shapes: a function-backed
  * curve (for flat credit spreads and ad-hoc shifted curves) and a bootstrapped
  * curve (pillar points + log-linear discount-factor interpolation).
  */
trait Curve {
  def rateAt(tenorYears: Double): Double

  /** Discount factor DF(t) = e^(-r·t) for a cashflow at tenor t (years). */
  def df(tenorYears: Double): Double = math.exp(-rateAt(tenorYears) * tenorYears)

  /** A new curve with an additive shift `delta(t)` added to every rate. */
  def shifted(delta: Double => Double): Curve = Curve.function(t => rateAt(t) + delta(t))

  /** The pointwise sum of this curve and another (rate + rate). */
  def plus(other: Curve): Curve = Curve.function(t => rateAt(t) + other.rateAt(t))
}

object Curve {
  private final case class FunctionCurve(rateFn: Double => Double) extends Curve {
    def rateAt(tenorYears: Double): Double = rateFn(tenorYears)
  }

  /** A curve backed by an arbitrary rate function. */
  def function(f: Double => Double): Curve = FunctionCurve(f)

  /** A flat curve at a single annualised rate (decimal). */
  def flat(rate: Double): Curve = FunctionCurve(_ => rate)
}

/**
  * A curve bootstrapped from quoted instruments, represented by its pillar
  * points `(tenorYears, discountFactor)` and interpolated log-linearly in
  * discount factors (flat forward) between pillars.
  */
final case class BootstrappedCurve(pillars: Vector[(Double, Double)]) extends Curve {
  override def df(tenorYears: Double): Double =
    DiscountCurveInterpolator.df(pillars, tenorYears)

  override def rateAt(tenorYears: Double): Double =
    if (tenorYears <= 0.0) 0.0 else -math.log(df(tenorYears)) / tenorYears
}
