# WrithLang Low-Level Design

This document is the **low-level design reference**. It documents, component by component, the **public API** (types, functions, signatures), the **shapes of the data** flowing between components, and — for software engineers with no finance background — the **plain-language meaning** of every pricing concept and formula. It complements [architecture.md](architecture.md) (the pipeline and data flow) and [dsl-reference.md](dsl-reference.md) (the DSL surface grammar). Finance terms are defined in place or in the finance glossary in [AGENTS.md](../AGENTS.md).

## 1. Conventions for reading this document

- **Rates are decimals, not percentages.** `0.04` means 4%. A rate is an *annual* decimal unless the name says otherwise (`rateMonthly` = per-month).
- **Time bases are explicit in names.** `maturityYears`, `expiryYears`, `tenorYears` are years; `termMonths`, `wamMonths`, `rampMonths` are months.
- **Effect separation.** Pure functions return `Either[String, A]`; effectful/parallel work returns a ZIO `IO[E, A]`. The parser and compiler are pure; the executor is effectful.
- **"API structure"** means the public case classes / sealed traits (the input & output *shapes*), the public functions with their signatures, and how they map together.
- **Sign convention.** A *positive* shock moves a factor up (rate up, spread up, volatility up, prepayment up, FX spot up). Whether that raises or lowers a price depends on the instrument (documented per model in §4.6).

## 2. The pipeline at a glance

| Stage | Component | Input | Output |
| ----- | --------- | ----- | ------ |
| Parse | `dsl.Parser.parseProgram` | `String` (DSL source) | `Either[String, dsl.Program]` |
| Compile | `engine.DslCompiler.build` | `dsl.Program` | `engine.Dag` |
| Execute | `engine.Executor.run` | `engine.Dag` | `zio.IO[ExecutionError, Map[String, Double]]` |
| Render | `render.DotRenderer.toDot` | `engine.Dag` | `String` (Graphviz DOT) |

The execution result is a `Map[String, Double]` keyed by **node id** (see §9): one number per shock literal, price, Greek, or scenario price.

## 3. `com.writhlang.dsl` — AST and parser

Source: `dsl/Ast.scala`, `dsl/Parser.scala`.

### 3.1 AST (`Ast.scala`)

The AST is a set of immutable case classes that mirror the DSL; there is no logic, only shapes.

**Curve shifts** — how a discount curve is deformed by a shock:

```scala
sealed trait CurveShift
case object NoCurveShift        extends CurveShift  // no change
case class FlatShift(amount: Double) extends CurveShift                 // +amount at every tenor
case class BucketShift(tenorYears: Double, amount: Double) extends CurveShift  // local bump, width 1y
case class TwistShift(shortAmount: Double, longAmount: Double, pivotYears: Double) extends CurveShift
```

Finance meaning: a *curve shift* moves interest rates of different maturities by different amounts. `parallel` moves all maturities equally; `bucket` moves only rates near one tenor (a tent that fades to zero one year away); `twist` rotates the curve around a pivot maturity.

**Prepayment curves**:

```scala
sealed trait PrepayCurve
case class FlatPrepay(cpr: Double) extends PrepayCurve
case class RampPrepay(startCpr: Double, endCpr: Double, rampMonths: Int) extends PrepayCurve
```

**Instrument specs** (sealed `InstrumentSpec`, each with `id: String`):

