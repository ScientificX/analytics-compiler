package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.marketdata._
import com.writhlang.risk._
import com.writhlang.scenario._

/**
  * Translates a [[Program]] (portfolio tree + shocks) and a [[MarketData]]
  * snapshot into a computation [[Dag]].
  *
  * Sensitivities are per-pillar: for a quote-backed curve, each par instrument
  * becomes its own [[RiskFactorKey]] (`DiscountCurve:EUR:2Y`), and a bump is a
  * par-conversion — bump that quote and re-bootstrap the curve.
  */
object DslCompiler {

  def build(program: Program, market: MarketData, config: SensitivityConfig = SensitivityConfig()): Dag = {
    val instruments = flattenInstruments(program.nodes)
    val shockNodes = program.shocks.flatMap(buildShockNodes)
    val instrumentNodes = instruments.flatMap { case (instr, _) =>
      buildInstrumentNodes(instr, program.shocks, market, config)
    }
    Dag((shockNodes ++ instrumentNodes).toMap)
  }

  /** Flatten a recursive portfolio tree into `(instrument, portfolio path)`. */
  def flattenInstruments(nodes: List[PortfolioNode]): List[(InstrumentSpec, List[String])] = {
    def walk(node: PortfolioNode, path: List[String]): List[(InstrumentSpec, List[String])] = node match {
      case InstrumentLeaf(spec) => List((spec, path))
      case Portfolio(name, children) => children.flatMap(c => walk(c, path :+ name))
    }
    nodes.flatMap(walk(_, Nil))
  }

  /** A `Const` node per scalar (point) shock move. */
  private def buildShockNodes(shock: Shock): List[(String, Instruction)] =
    shock.moves.collect { case PointMove(key, _, amount) =>
      val id = shockFactorId(shock.name, key)
      id -> Instruction(id, Const(amount), Nil)
    }

  private def buildInstrumentNodes(
    instrument: InstrumentSpec,
    shocks: List[Shock],
    market: MarketData,
    config: SensitivityConfig
  ): List[(String, Instruction)] = {
    val factors = factorsFor(instrument, market)
    val baseId = basePriceId(instrument.id)
    val base = Instruction(baseId, Price(instrument, market), Nil)

    // Per-factor up/down bumped prices and delta/gamma.
    val greekNodes = factors.flatMap { key =>
      val b = config.bumpFor(key)
      val upMarket = bumpScenario(key, b).applyTo(market)
      val downMarket = bumpScenario(key, -b).applyTo(market)
      val upId = bumpedPriceId(instrument.id, key, "up")
      val downId = bumpedPriceId(instrument.id, key, "down")
      val up = Instruction(upId, Price(instrument, upMarket), Nil)
      val down = Instruction(downId, Price(instrument, downMarket), Nil)

      val dId = deltaId(instrument.id, key)
      val gId = gammaId(instrument.id, key)
      val (deltaOp, deltaDeps) = config.shiftScheme match {
        case ShiftScheme.Central  => (DeltaCentral(baseId, upId, downId, b), List(baseId, upId, downId))
        case ShiftScheme.Forward  => (DeltaForward(baseId, upId, b), List(baseId, upId))
        case ShiftScheme.Backward => (DeltaBackward(baseId, downId, b), List(baseId, downId))
      }
      val delta = Instruction(dId, deltaOp, deltaDeps)
      val gamma = Instruction(gId, Gamma(baseId, upId, downId, b), List(baseId, upId, downId))
      List(upId -> up, downId -> down, dId -> delta, gId -> gamma)
    }

    // Cross gammas for each unordered pair of factors (always central).
    val crossNodes = factors.combinations(2).toList.flatMap {
      case List(k1, k2) =>
        val b1 = config.bumpFor(k1)
        val b2 = config.bumpFor(k2)
        val upIId = bumpedPriceId(instrument.id, k1, "up")
        val upJId = bumpedPriceId(instrument.id, k2, "up")
        val upIJId = crossBumpedPriceId(instrument.id, k1, k2)
        val upIJMarket = bumpScenario(k2, b2).applyTo(bumpScenario(k1, b1).applyTo(market))
        val upIJ = Instruction(upIJId, Price(instrument, upIJMarket), Nil)
        val xId = crossGammaId(instrument.id, k1, k2)
        val cross = Instruction(xId, CrossGamma(baseId, upIId, upJId, upIJId, b1, b2), List(baseId, upIId, upJId, upIJId))
        List(upIJId -> upIJ, xId -> cross)
      case _ => Nil
    }

    val scenarioNodes = shocks.flatMap { shock =>
      buildScenarioNodes(instrument, shock, baseId, factors, market)
    }

    // Time dimension (Stage 3): theta (time decay) = aged revaluation − base, plus the
    // cash carry accrued over the same period. Only emitted when a theta horizon is set.
    val timeNodes = config.thetaPeriod match {
      case Some(period) =>
        val elapsedYears = period.elapsedYears(market.asOf, config.calendar, config.dayCount)
        val thetaEvalNodeId = thetaEvalPriceId(instrument.id)
        val thetaEval = Instruction(thetaEvalNodeId, Price(instrument, market, elapsedYears), Nil)
        val thetaNodeId = thetaId(instrument.id)
        val theta = Instruction(thetaNodeId, Theta(baseId, thetaEvalNodeId), List(baseId, thetaEvalNodeId))
        val carryNodeId = carryId(instrument.id)
        val carry = Instruction(carryNodeId, Carry(instrument, market, elapsedYears), Nil)
        List(thetaEvalNodeId -> thetaEval, thetaNodeId -> theta, carryNodeId -> carry)
      case None => Nil
    }

    (baseId -> base) :: greekNodes ++ crossNodes ++ scenarioNodes ++ timeNodes
  }

