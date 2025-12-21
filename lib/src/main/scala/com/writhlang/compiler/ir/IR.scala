package com.writhlang.compiler.ir

sealed trait Instr
case class MovRegImm(reg: String, imm: Long) extends Instr
case class MovRegReg(dst: String, src: String) extends Instr
case class AddRegImm(reg: String, imm: Long) extends Instr
case class CallPrint() extends Instr
case class Label(name: String) extends Instr

case class Module(instrs: List[Instr])