| Case class | Fields | Finance meaning |
| ---------- | ------ | --------------- |
| `BondSpec` | `id`, `notional`, `coupon`, `maturityYears: Int`, `rate`, `spread`, `couponFreq: Int` | Fixed-coupon bond. |
| `MortgageSpec` | `id`, `notional`, `rate`, `spread`, `termMonths: Int`, `prepayCurve` | Amortizing mortgage loan. |
| `MbsPoolSpec` | `id`, `notional`, `rate`, `spread`, `wac`, `wamMonths: Int`, `prepayCurve` | Pass-through MBS pool (WAC/WAM). |
| `SwapSpec` | `id`, `notional`, `rate`, `spread`, `fixedRate`, `maturityYears: Int`, `freq: Int` | Interest-rate swap. |
| `CapSpec` | `id`, `notional`, `rate`, `spread`, `strike`, `maturityYears: Int`, `freq: Int`, `volatility` | Interest-rate cap. |
| `SwaptionSpec` | `id`, `notional`, `rate`, `spread`, `strike`, `expiryYears: Double`, `swapMaturityYears: Int`, `freq: Int`, `volatility`, `isPayer: Boolean` | Option to enter a swap. |
| `FxForwardSpec` | `id`, `notional`, `domesticRate`, `fxRate`, `foreignRate`, `maturityYears: Double` | FX forward. |

Field-by-field units and meaning (all `Double` unless noted):

- `notional` — the face amount cashflows are computed on.
- `coupon` — annual interest, decimal (`0.05` = 5%).
- `couponFreq` / `freq` — payments per year.
- `maturityYears` — years until final cashflow.
- `rate` — base discount rate, annual decimal.
- `spread` — extra yield for credit/liquidity risk, annual decimal; added to `rate`.
- `termMonths` / `wamMonths` — months until final cashflow.
- `wac` / `wam` — weighted-average coupon / weighted-average maturity of a mortgage pool.
- `strike` — the option's exercise rate.
- `volatility` — annualised volatility of rates, input to Black's model.
- `expiryYears` — time until the swaption can be exercised.
- `swapMaturityYears` — tenor of the swap the swaption delivers.
- `isPayer` — `true` = payer swaption (right to pay fixed), `false` = receiver.
- `domesticRate` / `foreignRate` — the two interest rates in an FX forward.
- `fxRate` — spot exchange rate (domestic per foreign, e.g. USD per EUR).

**Shock and Program**:

```scala
case class Shock(name: String, factors: Map[String, Double], curve: List[CurveShift])
case class Program(shocks: List[Shock], instruments: List[InstrumentSpec])
```

A `Shock` is a named bundle of market moves: `factors` maps a scalar factor word (`rate`, `spread`, `prepay`, `volatility`, `fx`) to its move; `curve` holds any curve shifts. A `Program` is the whole document.

### 3.2 Parser (`Parser.scala`)

```scala
object Parser {
  def parseProgram(input: String): Either[String, Program]
  // also: validateInstrument(...) — per-instrument field checks
}
```

`parseProgram` parses the DSL text (FastParse) then validates every instrument, aggregating errors into a single `; `-separated `Left` message. Input shape: raw DSL text (grammar in [dsl-reference.md](dsl-reference.md)). Output shape: `Either[String, Program]`.

## 4. `com.writhlang.engine` — the computation engine

### 4.1 Instruction model (`Instruction.scala`)

A computation DAG node is `Instruction(id, op, deps)`:

```scala
case class Instruction(id: String, op: Op, deps: List[String])
```

- `id` — a deterministic string key (see §9).
- `deps` — ids of nodes that must be evaluated first.
- `op` — the operation to perform.

`Op` is a sealed trait; each case is either a leaf (no deps) or a derived value (whose inputs are listed in `deps`):

```scala
sealed trait Op
case class Price(instrument: InstrumentSpec, curveShifts: List[CurveShift], scalar: Map[String, Double]) extends Op
case class Const(value: Double) extends Op
case class Delta(baseId: String, upId: String, downId: String, bump: Double) extends Op
case class Gamma(baseId: String, upId: String, downId: String, bump: Double) extends Op
case class CrossGamma(baseId: String, upIId: String, upJId: String, upIJId: String, bumpI: Double, bumpJ: Double) extends Op
case class LinearScenario(baseId: String, terms: List[(String, String)]) extends Op
case class QuadraticScenario(baseId: String, linear: List[(String, String)], gamma: List[(String, String)], cross: List[(String, String, String)]) extends Op
```

