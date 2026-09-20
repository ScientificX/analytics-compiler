package com.writhlang.risk

/**
  * The taxonomy of market risk-factor types.
  *
  * Each `KeyType` is one coordinate of a [[RiskFactorKey]]. The `name` is the
  * canonical string used in the key's round-trippable form, e.g. `DiscountCurve`,
  * `CreditCurve`, `SwaptionVolatility`. It is kept identical to the case-object
  * name so the canonical string reads like the type.
  *
  * This is the Stage 0 skeleton: only the types actually reachable from the
  * legacy DSL compatibility layer (DiscountCurve, CreditCurve, Prepay,
  * SwaptionVolatility, FxSpot) are exercised by the engine today; the rest exist
  * so the taxonomy does not have to be re-opened for each new asset class.
  */
sealed trait KeyType { def name: String }

object KeyType {
  case object DiscountCurve extends KeyType { val name = "DiscountCurve" }
  case object IndexCurve extends KeyType { val name = "IndexCurve" }
  case object YieldCurve extends KeyType { val name = "YieldCurve" }
  case object CreditCurve extends KeyType { val name = "CreditCurve" }
  case object FxSpot extends KeyType { val name = "FxSpot" }
  case object FxVolatility extends KeyType { val name = "FxVolatility" }
  case object CapFloorVolatility extends KeyType { val name = "CapFloorVolatility" }
  case object SwaptionVolatility extends KeyType { val name = "SwaptionVolatility" }
  case object EquitySpot extends KeyType { val name = "EquitySpot" }
  case object EquityVolatility extends KeyType { val name = "EquityVolatility" }
  case object DividendYield extends KeyType { val name = "DividendYield" }
  case object InflationZero extends KeyType { val name = "InflationZero" }
  case object InflationYoY extends KeyType { val name = "InflationYoY" }
  case object CommodityCurve extends KeyType { val name = "CommodityCurve" }
  case object CommodityVolatility extends KeyType { val name = "CommodityVolatility" }
  case object Correlation extends KeyType { val name = "Correlation" }
  case object Prepay extends KeyType { val name = "Prepay" }
  case object Security extends KeyType { val name = "Security" }

  val all: List[KeyType] = List(
    DiscountCurve,
    IndexCurve,
    YieldCurve,
    CreditCurve,
    FxSpot,
    FxVolatility,
    CapFloorVolatility,
    SwaptionVolatility,
    EquitySpot,
    EquityVolatility,
    DividendYield,
    InflationZero,
    InflationYoY,
    CommodityCurve,
    CommodityVolatility,
    Correlation,
    Prepay,
    Security
  )

  def fromName(name: String): Option[KeyType] = all.find(_.name == name)
}
