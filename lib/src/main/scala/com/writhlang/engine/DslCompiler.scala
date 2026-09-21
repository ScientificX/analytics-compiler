package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.risk._
import com.writhlang.scenario._

object DslCompiler {
  private val rateBump = 0.0001
  private val spreadBump = 0.0001
  private val prepayBump = 0.01
  private val volBump = 0.001
  private val fxBump = 0.001

  def build(program: Program): Dag = {
    val shockNodes = program.shocks.flatMap(buildShockNodes)
    val instrumentNodes = program.instruments.flatMap(buildInstrumentNodes(_, program.shocks))
    Dag((shockNodes ++ instrumentNodes).toMap)
  }

  private def buildShockNodes(shock: Shock): List[(String, Instruction)] =
    shock.factors.toList.flatMap { case (name, value) =>
      LegacyRiskFactors.fromWord(name).map { key =>
        val id = shockFactorId(shock.name, key)
        id -> Instruction(id, Const(value), Nil)
      }
    }

  /**
    * Build the unshocked market-data snapshot for an instrument from its embedded
    * DSL fields. This is the legacy-DSL compatibility layer: the DSL keeps market
    * inputs on the instrument spec, and we materialise them into keyed objects.
    */
  def baseMarketData(instrument: InstrumentSpec): MarketData = instrument match {
    case s: BondSpec =>
      MarketData(curves = Map(
        LegacyRiskFactors.rate -> Curve.flat(s.rate),
        LegacyRiskFactors.spread -> Curve.flat(s.spread)
      ))
    case s: MortgageSpec =>
      MarketData(
        curves = Map(
          LegacyRiskFactors.rate -> Curve.flat(s.rate),
          LegacyRiskFactors.spread -> Curve.flat(s.spread)
        ),
        prepayVectors = Map(
          LegacyRiskFactors.prepay -> prepayVector(s.prepayCurve, s.termMonths)
        )
      )
    case s: MbsPoolSpec =>
      MarketData(
        curves = Map(
          LegacyRiskFactors.rate -> Curve.flat(s.rate),
          LegacyRiskFactors.spread -> Curve.flat(s.spread)
        ),
        prepayVectors = Map(
          LegacyRiskFactors.prepay -> prepayVector(s.prepayCurve, s.wamMonths)
        )
      )
    case s: SwapSpec =>
      MarketData(curves = Map(
        LegacyRiskFactors.rate -> Curve.flat(s.rate),
        LegacyRiskFactors.spread -> Curve.flat(s.spread)
      ))
    case s: CapSpec =>
      MarketData(
        curves = Map(
          LegacyRiskFactors.rate -> Curve.flat(s.rate),
          LegacyRiskFactors.spread -> Curve.flat(s.spread)
        ),
        volSurfaces = Map(
          LegacyRiskFactors.volatility -> VolSurface.flat(s.volatility)
        )
      )
    case s: SwaptionSpec =>
      MarketData(
        curves = Map(
          LegacyRiskFactors.rate -> Curve.flat(s.rate),
          LegacyRiskFactors.spread -> Curve.flat(s.spread)
        ),
        volSurfaces = Map(
          LegacyRiskFactors.volatility -> VolSurface.flat(s.volatility)
        )
      )
    case s: FxForwardSpec =>
      MarketData(
        curves = Map(LegacyRiskFactors.rate -> Curve.flat(s.domesticRate)),
        fxSpots = Map(LegacyRiskFactors.fx -> SpotQuote(s.fxRate))
      )
  }

  private def prepayVector(curve: PrepayCurve, months: Int): PrepayVector = curve match {
    case FlatPrepay(cpr) => PrepayVector.constant(cpr, months)
    case RampPrepay(start, end, rampMonths) => PrepayVector.fromRamp(start, end, rampMonths, months)
  }

