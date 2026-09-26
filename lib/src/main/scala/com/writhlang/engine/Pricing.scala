package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import org.apache.commons.math3.distribution.NormalDistribution

/**
  * Closed-form pricing for the seven DSL instruments, consuming market-data
  * OBJECTS (curves, vol surface, prepay vector, FX spot) resolved from the
  * instrument spec's risk-factor-key references. Nothing here knows about a
  * "shock" — it prices what it is given.
  */
object Pricing {
  private val normal = new NormalDistribution()

  /**
    * Unified pricing entry point used by the executor. `elapsedYears` is the time
    * already passed (in years) since the valuation date; every time-to-cashflow
    * tenor is shortened by it. This is the Stage 3 "instrument rolls down"
    * mechanism: re-value the aged instrument against the frozen market (theta).
    */
  def price(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double = 0.0): Double = instrument match {
    case s: BondSpec =>
      bondPrice(s, market.combinedCurve(s.discountCurve, s.creditCurve), elapsedYears)
    case s: MortgageSpec =>
      mortgagePrice(
        s,
        market.combinedCurve(s.discountCurve, s.creditCurve),
        market.prepayVector(s.prepayCurve),
        elapsedYears
      )
    case s: MbsPoolSpec =>
      mbsPrice(
        s,
        market.combinedCurve(s.discountCurve, s.creditCurve),
        market.prepayVector(s.prepayCurve),
        elapsedYears
      )
    case s: SwapSpec =>
      swapPrice(s, market.combinedCurve(s.discountCurve, s.creditCurve), elapsedYears)
    case s: CapSpec =>
      capPrice(
        s,
        market.combinedCurve(s.discountCurve, s.creditCurve),
        market.volSurface(s.volSurface),
        elapsedYears
      )
    case s: SwaptionSpec =>
      swaptionPrice(
        s,
        market.combinedCurve(s.discountCurve, s.creditCurve),
        market.volSurface(s.volSurface),
        elapsedYears
      )
    case s: FxForwardSpec =>
      fxForwardPrice(s, market.curve(s.domesticCurve), market.curve(s.foreignCurve), market.fxSpot(s.fxSpot), elapsedYears)
  }

  /**
    * Cash carry over the holding period: the coupon a fixed-rate bond accrues in
    * `elapsedYears`. Stage 3 keeps this minimal — options and FX forwards have no
    * coupon accrual (so they are 0), and swap/mortgage accrual plus financing cost
    * are deferred. `market` is unused now but reserved for those later cases.
    */
  def carry(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double): Double = instrument match {
    case s: BondSpec => s.notional * s.coupon * elapsedYears
    case _           => 0.0
  }

  /** Fixed-rate bullet bond: sum of discounted coupons plus the final principal. */
  def bondPrice(spec: BondSpec, curve: Curve, elapsedYears: Double = 0.0): Double = {
    val periods = spec.maturityYears * spec.couponFreq
    val accrual = 1.0 / spec.couponFreq
    val couponCash = spec.notional * spec.coupon * accrual
    (1 to periods).map { i =>
      // Time-to-cashflow, shortened by the time already passed (theta aging).
      val t = math.max(0.0, i * accrual - elapsedYears)
      val cf = if (i == periods) couponCash + spec.notional else couponCash
      cf * curve.df(t)
    }.sum
  }

  def mortgagePrice(spec: MortgageSpec, curve: Curve, prepay: PrepayVector, elapsedYears: Double = 0.0): Double =
    amortisingPrice(spec.notional, spec.noteRate, spec.termMonths, prepay, curve, elapsedYears)

  def mbsPrice(spec: MbsPoolSpec, curve: Curve, prepay: PrepayVector, elapsedYears: Double = 0.0): Double =
    amortisingPrice(spec.notional, spec.wac, spec.wamMonths, prepay, curve, elapsedYears)

