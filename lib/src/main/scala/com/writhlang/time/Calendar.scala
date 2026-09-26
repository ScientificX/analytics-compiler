package com.writhlang.time

import java.time.{DayOfWeek, LocalDate}

/**
 * A business-day calendar: which dates count as business days, and how to step a
 * business-day count. Stage 3 ships a weekends-only calendar (Saturday/Sunday are
 * non-business); holiday calendars are a later concern.
 */
trait Calendar {
  /** True when `date` is a business day (a day markets are open). */
  def isBusinessDay(date: LocalDate): Boolean

  /**
   * The date `days` business days after (positive) or before (negative) `date`,
   * counting only business days.
   */
  def addBusinessDays(date: LocalDate, days: Int): LocalDate
}

/** A calendar where Saturday and Sunday are the only non-business days. */
final class WeekendCalendar extends Calendar {
  override def isBusinessDay(date: LocalDate): Boolean = {
    val dow = date.getDayOfWeek
    dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
  }

  override def addBusinessDays(date: LocalDate, days: Int): LocalDate = {
    val step = if (days >= 0) 1 else -1
    var current = date
    var remaining = days
    while (remaining != 0) {
      current = current.plusDays(step.toLong)
      if (isBusinessDay(current)) remaining -= step
    }
    current
  }
}

object Calendar {
  /** The shared weekends-only calendar. */
  val weekend: Calendar = new WeekendCalendar
}

/**
 * How to roll a date that falls on a non-business day. Mirrors the standard
 * QuantLib/ISDA conventions (Unadjusted = leave the date where it is).
 */
sealed trait BusinessDayConvention {
  def name: String
  def adjust(date: LocalDate, calendar: Calendar): LocalDate
}

object BusinessDayConvention {
  /** Move forward to the next business day. */
  case object Following extends BusinessDayConvention {
    val name = "Following"
    def adjust(date: LocalDate, calendar: Calendar): LocalDate = {
      var d = date
      while (!calendar.isBusinessDay(d)) d = d.plusDays(1)
      d
    }
  }

  /**
   * Move forward, but if that crosses into the next month, move backward to the
   * last business day of the current month.
   */
  case object ModifiedFollowing extends BusinessDayConvention {
    val name = "ModifiedFollowing"
    def adjust(date: LocalDate, calendar: Calendar): LocalDate = {
      var d = date
      while (!calendar.isBusinessDay(d)) d = d.plusDays(1)
      if (d.getMonthValue != date.getMonthValue) {
        d = date
        while (!calendar.isBusinessDay(d)) d = d.minusDays(1)
      }
      d
    }
  }

  /** Move backward to the previous business day. */
  case object Preceding extends BusinessDayConvention {
    val name = "Preceding"
    def adjust(date: LocalDate, calendar: Calendar): LocalDate = {
      var d = date
      while (!calendar.isBusinessDay(d)) d = d.minusDays(1)
      d
    }
  }

  /** Leave the date unchanged (no adjustment). */
  case object Unadjusted extends BusinessDayConvention {
    val name = "Unadjusted"
    def adjust(date: LocalDate, calendar: Calendar): LocalDate = date
  }

  val all: List[BusinessDayConvention] =
    List(Following, ModifiedFollowing, Preceding, Unadjusted)

  def fromName(name: String): Option[BusinessDayConvention] = all.find(_.name == name)
}

/**
 * A theta horizon: `days` business days over which the valuation date is advanced.
 * This is the Stage 3 analogue of ORE's `thetaPeriod_` in `SensitivityScenarioData`.
 *
 * `elapsedYears` converts the horizon to the year fraction actually passed into
 * pricing (the `elapsedYears` offset that shifts every time-to-cashflow tenor).
 */
final case class ThetaPeriod(days: Int) {
  require(days > 0, s"theta period must be a positive number of business days, got $days")

  /** The year fraction of `days` business days measured from `asOf`. */
  def elapsedYears(asOf: LocalDate, calendar: Calendar, dayCount: DayCount): Double = {
    val target = calendar.addBusinessDays(asOf, days)
    dayCount.yearFraction(asOf, target)
  }
}
