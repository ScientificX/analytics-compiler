package com.writhlang.engine

import com.writhlang.dsl.{BondSpec, MortgageSpec, Program, Shock}

object DslCompiler {
  private val rateBump = 0.0001
  private val spreadBump = 0.0001
  private val prepayBump = 0.01

  def build(program: Program): Dag = {
    val shockNodes = program.shocks.flatMap(buildShockNodes)

    val instrumentNodes = program.instruments.flatMap {
      case bond: BondSpec => buildBondNodes(bond, program.shocks)
      case mortgage: MortgageSpec => buildMortgageNodes(mortgage, program.shocks)
    }

    Dag((shockNodes ++ instrumentNodes).toMap)
  }

  private def buildShockNodes(shock: Shock): List[(String, Instruction)] = {
    shock.factors.toList.flatMap { case (name, value) =>
      Factor.fromName(name).map { factor =>
        val id = shockFactorId(shock, factor)
        id -> Instruction(id, Const(value), Nil)
      }
    }
  }

  private def buildBondNodes(spec: BondSpec, shocks: List[Shock]): List[(String, Instruction)] = {
    val baseId = basePriceId(spec.id)

    val base = Instruction(baseId, BondPrice(spec, 0.0, 0.0), Nil)

    val bumpedRateId = bumpedPriceId(spec.id, RateFactor)
    val bumpedSpreadId = bumpedPriceId(spec.id, SpreadFactor)

    val bumpedRate = Instruction(bumpedRateId, BondPrice(spec, rateBump, 0.0), Nil)
    val bumpedSpread = Instruction(bumpedSpreadId, BondPrice(spec, 0.0, spreadBump), Nil)

    val sensRateId = sensitivityId(spec.id, RateFactor)
    val sensSpreadId = sensitivityId(spec.id, SpreadFactor)

    val sensRate = Instruction(
      sensRateId,
      Sensitivity(baseId, bumpedRateId, rateBump),
      List(baseId, bumpedRateId)
    )
    val sensSpread = Instruction(
      sensSpreadId,
      Sensitivity(baseId, bumpedSpreadId, spreadBump),
      List(baseId, bumpedSpreadId)
    )

    val scenarioNodes = shocks.map { shock =>
      val terms = shockTerms(spec.id, shock, List(RateFactor, SpreadFactor))
      val scenarioId = scenarioPriceId(spec.id, shock.name)
      val deps = baseId :: terms.flatMap { case (sensId, shockId) => List(sensId, shockId) }
      scenarioId -> Instruction(scenarioId, ScenarioPrice(baseId, terms), deps.distinct)
    }

    List(
      baseId -> base,
      bumpedRateId -> bumpedRate,
      bumpedSpreadId -> bumpedSpread,
      sensRateId -> sensRate,
      sensSpreadId -> sensSpread
    ) ++ scenarioNodes
  }

  private def buildMortgageNodes(spec: MortgageSpec, shocks: List[Shock]): List[(String, Instruction)] = {
    val baseId = basePriceId(spec.id)

    val base = Instruction(baseId, MortgagePrice(spec, 0.0, 0.0, 0.0), Nil)

    val bumpedRateId = bumpedPriceId(spec.id, RateFactor)
    val bumpedSpreadId = bumpedPriceId(spec.id, SpreadFactor)
    val bumpedPrepayId = bumpedPriceId(spec.id, PrepayFactor)

    val bumpedRate = Instruction(bumpedRateId, MortgagePrice(spec, rateBump, 0.0, 0.0), Nil)
    val bumpedSpread = Instruction(bumpedSpreadId, MortgagePrice(spec, 0.0, spreadBump, 0.0), Nil)
    val bumpedPrepay = Instruction(bumpedPrepayId, MortgagePrice(spec, 0.0, 0.0, prepayBump), Nil)

    val sensRateId = sensitivityId(spec.id, RateFactor)
    val sensSpreadId = sensitivityId(spec.id, SpreadFactor)
    val sensPrepayId = sensitivityId(spec.id, PrepayFactor)

    val sensRate = Instruction(
      sensRateId,
      Sensitivity(baseId, bumpedRateId, rateBump),
      List(baseId, bumpedRateId)
    )
    val sensSpread = Instruction(
      sensSpreadId,
      Sensitivity(baseId, bumpedSpreadId, spreadBump),
      List(baseId, bumpedSpreadId)
    )
    val sensPrepay = Instruction(
      sensPrepayId,
      Sensitivity(baseId, bumpedPrepayId, prepayBump),
      List(baseId, bumpedPrepayId)
    )

    val scenarioNodes = shocks.map { shock =>
      val terms = shockTerms(spec.id, shock, List(RateFactor, SpreadFactor, PrepayFactor))
      val scenarioId = scenarioPriceId(spec.id, shock.name)
      val deps = baseId :: terms.flatMap { case (sensId, shockId) => List(sensId, shockId) }
      scenarioId -> Instruction(scenarioId, ScenarioPrice(baseId, terms), deps.distinct)
    }

    List(
      baseId -> base,
      bumpedRateId -> bumpedRate,
      bumpedSpreadId -> bumpedSpread,
      bumpedPrepayId -> bumpedPrepay,
      sensRateId -> sensRate,
      sensSpreadId -> sensSpread,
      sensPrepayId -> sensPrepay
    ) ++ scenarioNodes
  }

  private def shockTerms(instId: String, shock: Shock, factors: List[Factor]): List[(String, String)] = {
    factors.flatMap { factor =>
      shock.factors.get(factor.name).map { _ =>
        val sensId = sensitivityId(instId, factor)
        val shockId = shockFactorId(shock, factor)
        sensId -> shockId
      }
    }
  }

  def shockFactorId(shock: Shock, factor: Factor): String = s"shock:${shock.name}:${factor.name}"
  def basePriceId(instId: String): String = s"price:base:${instId}"
  def bumpedPriceId(instId: String, factor: Factor): String = s"price:bump:${instId}:${factor.name}"
  def sensitivityId(instId: String, factor: Factor): String = s"sensitivity:${instId}:${factor.name}"
  def scenarioPriceId(instId: String, shockName: String): String = s"price:${instId}:${shockName}"
}
