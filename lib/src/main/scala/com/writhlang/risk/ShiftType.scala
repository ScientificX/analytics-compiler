package com.writhlang.risk

/**
  * How a signed shock amount is applied to a market object's value.
  *
  * - `Absolute` adds the amount in the object's own units (the standard for
  *   interest rates and prepayment speeds: a +1bp move is `+0.0001`).
  * - `Relative` multiplies the value by `1 + amount` (the standard for FX spots
  *   and volatilities: a +1% move is `amount = 0.01`).
  *
  * The two conventions exist because rates are *additively* meaningful (a 1bp
  * bump is the same size anywhere on the curve) whereas a spot/vol move is
  * naturally *proportional* (a 1% FX move is 1% of the current spot).
  */
sealed trait ShiftType { def name: String }

object ShiftType {
  case object Absolute extends ShiftType { val name = "absolute" }
  case object Relative extends ShiftType { val name = "relative" }

  val all: List[ShiftType] = List(Absolute, Relative)

  def fromName(name: String): Option[ShiftType] = all.find(_.name == name)

  /** The conventional default shift type for a given risk-factor kind. */
  def defaultFor(keyType: KeyType): ShiftType = keyType match {
    case KeyType.FxSpot | KeyType.EquitySpot |
         KeyType.FxVolatility | KeyType.EquityVolatility |
         KeyType.SwaptionVolatility | KeyType.CapFloorVolatility |
         KeyType.CommodityVolatility => Relative
    case _ => Absolute
  }
}
