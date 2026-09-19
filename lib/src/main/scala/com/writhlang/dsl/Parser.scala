package com.writhlang.dsl

import fastparse._
import fastparse.NoWhitespace._

object Parser {
  private sealed trait PropValue
  private case class NumberValue(value: Double) extends PropValue
  private case class PrepayValue(curve: PrepayCurve) extends PropValue
  private case class FlagValue(value: Boolean) extends PropValue

  private case class InstrumentRaw(kind: String, id: String, props: List[(String, PropValue)])

  private sealed trait ShockItem
  private case class ScalarItem(name: String, value: Double) extends ShockItem
  private case class CurveItem(shift: CurveShift) extends ShockItem

  def parseProgram(input: String): Either[String, Program] =
    fastparse.parse(input, program(_)) match {
      case Parsed.Success((shocks, rawInstruments), _) =>
        val instruments = rawInstruments.map(validateInstrument)
        val errors = instruments.collect { case Left(err) => err }
        if (errors.nonEmpty) Left(errors.mkString("; "))
        else Right(Program(shocks, instruments.collect { case Right(v) => v }))
      case f: Parsed.Failure => Left(f.msg)
    }

  private def validateInstrument(raw: InstrumentRaw): Either[String, InstrumentSpec] = {
    val props = raw.props.toMap

    def reqNumber(name: String): Either[String, Double] =
      props.get(name) match {
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
        case None => Left(s"instrument ${raw.id} missing property: $name")
      }

    def optNumber(name: String, default: Double): Either[String, Double] =
      props.get(name) match {
        case None => Right(default)
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
      }

    def reqInt(name: String): Either[String, Int] =
      reqNumber(name).flatMap { v =>
        if (v % 1.0 == 0.0) Right(v.toInt) else Left(s"instrument ${raw.id} property $name must be an integer")
      }

    def reqPositiveInt(name: String): Either[String, Int] =
      reqInt(name).flatMap { v =>
        if (v > 0) Right(v) else Left(s"instrument ${raw.id} property $name must be > 0")
      }

    def reqPositiveNumber(name: String): Either[String, Double] =
      reqNumber(name).flatMap { v =>
        if (v > 0.0) Right(v) else Left(s"instrument ${raw.id} property $name must be > 0")
      }

    def optPositiveInt(name: String, default: Int): Either[String, Int] =
      props.get(name) match {
        case None => Right(default)
        case Some(NumberValue(v)) =>
          if (v % 1.0 == 0.0 && v > 0.0) Right(v.toInt)
          else Left(s"instrument ${raw.id} property $name must be a positive integer")
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a number")
      }

    def reqPrepay(name: String): Either[String, PrepayCurve] =
      props.get(name) match {
        case Some(PrepayValue(curve)) => Right(curve)
        case Some(_) => Left(s"instrument ${raw.id} property $name must be a prepayCurve")
        case None => Left(s"instrument ${raw.id} missing property: $name")
      }

    def optFlag(default: Boolean): Either[String, Boolean] =
      props.get("flag") match {
        case None => Right(default)
        case Some(FlagValue(v)) => Right(v)
        case Some(_) => Left(s"instrument ${raw.id} flag must be call or put")
      }

    def validRamp(curve: PrepayCurve): Either[String, Unit] = curve match {
      case RampPrepay(_, _, months) if months <= 0 =>
        Left(s"instrument ${raw.id} prepayCurve ramp months must be > 0")
      case _ => Right(())
    }

    raw.kind match {
      case "bond" =>
        for {
          notional <- reqNumber("notional")
          coupon <- reqNumber("coupon")
          maturity <- reqPositiveInt("maturity")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          freq <- optPositiveInt("freq", 1)
        } yield BondSpec(raw.id, notional, coupon, maturity, rate, spread, freq)

      case "mortgage" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          term <- reqPositiveInt("term")
          curve <- reqPrepay("prepayCurve")
          _ <- validRamp(curve)
        } yield MortgageSpec(raw.id, notional, rate, spread, term, curve)

      case "mbs" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          wac <- reqNumber("wac")
          wam <- reqPositiveInt("wam")
          curve <- reqPrepay("prepayCurve")
          _ <- validRamp(curve)
        } yield MbsPoolSpec(raw.id, notional, rate, spread, wac, wam, curve)

