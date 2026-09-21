package com.writhlang.app

import com.writhlang.dsl.Parser
import com.writhlang.engine.{DslCompiler, Executor}
import com.writhlang.marketdata.MarketDataJson
import com.writhlang.render.DotRenderer
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.sys.process.{ProcessLogger, _}

object Main extends ZIOAppDefault {

  val defaultMarketJson: String =
    """{
      |  "curves": {
      |    "EUR": { "type": "DiscountCurve", "instruments": [
      |      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
      |      { "kind": "swap", "tenor": "2Y", "rate": 0.040, "freq": 1 },
      |      { "kind": "swap", "tenor": "3Y", "rate": 0.045, "freq": 1 }
      |    ]},
      |    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0015 }
      |  },
      |  "fxSpots": { "EURUSD": 1.25 },
      |  "volSurfaces": { "EUR.SWAPTION": { "flat": 0.20 } },
      |  "prepayVectors": { "EUR.MBS": { "termMonths": 360, "flat": 0.02 } }
      |}""".stripMargin

  val defaultDsl: String =
    """shocks {
      |  shock up2y { discountcurve EUR 2Y +0.0001; }
      |  shock fxup { fxspot EURUSD relative +0.01; }
      |}
      |
      |portfolio RatesBook {
      |  instrument bond BondA { notional 1000000; coupon 0.05; maturity 3; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
      |  instrument swap SwapA { notional 1000000; fixedRate 0.04; maturity 2; discountCurve EUR; creditCurve EUR.CREDIT; freq 1; }
      |}
      |""".stripMargin

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    for {
      args <- ZIOAppArgs.getArgs.map(_.toList)
      config = CliConfig.fromArgs(args)
      dsl <- load(config.inputPath, defaultDsl)
      marketJson <- load(config.marketPath, defaultMarketJson)
      _ <- (for {
        market <- MarketDataJson.parse(marketJson)
        program <- Parser.parseProgram(dsl)
      } yield (market, program)) match {
        case Left(err) => ZIO.succeed(println(s"Error: $err"))
        case Right((market, program)) =>
          val dag = DslCompiler.build(program, market)
          val dot = DotRenderer.toDot(dag)
          val dotPath = config.dotPath.getOrElse(Paths.get("build", "writhlang_dag.dot"))
          val pngPath = config.pngPath.getOrElse(Paths.get("build", "writhlang_dag.png"))

          val writeDot = ZIO.attempt {
            val parent = dotPath.getParent
            if (parent != null) Files.createDirectories(parent)
            Files.write(dotPath, dot.getBytes(StandardCharsets.UTF_8))
          }.orElse(ZIO.succeed(()))

          val render = ZIO.attempt {
            if (graphvizAvailable()) {
              Seq("dot", "-Tpng", dotPath.toString, "-o", pngPath.toString)
                .!(ProcessLogger(_ => (), _ => ()))
            }
          }.orElse(ZIO.succeed(()))

          writeDot *> render *> Executor.run(dag).foldZIO(
            err => ZIO.succeed(println(s"Execution error: ${err.message}")),
            results => ZIO.succeed(printResults(program, results))
          )
      }
    } yield ()
  }

  private def graphvizAvailable(): Boolean = {
    try Seq("dot", "-V").!(ProcessLogger(_ => (), _ => ())) == 0
    catch { case _: Throwable => false }
  }

  private def printResults(program: com.writhlang.dsl.Program, results: Map[String, Double]): Unit = {
    println("--- Scenario Prices ---")
    DslCompiler.flattenInstruments(program.nodes).foreach { case (inst, _) =>
      val basePrice = results.getOrElse(DslCompiler.basePriceId(inst.id), Double.NaN)
      println(s"${inst.id} base=${format(basePrice)}")
      program.shocks.foreach { shock =>
        val full = results.getOrElse(DslCompiler.fullScenarioId(inst.id, shock.name), Double.NaN)
        val lin = results.getOrElse(DslCompiler.linearScenarioId(inst.id, shock.name), Double.NaN)
        val quad = results.getOrElse(DslCompiler.quadraticScenarioId(inst.id, shock.name), Double.NaN)
        println(s"  ${shock.name}: full=${format(full)} linear=${format(lin)} quadratic=${format(quad)}")
      }
    }
  }

  private def format(value: Double): String = f"$value%.4f"

  private def load(path: Option[java.nio.file.Path], default: String): ZIO[Any, Throwable, String] =
    path match {
      case Some(p) => ZIO.attempt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
      case None    => ZIO.succeed(default)
    }

  private case class CliConfig(
    inputPath: Option[java.nio.file.Path],
    marketPath: Option[java.nio.file.Path],
    dotPath: Option[java.nio.file.Path],
    pngPath: Option[java.nio.file.Path]
  )

  private object CliConfig {
    def fromArgs(args: List[String]): CliConfig = {
      val iter = args.iterator
      var input: Option[java.nio.file.Path] = None
      var market: Option[java.nio.file.Path] = None
      var dot: Option[java.nio.file.Path] = None
      var png: Option[java.nio.file.Path] = None

      while (iter.hasNext) {
        iter.next() match {
          case "--input" if iter.hasNext  => input = Some(Paths.get(iter.next()))
          case "--market" if iter.hasNext => market = Some(Paths.get(iter.next()))
          case "--dot" if iter.hasNext    => dot = Some(Paths.get(iter.next()))
          case "--png" if iter.hasNext    => png = Some(Paths.get(iter.next()))
          case path if input.isEmpty      => input = Some(Paths.get(path))
          case _                          => ()
        }
      }
      CliConfig(input, market, dot, png)
    }
  }
}
