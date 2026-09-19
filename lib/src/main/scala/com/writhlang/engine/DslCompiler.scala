package com.writhlang.engine

import com.writhlang.dsl._

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
      Factor.fromName(name).map { f =>
        val id = shockFactorId(shock.name, f.name)
        id -> Instruction(id, Const(value), Nil)
      }
    }

  private def buildInstrumentNodes(instrument: InstrumentSpec, shocks: List[Shock]): List[(String, Instruction)] = {
    val factors = factorsFor(instrument)

    val baseId = basePriceId(instrument.id)
    val base = Instruction(baseId, Price(instrument, Nil, Map.empty), Nil)

    // Per-factor up/down bumped prices and delta/gamma.
    val greekNodes = factors.flatMap { f =>
      val b = bumpFor(f)
      val (upCurve, upScalar) = perturb(f, b)
      val (downCurve, downScalar) = perturb(f, -b)
      val upId = bumpedPriceId(instrument.id, f.name, "up")
      val downId = bumpedPriceId(instrument.id, f.name, "down")
      val up = Instruction(upId, Price(instrument, upCurve, upScalar), Nil)
      val down = Instruction(downId, Price(instrument, downCurve, downScalar), Nil)
      val dId = deltaId(instrument.id, f.name)
      val gId = gammaId(instrument.id, f.name)
      val delta = Instruction(dId, Delta(baseId, upId, downId, b), List(baseId, upId, downId))
      val gamma = Instruction(gId, Gamma(baseId, upId, downId, b), List(baseId, upId, downId))
      List(upId -> up, downId -> down, dId -> delta, gId -> gamma)
    }

    // Cross gammas for each unordered pair.
    val crossNodes = factors.combinations(2).toList.flatMap {
      case List(f1, f2) =>
        val b1 = bumpFor(f1)
        val b2 = bumpFor(f2)
        val (c1, s1) = perturb(f1, b1)
        val (c2, s2) = perturb(f2, b2)
        val upIId = bumpedPriceId(instrument.id, f1.name, "up")
        val upJId = bumpedPriceId(instrument.id, f2.name, "up")
        val upIJId = crossBumpedPriceId(instrument.id, f1.name, f2.name)
        val upIJ = Instruction(upIJId, Price(instrument, c1 ++ c2, s1 ++ s2), Nil)
        val xId = crossGammaId(instrument.id, f1.name, f2.name)
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
    factors: List[Factor]
  ): List[(String, Instruction)] = {
    // Full reprice (exact).
    val (fullCurve, fullScalar) = shockMarket(shock)
    val fullId = fullScenarioId(instrument.id, shock.name)
    val full = Instruction(fullId, Price(instrument, fullCurve, fullScalar), Nil)

    // Scalar factors present in this shock (for Taylor terms).
    val present = factors.filter(f => shock.factors.contains(f.name))

    val linearTerms = present.map { f =>
      deltaId(instrument.id, f.name) -> shockFactorId(shock.name, f.name)
    }
    val gammaTerms = present.map { f =>
      gammaId(instrument.id, f.name) -> shockFactorId(shock.name, f.name)
    }
    val crossTerms = present.combinations(2).toList.map {
      case List(f1, f2) =>
        (crossGammaId(instrument.id, f1.name, f2.name),
          shockFactorId(shock.name, f1.name),
          shockFactorId(shock.name, f2.name))
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

  private def factorsFor(instrument: InstrumentSpec): List[Factor] = instrument match {
    case _: BondSpec => List(RateFactor, SpreadFactor)
    case _: MortgageSpec => List(RateFactor, SpreadFactor, PrepayFactor)
    case _: MbsPoolSpec => List(RateFactor, SpreadFactor, PrepayFactor)
    case _: SwapSpec => List(RateFactor, SpreadFactor)
    case _: CapSpec => List(RateFactor, SpreadFactor, VolatilityFactor)
    case _: SwaptionSpec => List(RateFactor, SpreadFactor, VolatilityFactor)
    case _: FxForwardSpec => List(RateFactor, FxFactor)
  }

  private def bumpFor(factor: Factor): Double = factor match {
    case RateFactor => rateBump
    case SpreadFactor => spreadBump
    case PrepayFactor => prepayBump
    case VolatilityFactor => volBump
    case FxFactor => fxBump
  }

  /** Translate a scalar factor perturbation into (curve shifts, scalar shifts). */
  private def perturb(factor: Factor, amount: Double): (List[CurveShift], Map[String, Double]) = factor match {
    case RateFactor | SpreadFactor => (List(FlatShift(amount)), Map.empty)
    case PrepayFactor => (Nil, Map("prepay" -> amount))
    case VolatilityFactor => (Nil, Map("volatility" -> amount))
    case FxFactor => (Nil, Map("fx" -> amount))
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

  def shockFactorId(shockName: String, factorName: String): String = s"shock:$shockName:$factorName"
  def basePriceId(instId: String): String = s"price:base:$instId"
  def bumpedPriceId(instId: String, factorName: String, dir: String): String = s"price:bump:$instId:$factorName:$dir"
  def crossBumpedPriceId(instId: String, f1: String, f2: String): String = s"price:cross:$instId:$f1:$f2"
  def deltaId(instId: String, factorName: String): String = s"greek:delta:$instId:$factorName"
  def gammaId(instId: String, factorName: String): String = s"greek:gamma:$instId:$factorName"
  def crossGammaId(instId: String, f1: String, f2: String): String = s"greek:cross:$instId:$f1:$f2"
  def linearScenarioId(instId: String, shockName: String): String = s"price:linear:$instId:$shockName"
  def quadraticScenarioId(instId: String, shockName: String): String = s"price:quad:$instId:$shockName"
  def fullScenarioId(instId: String, shockName: String): String = s"price:full:$instId:$shockName"

  /** Legacy alias: a scenario price now refers to the full re-priced value. */
  def scenarioPriceId(instId: String, shockName: String): String = fullScenarioId(instId, shockName)
}
