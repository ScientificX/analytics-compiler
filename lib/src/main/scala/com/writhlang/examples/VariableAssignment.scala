package com.writhlang.examples

import com.writhlang.interpreter.Interpreter

// This example is to demonstrate variable assignment in WrithLang
// This would be the first language feature that would be implemented in the interpreter

object VariableAssignment {

  def apply() : Unit = {
    val exampleAssignment: String =
      """
        |let x = 1;
        |let y = 5;
        |print(y)
        |""".stripMargin

    val result = Interpreter.compileToAssembly(exampleAssignment)
    result match {
      case Right(asm) => println("--- Asm ---\n" + asm)
      case Left(err)  => println("Compile error: " + err)
    }
  }

}
