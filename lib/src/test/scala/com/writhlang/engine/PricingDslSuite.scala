package com.writhlang.engine

import com.writhlang.dsl._
import com.writhlang.render.DotRenderer
import com.writhlang.risk._
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
    val delta = results(DslCompiler.deltaId("B1", LegacyRiskFactors.rate))
    val gamma = results(DslCompiler.gammaId("B1", LegacyRiskFactors.rate))
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
    val fxDelta = results(DslCompiler.deltaId("F1", LegacyRiskFactors.fx))
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

  test("RiskFactorKey taxonomy round-trips through canonical strings (AC1)") {
    val discount5y = RiskFactorKey(KeyType.DiscountCurve, "EUR", tenor = Some(Tenor(5.0)))
    assert(discount5y.canonical == "DiscountCurve:EUR:5Y")
    assert(RiskFactorKey.parse("DiscountCurve:EUR:5Y").toOption.contains(discount5y))

    val swaptionAtm = RiskFactorKey(
      KeyType.SwaptionVolatility,
      "EUR",
      expiry = Some(Tenor(5.0)),
      swapTenor = Some(Tenor(10.0)),
      strike = Some(ATM)
    )
    assert(swaptionAtm.canonical == "SwaptionVolatility:EUR:5Yx10Y:ATM")
    assert(RiskFactorKey.parse("SwaptionVolatility:EUR:5Yx10Y:ATM").toOption.contains(swaptionAtm))

    val roundTripped = RiskFactorKey.parse(discount5y.canonical).toOption.get
    assert(roundTripped == discount5y)
    assert(roundTripped.hashCode == discount5y.hashCode)
  }

  test("legacy DSL words resolve to distinct RiskFactorKeys (AC2)") {
    assert(LegacyRiskFactors.fromWord("rate").contains(LegacyRiskFactors.rate))
    assert(LegacyRiskFactors.rate.keyType == KeyType.DiscountCurve)
    assert(LegacyRiskFactors.fromWord("spread").contains(LegacyRiskFactors.spread))
    assert(LegacyRiskFactors.spread.keyType == KeyType.CreditCurve)
    assert(LegacyRiskFactors.fromWord("prepay").contains(LegacyRiskFactors.prepay))
    assert(LegacyRiskFactors.prepay.keyType == KeyType.Prepay)
    assert(LegacyRiskFactors.fromWord("volatility").contains(LegacyRiskFactors.volatility))
    assert(LegacyRiskFactors.volatility.keyType == KeyType.SwaptionVolatility)
    assert(LegacyRiskFactors.fromWord("fx").contains(LegacyRiskFactors.fx))
    assert(LegacyRiskFactors.fx.keyType == KeyType.FxSpot)
    assert(LegacyRiskFactors.all.distinct.size == 5)

    val results = run(DslCompiler.build(parse(
      """
        |shocks { shock s1 { rate 0.001; spread 0.0001; } }
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 5; rate 0.04; spread 0.0; freq 2; }
        |""".stripMargin)))
    assert(!results(DslCompiler.basePriceId("B1")).isNaN)
    assert(!results(DslCompiler.deltaId("B1", LegacyRiskFactors.rate)).isNaN)
    assert(!results(DslCompiler.deltaId("B1", LegacyRiskFactors.spread)).isNaN)
  }

  test("bond DAG has separate DiscountCurve and CreditCurve node families (AC3)") {
    val dag = DslCompiler.build(parse(
      """
        |shocks { shock s1 { rate 0.001; spread 0.0001; } }
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 5; rate 0.04; spread 0.0; freq 2; }
        |""".stripMargin))

    val rateDeltaId = DslCompiler.deltaId("B1", LegacyRiskFactors.rate)
    val spreadDeltaId = DslCompiler.deltaId("B1", LegacyRiskFactors.spread)
    val rateGammaId = DslCompiler.gammaId("B1", LegacyRiskFactors.rate)
    val spreadGammaId = DslCompiler.gammaId("B1", LegacyRiskFactors.spread)
    val crossId = DslCompiler.crossGammaId("B1", LegacyRiskFactors.rate, LegacyRiskFactors.spread)

    assert(dag.nodes.contains(rateDeltaId))
    assert(dag.nodes.contains(spreadDeltaId))
    assert(dag.nodes.contains(rateGammaId))
    assert(dag.nodes.contains(spreadGammaId))
    assert(dag.nodes.contains(crossId))
    assert(rateDeltaId != spreadDeltaId)
    assert(rateDeltaId.contains("DiscountCurve:EUR"))
    assert(spreadDeltaId.contains("CreditCurve:EUR"))
  }

  test("node ids encode the canonical key and are unique (AC4)") {
    val keys = List(LegacyRiskFactors.rate, LegacyRiskFactors.spread, LegacyRiskFactors.prepay)
    val instId = "B1"

    val deltaIds = keys.map(k => DslCompiler.deltaId(instId, k))
    val gammaIds = keys.map(k => DslCompiler.gammaId(instId, k))
    val bumpIds = keys.map(k => DslCompiler.bumpedPriceId(instId, k, "up"))
    val crossIds = keys.combinations(2).toList.map { case List(a, b) => DslCompiler.crossGammaId(instId, a, b) }

    assert(deltaIds.distinct.size == keys.size)
    assert(gammaIds.distinct.size == keys.size)
    assert(bumpIds.distinct.size == keys.size)
    assert(crossIds.distinct.size == keys.combinations(2).size)

    keys.foreach { key =>
      assert(DslCompiler.deltaId(instId, key).contains(key.canonical))
      assert(DslCompiler.gammaId(instId, key).contains(key.canonical))
      assert(DslCompiler.bumpedPriceId(instId, key, "up").contains(key.canonical))
    }
  }


  test("DOT rendering groups instrument nodes and edges stay valid (AC5)") {
    val program = parse(
      """
        |shocks { shock s1 { rate 0.001; } }
        |instrument bond B1 { notional 1000; coupon 0.05; maturity 5; rate 0.04; spread 0.0; freq 2; }
        |instrument swap SW1 { notional 1000000; rate 0.03; spread 0.0; fixedRate 0.031; maturity 5; freq 2; }
        |""".stripMargin)
    val dag = DslCompiler.build(program)
    val dot = DotRenderer.toDot(dag)

    assert(dot.contains("subgraph cluster_B1"))
    assert(dot.contains("subgraph cluster_SW1"))

    def clusterBody(instId: String): String = {
      val marker = s"subgraph cluster_$instId {"
      val start = dot.indexOf(marker)
      if (start < 0) ""
      else {
        val afterStart = dot.substring(start + marker.length)
        val end = afterStart.indexOf("\n  }")
        if (end < 0) afterStart else afterStart.substring(0, end)
      }
    }

    dag.nodes.keys.foreach(id => assert(dot.contains(s"\"$id\" [label="), s"missing node $id"))
    dag.nodes.values.foreach { instr =>
      instr.deps.foreach(dep => assert(dag.nodes.contains(dep), s"${instr.id} refs missing dep $dep"))
    }

    dag.nodes.values
      .filter(i => i.id.startsWith("price:") || i.id.startsWith("greek:"))
      .foreach { instr =>
        val instId = instr.id.split(":")(2)
        assert(clusterBody(instId).contains(s"\"${instr.id}\" [label="), s"${instr.id} not in cluster $instId")
      }
  }

  test("granular programmatic addressing resolves type/name/dimensions (AC6)") {
    val discount = RiskFactorKey.parse("DiscountCurve:EUR:5Y").toOption.get
    assert(discount.keyType == KeyType.DiscountCurve)
    assert(discount.name == "EUR")
    assert(discount.dimensions == Vector[Dimension](TenorDim(5.0)))

    val swaption = RiskFactorKey.parse("SwaptionVolatility:EUR:5Yx10Y:ATM").toOption.get
    assert(swaption.keyType == KeyType.SwaptionVolatility)
    assert(swaption.name == "EUR")
    assert(swaption.dimensions == Vector[Dimension](ExpiryDim(5.0), SwapTenorDim(10.0), StrikeDim(ATM)))
  }

}
