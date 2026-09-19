package com.writhlang.engine

import com.writhlang.dsl._
import org.apache.commons.math3.distribution.NormalDistribution

object Pricing {
  private val normal = new NormalDistribution()

  /** Base flat discount rate for an instrument (before any shock). */
  def baseRate(instrument: InstrumentSpec): Double = instrument match {
    case s: BondSpec => s.rate + s.spread
    case s: MortgageSpec => s.rate + s.spread
    case s: MbsPoolSpec => s.rate + s.spread
    case s: SwapSpec => s.rate + s.spread
    case s: CapSpec => s.rate + s.spread
    case s: SwaptionSpec => s.rate + s.spread
    case s: FxForwardSpec => s.domesticRate
  }

  /** Unified pricing entry point used by the executor. */
  def price(instrument: InstrumentSpec, curveShifts: List[CurveShift], scalar: Map[String, Double]): Double = {
    val curve = DiscountCurve(baseRate(instrument), curveShifts)
    instrument match {
      case s: BondSpec => bondPrice(s, curve)
      case s: MortgageSpec => mortgagePrice(s, curve, scalar.getOrElse("prepay", 0.0))
      case s: MbsPoolSpec => mbsPrice(s, curve, scalar.getOrElse("prepay", 0.0))
      case s: SwapSpec => swapPrice(s, curve)
      case s: CapSpec => capPrice(s, curve, scalar.getOrElse("volatility", 0.0))
      case s: SwaptionSpec => swaptionPrice(s, curve, scalar.getOrElse("volatility", 0.0))
      case s: FxForwardSpec => fxForwardPrice(s, curve, scalar.getOrElse("fx", 0.0))
    }
  }

  def bondPrice(spec: BondSpec, curve: DiscountCurve): Double = {
    val periods = spec.maturityYears * spec.couponFreq
    val accrual = 1.0 / spec.couponFreq
    val couponCash = spec.notional * spec.coupon * accrual
    (1 to periods).map { i =>
      val t = i * accrual
      val cf = if (i == periods) couponCash + spec.notional else couponCash
      cf * curve.df(t)
    }.sum
  }

  def mortgagePrice(spec: MortgageSpec, curve: DiscountCurve, prepayShift: Double): Double =
    amortisingPrice(spec.notional, spec.rate, spec.termMonths, spec.prepayCurve, prepayShift, curve)

  def mbsPrice(spec: MbsPoolSpec, curve: DiscountCurve, prepayShift: Double): Double =
    amortisingPrice(spec.notional, spec.wac, spec.wamMonths, spec.prepayCurve, prepayShift, curve)

  private def amortisingPrice(
    notional: Double,
    noteRate: Double,
    termMonths: Int,
    prepayCurve: PrepayCurve,
    prepayShift: Double,
    curve: DiscountCurve
  ): Double = {
    val noteRateMonthly = noteRate / 12.0
    val payment =
      if (noteRateMonthly == 0.0) notional / termMonths
      else notional * noteRateMonthly / (1.0 - math.pow(1.0 + noteRateMonthly, -termMonths.toDouble))

    val cprCurve = expandPrepayCurve(prepayCurve, termMonths)

    var balance = notional
    var pv = 0.0
    var month = 1
    while (month <= termMonths && balance > 0.0) {
      val interest = balance * noteRateMonthly
      val scheduledPrincipal = payment - interest
      val cpr = clamp(cprCurve(month - 1) + prepayShift, 0.0, 1.0)
      val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
      val prepay = (balance - scheduledPrincipal) * smm
      val totalPrincipal = scheduledPrincipal + prepay
      val cashflow = interest + totalPrincipal
      pv += cashflow * curve.df(month / 12.0)
      balance -= totalPrincipal
      month += 1
    }
    pv
  }

  def swapPrice(spec: SwapSpec, curve: DiscountCurve): Double = {
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val fixedCash = spec.notional * spec.fixedRate * accrual
    val fixedPV = (1 to periods).map { i => fixedCash * curve.df(i * accrual) }.sum
    val floatPV = spec.notional * (1.0 - curve.df(spec.maturityYears.toDouble))
    fixedPV - floatPV
  }

  def capPrice(spec: CapSpec, curve: DiscountCurve, volShift: Double): Double = {
    val vol = math.max(spec.volatility + volShift, 0.0)
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val strike = spec.strike
    (1 to periods).map { i =>
      val t0 = (i - 1) * accrual
      val t1 = i * accrual
      val forward = forwardRate(curve, t0, t1, accrual)
      val black = blackCall(forward, strike, vol, math.max(t0, 1e-9))
      spec.notional * accrual * black * curve.df(t1)
    }.sum
  }

  def swaptionPrice(spec: SwaptionSpec, curve: DiscountCurve, volShift: Double): Double = {
    val vol = math.max(spec.volatility + volShift, 0.0)
    val t = spec.expiryYears
    val accrual = 1.0 / spec.freq
    val start = t
    val periods = spec.swapMaturityYears * spec.freq

    val annuity = (1 to periods).map { i =>
      accrual * curve.df(start + i * accrual)
    }.sum

    val forwardSwap = (curve.df(start) - curve.df(start + spec.swapMaturityYears.toDouble)) / annuity

    val payoff =
      if (spec.isPayer) blackCall(forwardSwap, spec.strike, vol, t)
      else blackPut(forwardSwap, spec.strike, vol, t)

    spec.notional * annuity * payoff
  }

  def fxForwardPrice(spec: FxForwardSpec, curve: DiscountCurve, fxShift: Double): Double = {
    val t = spec.maturityYears
    val dfDomestic = curve.df(t)
    val dfForeign = math.exp(-spec.foreignRate * t)
    val spotFx = spec.fxRate * (1.0 + fxShift)
    spec.notional * (spotFx * dfForeign - spec.fxRate * dfDomestic)
  }

  def expandPrepayCurve(curve: PrepayCurve, termMonths: Int): Vector[Double] = curve match {
    case FlatPrepay(cpr) => Vector.fill(termMonths)(cpr)
    case RampPrepay(start, end, rampMonths) =>
      val ramp = math.max(1, rampMonths)
      val slope = (end - start) / (ramp.toDouble - 1.0).max(1.0)
      (0 until termMonths).map { idx =>
        if (idx < ramp) start + slope * idx.toDouble else end
      }.toVector
  }

  private def forwardRate(curve: DiscountCurve, t0: Double, t1: Double, accrual: Double): Double = {
    val df0 = curve.df(t0)
    val df1 = curve.df(t1)
    if (df1 <= 0.0 || accrual <= 0.0) 0.0
    else (df0 / df1 - 1.0) / accrual
  }

  private def blackCall(f: Double, k: Double, sigma: Double, t: Double): Double = {
    if (f <= 0.0 || k <= 0.0) math.max(f - k, 0.0)
    else if (sigma <= 0.0 || t <= 0.0) math.max(f - k, 0.0)
    else {
      val sqrtT = math.sqrt(t)
      val d1 = (math.log(f / k) + 0.5 * sigma * sigma * t) / (sigma * sqrtT)
      val d2 = d1 - sigma * sqrtT
      f * normal.cumulativeProbability(d1) - k * normal.cumulativeProbability(d2)
    }
  }

  private def blackPut(f: Double, k: Double, sigma: Double, t: Double): Double = {
    if (f <= 0.0 || k <= 0.0) math.max(k - f, 0.0)
    else blackCall(f, k, sigma, t) - f + k
  }

  private def clamp(value: Double, min: Double, max: Double): Double =
    math.max(min, math.min(max, value))
}
