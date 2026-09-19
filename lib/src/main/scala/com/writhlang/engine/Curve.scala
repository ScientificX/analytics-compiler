package com.writhlang.engine

import com.writhlang.dsl._

/** Evaluates the amount a single curve shift contributes at a given tenor (years). */
object Curve {
  private val bucketWidth = 1.0

  def shiftAt(shift: CurveShift, tenorYears: Double): Double = shift match {
    case NoCurveShift => 0.0
    case FlatShift(amount) => amount
    case BucketShift(tenor, amount) =>
      val d = math.abs(tenorYears - tenor)
      if (d >= bucketWidth) 0.0 else amount * (1.0 - d / bucketWidth)
    case TwistShift(short, long, pivot) =>
      val p = math.max(pivot, 1e-9)
      if (tenorYears <= 0.0) short
      else if (tenorYears >= p) long
      else short + (long - short) * (tenorYears / p)
  }
}

/** A discount curve built from a flat base rate plus a list of tenor shifts. */
case class DiscountCurve(baseFlat: Double, shifts: List[CurveShift]) {
  def rateAt(tenorYears: Double): Double =
    baseFlat + shifts.map(Curve.shiftAt(_, tenorYears)).sum

  def df(tenorYears: Double): Double =
    math.exp(-rateAt(tenorYears) * tenorYears)
}

object DiscountCurve {
  def flat(rate: Double): DiscountCurve = DiscountCurve(rate, Nil)
}
