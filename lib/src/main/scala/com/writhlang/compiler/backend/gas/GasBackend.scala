package com.writhlang.compiler.backend.gas

import com.writhlang.compiler.ir._

object GasBackend {
  // Emits a minimal GAS-style x86-64 assembly string for a Module
  def emit(module: Module): String = {
    val header = ".text\n.globl main\nmain:\n"
    val body = module.instrs.map {
      case MovRegImm(reg, imm) => s"    movq $$${imm}, %${reg}\n"
      case MovRegReg(dst, src) => s"    movq %${src}, %${dst}\n"
      case AddRegImm(reg, imm) => s"    addq $$${imm}, %${reg}\n"
      case CallPrint() =>
        // call a helper which will print value in rdi
        s"    call print_long\n"
      case Label(name) => s"${name}:\n"
      case other => "    # unhandled instr\n"
    }.mkString("")

    val footer = "    ret\n\n" +
      "// helper to print a 64-bit integer using libc (printf)\n" +
      ".extern printf\n" +
      ".section .rodata\n" +
      "fmt: .string \"%ld\\n\"\n" +
      ".text\n" +
      "print_long:\n" +
      "    pushq %rbp\n" +
      "    movq %rsp, %rbp\n" +
      "    movq %rdi, %rsi\n" +
      "    leaq fmt(%rip), %rdi\n" +
      "    xor %rax, %rax\n" +
      "    call printf\n" +
      "    popq %rbp\n" +
      "    ret\n"

    header + body + footer
  }
}

