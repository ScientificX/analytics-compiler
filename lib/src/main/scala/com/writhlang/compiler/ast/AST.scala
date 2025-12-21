package com.writhlang.compiler.ast

sealed trait Expr
case class IntLiteral(value: Long) extends Expr
case class Var(name: String) extends Expr
case class Let(name: String, value: Expr) extends Expr
case class Print(expr: Expr) extends Expr
case class Fn(params: List[String], body: Expr) extends Expr

case class Program(stmts: List[Expr])

