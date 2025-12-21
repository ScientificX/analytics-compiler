package com.writhlang.engine

import com.writhlang.dsl.{BondSpec, MortgageSpec}

sealed trait Op
case class Const(value: Double) extends Op
case class BondPrice(spec: BondSpec, rateShift: Double, spreadShift: Double) extends Op
case class MortgagePrice(spec: MortgageSpec, rateShift: Double, spreadShift: Double, prepayShift: Double) extends Op
case class Sensitivity(baseId: String, bumpedId: String, bump: Double) extends Op
case class ScenarioPrice(baseId: String, terms: List[(String, String)]) extends Op

case class Instruction(id: String, op: Op, deps: List[String])
