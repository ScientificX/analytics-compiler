package com.writhlang.app

import com.writhlang.dsl.Parser
import com.writhlang.engine.{DslCompiler, Executor}
import com.writhlang.render.DotRenderer
import zio._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.sys.process.{ProcessLogger, _}

object Main extends ZIOAppDefault {

  val defaultDsl: String =
    """
      |shocks {
      |  shock up { rate 0.0005; spread 0.0001; prepay -0.005; }
      |  shock down { rate -0.0005; spread -0.0001; prepay 0.005; }
      |  shock twist { curve twist -0.001 0.001 10; }
      |  shock bucket { curve bucket 5 0.001; }
      |  shock vol { volatility 0.01; }
      |}
      |
      |instrument bond BondA {
      |  notional 1000000
      |  coupon 0.05
      |  maturity 7
      |  rate 0.042
      |  spread 0.0015
      |  freq 2
      |}
      |
      |instrument mortgage MortA {
      |  notional 350000
      |  rate 0.045
      |  term 360
      |  spread 0.0020
      |  prepayCurve ramp 0.02 0.06 24
      |}
      |
      |instrument swap SwapA {
      |  notional 5000000
      |  rate 0.03
      |  spread 0.0005
      |  fixedRate 0.031
      |  maturity 5
      |  freq 2
      |}
      |
      |instrument cap CapA {
      |  notional 2000000
      |  rate 0.035
      |  spread 0.0005
      |  strike 0.04
      |  maturity 3
      |  freq 4
      |  volatility 0.20
      |}
      |
      |instrument mbs MbsA {
      |  notional 500000
      |  rate 0.04
      |  spread 0.001
      |  wac 0.05
      |  wam 300
      |  prepayCurve ramp 0.02 0.06 24
      |}
      |
      |instrument fxforward FxA {
      |  notional 1000000
      |  domesticRate 0.02
      |  fxRate 1.25
      |  foreignRate 0.01
      |  maturity 1
      |}
      |""".stripMargin

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    for {
      args <- ZIOAppArgs.getArgs.map(_.toList)
      config = CliConfig.fromArgs(args)
      dsl <- loadDsl(config.inputPath)
      result <- Parser.parseProgram(dsl) match {
        case Left(err) =>
          ZIO.succeed(println(s"Parse error: $err"))
        case Right(program) =>
          val dag = DslCompiler.build(program)
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
    } yield result
  }

  private def graphvizAvailable(): Boolean = {
    try {
      Seq("dot", "-V").!(ProcessLogger(_ => (), _ => ())) == 0
    } catch {
      case _: Throwable => false
    }
  }

  private def printResults(program: com.writhlang.dsl.Program, results: Map[String, Double]): Unit = {
    println("--- Scenario Prices ---")
    program.instruments.foreach { inst =>
      val baseId = DslCompiler.basePriceId(inst.id)
      val basePrice = results.getOrElse(baseId, Double.NaN)
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

  private case class CliConfig(
    inputPath: Option[java.nio.file.Path],
    dotPath: Option[java.nio.file.Path],
    pngPath: Option[java.nio.file.Path]
  )

  private object CliConfig {
    def fromArgs(args: List[String]): CliConfig = {
      val iter = args.iterator
      var input: Option[java.nio.file.Path] = None
      var dot: Option[java.nio.file.Path] = None
      var png: Option[java.nio.file.Path] = None

      while (iter.hasNext) {
        iter.next() match {
          case "--input" if iter.hasNext => input = Some(Paths.get(iter.next()))
          case "--dot" if iter.hasNext => dot = Some(Paths.get(iter.next()))
          case "--png" if iter.hasNext => png = Some(Paths.get(iter.next()))
          case path if input.isEmpty => input = Some(Paths.get(path))
          case _ => ()
        }
      }

      CliConfig(input, dot, png)
    }
  }

  private def loadDsl(path: Option[java.nio.file.Path]): ZIO[Any, Throwable, String] = {
    path match {
      case Some(p) => ZIO.attempt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
      case None => ZIO.succeed(Main.defaultDsl)
    }
  }
}
