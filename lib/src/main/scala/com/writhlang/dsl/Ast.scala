package com.writhlang.dsl

sealed trait InstrumentSpec {
  def id: String
}

sealed trait PrepayCurve
case class FlatPrepay(cpr: Double) extends PrepayCurve
case class RampPrepay(startCpr: Double, endCpr: Double, rampMonths: Int) extends PrepayCurve

case class BondSpec(
  id: String,
  notional: Double,
  coupon: Double,
  maturityYears: Int,
  rate: Double,
  spread: Double,
  couponFreq: Int
) extends InstrumentSpec

case class MortgageSpec(
  id: String,
  notional: Double,
  rate: Double,
  spread: Double,
  termMonths: Int,
  prepayCurve: PrepayCurve
) extends InstrumentSpec

case class Shock(name: String, factors: Map[String, Double])

case class Program(shocks: List[Shock], instruments: List[InstrumentSpec])
