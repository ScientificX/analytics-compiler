package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.risk._
import org.apache.commons.math3.distribution.NormalDistribution

/**
  * Closed-form pricing for the seven DSL instruments, consuming market-data
  * OBJECTS (curve, vol surface, prepay vector, FX spot) rather than scalar
  * shock parameters. Shocks are applied upstream by transforming the
  * [[com.writhlang.marketdata.MarketData]] snapshot (see `scenario.Scenario`);
  * nothing here knows about a "shock" — it just prices what it is given.
  */
object Pricing {
  // Shared standard-normal CDF for Black pricing. (Known, pre-existing: this
  // singleton is not thread-safe; see plan.md risks. Left as-is for Stage 1.)
  private val normal = new NormalDistribution()

  /**
    * Unified pricing entry point used by the executor. Resolves the market-data
    * objects each instrument needs from the snapshot, keyed by the legacy DSL
    * factor keys (the Stage 0 compatibility boundary), then delegates to the
    * typed per-instrument functions.
    */
  def price(instrument: InstrumentSpec, market: MarketData): Double = instrument match {
    case s: BondSpec =>
      bondPrice(s, market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread))
    case s: MortgageSpec =>
      mortgagePrice(
        s,
        market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread),
        market.prepayVector(LegacyRiskFactors.prepay)
      )
    case s: MbsPoolSpec =>
      mbsPrice(
        s,
        market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread),
        market.prepayVector(LegacyRiskFactors.prepay)
      )
    case s: SwapSpec =>
      swapPrice(s, market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread))
    case s: CapSpec =>
      capPrice(
        s,
        market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread),
        market.volSurface(LegacyRiskFactors.volatility)
      )
    case s: SwaptionSpec =>
      swaptionPrice(
        s,
        market.combinedCurve(LegacyRiskFactors.rate, LegacyRiskFactors.spread),
        market.volSurface(LegacyRiskFactors.volatility)
      )
    case s: FxForwardSpec =>
      fxForwardPrice(s, market.curve(LegacyRiskFactors.rate), market.fxSpot(LegacyRiskFactors.fx))
  }

  /**
    * Fixed-rate bullet bond: sum of discounted coupons plus the final principal.
    * Each coupon is `notional * couponRate / freq`; the final cashflow adds the
    * notional. Discounted on the supplied (discount + spread) curve.
    */
  def bondPrice(spec: BondSpec, curve: Curve): Double = {
    val periods = spec.maturityYears * spec.couponFreq
    val accrual = 1.0 / spec.couponFreq
    val couponCash = spec.notional * spec.coupon * accrual
    (1 to periods).map { i =>
      val t = i * accrual
      val cf = if (i == periods) couponCash + spec.notional else couponCash
      cf * curve.df(t)
    }.sum
  }

  def mortgagePrice(spec: MortgageSpec, curve: Curve, prepay: PrepayVector): Double =
    amortisingPrice(spec.notional, spec.rate, spec.termMonths, prepay, curve)

  def mbsPrice(spec: MbsPoolSpec, curve: Curve, prepay: PrepayVector): Double =
    amortisingPrice(spec.notional, spec.wac, spec.wamMonths, prepay, curve)

  /**
    * Level-payment amortising loan (mortgage/MBS): a constant annuity payment is
    * split into interest and scheduled principal each month; prepayment (from the
    * prepay vector) retires additional principal. Cashflows are discounted on the
    * supplied curve.
    */
  private def amortisingPrice(
    notional: Double,
    noteRate: Double,
    termMonths: Int,
    prepay: PrepayVector,
    curve: Curve
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
      // CPR -> SMM (Single Monthly Mortality): SMM = 1 - (1 - CPR)^(1/12),
      // the monthly prepayment speed equivalent to the annualised CPR.
      val cpr = clamp(prepay.cpr(month - 1), 0.0, 1.0) // 0..1 decimal, not %
      val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
      // Prepayment = SMM applied to the principal remaining after scheduled amortisation.
      val prepayment = (balance - scheduledPrincipal) * smm
      val totalPrincipal = scheduledPrincipal + prepayment
      val cashflow = interest + totalPrincipal
      pv += cashflow * curve.df(month / 12.0)
      balance -= totalPrincipal
      month += 1
    }
    pv
  }

  /**
    * Interest-rate swap: fixed-leg PV minus floating-leg PV. The floating leg is
    * priced from the discount curve as N * (1 - df(T)).
    */
  def swapPrice(spec: SwapSpec, curve: Curve): Double = {
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val fixedCash = spec.notional * spec.fixedRate * accrual
    val fixedPV = (1 to periods).map { i => fixedCash * curve.df(i * accrual) }.sum
    val floatPV = spec.notional * (1.0 - curve.df(spec.maturityYears.toDouble))
    fixedPV - floatPV
  }

  /**
    * Interest-rate cap: a strip of caplets, each paying when the forward rate
    * exceeds the strike. Each caplet is priced with the Black (Black-76) model,
    * using the forward rate over the caplet's accrual period and the vol surface
    * at that caplet's start (expiry) time.
    */
  def capPrice(spec: CapSpec, curve: Curve, volSurface: VolSurface): Double = {
    val periods = spec.maturityYears * spec.freq
    val accrual = 1.0 / spec.freq
    val strike = spec.strike
    (1 to periods).map { i =>
      val t0 = (i - 1) * accrual
      val t1 = i * accrual
      val forward = forwardRate(curve, t0, t1, accrual)
      val vol = math.max(volSurface.volatility(t0, strike), 0.0)
      val black = blackCall(forward, strike, vol, math.max(t0, 1e-9))
      spec.notional * accrual * black * curve.df(t1)
    }.sum
  }

  /**
    * Swaption: an option to enter a swap. Priced with the Black (Black-76) model
    * on the forward swap rate; `call` = payer swaption, `put` = receiver swaption.
    */
  def swaptionPrice(spec: SwaptionSpec, curve: Curve, volSurface: VolSurface): Double = {
    val vol = math.max(volSurface.volatility(spec.expiryYears, spec.strike), 0.0)
    val t = spec.expiryYears
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
    * FX forward, priced by covered interest parity: value = notional *
    * (spot * dfForeign - contractedRate * dfDomestic). The spot is the (possibly
    * shocked) FX spot; the contracted rate and foreign rate are instrument terms.
    */
  def fxForwardPrice(spec: FxForwardSpec, domestic: Curve, fx: SpotQuote): Double = {
    val t = spec.maturityYears
    val dfDomestic = domestic.df(t)
    val dfForeign = math.exp(-spec.foreignRate * t)
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


