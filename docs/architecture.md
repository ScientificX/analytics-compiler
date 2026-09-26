# WrithLang Architecture

This document explains the internal design of the WrithLang compiler and execution engine. It complements [README.md](../README.md) and [dsl-reference.md](dsl-reference.md).

## 1. Overview

WrithLang turns a textual DSL (a recursive portfolio of instruments plus shocks) and a separate JSON market-data snapshot into numeric results and a visualization:

```
DSL source (.dsl)                 Market data (JSON)
   │  dsl.Parser.parseProgram         │  marketdata.MarketDataJson.parse
   ▼                                  ▼
dsl.Program ──────────────────── marketdata.MarketData
   │                                  │
   │  engine.DslCompiler.build(program, market, config)
   ▼
engine.Dag  (nodes are Instruction values)
   │  engine.Executor.run  (ZIO, level-parallel)
   ▼
Map[String, Double]  (node id → value)
   │  render.DotRenderer.toDot
   ▼
Graphviz DOT (optionally rendered to PNG by the CLI)
```

Market data is **external**: the DSL never contains quotes — instruments and shocks reference market objects by `RiskFactorKey`, and the quotes/surfaces/vectors/spots come from the JSON snapshot.

There are **two independent compiler systems**:

1. **The risk DSL + engine** (`dsl`, `engine`, `risk`, `marketdata`, `scenario`, `render`) — the primary, tested system described here.
2. **The native compiler stub** (`compiler`, `interpreter`, `examples`) — a toy `let`/`print` → x86-64 GAS backend; see [§8](#8-native-compiler-stub).

## 2. Modules

| Module | Type | Responsibility |
| ------ | ---- | -------------- |
| `lib`  | library | DSL parser/AST, DAG builder, bootstrapper, pricing, executor, renderer, native compiler stub. |
| `app`  | application | ZIO CLI (`com.writhlang.app.Main`); depends on `lib`. |

## 3. Package walkthrough (`com.writhlang`)

| Package | Key files | Role |
| --- | --- | --- |
| `dsl` | `Ast.scala`, `Parser.scala` | DSL AST and FastParse parser + validation. |
| `engine` | `DslCompiler.scala` | Translates `Program` + `MarketData` into a `Dag`. |
| | `Instruction.scala`, `Dag.scala` | Computation-node and graph model. |
| | `Executor.scala` | ZIO-based topological execution. |
| | `Pricing.scala` | Closed-form pricing consuming market-data objects. |
| `risk` | `KeyType.scala`, `RiskFactorKey.scala`, `ShiftType.scala` | Risk-factor taxonomy, canonical keys, shift types. |
| `marketdata` | `Curve.scala`, `MarketData.scala`, `VolSurface.scala`, `PrepayVector.scala`, `SpotQuote.scala`, `CorrelationMatrix.scala` | Market-data object types + snapshot. |
| | `DiscountCurveInterpolator.scala` | Log-linear discount-factor interpolation. |
| | `bootstrap/` (`ParInstrument`, `QuoteSet`, `Bootstrapper`) | Curve bootstrapping from quoted instruments. |
| | `MarketDataJson.scala` | JSON market-data loader (ujson). |
| `scenario` | `ShiftShape.scala`, `Scenario.scala`, `ScenarioGenerator.scala` | Shock shapes and scenario transformations. |
| | `ShiftScheme.scala` | `ShiftScheme` + `SensitivityConfig`. |
| | `par/CurveShiftParData.scala` | Pillar → par-instrument mapping. |
| | `time` | `DayCount.scala`, `Calendar.scala` | Day-count conventions, business-day calendar, and the theta horizon (`ThetaPeriod`). |

| `render` | `DotRenderer.scala` | Graphviz DOT emission. |
| `compiler` | `Compiler.scala` + subpackages | Native x86-64 GAS backend (stub). |
| `interpreter` | `Interpreter.scala` | Thin wrapper around `Compiler`. |
| `examples` | `VariableAssignment.scala` | Demo of the native compiler. |

## 4. DSL AST

The parser produces a `Program` with two parts:

- `shocks: List[Shock]` — each `Shock` has a `name` and a `List[ShockMove]`; a move is a `PointMove(key, shiftType, amount)` (bump one object) or a `ShapeMove(key, shape)` (transform a whole curve).
- `nodes: List[PortfolioNode]` — a recursive portfolio tree: `Portfolio(name, children)` or `InstrumentLeaf(spec)`.

Instrument specs carry trade terms (notional, coupon, strike, maturity, …) and **reference** market objects by `RiskFactorKey` (e.g. `BondSpec(..., discountCurve, creditCurve)`). Supporting types: `CurveShift` (`FlatShift`, `BucketShift`, `TwistShift`, `SineShift`).

`Parser.parseProgram` returns `Either[String, Program]`, validating each instrument and aggregating errors.

## 5. Engine: the computation DAG

### 5.1 Instruction model

`Instruction(id, op, deps)`; `Op` is a sealed trait:

| Op | Meaning |
| --- | --- |
| `Const(v)` | literal shock value (leaf) |
| `Price(instrument, market, elapsedYears)` | re-price under a market snapshot at an elapsed time offset (leaf) |
| `Theta(baseId, thetaEvalId)` | theta (time decay): aged revaluation minus base |
| `Carry(instrument, market, elapsedYears)` | cash carry (accrued coupon; leaf) |
| `DeltaCentral` / `DeltaForward` / `DeltaBackward` | first-order sensitivity (central `(up−down)/2h`, forward `(up−base)/h`, backward `(base−down)/h`) |
| `Gamma` | second-order `(up−2·base+down)/h²` (always central) |
| `CrossGamma` | mixed second-order |
| `LinearScenario` / `QuadraticScenario` | Taylor scenario prices |

### 5.2 Node ids

Deterministic ids encode type, instrument, and factor canonical key:

| Pattern | Meaning |
| --- | --- |
| `shock:<name>:<key>` | shock literal |
| `price:base:<inst>` | base price |
| `price:bump:<inst>:<key>:<dir>` | one-factor bumped price |
| `price:cross:<inst>:<k1>:<k2>` | two-factor bumped price |
| `greek:delta:<inst>:<key>` / `greek:gamma:<inst>:<key>` | Greeks |
| `greek:cross:<inst>:<k1>:<k2>` | cross-gamma |
| `price:linear:<inst>:<shock>` / `price:quad:<inst>:<shock>` / `price:full:<inst>:<shock>` | scenario prices |
| `price:thetaeval:<inst>` | aged revaluation (leaf) |
| `price:theta:<inst>` / `price:carry:<inst>` | theta (time decay) / cash carry |

### 5.3 DAG construction

`DslCompiler.build(program, market, config)` flattens the portfolio tree and, per instrument, emits a base price, per-factor up/down bumped prices with delta/gamma, cross-gammas for every pair of factors, and per-shock full/linear/quadratic scenario nodes.

**Risk factors are per-pillar**: for a quote-backed curve, each par instrument is its own `RiskFactorKey` (`DiscountCurve:EUR:2Y`). A bump is a **par-conversion** — bump that quote and re-bootstrap — via `ParQuoteShift`.

### 5.4 Topological execution

`Dag.levels` does a Kahn-style topological sort; `Executor.run` processes levels sequentially and evaluates each level's nodes in parallel (`ZIO.foreachPar`), accumulating into a `Ref[Map[String, Double]]`.

## 6. Market data & bootstrap

`MarketData` holds keyed market objects: `curves` (bootstrapped `Curve`), `curveQuotes` (the `QuoteSet` inputs), `volSurfaces`, `prepayVectors`, `fxSpots`, `correlations`, and a valuation date `asOf: LocalDate`. `MarketDataJson.parse` builds it from JSON (an optional `"asOf"` key sets the date).

`Bootstrapper.bootstrap(quotes)` builds a `BootstrappedCurve` sequentially (deposits → futures → swaps), pinning one discount factor per instrument, with log-linear discount-factor interpolation between pillars (`DiscountCurveInterpolator`).

## 7. Pricing model notes

All prices are closed-form present values. Each instrument consumes market-data objects resolved from its key references; a shock transforms the market snapshot (quotes → re-bootstrap), never a scalar inside `Pricing`.

Stage 3 adds the **time dimension**: `Pricing.price(instrument, market, elapsedYears)` shortens every time-to-cashflow tenor by `elapsedYears` (the "instrument rolls down" theta mechanism), and `Pricing.carry(...)` returns the accrued coupon (bond) over the period. The `SensitivityConfig.thetaPeriod` (a business-day `ThetaPeriod`) sets the horizon.

| Instrument | Model |
| --- | --- |
| `bond` | sum of discounted fixed coupons + principal |
| `mortgage` / `mbs` | monthly amortization with prepayment (CPR → SMM) |
| `swap` | `fixedPV − floatPV` |
| `cap` | strip of Black-76 caplets |
| `swaption` | Black-76 on the forward swap rate |
| `fxforward` | covered-interest-parity forward value |

## 8. Rendering

`DotRenderer.toDot(dag)` emits a left-to-right `digraph`, colored by `Op` type and clustered per instrument. The CLI writes the DOT file and, if Graphviz `dot` is on `PATH`, renders a PNG.

## 9. Native compiler stub

A separate, early-stage path under `com.writhlang.compiler` compiles a tiny `let`/`print` language to x86-64 GAS assembly. It is **not** wired into the CLI and has no tests.

## 10. Error handling

- **Parsing/validation** — `Parser.parseProgram` returns `Either[String, Program]`.
- **Market data** — `MarketDataJson.parse` and `Bootstrapper.bootstrap` return `Either[String, _]`.
- **Execution** — `Executor.run` returns `IO[ExecutionError, Map[String, Double]]`.
- **CLI** — `Main` maps failures to human-readable messages.

