package com.writhlang.dsl

import fastparse._
import fastparse.NoWhitespace._

object Parser {
  private sealed trait PropValue
  private case class NumberValue(value: Double) extends PropValue
  private case class PrepayValue(curve: PrepayCurve) extends PropValue

  private case class InstrumentRaw(kind: String, id: String, props: List[(String, PropValue)])

  def parseProgram(input: String): Either[String, Program] = {
    fastparse.parse(input, program(_)) match {
      case Parsed.Success((shocks, rawInstruments), _) =>
        val instruments = rawInstruments.map(validateInstrument)
        val errors = instruments.collect { case Left(err) => err }
        if (errors.nonEmpty) Left(errors.mkString("; "))
        else Right(Program(shocks, instruments.collect { case Right(v) => v }))
      case f: Parsed.Failure => Left(f.msg)
    }
  }

  private def validateInstrument(raw: InstrumentRaw): Either[String, InstrumentSpec] = {
    val props = raw.props.toMap

    def reqNumber(name: String): Either[String, Double] =
      props.get(name) match {
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property ${name} must be a number")
        case None => Left(s"instrument ${raw.id} missing property: ${name}")
      }

    def optNumber(name: String, default: Double): Either[String, Double] =
      props.get(name) match {
        case None => Right(default)
        case Some(NumberValue(value)) => Right(value)
        case Some(_) => Left(s"instrument ${raw.id} property ${name} must be a number")
      }

    def reqInt(name: String): Either[String, Int] =
      reqNumber(name).flatMap { v =>
        if (v % 1.0 == 0.0) Right(v.toInt)
        else Left(s"instrument ${raw.id} property ${name} must be an integer")
      }

    def reqPositiveInt(name: String): Either[String, Int] =
      reqInt(name).flatMap { v =>
        if (v > 0) Right(v) else Left(s"instrument ${raw.id} property ${name} must be > 0")
      }

    def reqPrepay(name: String): Either[String, PrepayCurve] =
      props.get(name) match {
        case Some(PrepayValue(curve)) => Right(curve)
        case Some(_) => Left(s"instrument ${raw.id} property ${name} must be a prepayCurve")
        case None => Left(s"instrument ${raw.id} missing property: ${name}")
      }

    raw.kind match {
      case "bond" =>
        for {
          notional <- reqNumber("notional")
          coupon <- reqNumber("coupon")
          maturity <- reqPositiveInt("maturity")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          freq <- optNumber("freq", 1.0).flatMap { v =>
            if (v % 1.0 == 0.0) Right(v.toInt)
            else Left(s"instrument ${raw.id} property freq must be an integer")
          }
          _ <- if (freq > 0) Right(()) else Left(s"instrument ${raw.id} property freq must be > 0")
        } yield BondSpec(raw.id, notional, coupon, maturity, rate, spread, freq)
      case "mortgage" =>
        for {
          notional <- reqNumber("notional")
          rate <- reqNumber("rate")
          spread <- optNumber("spread", 0.0)
          term <- reqPositiveInt("term")
          curve <- reqPrepay("prepayCurve")
          _ <- curve match {
            case RampPrepay(_, _, months) if months <= 0 =>
              Left(s"instrument ${raw.id} prepayCurve ramp months must be > 0")
            case _ => Right(())
          }
        } yield MortgageSpec(raw.id, notional, rate, spread, term, curve)
      case other => Left(s"unknown instrument type: ${other}")
    }
  }

  private def program[_: P]: P[(List[Shock], List[InstrumentRaw])] = P(
    ws ~ shocksBlock ~ ws ~ instrumentBlock.rep ~ ws ~ End
  ).map { case (shocks, instruments) => (shocks, instruments.toList) }

  private def shocksBlock[_: P]: P[List[Shock]] = P(
    "shocks" ~ ws ~ "{" ~ ws ~ shockEntry.rep ~ ws ~ "}"
  ).map(_.toList)

  private def shockEntry[_: P]: P[Shock] = P(
    "shock" ~ ws ~ ident ~ ws ~ "{" ~ ws ~ shockFactor.rep ~ ws ~ "}" ~ entryEnd
  ).map { case (name, factors) => Shock(name, factors.toMap) }

  private def shockFactor[_: P]: P[(String, Double)] = P(
    ident ~ ws ~ number ~ entryEnd
  ).map { case (name, value) => (name, value) }

  private def instrumentBlock[_: P]: P[InstrumentRaw] = P(
    "instrument" ~ ws ~ ("bond" | "mortgage").! ~ ws ~ ident ~ ws ~ "{" ~ ws ~ prop.rep ~ ws ~ "}"
  ).map { case (kind, id, props) => InstrumentRaw(kind, id, props.toList) }

  private def prop[_: P]: P[(String, PropValue)] = P(
    prepayProp | numberProp
  )

  private def numberProp[_: P]: P[(String, PropValue)] = P(
    ident ~ ws ~ number ~ entryEnd
  ).map { case (name, value) => (name, NumberValue(value)) }

  private def prepayProp[_: P]: P[(String, PropValue)] = P(
    "prepayCurve" ~ ws ~ prepayCurve ~ entryEnd
  ).map(curve => ("prepayCurve", PrepayValue(curve)))

  private def prepayCurve[_: P]: P[PrepayCurve] = P(
    ("flat" ~ ws ~ number).map(v => FlatPrepay(v)) |
      ("ramp" ~ ws ~ number ~ ws ~ number ~ ws ~ intNumber).map { case (start, end, months) =>
        RampPrepay(start, end, months)
      }
  )

  private def ident[_: P]: P[String] = P(CharIn("a-zA-Z") ~ CharsWhileIn("a-zA-Z0-9_", 0)).!

  private def number[_: P]: P[Double] = P(
    (CharIn("+\\-").? ~ CharIn("0-9").rep(1) ~ ("." ~ CharIn("0-9").rep(1)).?).!
  ).map(_.toDouble)

  private def intNumber[_: P]: P[Int] = P(
    CharIn("0-9").rep(1).!
  ).map(_.toInt)

  private def ws[_: P]: P[Unit] = P(CharsWhileIn(" \r\n\t").rep)

  private def entryEnd[_: P]: P[Unit] = P(ws ~ ";".? ~ ws)
}