  private def buildInstrumentNodes(instrument: InstrumentSpec, shocks: List[Shock]): List[(String, Instruction)] = {
    val factors = factorsFor(instrument)
    val baseMarket = baseMarketData(instrument)

    val baseId = basePriceId(instrument.id)
    val base = Instruction(baseId, Price(instrument, baseMarket), Nil)

    // Per-factor up/down bumped prices and delta/gamma.
    val greekNodes = factors.flatMap { key =>
      val b = bumpFor(key)
      val upMarket = bumpScenario(key, b).applyTo(baseMarket)
      val downMarket = bumpScenario(key, -b).applyTo(baseMarket)
      val upId = bumpedPriceId(instrument.id, key, "up")
      val downId = bumpedPriceId(instrument.id, key, "down")
      val up = Instruction(upId, Price(instrument, upMarket), Nil)
      val down = Instruction(downId, Price(instrument, downMarket), Nil)
      val dId = deltaId(instrument.id, key)
      val gId = gammaId(instrument.id, key)
      val delta = Instruction(dId, Delta(baseId, upId, downId, b), List(baseId, upId, downId))
      val gamma = Instruction(gId, Gamma(baseId, upId, downId, b), List(baseId, upId, downId))
      List(upId -> up, downId -> down, dId -> delta, gId -> gamma)
    }

    // Cross gammas for each unordered pair.
    val crossNodes = factors.combinations(2).toList.flatMap {
      case List(k1, k2) =>
        val b1 = bumpFor(k1)
        val b2 = bumpFor(k2)
        val upIId = bumpedPriceId(instrument.id, k1, "up")
        val upJId = bumpedPriceId(instrument.id, k2, "up")
        val upIJId = crossBumpedPriceId(instrument.id, k1, k2)
        val upIJMarket = bumpScenario(k2, b2).applyTo(bumpScenario(k1, b1).applyTo(baseMarket))
        val upIJ = Instruction(upIJId, Price(instrument, upIJMarket), Nil)
        val xId = crossGammaId(instrument.id, k1, k2)
        val cross = Instruction(xId, CrossGamma(baseId, upIId, upJId, upIJId, b1, b2), List(baseId, upIId, upJId, upIJId))
        List(upIJId -> upIJ, xId -> cross)
      case _ => Nil
    }

    val scenarioNodes = shocks.flatMap { shock =>
      buildScenarioNodes(instrument, shock, baseId, factors, baseMarket)
    }

    (baseId -> base) :: greekNodes ++ crossNodes ++ scenarioNodes
  }