Finance meaning of each `Op`:

| Op | Formula | Plain-language meaning |
| -- | ------- | ---------------------- |
| `Const(v)` | `v` | A literal shock value (leaf). |
| `Price(...)` | full repricing | Re-price an instrument under a market state (leaf). |
| `Delta` | `(up − down) / (2·bump)` | Central-difference estimate of the *first* derivative: how fast price changes per unit of a risk factor. |
| `Gamma` | `(up − 2·base + down) / bump²` | Second derivative: how fast the delta itself changes (convexity). |
| `CrossGamma` | `(upIJ − upI − upJ + base) / (bumpI·bumpJ)` | Mixed second derivative between two factors. |
| `LinearScenario` | `base + Σ (delta_i · shock_i)` | First-order (linear Taylor) approximation of a shocked price. |
| `QuadraticScenario` | linear + `½ Σ gamma_i·shock_i²` + `Σ cross_ij·shock_i·shock_j` | Second-order (quadratic Taylor) approximation. |

`Delta` and `Gamma` are the "Greeks" — sensitivities used in risk management. A delta of `X` means "if the factor moves up by a full unit, the price moves by about `X`."

### 4.2 DAG (`Dag.scala`)

```scala
case class Dag(nodes: Map[String, Instruction]) {
  def levels: Either[String, List[List[Instruction]]]
}
```

`levels` does a Kahn-style topological sort: `Left("cycle detected in instruction DAG")` if there is a cycle, otherwise an ordered list of *levels*, where each level is a list of nodes whose dependencies are all satisfied by earlier levels. Nodes within a level are independent and can run in parallel.

### 4.3 DAG construction (`DslCompiler.scala`)

```scala
object DslCompiler {
  def build(program: Program): Dag
  // deterministic node-id helpers (see §9), plus factorsFor / bumpFor / perturb / shockMarket
}
```

`build` produces, for every shock, a `Const` node per scalar factor; and for every instrument:

1. a **base price** node (`Price` with no shifts),
2. **Greek nodes**: for each risk factor, up/down bumped prices plus `Delta`/`Gamma`; plus `CrossGamma` for every unordered pair of factors,
3. **scenario nodes**: for each shock, a full reprice, a linear Taylor, and a quadratic Taylor.

Risk factors per instrument (`factorsFor`):

| Instrument | Risk factors |
| ---------- | ------------ |
| bond, swap | `rate`, `spread` |
| mortgage, mbs | `rate`, `spread`, `prepay` |
| cap, swaption | `rate`, `spread`, `volatility` |
| fxforward | `rate`, `fx` |

Finite-difference bump sizes (`bumpFor`): rate/spread `1e-4` (1 basis point), prepay `1e-2`, volatility `1e-3`, fx `1e-3`. A basis point (bp) is one-hundredth of a percent = `1e-4` in decimal.

`perturb(key, amount)` translates a factor move to the right place: `rate`/`spread` become a `FlatShift` on the discount curve; `prepay`/`volatility`/`fx` become scalar entries consumed by `Pricing.price`.

`shockMarket(shock)` builds the full market state for exact repricing: `rate`/`spread` → flat curve shifts; `prepay`/`volatility`/`fx` → scalar map; plus any `curve` shifts from the shock.

### 4.4 Executor (`Executor.scala`)

```scala
object Executor {
  sealed trait ExecutionError { def message: String }
  case class MissingDependency(node: String, dep: String) extends ExecutionError
  case class GraphError(reason: String) extends ExecutionError

  def run(dag: Dag): zio.IO[ExecutionError, Map[String, Double]]
}
```

`run` topologically sorts the DAG, then processes each level in sequence, evaluating the nodes *within* a level in parallel (`ZIO.foreachPar`) and accumulating results in a `Ref[Map[String, Double]]`. Each `Op` is evaluated by `evalInstruction`, reading dependency values from the accumulated map. A missing dependency yields `MissingDependency`; an unresolvable graph yields `GraphError`.

