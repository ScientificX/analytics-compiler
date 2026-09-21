package com.writhlang.marketdata

import com.writhlang.risk.RiskFactorKey

/**
  * A market-data snapshot: the set of market objects a pricing function needs,
  * each addressed by a [[RiskFactorKey]]. The key's `KeyType` determines which
  * collection holds the object (curve vs surface vs vector vs spot vs matrix).
  *
  * This is the "market input object" that Stage 1 introduces. A scenario is a
  * transformation of this snapshot (see `com.writhlang.scenario.Scenario`); the
  * snapshot itself is immutable and updated via the `with*` copy methods.
  */
final case class MarketData(
  curves: Map[RiskFactorKey, Curve] = Map.empty,
  volSurfaces: Map[RiskFactorKey, VolSurface] = Map.empty,
  prepayVectors: Map[RiskFactorKey, PrepayVector] = Map.empty,
  fxSpots: Map[RiskFactorKey, SpotQuote] = Map.empty,
  correlations: Map[RiskFactorKey, CorrelationMatrix] = Map.empty
) {
  def curve(key: RiskFactorKey): Curve = curves(key)
  def volSurface(key: RiskFactorKey): VolSurface = volSurfaces(key)
  def prepayVector(key: RiskFactorKey): PrepayVector = prepayVectors(key)
  def fxSpot(key: RiskFactorKey): SpotQuote = fxSpots(key)
  def correlation(key: RiskFactorKey): CorrelationMatrix = correlations(key)

  /**
    * The effective discount curve for credit-risky instruments: the risk-free
    * discount curve plus the credit spread curve, added pointwise.
    */
  def combinedCurve(discountKey: RiskFactorKey, creditKey: RiskFactorKey): Curve =
    curve(discountKey).plus(curve(creditKey))

  def withCurve(key: RiskFactorKey, value: Curve): MarketData =
    copy(curves = curves.updated(key, value))
  def withVolSurface(key: RiskFactorKey, value: VolSurface): MarketData =
    copy(volSurfaces = volSurfaces.updated(key, value))
  def withPrepayVector(key: RiskFactorKey, value: PrepayVector): MarketData =
    copy(prepayVectors = prepayVectors.updated(key, value))
  def withFxSpot(key: RiskFactorKey, value: SpotQuote): MarketData =
    copy(fxSpots = fxSpots.updated(key, value))
  def withCorrelation(key: RiskFactorKey, value: CorrelationMatrix): MarketData =
    copy(correlations = correlations.updated(key, value))
}
