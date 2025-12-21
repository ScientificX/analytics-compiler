package com.writhlang.engine

import zio._

object Executor {
  sealed trait ExecutionError extends  Throwable {
    def message: String
  }
  case class MissingDependency(node: String, dep: String) extends ExecutionError {
    val message = s"missing dependency ${dep} for node ${node}"
  }
  case class GraphError(reason: String) extends ExecutionError {
    val message = reason
  }

  def run(dag: Dag): IO[ExecutionError, Map[String, Double]] = {
    for {
      levels <- ZIO.fromEither(dag.levels.left.map(GraphError))
      ref <- Ref.make(Map.empty[String, Double])
      _ <- ZIO.foreach(levels) { level =>
        for {
          current <- ref.get
          computed <- ZIO.foreachPar(level) { instr =>
            ZIO.fromEither(evalInstruction(instr, current)).map(value => instr.id -> value)
          }
          _ <- ref.update(_ ++ computed.toMap)
        } yield ()
      }
      result <- ref.get
    } yield result
  }

  private def evalInstruction(instr: Instruction, values: Map[String, Double]): Either[ExecutionError, Double] = {
    def requireValue(id: String): Either[ExecutionError, Double] =
      values.get(id).toRight(MissingDependency(instr.id, id))

    instr.op match {
      case Const(value) => Right(value)
      case BondPrice(spec, rateShift, spreadShift) =>
        Right(Pricing.bondPrice(spec, rateShift, spreadShift))
      case MortgagePrice(spec, rateShift, spreadShift, prepayShift) =>
        Right(Pricing.mortgagePrice(spec, rateShift, spreadShift, prepayShift))
      case Sensitivity(baseId, bumpedId, bump) =>
        for {
          base <- requireValue(baseId)
          bumped <- requireValue(bumpedId)
        } yield (bumped - base) / bump
      case ScenarioPrice(baseId, terms) =>
        for {
          base <- requireValue(baseId)
          termValues <- collectTerms(instr.id, terms, values)
        } yield base + termValues.sum
    }
  }

  private def collectTerms(
    nodeId: String,
    terms: List[(String, String)],
    values: Map[String, Double]
  ): Either[ExecutionError, List[Double]] = {
    terms.foldLeft(Right(List.empty[Double]): Either[ExecutionError, List[Double]]) { (acc, term) =>
      for {
        list <- acc
        sens <- values.get(term._1).toRight(MissingDependency(nodeId, term._1))
        shock <- values.get(term._2).toRight(MissingDependency(nodeId, term._2))
      } yield list :+ (sens * shock)
    }
  }
}
