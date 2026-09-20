package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.risk._

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

  private def buildInstrumentNodes(instrument: InstrumentSpec, shocks: List[Shock]): List[(String, Instruction)] = {
    val factors = factorsFor(instrument)

    val baseId = basePriceId(instrument.id)
    val base = Instruction(baseId, Price(instrument, Nil, Map.empty), Nil)

    // Per-factor up/down bumped prices and delta/gamma.
    val greekNodes = factors.flatMap { key =>
      val b = bumpFor(key)
      val (upCurve, upScalar) = perturb(key, b)
      val (downCurve, downScalar) = perturb(key, -b)
      val upId = bumpedPriceId(instrument.id, key, "up")
      val downId = bumpedPriceId(instrument.id, key, "down")
      val up = Instruction(upId, Price(instrument, upCurve, upScalar), Nil)
      val down = Instruction(downId, Price(instrument, downCurve, downScalar), Nil)
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
        val (c1, s1) = perturb(k1, b1)
        val (c2, s2) = perturb(k2, b2)
        val upIId = bumpedPriceId(instrument.id, k1, "up")
        val upJId = bumpedPriceId(instrument.id, k2, "up")
        val upIJId = crossBumpedPriceId(instrument.id, k1, k2)
        val upIJ = Instruction(upIJId, Price(instrument, c1 ++ c2, s1 ++ s2), Nil)
        val xId = crossGammaId(instrument.id, k1, k2)
        val cross = Instruction(xId, CrossGamma(baseId, upIId, upJId, upIJId, b1, b2), List(baseId, upIId, upJId, upIJId))
        List(upIJId -> upIJ, xId -> cross)
      case _ => Nil
    }

    val scenarioNodes = shocks.flatMap { shock =>
      buildScenarioNodes(instrument, shock, baseId, factors)
    }

    (baseId -> base) :: greekNodes ++ crossNodes ++ scenarioNodes
  }

  private def buildScenarioNodes(
    instrument: InstrumentSpec,
    shock: Shock,
    baseId: String,
    factors: List[RiskFactorKey]
  ): List[(String, Instruction)] = {
    // Full reprice (exact).
    val (fullCurve, fullScalar) = shockMarket(shock)
    val fullId = fullScenarioId(instrument.id, shock.name)
    val full = Instruction(fullId, Price(instrument, fullCurve, fullScalar), Nil)

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

  /** Translate a scalar factor perturbation into (curve shifts, scalar shifts). */
  private def perturb(key: RiskFactorKey, amount: Double): (List[CurveShift], Map[String, Double]) = key.keyType match {
    case KeyType.DiscountCurve | KeyType.CreditCurve => (List(FlatShift(amount)), Map.empty)
    case KeyType.Prepay => (Nil, Map("prepay" -> amount))
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility => (Nil, Map("volatility" -> amount))
    case KeyType.FxSpot => (Nil, Map("fx" -> amount))
    case _ => (Nil, Map.empty)
  }

  /** Full market state for a shock (used for exact re-pricing). */
  private def shockMarket(shock: Shock): (List[CurveShift], Map[String, Double]) = {
    val flat = List(
      shock.factors.get("rate").map(FlatShift(_)),
      shock.factors.get("spread").map(FlatShift(_))
    ).flatten
    val scalar = List("prepay", "volatility", "fx").flatMap { k =>
      shock.factors.get(k).map(v => k -> v)
    }.toMap
    (flat ++ shock.curve, scalar)
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