      case "swap" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          fixedRate <- reqNumber("fixedRate")
          maturity <- reqPositiveInt("maturity")
          freq <- optPositiveInt("freq", 1)
        } yield SwapSpec(raw.id, notional, rate, spread, fixedRate, maturity, freq)

      case "cap" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          strike <- reqNumber("strike")
          maturity <- reqPositiveInt("maturity")
          freq <- optPositiveInt("freq", 4)
          volatility <- reqNumber("volatility")
        } yield CapSpec(raw.id, notional, rate, spread, strike, maturity, freq, volatility)

      case "swaption" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          strike <- reqNumber("strike")
          expiry <- reqPositiveNumber("expiry")
          maturity <- reqPositiveInt("maturity")
          freq <- optPositiveInt("freq", 2)
          volatility <- reqNumber("volatility")
          isPayer <- optFlag(default = true)
        } yield SwaptionSpec(raw.id, notional, rate, spread, strike, expiry, maturity, freq, volatility, isPayer)

      case "fxforward" =>
        for {
          notional <- reqNumber("notional")
          domesticRate <- reqNumber("domesticRate")
          fxRate <- reqNumber("fxRate")
          foreignRate <- reqNumber("foreignRate")
          maturity <- reqPositiveNumber("maturity")
        } yield FxForwardSpec(raw.id, notional, domesticRate, fxRate, foreignRate, maturity)

      case other => Left(s"unknown instrument type: $other")
    }
  }

  private def program[_: P]: P[(List[Shock], List[InstrumentRaw])] = P(
    ws ~ shocksBlock ~ ws ~ (ws ~ instrumentBlock).rep ~ ws ~ End
  ).map { case (shocks, instruments) => (shocks, instruments.toList) }

  private def shocksBlock[_: P]: P[List[Shock]] = P(
    "shocks" ~ ws ~ "{" ~ ws ~ shockEntry.rep ~ ws ~ "}"
  ).map(_.toList)

  private def shockEntry[_: P]: P[Shock] = P(
    "shock" ~ ws ~ ident ~ ws ~ "{" ~ ws ~ shockItem.rep ~ ws ~ "}" ~ entryEnd
  ).map { case (name, items) =>
    val (scalars, curves) = items.foldLeft((Map.empty[String, Double], List.empty[CurveShift])) {
      case ((s, c), ScalarItem(k, v)) => (s + (k -> v), c)
      case ((s, c), CurveItem(cs)) => (s, c :+ cs)
    }
    Shock(name, scalars, curves)
  }

  private def shockItem[_: P]: P[ShockItem] = P(curveItem | scalarItem)

  private def scalarItem[_: P]: P[ShockItem] = P(
    ident ~ ws ~ number ~ entryEnd
  ).map { case (name, value) => ScalarItem(name, value) }

  private def curveItem[_: P]: P[ShockItem] = P(
    "curve" ~ ws ~ curveShift ~ entryEnd
  ).map(cs => CurveItem(cs))

  private def curveShift[_: P]: P[CurveShift] = P(
    ("parallel" ~ ws ~ number).map(v => FlatShift(v): CurveShift) |
      ("bucket" ~ ws ~ number ~ ws ~ number).map { case (t, a) => BucketShift(t, a): CurveShift } |
      ("twist" ~ ws ~ number ~ ws ~ number ~ (ws ~ number).?).map {
        case (short, long, pivot) => TwistShift(short, long, pivot.getOrElse(10.0)): CurveShift
      }
  )

  private def instrumentBlock[_: P]: P[InstrumentRaw] = P(
    "instrument" ~ ws ~ ("bond" | "mortgage" | "mbs" | "swaption" | "swap" | "cap" | "fxforward").! ~ ws ~ ident ~ ws ~ "{" ~ ws ~ prop.rep ~ ws ~ "}"
  ).map { case (kind, id, props) => InstrumentRaw(kind, id, props.toList) }

  private def prop[_: P]: P[(String, PropValue)] = P(prepayProp | flagProp | numberProp)

  private def prepayProp[_: P]: P[(String, PropValue)] = P(
    "prepayCurve" ~ ws ~ prepayCurve ~ entryEnd
  ).map(curve => ("prepayCurve", PrepayValue(curve)))

  private def flagProp[_: P]: P[(String, PropValue)] = P(
    ("call" | "put").! ~ entryEnd
  ).map { s => ("flag", FlagValue(s == "call")) }

  private def numberProp[_: P]: P[(String, PropValue)] = P(
    ident ~ ws ~ number ~ entryEnd
  ).map { case (name, value) => (name, NumberValue(value)) }

  private def prepayCurve[_: P]: P[PrepayCurve] = P(
    ("flat" ~ ws ~ number).map(v => FlatPrepay(v): PrepayCurve) |
      ("ramp" ~ ws ~ number ~ ws ~ number ~ ws ~ intNumber).map { case (start, end, months) =>
        RampPrepay(start, end, months): PrepayCurve
      }
  )

  private def ident[_: P]: P[String] = P(CharIn("a-zA-Z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def number[_: P]: P[Double] = P(
    (("-" | "+").? ~ CharsWhileIn("0-9", 1) ~ ("." ~ CharsWhileIn("0-9", 1)).?).!
  ).map(_.toDouble)

  private def intNumber[_: P]: P[Int] = P(CharsWhileIn("0-9", 1).!).map(_.toInt)

  private def ws[_: P]: P[Unit] = P(CharsWhileIn(" \r\n\t").rep)

  private def entryEnd[_: P]: P[Unit] = P(ws ~ ";".? ~ ws)
}

