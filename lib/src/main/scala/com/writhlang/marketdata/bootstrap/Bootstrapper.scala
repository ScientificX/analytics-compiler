package com.writhlang.marketdata.bootstrap

import com.writhlang.marketdata.{BootstrappedCurve, DiscountCurveInterpolator}

/**
  * Sequential bootstrapping: turn a set of quoted instruments into a discount
  * curve. "Sequential" means the instruments are solved in ascending maturity
  * order — each step has exactly one unknown discount factor, because the
  * shorter-maturity factors it depends on were already solved.
  *
  * Steps:
  *   - a deposit pins `df(T) = e^(-rate · T)` directly;
  *   - a future/FRA pins `df(end)` from the (interpolated) `df(start)`;
  *   - a swap pins `df(T)` from its par equation.
  *
  * Returns `Left` with a message if a step yields a non-positive or
  * non-decreasing discount factor (an arbitrage / bad-quote condition).
  */
object Bootstrapper {

  def bootstrap(quotes: QuoteSet): Either[String, BootstrappedCurve] = {
    val sorted = quotes.sorted
    if (sorted.isEmpty) Left("cannot bootstrap an empty quote set")
    else {
      sorted.foldLeft[Either[String, Vector[(Double, Double)]]](Right(Vector.empty)) {
        case (acc, instrument) =>
          acc.flatMap(pillars => pillarFor(instrument, pillars).map(pillars :+ _))
      }.map(BootstrappedCurve(_))
    }
  }

  /** Throw on failure — used in the scenario hot path where quotes are known-valid. */
  def bootstrapOrThrow(quotes: QuoteSet): BootstrappedCurve =
    bootstrap(quotes).fold(err => throw new IllegalStateException(s"bootstrap failed: $err"), identity)

  private def pillarFor(instrument: ParInstrument, pillars: Vector[(Double, Double)]): Either[String, (Double, Double)] = {
    val maturity = instrument.maturityYears
    val df = instrument match {
      case d: Deposit =>
        // Continuous compounding: df(T) = e^(-rate·T).
        math.exp(-d.rate * d.tenor.years)
      case f: Future =>
        // Forward rate r over [start, end]: df(end) = df(start)·e^(-r·(end-start)).
        val dfStart = DiscountCurveInterpolator.df(pillars, f.start.years)
        dfStart * math.exp(-f.rate * (f.end.years - f.start.years))
      case s: Swap =>
        swapDiscountFactor(s.rate, s.tenor.years, s.freq, pillars)
    }

    if (df.isNaN || df.isInfinity || df <= 0.0)
      Left(s"non-positive discount factor $df at tenor $maturity")
    else pillars.lastOption match {
      case Some((prevTenor, prevDf)) if df >= prevDf =>
        Left(s"discount factor not decreasing: df($maturity)=$df >= df($prevTenor)=$prevDf")
      case _ => Right(maturity -> df)
    }
  }

  /**
    * Solve for the discount factor at a par swap's maturity.
    *
    * A par swap (notional 1, fixed rate `K`, `freq` payments/year, `N` periods
    * to maturity `T`) satisfies: fixed-leg PV = floating-leg PV. The floating
    * leg is `1 - df(T)`, giving
    *   `K·accrual·Σ_{i=1..N} df(tᵢ) = 1 - df(T)`.
    * The last term in the sum is `df(t_N) = df(T)` itself, so isolating it:
    *   `df(T) = (1 - K·accrual·Σ_{i=1..N-1} df(tᵢ)) / (1 + K·accrual)`.
    * Intermediate `df(tᵢ)` are interpolated from the already-solved pillars.
    */
  private def swapDiscountFactor(fixedRate: Double, maturity: Double, freq: Int, pillars: Vector[(Double, Double)]): Double = {
    val accrual = 1.0 / freq.toDouble
    val periods = math.max(1, (maturity * freq).round.toInt)
    val knownSum = (1 until periods).map { i =>
      accrual * DiscountCurveInterpolator.df(pillars, i * accrual)
    }.sum
    (1.0 - fixedRate * knownSum) / (1.0 + fixedRate * accrual)
  }
}
