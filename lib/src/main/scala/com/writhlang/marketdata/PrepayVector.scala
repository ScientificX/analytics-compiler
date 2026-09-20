package com.writhlang.marketdata

/**
  * A monthly prepayment vector: the Conditional Prepayment Rate (CPR) for each
  * month of a mortgage/MBS pool's life. CPR is the annualised fraction of
  * outstanding principal expected to prepay, stored as a decimal (0.02 == 2%).
  *
  * The vector is indexed from 0 (`cpr(0)` is the first month). A shock is an
  * additive pointwise transform; the `shifted` method applies a `delta` function
  * evaluated at the month's time in years (`month / 12`), so a sine-wave prepay
  * shock `cpr(t) = base(t) + A·sin(ωt+φ)` is just `shifted(t => A·sin(ωt+φ))`.
  */
final case class PrepayVector(values: Vector[Double]) {
  def months: Int = values.size

  /** CPR (decimal) for a 0-based month index. */
  def cpr(monthIndex: Int): Double = values(monthIndex)

  /** Add `delta(t)` to each month's CPR, where t = month/12 (years). */
  def shifted(delta: Double => Double): PrepayVector =
    PrepayVector(values.indices.map(m => values(m) + delta(m / 12.0)).toVector)
}

object PrepayVector {
  /** A flat CPR vector: the same annualised prepay speed for every month. */
  def constant(cpr: Double, months: Int): PrepayVector =
    PrepayVector(Vector.fill(months)(cpr))

  /**
    * A ramp CPR vector: CPR ramps linearly from `startCpr` to `endCpr` over the
    * first `rampMonths` months, then stays flat at `endCpr`.
    */
  def fromRamp(startCpr: Double, endCpr: Double, rampMonths: Int, months: Int): PrepayVector = {
    val ramp = math.max(1, rampMonths)
    val slope = (endCpr - startCpr) / (ramp.toDouble - 1.0).max(1.0)
    val values = (0 until months).map { idx =>
      if (idx < ramp) startCpr + slope * idx.toDouble else endCpr
    }.toVector
    PrepayVector(values)
  }
}
