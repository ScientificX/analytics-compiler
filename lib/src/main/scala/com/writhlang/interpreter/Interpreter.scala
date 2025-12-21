package com.writhlang.interpreter

import com.writhlang.compiler.Compiler
import zio._

class Interpreter {

}

object Interpreter {
  def apply(): Interpreter = new Interpreter()

  sealed trait CompileError
  case class GenericCompileError(msg: String) extends CompileError

  // Pure API: delegate to Compiler and return Either
  def compileToAssembly(src: String): Either[CompileError, String] =
    Compiler.compileToAssembly(src).left.map(e => GenericCompileError(e.toString))

  // ZIO wrapper: lift the pure Either into a zio.IO so callers inside ZIO can compose it
  def compileToAssemblyZIO(src: String): IO[CompileError, String] =
    ZIO.fromEither(compileToAssembly(src))
}
