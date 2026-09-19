package com.writhlang.dsl

/** A tenor-shaped shift applied to a discount curve. */
sealed trait CurveShift
case object NoCurveShift extends CurveShift
/** Uniform shift across all tenors (flat bump). */
case class FlatShift(amount: Double) extends CurveShift
/** Localised bump centred on a single tenor (linear tent, width 1 year). */
case class BucketShift(tenorYears: Double, amount: Double) extends CurveShift
/** Twist from a short-end shift to a long-end shift, linear up to a pivot tenor. */
case class TwistShift(shortAmount: Double, longAmount: Double, pivotYears: Double) extends CurveShift

sealed trait InstrumentSpec {
  def id: String
}

sealed trait PrepayCurve
case class FlatPrepay(cpr: Double) extends PrepayCurve
case class RampPrepay(startCpr: Double, endCpr: Double, rampMonths: Int) extends PrepayCurve

case class BondSpec(
  id: String,
  notional: Double,
  coupon: Double,
  maturityYears: Int,
  rate: Double,
  spread: Double,
  couponFreq: Int
) extends InstrumentSpec

case class MortgageSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  termMonths: Int,
  prepayCurve: PrepayCurve
) extends InstrumentSpec

/** Pass-through MBS pool: WAC note rate, WAM maturity, prepay behaviour. */
case class MbsPoolSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  wac: Double,
  wamMonths: Int,
  prepayCurve: PrepayCurve
) extends InstrumentSpec

case class SwapSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  fixedRate: Double,
  maturityYears: Int,
  freq: Int
) extends InstrumentSpec

case class CapSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  strike: Double,
  maturityYears: Int,
  freq: Int,
  volatility: Double
) extends InstrumentSpec

case class SwaptionSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  strike: Double,
  expiryYears: Double,
  swapMaturityYears: Int,
  freq: Int,
  volatility: Double,
  isPayer: Boolean
) extends InstrumentSpec

case class FxForwardSpec(
  id: String,
  notional: Double,
  domesticRate: Double,
  fxRate: Double,
  foreignRate: Double,
  maturityYears: Double
) extends InstrumentSpec

case class Shock(name: String, factors: Map[String, Double], curve: List[CurveShift])

case class Program(shocks: List[Shock], instruments: List[InstrumentSpec])
