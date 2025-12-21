package com.writhlang.engine

case class Dag(nodes: Map[String, Instruction]) {
  def levels: Either[String, List[List[Instruction]]] = {
    val deps = nodes.view.mapValues(_.deps.toSet).toMap
    val reverse = nodes.values.flatMap { instr =>
      instr.deps.map(dep => dep -> instr.id)
    }.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap

    var remaining = deps
    var ready = remaining.collect { case (id, ds) if ds.isEmpty => id }.toSet
    var result = List.empty[List[Instruction]]

    while (ready.nonEmpty) {
      val level = ready.toList.sorted
      result ::= level.map(nodes)
      val nextReady = scala.collection.mutable.Set.empty[String]

      level.foreach { id =>
        remaining -= id
        reverse.getOrElse(id, Set.empty).foreach { child =>
          val updated = remaining.getOrElse(child, Set.empty) - id
          remaining = remaining.updated(child, updated)
          if (updated.isEmpty) nextReady += child
        }
      }

      ready = nextReady.toSet
    }

    if (remaining.nonEmpty) Left("cycle detected in instruction DAG")
    else Right(result.reverse)
  }
}