  private def buildScenarioNodes(
    instrument: InstrumentSpec,
    shock: Shock,
    baseId: String,
    factors: List[RiskFactorKey],
    market: MarketData
  ): List[(String, Instruction)] = {
    // Full reprice: apply the whole shock as a scenario, then reprice.
    val fullMarket = shockScenario(shock).applyTo(market)
    val fullId = fullScenarioId(instrument.id, shock.name)
    val full = Instruction(fullId, Price(instrument, fullMarket), Nil)

    // Taylor terms use only the scalar (point) moves present in this shock.
    val presentKeys = shock.moves.collect { case PointMove(key, _, _) => key }.filter(factors.contains)

    val linearTerms = presentKeys.map { key =>
      deltaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val gammaTerms = presentKeys.map { key =>
      gammaId(instrument.id, key) -> shockFactorId(shock.name, key)
    }
    val crossTerms = presentKeys.combinations(2).toList.map {
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

  /** The risk factors for an instrument: curve pillars + direct (prepay/vol/fx) keys. */
  def factorsFor(instrument: InstrumentSpec, market: MarketData): List[RiskFactorKey] =
    curveKeysFor(instrument).flatMap(k => pillarKeys(k, market)) ++ directKeysFor(instrument)

  private def curveKeysFor(instrument: InstrumentSpec): List[RiskFactorKey] = instrument match {
    case s: BondSpec      => List(s.discountCurve, s.creditCurve)
    case s: MortgageSpec  => List(s.discountCurve, s.creditCurve)
    case s: MbsPoolSpec   => List(s.discountCurve, s.creditCurve)
    case s: SwapSpec      => List(s.discountCurve, s.creditCurve)
    case s: CapSpec       => List(s.discountCurve, s.creditCurve)
    case s: SwaptionSpec  => List(s.discountCurve, s.creditCurve)
    case s: FxForwardSpec => List(s.domesticCurve, s.foreignCurve)
  }

  private def directKeysFor(instrument: InstrumentSpec): List[RiskFactorKey] = instrument match {
    case s: MortgageSpec  => List(s.prepayCurve)
    case s: MbsPoolSpec   => List(s.prepayCurve)
    case s: CapSpec       => List(s.volSurface)
    case s: SwaptionSpec  => List(s.volSurface)
    case s: FxForwardSpec => List(s.fxSpot)
    case _                => Nil
  }

  /** One key per par instrument for a quote-backed curve; the curve key otherwise. */
  private def pillarKeys(curveKey: RiskFactorKey, market: MarketData): List[RiskFactorKey] =
    market.curveQuotes.get(curveKey) match {
      case Some(quotes) => quotes.instruments.map(i => curveKey.copy(tenor = Some(Tenor(i.tenorYears))))
      case None         => List(curveKey)
    }

  /** The market-object key + shift for a single-factor perturbation. */
  private def scenarioShiftFor(key: RiskFactorKey, shiftType: ShiftType, amount: Double): (RiskFactorKey, ScenarioShift) =
    key.keyType match {
      case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve | KeyType.CreditCurve =>
        val curveKey = key.withoutTenor
        key.tenor match {
          case Some(t) => curveKey -> ParQuoteShift(t.years, amount)
          case None    => curveKey -> AdditiveShift(Flat(amount))
        }
      case KeyType.Prepay =>
        key -> AdditiveShift(Flat(amount))
      case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility |
           KeyType.FxVolatility | KeyType.EquityVolatility | KeyType.CommodityVolatility =>
        key -> AdditiveShift(Flat(amount))
      case KeyType.FxSpot | KeyType.EquitySpot =>
        key -> (if (shiftType == ShiftType.Relative) RelativeSpotShift(amount) else AbsoluteSpotShift(amount))
      case _ => key -> AdditiveShift(Flat(0.0))
    }

  /** A single-factor bump scenario (for up/down finite differences). */
  private def bumpScenario(key: RiskFactorKey, amount: Double): Scenario = {
    val (marketKey, shift) = scenarioShiftFor(key, ShiftType.defaultFor(key.keyType), amount)
    Scenario(s"bump:${key.canonical}:$amount", Map(marketKey -> List(shift)))
  }

  /** Translate a DSL curve shift into a scenario shift shape. */
  private def shapeToShiftShape(shift: CurveShift): ShiftShape = shift match {
    case NoCurveShift                    => Flat(0.0)
    case FlatShift(amount)               => Flat(amount)
    case BucketShift(tenorYears, amount) => Bucket(tenorYears, amount)
    case TwistShift(short, long, pivot)  => Twist(short, long, pivot)
    case SineShift(amp, omega, phase)    => Sine(amp, omega, phase)
  }

  /** Full market transformation for a shock (used for exact re-pricing). */
  private def shockScenario(shock: Shock): Scenario = {
    val grouped: Map[RiskFactorKey, List[ScenarioShift]] = shock.moves.map {
      case PointMove(key, shiftType, amount) => scenarioShiftFor(key, shiftType, amount)
      case ShapeMove(key, shape)             => key -> AdditiveShift(shapeToShiftShape(shape))
    }.groupMap(_._1)(_._2)
    Scenario(shock.name, grouped)
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
  def thetaEvalPriceId(instId: String): String = s"price:thetaeval:$instId"
  def thetaId(instId: String): String = s"price:theta:$instId"
  def carryId(instId: String): String = s"price:carry:$instId"
}

