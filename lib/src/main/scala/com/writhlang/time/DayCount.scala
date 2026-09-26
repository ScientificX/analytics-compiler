package com.writhlang.time

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A day-count convention: how to turn the calendar time between two dates into a
 * *year fraction*. Year fractions are what pricing uses for time-to-cashflow
 * (`t` in `e^(-r·t)`) and for accrual, so a convention is the bridge between the
 * `asOf` valuation date and the instrument's cashflow dates.
 *
 * Stage 3 ships the three conventions sufficient for the instruments in scope.
 */
sealed trait DayCount {
  def name: String

  /** The time from `from` to `to` expressed in years (can be negative). */
  def yearFraction(from: LocalDate, to: LocalDate): Double
}

object DayCount {

  /**
   * Actual/365 (fixed): every year is 365 days, so the fraction is the actual day
   * count divided by 365. The simplest convention and the default here.
   */
  case object Actual365Fixed extends DayCount {
    val name = "Actual365Fixed"
    def yearFraction(from: LocalDate, to: LocalDate): Double =
      ChronoUnit.DAYS.between(from, to).toDouble / 365.0
  }

  /**
   * Actual/360: the actual day count divided by a 360-day year. Common in money
   * markets; a full calendar year yields 365/360 (or 366/360 in a leap year).
   */
  case object Actual360 extends DayCount {
    val name = "Actual360"
    def yearFraction(from: LocalDate, to: LocalDate): Double =
      ChronoUnit.DAYS.between(from, to).toDouble / 360.0
  }

  /**
   * 30/360 (Bond Basis): every month is treated as 30 days and every year as 360,
   * with the end-of-month adjustments of the US bond convention. A full year is
   * exactly 1.0 regardless of leap years, which is why it suits fixed-coupon bonds.
   */
  case object Thirty360 extends DayCount {
    val name = "Thirty360"
    def yearFraction(from: LocalDate, to: LocalDate): Double =
      thirty360Days(from, to) / 360.0

    private def thirty360Days(from: LocalDate, to: LocalDate): Double = {
      var d1 = from.getDayOfMonth
      var d2 = to.getDayOfMonth
      // If a start/end day is the 31st, treat it as the 30th; if the start day is
      // the (adjusted) 30th, an end day of 31 is also treated as the 30th.
      if (d1 == 31) d1 = 30
      if (d2 == 31 && d1 == 30) d2 = 30
      360.0 * (to.getYear - from.getYear) +
        30.0 * (to.getMonthValue - from.getMonthValue) +
        (d2 - d1).toDouble
    }
  }

  val all: List[DayCount] = List(Actual365Fixed, Actual360, Thirty360)

  def fromName(name: String): Option[DayCount] = all.find(_.name == name)
}
