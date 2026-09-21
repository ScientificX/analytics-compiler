package com.writhlang.scenario

import com.writhlang.marketdata.MarketData
import com.writhlang.risk.{KeyType, RiskFactorKey}

/**
  * A shift applied to a single risk factor's market object. The object's shape is
  * implied by the factor's `KeyType` (curve vs vector vs surface vs spot).
  */
sealed trait ScenarioShift

/** Add `shape` to the factor's object (used for curves, prepay vectors, vol). */
final case class AdditiveShift(shape: ShiftShape) extends ScenarioShift

/** Multiply a spot by (1 + amount) — the FX convention. */
final case class RelativeSpotShift(amount: Double) extends ScenarioShift

/** Add `amount` to a spot (absolute move). */
final case class AbsoluteSpotShift(amount: Double) extends ScenarioShift

/**
  * A named transformation of a [[MarketData]] snapshot: a map from risk-factor
  * key to the shift to apply to that factor's object. Applying a scenario
  * re-derives every shifted object downstream, which is the core Stage 1
  * principle — shock the market *input*, then reprice.
  */
final case class Scenario(name: String, shifts: Map[RiskFactorKey, ScenarioShift]) {
  def applyTo(market: MarketData): MarketData =
    shifts.foldLeft(market) { case (current, (key, shift)) =>
      applyShift(current, key, shift)
    }

  private def applyShift(market: MarketData, key: RiskFactorKey, shift: ScenarioShift): MarketData =
    shift match {
      case AdditiveShift(shape) if isCurveKey(key.keyType) =>
        market.withCurve(key, market.curve(key).shifted(shape.at))
      case AdditiveShift(shape) if key.keyType == KeyType.Prepay =>
        market.withPrepayVector(key, market.prepayVector(key).shifted(shape.at))
      case AdditiveShift(shape) if isVolKey(key.keyType) =>
        market.withVolSurface(key, market.volSurface(key).shifted(shape.at))
      case RelativeSpotShift(amount) if isSpotKey(key.keyType) =>
        market.withFxSpot(key, market.fxSpot(key).shiftedRelative(amount))
      case AbsoluteSpotShift(amount) if isSpotKey(key.keyType) =>
        market.withFxSpot(key, market.fxSpot(key).shiftedAbsolute(amount))
      case _ =>
        // Unsupported combination (e.g. a correlation or security factor): leave
        // the object untouched. Correlation shocks are a future matrix concern.
        market
    }

  private def isCurveKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.DiscountCurve | KeyType.IndexCurve | KeyType.YieldCurve | KeyType.CreditCurve |
         KeyType.CommodityCurve | KeyType.InflationZero | KeyType.InflationYoY | KeyType.DividendYield => true
    case _ => false
  }

  private def isVolKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.SwaptionVolatility | KeyType.CapFloorVolatility | KeyType.FxVolatility |
         KeyType.EquityVolatility | KeyType.CommodityVolatility => true
    case _ => false
  }

  private def isSpotKey(keyType: KeyType): Boolean = keyType match {
    case KeyType.FxSpot | KeyType.EquitySpot => true
    case _ => false
  }
}
