package com.writhlang.scenario

import com.writhlang.risk.RiskFactorKey

/**
  * A source of scenarios for revaluation. `next()` yields the next scenario and
  * `None` when a finite generator is exhausted; `reset()` rewinds to the start.
  *
  * Stage 1 ships the sensitivity and replay generators. Historical, CSV and
  * Monte Carlo generators arrive with the backtesting layer (Stage 7).
  */
trait ScenarioGenerator {
  def next(): Option[Scenario]
  def reset(): Unit
}

/**
  * Finite-difference sensitivity scenarios: for each factor, an up (+bump) and a
  * down (-bump) scenario, in factor order. `shiftFor` maps (factor, signed
  * amount) to the appropriate shift (e.g. FX is relative, rates are additive).
  */
final class SensitivityScenarioGenerator(
  factors: List[RiskFactorKey],
  bumpFor: RiskFactorKey => Double,
  shiftFor: (RiskFactorKey, Double) => ScenarioShift
) extends ScenarioGenerator {
  private val ordered: Vector[(RiskFactorKey, Double)] =
    factors.toVector.flatMap { factor =>
      val bump = bumpFor(factor)
      Vector(factor -> bump, factor -> -bump)
    }
  private var index = 0

  override def next(): Option[Scenario] = {
    if (index >= ordered.size) None
    else {
      val (factor, amount) = ordered(index)
      index += 1
      val direction = if (amount >= 0.0) "up" else "down"
      Some(Scenario(s"bump:${factor.canonical}:$direction", Map(factor -> List(shiftFor(factor, amount)))))
    }
  }

  override def reset(): Unit = { index = 0 }
}

/** Replays a fixed, in-memory list of scenarios. */
final class ListScenarioGenerator(scenarios: List[Scenario]) extends ScenarioGenerator {
  private var index = 0

  override def next(): Option[Scenario] = {
    if (index >= scenarios.size) None
    else {
      val scenario = scenarios(index)
      index += 1
      Some(scenario)
    }
  }

  override def reset(): Unit = { index = 0 }
}
