package com.writhlang.compiler.frontend

import com.writhlang.compiler.ast._

object Frontend {
  sealed trait FrontendError
  case class ParseError(msg: String) extends FrontendError

  // Very small stub parser — in future replace with FastParse implementation
  def parse(src: String): Either[FrontendError, Program] = {
    // naive parser for the tiny example: parse lines "let <id> = <int>;" and "print(<id>)"
    val lines = src.split(";|\n").map(_.trim).filter(_.nonEmpty)
    val stmts = lines.toList.flatMap {
      case s if s.startsWith("let ") =>
        // let x = 1
        val parts = s.stripPrefix("let ").split("=").map(_.trim)
        if (parts.length == 2) Some(Let(parts(0), IntLiteral(parts(1).replaceAll(";","").toLong))) else None
      case s if s.startsWith("print(") && s.endsWith(")") =>
        val name = s.stripPrefix("print(").stripSuffix(")")
        Some(Print(Var(name)))
      case _ => None
    }
    Right(Program(stmts))
  }
}

