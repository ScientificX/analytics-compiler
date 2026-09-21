package com.writhlang.marketdata

/**
  * A symmetric N×N correlation matrix over a set of named risk factors.
  *
  * Correlation is the pair-wise co-movement between factors, each entry in
  * [-1, +1] with a unit diagonal (a factor is perfectly correlated with itself).
  * It is the one market-data object that is a matrix rather than a curve,
  * surface, vector or spot.
  *
  * STAGE 1 PLACEHOLDER: the type exists so the `MarketData` taxonomy is
  * shape-complete, but no instrument prices through it yet and no scenario
  * shifts it. It will be consumed by multi-factor products and Monte Carlo
  * scenario generation (via a Cholesky factorisation) in later stages.
  */
final case class CorrelationMatrix(labels: Vector[String], values: Vector[Vector[Double]]) {
  def size: Int = labels.size

  /** Correlation between factor `i` and factor `j` (row/column index). */
  def apply(i: Int, j: Int): Double = values(i)(j)
}