### 4.5 Discount curve (`Curve.scala`)

```scala
object Curve {
  def shiftAt(shift: CurveShift, tenorYears: Double): Double
}

case class DiscountCurve(baseFlat: Double, shifts: List[CurveShift]) {
  def rateAt(tenorYears: Double): Double   // baseFlat + Σ shiftAt(...)
  def df(tenorYears: Double): Double       // exp(−rateAt(tenorYears) · tenorYears)
}

object DiscountCurve {
  def flat(rate: Double): DiscountCurve
}
```

Finance meaning: a **discount curve** is the function that turns a future time `t` into a **discount factor** `df(t) = e^(−r(t)·t)`. A discount factor is "how much $1 at time `t` is worth today" (continuous compounding). `rateAt` is the annual rate at a given tenor; `df` is the factor. This single building block is used by every pricing model in §4.6.

### 4.6 Pricing (`Pricing.scala`)

```scala
object Pricing {
  def baseRate(instrument: InstrumentSpec): Double
  def price(instrument: InstrumentSpec, curveShifts: List[CurveShift], scalar: Map[String, Double]): Double
  def bondPrice(spec: BondSpec, curve: DiscountCurve): Double
  def mortgagePrice(spec: MortgageSpec, curve: DiscountCurve, prepayShift: Double): Double
  def mbsPrice(spec: MbsPoolSpec, curve: DiscountCurve, prepayShift: Double): Double
  def swapPrice(spec: SwapSpec, curve: DiscountCurve): Double
  def capPrice(spec: CapSpec, curve: DiscountCurve, volShift: Double): Double
  def swaptionPrice(spec: SwaptionSpec, curve: DiscountCurve, volShift: Double): Double
  def fxForwardPrice(spec: FxForwardSpec, curve: DiscountCurve, fxShift: Double): Double
  def expandPrepayCurve(curve: PrepayCurve, termMonths: Int): Vector[Double]
  // private: forwardRate, blackCall, blackPut, clamp, amortisingPrice
}
```

`price` is the unified entry point the executor uses: it builds a `DiscountCurve(baseRate(instrument), curveShifts)` and dispatches to the per-instrument function, passing the relevant scalar shift.

Each model, in plain language:

**Bond (`bondPrice`).** A bond pays a fixed *coupon* every `1/freq` years and returns the *notional* (face value) at maturity. Its value is the sum of each cashflow times its discount factor: `PV = Σ CF(t) · df(t)`. "Present value" is today's worth of future cash: money later is worth less than money now, because money now can earn interest.

**Mortgage (`mortgagePrice`) and MBS (`mbsPrice`).** Both call `amortisingPrice`. A mortgage is a loan repaid in equal monthly *annuity payments*; each payment is part interest and part principal. The constant payment that exactly repays `notional` at note rate `r` over `n` months is the **annuity payment**:

```text
payment = notional · (r/12) / (1 − (1 + r/12)^(−n))
```

Each month the borrower may also *prepay* (repay principal early). Prepayment speed is the **CPR** (Conditional Prepayment Rate, annualised) converted to **SMM** (Single Monthly Mortality, monthly) via `SMM = 1 − (1 − CPR)^(1/12)`. The prepaid amount is `SMM` applied to the principal remaining after the scheduled payment. Price = sum of monthly cashflows (interest + scheduled principal + prepayment), each discounted. The MBS (mortgage-backed security) uses the pool's weighted-average coupon (`wac`) as the note rate and weighted-average maturity (`wam`) as the term.

**Swap (`swapPrice`).** A swap exchanges a *fixed* leg for a *floating* leg: value = fixed-leg PV − floating-leg PV. The fixed leg is the same as bond coupons at `fixedRate`. The floating leg is `notional · (1 − df(T))` — receiving floating interest and repaying principal at the end is worth `notional − notional·df(T)`. A positive value means the fixed leg is worth more than the floating leg.

