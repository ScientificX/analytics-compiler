package com.writhlang.compiler

import com.writhlang.compiler.frontend._
import com.writhlang.compiler.ast._
import com.writhlang.compiler.ir._
import com.writhlang.compiler.backend.gas.GasBackend

object Compiler {
  sealed trait CompilerError
  case class FrontendErr(msg: String) extends CompilerError

  // Compile source to GAS assembly text (pure API returning Either)
  def compileToAssembly(src: String): Either[CompilerError, String] = {
    try {
      Frontend.parse(src) match {
        case Left(err) => Left(FrontendErr(err.toString))
        case Right(program) =>
          // naive lowering: for each let generate mov into register, then print uses rdi
          // We'll allocate for simplicity rax/rdi sequentially
          var instrs = List.empty[Instr]
          var regValue: Map[String, String] = Map()
          var regCount = 0

          def newReg(): String = {
            regCount += 1
            // map 1->rdi, 2->rsi, else rax (simplified)
            regCount match {
              case 1 => "rdi"
              case 2 => "rsi"
              case _ => s"rax"
            }
          }

          program.stmts.foreach {
            case Let(name, IntLiteral(v)) =>
              val reg = newReg()
              regValue += (name -> reg)
              instrs ::= MovRegImm(reg, v)
            case Print(Var(name)) =>
              regValue.get(name) match {
                case Some(r) => instrs ::= MovRegReg("rdi", r); instrs ::= CallPrint()
                case None => // ignore
              }
            case _ => // ignore
          }

          val module = Module(instrs.reverse)
          Right(GasBackend.emit(module))
      }
    } catch {
      case e: Throwable => Left(FrontendErr(e.toString))
    }
  }
}
