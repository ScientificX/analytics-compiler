package com.writhlang.engine

import com.writhlang.dsl.InstrumentSpec
import com.writhlang.marketdata.MarketData

sealed trait Op

/** Price an instrument under a market-data snapshot (leaf node). */
case class Price(instrument: InstrumentSpec, market: MarketData) extends Op

/** Literal shock value. Leaf node. */
case class Const(value: Double) extends Op

/** First-order sensitivity: (up - down) / (2 * bump). */
case class Delta(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Second-order sensitivity: (up - 2*base + down) / bump^2. */
case class Gamma(baseId: String, upId: String, downId: String, bump: Double) extends Op

/** Cross second-order sensitivity: (upIJ - upI - upJ + base) / (bumpI * bumpJ). */
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
