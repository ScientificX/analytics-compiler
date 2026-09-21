package com.writhlang.marketdata

/**
  * Interpolates a discount factor at an arbitrary tenor from a set of known
  * pillar points `(tenorYears, discountFactor)`.
  *
  * The interpolation is **log-linear in discount factors** — the market
  * standard. Since `df(t) = e^(-r(t)·t)`, making `ln df` linear in tenor means
  * the instantaneous forward rate is *constant* between pillars ("flat
  * forward"). This keeps discount factors monotonically decreasing and forward
  * rates non-negative (no arbitrage), which is why it is preferred over
  * "linear in rates" or "linear in raw discount factors".
  *
  * Worked example: with pillars `(1Y, 0.9656)` and `(2Y, 0.9244)`, at the
  * midpoint `t = 1.5Y` the log-linear rule gives
  * `ln df(1.5Y) = (ln 0.9656 + ln 0.9244) / 2`, hence `df(1.5Y) ≈ 0.9448`.
  */
object DiscountCurveInterpolator {

  /**
    * Discount factor at tenor `t` (years).
    *
    * - `t <= 0`     -> `1.0` (money today is worth itself).
    * - `0 < t <= first pillar` -> flat forward from `(0, 1)` to the first pillar.
    * - between pillars -> log-linear in discount factors.
    * - `t >= last pillar` -> flat forward extrapolation of the last segment.
    */
  def df(pillars: Vector[(Double, Double)], t: Double): Double = {
    if (pillars.isEmpty) return 1.0
    val sorted = pillars.sortBy(_._1)
    val tenors = sorted.map(_._1)
    val dfs    = sorted.map(_._2)

    if (t <= 0.0) {
      1.0
    } else if (t <= tenors.head) {
      // Flat forward from the anchor (0, 1) up to the first pillar.
      val lnFirst = math.log(dfs.head)
      math.exp(lnFirst * t / tenors.head)
    } else if (t >= tenors.last) {
      // Flat forward beyond the last pillar.
      if (tenors.size >= 2) {
        val t0 = tenors(tenors.size - 2); val df0 = dfs(dfs.size - 2)
        val t1 = tenors.last;              val df1 = dfs.last
        val fwd = -math.log(df1 / df0) / (t1 - t0)
        df1 * math.exp(-fwd * (t - t1))
      } else dfs.last
    } else {
      // Log-linear in discount factors between the two surrounding pillars.
      val idx = tenors.lastIndexWhere(_ <= t)
      val (t0, df0) = (tenors(idx),     dfs(idx))
      val (t1, df1) = (tenors(idx + 1), dfs(idx + 1))
      val w = (t - t0) / (t1 - t0)
      math.exp(math.log(df0) * (1.0 - w) + math.log(df1) * w)
    }
  }
}
