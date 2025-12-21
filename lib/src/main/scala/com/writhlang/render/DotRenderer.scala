package com.writhlang.render

import com.writhlang.engine._

object DotRenderer {
  def toDot(dag: Dag): String = {
    val edges = dag.nodes.values.toList.flatMap { instr =>
      instr.deps.map { dep => s"  \"${dep}\" -> \"${instr.id}\";" }
    }

    val clusters = dag.nodes.values.toList.groupBy(instrumentKey).toList.flatMap {
      case (None, instrs) => instrs.map(renderNode)
      case (Some(instId), instrs) =>
        val body = instrs.map(renderNode).mkString("\n")
        List(
          s"  subgraph cluster_${sanitize(instId)} {",
          s"    label=\"${instId}\";",
          "    color=\"#9aa0a6\";",
          body,
          "  }"
        )
    }

    (
      List(
        "digraph WrithDag {",
        "  rankdir=LR;",
        "  node [shape=box style=filled fontname=\"Helvetica\"];"
      ) ++ clusters ++ edges :+ "}"
    ).mkString("\n")
  }

  private def instrumentKey(instr: Instruction): Option[String] = {
    val parts = instr.id.split(":").toList
    parts match {
      case "price" :: "base" :: inst :: Nil => Some(inst)
      case "price" :: "bump" :: inst :: _ => Some(inst)
      case "sensitivity" :: inst :: _ => Some(inst)
      case "price" :: inst :: _ => Some(inst)
      case _ => None
    }
  }

  private def renderNode(instr: Instruction): String = {
    val (label, color) = nodeLabel(instr)
    s"  \"${instr.id}\" [label=\"${label}\" fillcolor=\"${color}\"];"
  }

  private def nodeLabel(instr: Instruction): (String, String) = {
    instr.op match {
      case Const(_) => (s"${instr.id}\\nshock", "#fbbc04")
      case BondPrice(_, _, _) => (s"${instr.id}\\nprice", "#a7c7e7")
      case MortgagePrice(_, _, _, _) => (s"${instr.id}\\nprice", "#a7c7e7")
      case Sensitivity(_, _, _) => (s"${instr.id}\\nsensitivity", "#c8e6c9")
      case ScenarioPrice(_, _) => (s"${instr.id}\\nscenario", "#e0e0e0")
    }
  }

  private def sanitize(value: String): String = value.replaceAll("[^a-zA-Z0-9_]", "_")
}