  /**
    * Level-payment amortising loan (mortgage/MBS): a constant annuity payment is
    * split into interest and scheduled principal each month; prepayment retires
    * additional principal. Cashflows are discounted on the supplied curve.
    */
  private def amortisingPrice(
    notional: Double,
    noteRate: Double,
    termMonths: Int,
    prepay: PrepayVector,
    curve: Curve,
    elapsedYears: Double = 0.0
  ): Double = {
    val noteRateMonthly = noteRate / 12.0
    // Annuity payment: P = N * r / (1 - (1+r)^-n), the constant monthly payment
    // that exactly repays the loan at the note rate.
    val payment =
      if (noteRateMonthly == 0.0) notional / termMonths
      else notional * noteRateMonthly / (1.0 - math.pow(1.0 + noteRateMonthly, -termMonths.toDouble))

    var balance = notional
    var pv = 0.0
    var month = 1
    while (month <= termMonths && balance > 0.0) {
      val interest = balance * noteRateMonthly
      val scheduledPrincipal = payment - interest
      // CPR -> SMM (Single Monthly Mortality): SMM = 1 - (1 - CPR)^(1/12).
      val cpr = clamp(prepay.cpr(month - 1), 0.0, 1.0) // 0..1 decimal, not %
      val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
      // Prepayment = SMM applied to the principal remaining after scheduled amortisation.
      val prepayment = (balance - scheduledPrincipal) * smm
      val totalPrincipal = scheduledPrincipal + prepayment
      val cashflow = interest + totalPrincipal
      pv += cashflow * curve.df(math.max(0.0, month / 12.0 - elapsedYears))
      balance -= totalPrincipal
      month += 1
    }
    pv
  }

  /** Interest-rate swap: fixed-leg PV minus floating-leg PV. */
  def swapPrice(spec: SwapSpec, curve: Curve, elapsedYears: Double = 0.0): Double = {
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val fixedCash = spec.notional * spec.fixedRate * accrual
    val fixedPV = (1 to periods).map { i => fixedCash * curve.df(math.max(0.0, i * accrual - elapsedYears)) }.sum
    val floatPV = spec.notional * (1.0 - curve.df(math.max(0.0, spec.maturityYears.toDouble - elapsedYears)))
    fixedPV - floatPV
  }

  /** Interest-rate cap: a strip of caplets priced with Black (Black-76). */
  def capPrice(spec: CapSpec, curve: Curve, volSurface: VolSurface, elapsedYears: Double = 0.0): Double = {
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val strike = spec.strike
    (1 to periods).map { i =>
      val t0 = math.max(0.0, (i - 1) * accrual - elapsedYears)
      val t1 = math.max(0.0, i * accrual - elapsedYears)
      val forward = forwardRate(curve, t0, t1, accrual)
      val vol = math.max(volSurface.volatility(t0, strike), 0.0)
      val black = blackCall(forward, strike, vol, math.max(t0, 1e-9))
      spec.notional * accrual * black * curve.df(t1)
    }.sum
  }

  /** Swaption: an option to enter a swap, priced with Black (Black-76). */
  def swaptionPrice(spec: SwaptionSpec, curve: Curve, volSurface: VolSurface, elapsedYears: Double = 0.0): Double = {
    val t = math.max(0.0, spec.expiryYears - elapsedYears)
    val vol = math.max(volSurface.volatility(t, spec.strike), 0.0)
    val accrual = 1.0 / spec.freq
    val start = t
    val periods = spec.swapMaturityYears * spec.freq

    // Annuity: sum of accrual * df over the swap's payment dates.
    val annuity = (1 to periods).map { i =>
      accrual * curve.df(start + i * accrual)
    }.sum

    // Forward swap rate: the fixed rate that makes the swap value zero.
    val forwardSwap = (curve.df(start) - curve.df(start + spec.swapMaturityYears.toDouble)) / annuity

    val payoff =
      if (spec.isPayer) blackCall(forwardSwap, spec.strike, vol, t)
      else blackPut(forwardSwap, spec.strike, vol, t)

    spec.notional * annuity * payoff
  }

  /**
    * FX forward, priced by covered interest parity:
    * `notional · (spot · dfForeign - contractedRate · dfDomestic)`.
    */
  def fxForwardPrice(spec: FxForwardSpec, domestic: Curve, foreign: Curve, fx: SpotQuote, elapsedYears: Double = 0.0): Double = {
    val t = math.max(0.0, spec.maturityYears - elapsedYears)
    val dfDomestic = domestic.df(t)
    val dfForeign = foreign.df(t)
    val spotFx = fx.value
    spec.notional * (spotFx * dfForeign - spec.fxRate * dfDomestic)
  }

  private def forwardRate(curve: Curve, t0: Double, t1: Double, accrual: Double): Double = {
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
