package com.writhlang.engine

import com.writhlang.dsl._
import org.scalatest.funsuite.AnyFunSuite
import org.junit.runner.RunWith
import org.scalatestplus.junit.JUnitRunner
import zio.{Runtime, Unsafe}

@RunWith(classOf[JUnitRunner])
class PricingDslSuite extends AnyFunSuite {

  private def run(dag: Dag): Map[String, Double] =
    Unsafe.unsafe { implicit u =>
      Runtime.default.unsafe.run(Executor.run(dag)).getOrThrow()
    }

  private def parse(dsl: String): Program = Parser.parseProgram(dsl).toOption.get

  test("parse DSL with scalar and curve shocks and all instrument types") {
    val input =
      """
        |shocks {
        |  shock s1 { rate 0.001; spread 0.0001; prepay -0.005; volatility 0.01; fx 0.02; }
        |  shock twist { curve twist -0.001 0.001 10; }
        |  shock bucket { curve bucket 5 0.001; }
        |}
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 5; rate 0.04; spread 0.0; freq 2; }
        |instrument mortgage M1 { notional 100000; rate 0.04; term 120; spread 0.001; prepayCurve ramp 0.02 0.05 12; }
        |instrument mbs MB1 { notional 100000; rate 0.04; spread 0.001; wac 0.05; wam 300; prepayCurve flat 0.02; }
        |instrument swap SW1 { notional 1000000; rate 0.03; spread 0.0005; fixedRate 0.031; maturity 5; freq 2; }
        |instrument cap C1 { notional 1000000; rate 0.035; spread 0.0005; strike 0.04; maturity 3; freq 4; volatility 0.20; }
        |instrument swaption S1 { notional 1000000; rate 0.03; spread 0.0005; strike 0.031; expiry 2; maturity 5; freq 2; volatility 0.18; call; }
        |instrument fxforward F1 { notional 1000000; domesticRate 0.02; fxRate 1.25; foreignRate 0.01; maturity 1; }
        |""".stripMargin

    val program = parse(input)
    assert(program.shocks.map(_.name) == List("s1", "twist", "bucket"))
    assert(program.shocks.head.factors("rate") == 0.001)
    assert(program.shocks(1).curve.head.isInstanceOf[TwistShift])
    assert(program.shocks(2).curve.head.isInstanceOf[BucketShift])
    assert(program.instruments.size == 7)
  }

  test("bond: delta negative, gamma positive, quadratic closer to full than linear") {
    val input =
      """
        |shocks { shock s1 { rate 0.001; } }
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 10; rate 0.04; spread 0.0; freq 1; }
        |""".stripMargin

    val results = run(DslCompiler.build(parse(input)))
    val base = results(DslCompiler.basePriceId("B1"))
    val delta = results(DslCompiler.deltaId("B1", "rate"))
    val gamma = results(DslCompiler.gammaId("B1", "rate"))
    val full = results(DslCompiler.fullScenarioId("B1", "s1"))
    val linear = results(DslCompiler.linearScenarioId("B1", "s1"))
    val quad = results(DslCompiler.quadraticScenarioId("B1", "s1"))

    assert(base > 0.0)
    assert(delta < 0.0)
    assert(gamma > 0.0)
    assert(math.abs(quad - full) <= math.abs(linear - full))
  }

  test("swap prices near par when fixedRate equals discount rate") {
    val input =
      """
        |shocks { shock s1 { rate 0.001; } }
        |instrument swap SW1 { notional 1000000; rate 0.03; spread 0.0; fixedRate 0.03; maturity 5; freq 2; }
        |""".stripMargin

    val results = run(DslCompiler.build(parse(input)))
    val base = results(DslCompiler.basePriceId("SW1"))
    val full = results(DslCompiler.fullScenarioId("SW1", "s1"))
    assert(math.abs(base) < 0.02 * 1000000.0)
    assert(math.abs(full - base) > 0.0)
  }

  test("cap price is positive for an in-the-money cap") {
    val input =
      """
        |shocks { shock v { volatility 0.01; } }
        |instrument cap C1 { notional 1000000; rate 0.03; spread 0.0; strike 0.02; maturity 2; freq 4; volatility 0.20; }
        |""".stripMargin

    val results = run(DslCompiler.build(parse(input)))
    assert(results(DslCompiler.basePriceId("C1")) > 0.0)
  }

  test("fxforward base is near zero when domestic equals foreign rate") {
    val input =
      """
        |shocks { shock f { fx 0.01; } }
        |instrument fxforward F1 { notional 1000000; domesticRate 0.02; fxRate 1.25; foreignRate 0.02; maturity 1; }
        |""".stripMargin

    val results = run(DslCompiler.build(parse(input)))
    val base = results(DslCompiler.basePriceId("F1"))
    val fxDelta = results(DslCompiler.deltaId("F1", "fx"))
    assert(math.abs(base) < 1.0)
    assert(fxDelta > 0.0)
  }

  test("curve shocks produce distinct full reprice values") {
    val input =
      """
        |shocks {
        |  shock bucket { curve bucket 5 0.001; }
        |  shock twist { curve twist -0.001 0.001 10; }
        |}
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 5; rate 0.04; spread 0.0; freq 1; }
        |""".stripMargin

    val results = run(DslCompiler.build(parse(input)))
    val base = results(DslCompiler.basePriceId("B1"))
    val bucket = results(DslCompiler.fullScenarioId("B1", "bucket"))
    val twist = results(DslCompiler.fullScenarioId("B1", "twist"))
    assert(math.abs(bucket - base) > 0.0)
    assert(math.abs(twist - base) > 0.0)
    assert(math.abs(bucket - twist) > 0.0)
  }
}
