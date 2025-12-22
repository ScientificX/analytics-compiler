package com.writhlang.engine

import com.writhlang.dsl.Parser
import org.scalatest.funsuite.AnyFunSuite
import zio.{Runtime, Unsafe}
import com.typesafe.scalalogging.LazyLogging

class PricingDslSuite extends AnyFunSuite with LazyLogging {
  test("parse DSL with shocks and prepay curve") {
    val input =
      """
        |shocks {
        |  shock base { rate 0.0; spread 0.0; prepay 0.0; }
        |}
        |instrument mortgage M1 {
        |  notional 100000
        |  rate 0.04
        |  term 120
        |  spread 0.001
        |  prepayCurve ramp 0.02 0.05 12
        |}
        |""".stripMargin

    logger.info(s"Parsing input:\n$input")

    val parsed = Parser.parseProgram(input)
    logger.info(s"Parsed program: $parsed")
    assert(parsed.isRight)
    val program = parsed.toOption.get
    assert(program.shocks.head.name == "base")
    assert(program.shocks.head.factors("rate") == 0.0)
    assert(program.instruments.head.id == "M1")
  }

  test("executor computes scenario price using linearized shocks") {
    val input =
      """
        |shocks {
        |  shock s1 { rate 0.001; }
        |}
        |
        |instrument bond B1 {
        |  notional 1000
        |  coupon 0.05
        |  maturity 2
        |  rate 0.04
        |  spread 0.0
        |  freq 1
        |}
        |""".stripMargin

    val program = Parser.parseProgram(input).toOption.get
    val dag = DslCompiler.build(program)
    val results = Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

    val base = results(DslCompiler.basePriceId("B1"))
    val scenario = results(DslCompiler.scenarioPriceId("B1", "s1"))
    assert(base > 0.0)
    assert(math.abs(scenario - base) > 0.0)
  }
}
