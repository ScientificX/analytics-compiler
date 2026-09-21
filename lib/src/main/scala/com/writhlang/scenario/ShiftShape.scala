package com.writhlang.scenario

/**
  * A shock shape: a function `at(x)` describing how much to add to a market
  * object at coordinate `x`. For curves `x` is a tenor (years); for a prepay
  * vector it is the month's time in years; for a flat vol surface it is an
  * expiry. Shapes are deliberately just functions so that flat, bucket, twist,
  * sine and custom shocks are all the same kind of thing — an additive
  * transformation of the input object, never a scalar inside the pricing code.
  */
sealed trait ShiftShape {
  /** The additive shift at coordinate `x`. */
  def at(x: Double): Double
}

/** Flat shock: the same additive amount everywhere. */
final case class Flat(amount: Double) extends ShiftShape {
  def at(x: Double): Double = amount
}

/** Bucket shock: a tent centred on `tenor` (width 1 year), peaking at `amount`. */
final case class Bucket(tenor: Double, amount: Double) extends ShiftShape {
  private val bucketWidth = 1.0
  def at(x: Double): Double = {
    val distance = math.abs(x - tenor)
    if (distance >= bucketWidth) 0.0 else amount * (1.0 - distance / bucketWidth)
  }
}

/** Twist shock: linear from a short-end shift to a long-end shift up to `pivot`. */
final case class Twist(short: Double, long: Double, pivot: Double) extends ShiftShape {
  def at(x: Double): Double = {
    val pivotYears = math.max(pivot, 1e-9)
    if (x <= 0.0) short
    else if (x >= pivotYears) long
    else short + (long - short) * (x / pivotYears)
  }
}

/**
  * Sine-wave shock: `A·sin(ω·x + φ)`. For a prepay vector this is the
  * `cpr(t) = base(t) + A·sin(ωt+φ)` scenario, with `ω` in radians per year and
  * `φ` in radians.
  */
final case class Sine(amplitude: Double, omega: Double, phase: Double) extends ShiftShape {
  def at(x: Double): Double = amplitude * math.sin(omega * x + phase)
}

/** Arbitrary custom shock function. */
final case class Custom(f: Double => Double) extends ShiftShape {
  def at(x: Double): Double = f(x)
}
