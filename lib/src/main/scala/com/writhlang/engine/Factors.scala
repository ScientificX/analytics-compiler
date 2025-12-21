package com.writhlang.engine

sealed trait Factor { def name: String }
case object RateFactor extends Factor { val name = "rate" }
case object SpreadFactor extends Factor { val name = "spread" }
case object PrepayFactor extends Factor { val name = "prepay" }

object Factor {
  val all: List[Factor] = List(RateFactor, SpreadFactor, PrepayFactor)

  def fromName(name: String): Option[Factor] = name match {
    case "rate" => Some(RateFactor)
    case "spread" => Some(SpreadFactor)
    case "prepay" => Some(PrepayFactor)
    case _ => None
  }
}