**Cap (`capPrice`).** A cap is a strip of **caplets**; each caplet pays when a floating rate exceeds `strike`. Each caplet uses **Black's model (Black-76)** on the forward rate, discounted back: `notional · accrual · blackCall(forward, strike, vol, t) · df(t1)`. Black-76 is the standard log-normal model for interest-rate options. Because a cap pays out, its value rises when volatility rises.

**Swaption (`swaptionPrice`).** An option to enter a swap at a fixed `strike`. It first computes the **forward swap rate** — the fixed rate that makes the swap worth zero today — then prices the option with Black-76 on that rate. A *payer* swaption uses `blackCall`; a *receiver* uses `blackPut`. The payoff is scaled by the **annuity** (the present value of the fixed-leg accruals).

**FX forward (`fxForwardPrice`).** An agreement to exchange currencies at a future date, priced via **covered interest parity**: `notional · (spot·(1+fxShift) · dfForeign − fxRate · dfDomestic)`. Intuition: holding foreign currency earns the foreign rate while the domestic equivalent earns the domestic rate; the forward value is the difference in their discounted values.

**Supporting helpers.** `forwardRate(curve, t0, t1, accrual)` derives the forward rate between two dates from discount factors: `(df(t0)/df(t1) − 1) / accrual`. `blackCall`/`blackPut` implement Black-76 (with `d1`, `d2` and the normal CDF). `expandPrepayCurve` turns a `FlatPrepay`/`RampPrepay` into a per-month `Vector[Double]` of CPR. `clamp` bounds a value to `[min, max]`.

## 5. `com.writhlang.risk` — risk-factor taxonomy

Source: `risk/KeyType.scala`, `risk/RiskFactorKey.scala`, `risk/LegacyRiskFactors.scala`. This package gives risk factors a precise, round-trippable identity; the DSL's five legacy words map onto it.

**`KeyType`** — the taxonomy of *kinds* of market risk (a sealed trait of case objects). Only a few are exercised today (noted in the code); the rest reserve the taxonomy for future asset classes:

```scala
sealed trait KeyType { def name: String }
object KeyType {
  case object DiscountCurve; case object IndexCurve; case object YieldCurve
  case object CreditCurve; case object FxSpot; case object FxVolatility
  case object CapFloorVolatility; case object SwaptionVolatility
  case object EquitySpot; case object EquityVolatility; case object DividendYield
  case object InflationZero; case object InflationYoY
  case object CommodityCurve; case object CommodityVolatility
  case object Correlation; case object Prepay; case object Security
  val all: List[KeyType]; def fromName(name: String): Option[KeyType]
}
```

**`Tenor`** — a time in years, canonical form `5Y`:

```scala
final case class Tenor(years: Double) { def canonical: String }
object Tenor { def renderYears(years: Double): String; /* implicit .Y syntax */ }
```

**`Strike`** — an option strike coordinate: `ATM` ("at-the-money") or `StrikeValue(rate: Double)`.

**`Dimension`** — typed coordinates of a risk key: `TenorDim`, `ExpiryDim`, `SwapTenorDim`, `StrikeDim`, `LossLevelDim`.

**`RiskFactorKey`** — a fully-addressed risk factor:

```scala
final case class RiskFactorKey(
  keyType: KeyType, name: String,
  tenor: Option[Tenor] = None, expiry: Option[Tenor] = None,
  swapTenor: Option[Tenor] = None, strike: Option[Strike] = None,
  lossLevel: Option[Double] = None
) {
  def dimensions: Vector[Dimension]
  def canonical: String   // e.g. "DiscountCurve:EUR:5Y", "SwaptionVolatility:EUR:5Yx10Y:ATM"
}
object RiskFactorKey {
  def renderDecimal(value: Double): String
  def parse(canonical: String): Either[String, RiskFactorKey]
}
```

Two keys are equal iff their `canonical` strings are equal, which is what makes them safe to embed in DAG node ids and use as map keys.

