# WrithLang Architecture

This document explains the internal design of the WrithLang compiler and execution engine. It complements [README.md](../README.md) and [dsl-reference.md](dsl-reference.md).

## 1. Overview

WrithLang is organized as a pipeline that turns a textual DSL into numeric results and a visualization:

```
DSL source text
   │  com.writhlang.dsl.Parser.parseProgram  (FastParse)
   ▼
AST: com.writhlang.dsl.Program
   │  com.writhlang.engine.DslCompiler.build
   ▼
DAG: com.writhlang.engine.Dag  (nodes are Instruction values)
   │  com.writhlang.engine.Executor.run  (ZIO, level-parallel)
   ▼
Map[String, Double]  (node id → value)
   │  com.writhlang.render.DotRenderer.toDot
   ▼
Graphviz DOT (optionally rendered to PNG by the CLI)
```

There are **two independent compiler systems** in the codebase, and it is important not to confuse them:

1. **The risk DSL + engine** (`dsl`, `engine`, `render`) — the primary, tested system described here.
2. **The native compiler stub** (`compiler`, `interpreter`, `examples`) — an early-stage toy that emits x86-64 GAS assembly; described in [§8](#8-native-compiler-stub).

## 2. Modules

| Module | Type | Responsibility |
| ------ | ---- | -------------- |
| `lib`  | library | DSL parser, AST, DAG builder, pricing engine, executor, renderer, native compiler stub. |
| `app`  | application | ZIO CLI (`com.writhlang.app.Main`); depends on `lib`. |

## 3. Package walkthrough (`com.writhlang`)

| Package      | Key files            | Role |
| ------------ | -------------------- | ---- |
| `dsl`        | `Ast.scala`, `Parser.scala` | DSL AST and FastParse parser + validation. |
| `engine`     | `DslCompiler.scala`  | Translates `Program` into a `Dag` of computations. |
|              | `Instruction.scala`, `Dag.scala` | Computation-node and graph model. |
|              | `Executor.scala`     | ZIO-based topological execution. |
|              | `Pricing.scala`, `Curve.scala`, `Factors.scala` | Pricing math, discount curve, risk factors. |
| `render`     | `DotRenderer.scala`  | Graphviz DOT emission. |
| `compiler`   | `Compiler.scala` + subpackages | Native x86-64 GAS backend (stub). |
| `interpreter`| `Interpreter.scala`  | Thin wrapper around `Compiler`. |
| `examples`   | `VariableAssignment.scala` | Demo of the native compiler. |

## 4. DSL AST

The parser produces a `Program` with two lists:

- `shocks: List[Shock]` — each `Shock` has a `name`, a `Map[String, Double]` of scalar factor moves, and a `List[CurveShift]` of curve shifts.
- `instruments: List[InstrumentSpec]` — a sealed hierarchy of instrument specs (`BondSpec`, `MortgageSpec`, `MbsPoolSpec`, `SwapSpec`, `CapSpec`, `SwaptionSpec`, `FxForwardSpec`).

Supporting types:

- `CurveShift` — `NoCurveShift`, `FlatShift`, `BucketShift`, `TwistShift`.
- `PrepayCurve` — `FlatPrepay`, `RampPrepay`.

The parser (`Parser.parseProgram`) returns `Either[String, Program]`. It first parses to raw records, then validates each instrument (`validateInstrument`), aggregating errors.

## 5. Engine: the computation DAG

### 5.1 Instruction model

A computation node is an `Instruction(id, op, deps)`:

```scala
case class Instruction(id: String, op: Op, deps: List[String])
```

`id` is a structured string (see below). `deps` lists the node ids this node needs. `Op` is a sealed trait with these variants:

| Op | Meaning | Dependencies |
| -- | ------- | ------------ |
| `Const(v)` | Literal shock value. | none (leaf) |
| `Price(instrument, curveShifts, scalar)` | Re-price an instrument under a market state. | none (leaf) |
| `Delta(base, up, down, bump)` | `(up − down) / (2·bump)`. | base, up, down |
| `Gamma(base, up, down, bump)` | `(up − 2·base + down) / bump²`. | base, up, down |
| `CrossGamma(base, upI, upJ, upIJ, bI, bJ)` | `(upIJ − upI − upJ + base) / (bI·bJ)`. | base, upI, upJ, upIJ |
| `LinearScenario(base, terms)` | `base + Σ delta·shock`. | base + each delta/shock |
| `QuadraticScenario(base, lin, gam, cross)` | linear + ½Σ gamma·s² + Σ cross·sᵢ·sⱼ. | base + all terms |

### 5.2 Node ids

`DslCompiler` generates deterministic ids that encode type and instrument:

| Pattern | Meaning |
| ------- | ------- |
| `shock:<name>:<factor>` | shock factor literal |
| `price:base:<inst>` | unshocked price |
| `price:bump:<inst>:<factor>:<dir>` | one-factor bumped price |
| `price:cross:<inst>:<f1>:<f2>` | two-factor bumped price |
| `greek:delta:<inst>:<factor>` / `greek:gamma:<inst>:<factor>` | Greeks |
| `greek:cross:<inst>:<f1>:<f2>` | cross-gamma |
| `price:linear:<inst>:<shock>` / `price:quad:<inst>:<shock>` | Taylor scenario prices |
| `price:full:<inst>:<shock>` | exact re-priced value |

### 5.3 DAG construction

`DslCompiler.build` emits, for each instrument:

1. A **base price** node.
2. **Greek nodes**: per risk factor, up/down bumped prices and derived delta/gamma; cross-gammas for every unordered pair of factors.
3. **Scenario nodes**: for each shock, a full re-price plus linear and quadratic Taylor nodes.

Risk factors per instrument are defined in `DslCompiler.factorsFor`:

| Instrument | Factors |
| ---------- | ------- |
| bond, swap | `rate`, `spread` |
| mortgage, mbs | `rate`, `spread`, `prepay` |
| cap, swaption | `rate`, `spread`, `volatility` |
| fxforward | `rate`, `fx` |

### 5.4 Topological execution

`Dag.levels` performs a Kahn-style topological sort, returning either `Left("cycle detected …")` or an ordered list of levels (lists of independent nodes). `Executor.run` processes levels sequentially, evaluating each level's nodes in parallel (`ZIO.foreachPar`) and accumulating results into a `Ref[Map[String, Double]]`.

## 6. Pricing model notes

All prices are closed-form present values under a flat discount curve (`DiscountCurve`) built from a base rate plus tenor shifts.

| Instrument | Model |
| ---------- | ----- |
| `bond` | Sum of discounted fixed coupons + principal. |
| `mortgage` / `mbs` | Monthly amortization with prepayment (CPR → SMM); see `Pricing.amortisingPrice`. |
| `swap` | `fixedPV − floatPV`, float leg from discount factors. |
| `cap` | Sum of caplets priced with Black's formula on forward rates. |
| `swaption` | Black's formula on the forward swap rate (payer or receiver). |
| `fxforward` | Covered-interest-parity forward value. |

`Curve.shiftAt` evaluates a single `CurveShift` at a tenor; `DiscountCurve.rateAt` sums all shifts over the base flat rate, and `df` computes `exp(−rate·t)`.

Risk sensitivities are **finite differences** with fixed bumps (e.g., rate/spread `1e-4`, prepay `1e-2`, volatility `1e-3`, fx `1e-3`) defined at the top of `DslCompiler`.

## 7. Rendering

`DotRenderer.toDot(dag)` emits a left-to-right `digraph` where edges point from dependencies to dependents. Nodes are colored by `Op` type and grouped into subgraph clusters per instrument (clustering keys are derived from the structured node ids).

The CLI (`Main`) writes the DOT file and, if Graphviz `dot` is on `PATH`, renders it to PNG via `dot -Tpng`.

## 8. Native compiler stub

A separate, early-stage path under `com.writhlang.compiler` compiles a tiny `let`/`print` language to x86-64 GAS assembly:

- `frontend.Frontend.parse` — naive parser for `let <id> = <int>;` and `print(<id>)`.
- `ast.AST` — `IntLiteral`, `Var`, `Let`, `Print`, `Fn`, `Program`.
- `ir.IR` — register instructions (`MovRegImm`, `MovRegReg`, `AddRegImm`, `CallPrint`, `Label`) and `Module`.
- `backend.gas.GasBackend.emit` — emits a `main:` routine plus a `print_long` helper using libc `printf`.

`interpreter.Interpreter` exposes this as a pure `Either` API (`compileToAssembly`) and a ZIO wrapper (`compileToAssemblyZIO`). This path is **not** wired into the CLI and has no tests. The `WrithLang` class and the `Interpreter` class are empty scaffolding.

## 9. Error handling

- **Parsing/validation** — `Parser.parseProgram` returns `Either[String, Program]` with validation messages.
- **Execution** — `Executor.run` returns `IO[ExecutionError, Map[String, Double]]`; `ExecutionError` covers missing dependencies (`MissingDependency`) and graph-level failures (`GraphError`, e.g., a cycle).
- **CLI** — `Main` maps parse and execution failures to human-readable `println` messages.


