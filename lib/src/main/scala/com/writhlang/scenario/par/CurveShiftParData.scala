package com.writhlang.scenario.par

import com.writhlang.risk.RiskFactorKey

/**
  * Par-conversion metadata: which par instrument to bump for a given curve
  * pillar. A pillar key such as `DiscountCurve:EUR:2Y` is addressed by bumping
  * the **2Y swap quote** on the `EUR` discount curve and re-bootstrapping —
  * never by shifting an interpolated 2Y zero rate.
  *
  * `curveKey` is the undimensioned curve key (e.g. `DiscountCurve:EUR`) and
  * `parTenorYears` is the maturity of the par instrument whose quote is bumped.
  */
final case class CurveShiftParData(
  curveKey: RiskFactorKey,
  parTenorYears: Double
)
