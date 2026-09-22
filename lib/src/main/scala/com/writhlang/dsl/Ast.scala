package com.writhlang.dsl

import com.writhlang.risk.{RiskFactorKey, ShiftType}

/**
  * Instrument specs carry trade terms (notional, coupon, strike, maturity, …)
  * and *reference* market objects by [[RiskFactorKey]]. Market inputs are no
  * longer embedded scalars — they come from a separate market-data snapshot.
  */
sealed trait InstrumentSpec { def id: String }

/** Fixed-coupon bond. */
case class BondSpec(
  id: String,
  notional: Double,
  coupon: Double,
  maturityYears: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  couponFreq: Int
) extends InstrumentSpec

/** Amortising mortgage loan. `noteRate` is the loan's contractual rate. */
case class MortgageSpec(
  id: String,
  notional: Double,
  noteRate: Double,
  termMonths: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  prepayCurve: RiskFactorKey
) extends InstrumentSpec

/** Pass-through MBS pool (WAC/WAM). */
case class MbsPoolSpec(
  id: String,
  notional: Double,
  wac: Double,
  wamMonths: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  prepayCurve: RiskFactorKey
) extends InstrumentSpec

/** Interest-rate swap. */
case class SwapSpec(
  id: String,
  notional: Double,
  fixedRate: Double,
  maturityYears: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  freq: Int
) extends InstrumentSpec

/** Interest-rate cap. */
case class CapSpec(
  id: String,
  notional: Double,
  strike: Double,
  maturityYears: Int,
  freq: Int,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  volSurface: RiskFactorKey
) extends InstrumentSpec

/** Option to enter a swap. */
case class SwaptionSpec(
  id: String,
  notional: Double,
  strike: Double,
  expiryYears: Double,
  swapMaturityYears: Int,
  freq: Int,
  isPayer: Boolean,
  discountCurve: RiskFactorKey,
  creditCurve: RiskFactorKey,
  volSurface: RiskFactorKey
) extends InstrumentSpec

/** FX forward, priced by covered interest parity. `fxRate` is the contracted rate. */
case class FxForwardSpec(
  id: String,
  notional: Double,
  fxRate: Double,
  maturityYears: Double,
  domesticCurve: RiskFactorKey,
  foreignCurve: RiskFactorKey,
  fxSpot: RiskFactorKey
) extends InstrumentSpec

/**
  * A curve-shift shape expressed in the DSL. Applied to a curve's *quotes*
  * before re-bootstrapping (par-conversion), never to an interpolated rate.
  */
sealed trait CurveShift
case object NoCurveShift extends CurveShift
/** Uniform additive shift across all tenors. */
case class FlatShift(amount: Double) extends CurveShift
/** Localised tent bump centred on one tenor (width 1 year). */
case class BucketShift(tenorYears: Double, amount: Double) extends CurveShift
/** Twist from a short-end shift to a long-end shift, linear up to a pivot tenor. */
case class TwistShift(shortAmount: Double, longAmount: Double, pivotYears: Double) extends CurveShift
/** Sine-wave shift `A·sin(ω·x + φ)`. */
case class SineShift(amplitude: Double, omega: Double, phase: Double) extends CurveShift

/**
  * A single move inside a shock.
  *
  * - [[PointMove]] shifts one market object (a curve pillar, an FX spot, a vol
  *   surface, or a prepay vector) by a signed amount. `shiftType` says whether
  *   the amount is additive (rates) or relative (FX/vol).
  * - [[ShapeMove]] applies a whole-curve shape (parallel/bucket/twist/sine).
  */
sealed trait ShockMove
case class PointMove(key: RiskFactorKey, shiftType: ShiftType, amount: Double) extends ShockMove
case class ShapeMove(key: RiskFactorKey, shape: CurveShift) extends ShockMove

/** A named bundle of factor moves (scenario). */
case class Shock(name: String, moves: List[ShockMove])

/** A portfolio tree node: either a leaf instrument or a named sub-portfolio. */
sealed trait PortfolioNode
case class InstrumentLeaf(spec: InstrumentSpec) extends PortfolioNode
case class Portfolio(name: String, children: List[PortfolioNode]) extends PortfolioNode

/** A whole DSL document: optional shocks plus a portfolio tree. */
case class Program(shocks: List[Shock], nodes: List[PortfolioNode])
