package com.writhlang.engine

import com.writhlang.dsl.InstrumentSpec
import com.writhlang.marketdata.MarketData

sealed trait Op

/** Price an instrument under a market-data snapshot (leaf). */
case class Price(instrument: InstrumentSpec, market: MarketData) extends Op

/** Literal shock value (leaf). */
case class Const(value: Double) extends Op

/** Central-difference delta: (up - down) / (2·bump). */
case class DeltaCentral(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Forward-difference delta: (up - base) / bump. */
case class DeltaForward(baseId: String, upId: String, bump: Double) extends Op

/** Backward-difference delta: (base - down) / bump. */
case class DeltaBackward(baseId: String, downId: String, bump: Double) extends Op

/** Gamma (always central): (up - 2·base + down) / bump². */
case class Gamma(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Cross gamma (central, two factors): (upIJ - upI - upJ + base) / (bumpI·bumpJ). */
case class CrossGamma(
  baseId: String,
  upIId: String,
  upJId: String,
  upIJId: String,
  bumpI: Double,
  bumpJ: Double
) extends Op

/** First-order Taylor scenario: base + sum(delta_i * shock_i). */
case class LinearScenario(baseId: String, terms: List[(String, String)]) extends Op

/** Second-order Taylor scenario: linear + 1/2 sum(gamma_i s_i^2) + sum(cross_ij s_i s_j). */
case class QuadraticScenario(
  baseId: String,
  linear: List[(String, String)],
  gamma: List[(String, String)],
  cross: List[(String, String, String)]
) extends Op

case class Instruction(id: String, op: Op, deps: List[String])