  private def buildScenarioNodes(
    instrument: InstrumentSpec,
    shock: Shock,
    baseId: String,
    factors: List[RiskFactorKey],
    baseMarket: MarketData
  ): List[(String, Instruction)] = {
    // Full reprice (exact): apply the whole shock as a scenario, then reprice.
    val fullMarket = shockScenario(shock).applyTo(baseMarket)
    val fullId = fullScenarioId(instrument.id, shock.name)
    val full = Instruction(fullId, Price(instrument, fullMarket), Nil)

    // Risk-factor keys present in this shock (for Taylor terms). The shock's
    // scalar moves are still stored under the legacy DSL words, so resolve each
    // key back to its word and check membership.
    val present = factors.filter(key => LegacyRiskFactors.wordFor(key).exists(shock.factors.contains))

    val linearTerms = present.map { key =>
      deltaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val gammaTerms = present.map { key =>
      gammaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val crossTerms = present.combinations(2).toList.map {
      case List(k1, k2) =>
        (crossGammaId(instrument.id, k1, k2),
          shockFactorId(shock.name, k1),
          shockFactorId(shock.name, k2))
      case _ => ("", "", "")
    }

    val linId = linearScenarioId(instrument.id, shock.name)
    val linDeps = baseId :: linearTerms.flatMap(t => List(t._1, t._2))
    val lin = Instruction(linId, LinearScenario(baseId, linearTerms), linDeps.distinct)

    val quadId = quadraticScenarioId(instrument.id, shock.name)
    val quadDeps = baseId ::
      linearTerms.flatMap(t => List(t._1, t._2)) ++
      gammaTerms.flatMap(t => List(t._1, t._2)) ++
      crossTerms.flatMap(t => List(t._1, t._2, t._3))
    val quad = Instruction(quadId, QuadraticScenario(baseId, linearTerms, gammaTerms, crossTerms), quadDeps.distinct)

    List(fullId -> full, linId -> lin, quadId -> quad)
  }
  private def factorsFor(instrument: InstrumentSpec): List[RiskFactorKey] = instrument match {
    case _: BondSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread)
    case _: MortgageSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread, LegacyRiskFactors.prepay)
    case _: MbsPoolSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread, LegacyRiskFactors.prepay)
    case _: SwapSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread)
    case _: CapSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread, LegacyRiskFactors.volatility)
    case _: SwaptionSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.spread, LegacyRiskFactors.volatility)
    case _: FxForwardSpec => List(LegacyRiskFactors.rate, LegacyRiskFactors.fx)
  }

  private def bumpFor(key: RiskFactorKey): Double = key.keyType match {
    case KeyType.DiscountCurve => rateBump
    case KeyType.CreditCurve => spreadBump
    case KeyType.Prepay => prepayBump
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility => volBump
    case KeyType.FxSpot => fxBump
    case _ => 0.0
  }

  /** The scenario shift corresponding to a single-factor perturbation. */
  private def scenarioShiftFor(key: RiskFactorKey, amount: Double): ScenarioShift = key.keyType match {
    case KeyType.DiscountCurve | KeyType.CreditCurve => AdditiveShift(Flat(amount))
    case KeyType.Prepay => AdditiveShift(Flat(amount))
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility => AdditiveShift(Flat(amount))
    case KeyType.FxSpot => RelativeSpotShift(amount)
    case _ => AdditiveShift(Flat(0.0))
  }

  /** A single-factor bump scenario (used for up/down finite differences). */
  private def bumpScenario(key: RiskFactorKey, amount: Double): Scenario =
    Scenario(s"bump:${key.canonical}:$amount", Map(key -> scenarioShiftFor(key, amount)))

  /** Translate a DSL curve shift into a scenario shift shape. */
  private def curveShape(shift: CurveShift): Option[ShiftShape] = shift match {
    case NoCurveShift => None
    case FlatShift(amount) => Some(Flat(amount))
    case BucketShift(tenorYears, amount) => Some(Bucket(tenorYears, amount))
    case TwistShift(shortAmount, longAmount, pivotYears) => Some(Twist(shortAmount, longAmount, pivotYears))
  }

  private def sumShapes(shapes: List[ShiftShape]): ShiftShape = shapes match {
    case Nil => Flat(0.0)
    case single :: Nil => single
    case multiple => Custom(x => multiple.map(_.at(x)).sum)
  }

  /** Full market transformation for a shock (used for exact re-pricing). */
  private def shockScenario(shock: Shock): Scenario = {
    // Rate risk: the discount curve gets the scalar `rate` move plus any curve
    // shifts (parallel/bucket/twist), combined additively on the same key.
    val rateShapes: List[ShiftShape] =
      shock.factors.get("rate").map(v => Flat(v): ShiftShape).toList ++
        shock.curve.flatMap(curveShape)
    val rateShift: List[(RiskFactorKey, ScenarioShift)] =
      if (rateShapes.isEmpty) Nil
      else List(LegacyRiskFactors.rate -> AdditiveShift(sumShapes(rateShapes)))

    val spreadShift: List[(RiskFactorKey, ScenarioShift)] =
      shock.factors.get("spread").map(v => LegacyRiskFactors.spread -> AdditiveShift(Flat(v))).toList
    val prepayShift: List[(RiskFactorKey, ScenarioShift)] =
      shock.factors.get("prepay").map(v => LegacyRiskFactors.prepay -> AdditiveShift(Flat(v))).toList
    val volShift: List[(RiskFactorKey, ScenarioShift)] =
      shock.factors.get("volatility").map(v => LegacyRiskFactors.volatility -> AdditiveShift(Flat(v))).toList
    val fxShift: List[(RiskFactorKey, ScenarioShift)] =
      shock.factors.get("fx").map(v => LegacyRiskFactors.fx -> RelativeSpotShift(v)).toList

    Scenario(shock.name, (rateShift ++ spreadShift ++ prepayShift ++ volShift ++ fxShift).toMap)
  }

  // --- id helpers ---

  def shockFactorId(shockName: String, key: RiskFactorKey): String = s"shock:$shockName:${key.canonical}"
  def basePriceId(instId: String): String = s"price:base:$instId"
  def bumpedPriceId(instId: String, key: RiskFactorKey, dir: String): String = s"price:bump:$instId:${key.canonical}:$dir"
  def crossBumpedPriceId(instId: String, k1: RiskFactorKey, k2: RiskFactorKey): String = s"price:cross:$instId:${k1.canonical}:${k2.canonical}"
  def deltaId(instId: String, key: RiskFactorKey): String = s"greek:delta:$instId:${key.canonical}"
  def gammaId(instId: String, key: RiskFactorKey): String = s"greek:gamma:$instId:${key.canonical}"
  def crossGammaId(instId: String, k1: RiskFactorKey, k2: RiskFactorKey): String = s"greek:cross:$instId:${k1.canonical}:${k2.canonical}"
  def linearScenarioId(instId: String, shockName: String): String = s"price:linear:$instId:$shockName"
  def quadraticScenarioId(instId: String, shockName: String): String = s"price:quad:$instId:$shockName"
  def fullScenarioId(instId: String, shockName: String): String = s"price:full:$instId:$shockName"

  /** Legacy alias: a scenario price now refers to the full re-priced value. */
  def scenarioPriceId(instId: String, shockName: String): String = fullScenarioId(instId, shockName)
}


