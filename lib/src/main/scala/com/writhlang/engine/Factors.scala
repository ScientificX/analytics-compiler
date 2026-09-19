package com.writhlang.engine

sealed trait Factor { def name: String }
case object RateFactor extends Factor { val name = "rate" }
case object SpreadFactor extends Factor { val name = "spread" }
case object PrepayFactor extends Factor { val name = "prepay" }
case object VolatilityFactor extends Factor { val name = "volatility" }
case object FxFactor extends Factor { val name = "fx" }

object Factor {
  val all: List[Factor] = List(RateFactor, SpreadFactor, PrepayFactor, VolatilityFactor, FxFactor)

  def fromName(name: String): Option[Factor] = name match {
    case "rate" => Some(RateFactor)
    case "spread" => Some(SpreadFactor)
    case "prepay" => Some(PrepayFactor)
    case "volatility" => Some(VolatilityFactor)
    case "fx" => Some(FxFactor)
    case _ => None
  }
}
