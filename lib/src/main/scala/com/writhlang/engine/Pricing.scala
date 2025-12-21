package com.writhlang.engine

import com.writhlang.dsl.{BondSpec, FlatPrepay, MortgageSpec, PrepayCurve, RampPrepay}

object Pricing {
  def bondPrice(spec: BondSpec, rateShift: Double, spreadShift: Double): Double = {
    val periods = spec.maturityYears * spec.couponFreq
    val yieldRate = spec.rate + spec.spread + rateShift + spreadShift
    val periodRate = yieldRate / spec.couponFreq.toDouble
    val couponCash = spec.notional * spec.coupon / spec.couponFreq.toDouble

    (1 to periods).map { t =>
      val cf = if (t == periods) couponCash + spec.notional else couponCash
      cf / math.pow(1.0 + periodRate, t.toDouble)
    }.sum
  }

  def mortgagePrice(spec: MortgageSpec, rateShift: Double, spreadShift: Double, prepayShift: Double): Double = {
    val discountRate = spec.rate + spec.spread + rateShift + spreadShift
    val discountRateMonthly = discountRate / 12.0
    val noteRateMonthly = spec.rate / 12.0
    val term = spec.termMonths

    val payment = if (noteRateMonthly == 0.0) spec.notional / term
    else spec.notional * noteRateMonthly / (1.0 - math.pow(1.0 + noteRateMonthly, -term.toDouble))

    val cprCurve = expandPrepayCurve(spec.prepayCurve, term)

    var balance = spec.notional
    var pv = 0.0

    var month = 1
    while (month <= term && balance > 0.0) {
      val interest = balance * noteRateMonthly
      val scheduledPrincipal = payment - interest
      val cpr = clamp(cprCurve(month - 1) + prepayShift, 0.0, 1.0)
      val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
      val prepay = (balance - scheduledPrincipal) * smm
      val totalPrincipal = scheduledPrincipal + prepay
      val cashflow = interest + totalPrincipal

      pv += cashflow / math.pow(1.0 + discountRateMonthly, month.toDouble)
      balance -= totalPrincipal
      month += 1
    }

    pv
  }

  def expandPrepayCurve(curve: PrepayCurve, termMonths: Int): Vector[Double] = curve match {
    case FlatPrepay(cpr) => Vector.fill(termMonths)(cpr)
    case RampPrepay(start, end, rampMonths) =>
      val ramp = math.max(1, rampMonths)
      val slope = (end - start) / (ramp.toDouble - 1.0).max(1.0)
      val ramped = (0 until termMonths).map { idx =>
        if (idx < ramp) start + slope * idx.toDouble else end
      }
      ramped.toVector
  }

  private def clamp(value: Double, min: Double, max: Double): Double =
    math.max(min, math.min(max, value))
}