**`LegacyRiskFactors`** maps the five DSL words to fixed keys:

| DSL word | `RiskFactorKey` |
| -------- | --------------- |
| `rate` | `DiscountCurve:EUR` |
| `spread` | `CreditCurve:EUR` |
| `prepay` | `Prepay:EUR` |
| `volatility` | `SwaptionVolatility:EUR` |
| `fx` | `FxSpot:EURUSD` |

It provides `fromWord(word): Option[RiskFactorKey]` and the inverse `wordFor(key): Option[String]`, used by `DslCompiler` to decide which keys a shock touches.

## 6. `com.writhlang.render` — DOT rendering

```scala
object DotRenderer {
  def toDot(dag: Dag): String
}
```

`toDot` emits a left-to-right Graphviz `digraph`. Edges point from dependencies to dependents. Nodes are colored by `Op` type (shock = yellow, price = blue, delta = green, gamma = teal, cross = purple, linear = grey, quadratic = orange) and grouped into subgraph clusters per instrument, keyed by the instrument id embedded in the node id.

## 7. `com.writhlang.app` — the CLI

```scala
object Main extends ZIOAppDefault {
  val defaultDsl: String            // built-in example program
  override def run: ZIO[/* ... */, Any, Any]
  // private: CliConfig (--input/--dot/--png), graphvizAvailable, printResults, loadDsl, format
}
```

Flow: read args → load DSL (file or `defaultDsl`) → `Parser.parseProgram` → `DslCompiler.build` → `DotRenderer.toDot` → write DOT (and PNG if Graphviz `dot` is on `PATH`) → `Executor.run` → print per-instrument base price and per-shock `full`/`linear`/`quadratic` prices. CLI flags: `--input <file>`, `--dot <file>`, `--png <file>` (a bare positional is treated as input).

## 8. Native compiler stub (`compiler`, `interpreter`, `examples`)

A separate, early-stage path that compiles a tiny `let`/`print` language to x86-64 GAS assembly. It is **not** wired into the CLI and has **no tests**; it is kept distinct from the risk DSL:

- `compiler.frontend.Frontend.parse` — parses `let <id> = <int>;` and `print(<id>)`.
- `compiler.ast.AST` — `IntLiteral`, `Var`, `Let`, `Print`, `Fn`, `Program`.
- `compiler.ir.IR` — register instructions (`MovRegImm`, `MovRegReg`, `AddRegImm`, `CallPrint`, `Label`) and `Module`.
- `compiler.backend.gas.GasBackend.emit` — emits a `main:` routine plus a `print_long` libc `printf` helper.
- `interpreter.Interpreter` — `compileToAssembly(src): Either[CompileError, String]` and a ZIO wrapper `compileToAssemblyZIO`.

## 9. Node-id reference

`DslCompiler` generates deterministic node ids that encode type, instrument, and factor. The canonical factor string may itself contain `:` (e.g. `DiscountCurve:EUR`), so ids are parsed positionally where needed.

| Pattern | Meaning |
| ------- | ------- |
| `shock:<name>:<key>` | scalar shock literal (`Const`) |
| `price:base:<inst>` | unshocked price |
| `price:bump:<inst>:<key>:<dir>` | one-factor bumped price (`dir` = `up`/`down`) |
| `price:cross:<inst>:<k1>:<k2>` | two-factor bumped price |
| `greek:delta:<inst>:<key>` / `greek:gamma:<inst>:<key>` | delta / gamma |
| `greek:cross:<inst>:<k1>:<k2>` | cross-gamma |
| `price:linear:<inst>:<shock>` / `price:quad:<inst>:<shock>` | linear / quadratic Taylor scenario prices |
| `price:full:<inst>:<shock>` | exact re-priced scenario value |

The execution result `Map[String, Double]` is keyed by these ids; the CLI reads `price:base:<id>`, `price:full:<id>:<shock>`, `price:linear:<id>:<shock>`, and `price:quad:<id>:<shock>` to print results.
