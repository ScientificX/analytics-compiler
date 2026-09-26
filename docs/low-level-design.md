# WrithLang Low-Level Design

This document is the **low-level design reference**: the public API (types, signatures), the shapes of data flowing between components, and — for software engineers with no finance background — the plain-language meaning of every pricing concept and formula. It complements [architecture.md](architecture.md) (the pipeline) and [dsl-reference.md](dsl-reference.md) (the DSL grammar). Finance terms are defined in place or in the glossary in [AGENTS.md](../AGENTS.md).

## 1. Conventions

- **Rates are decimals, not percentages.** `0.04` means 4%.
- **Time bases are explicit in names.** `maturityYears`/`expiryYears`/`tenorYears` are years; `termMonths`/`wamMonths` are months.
- **Effect separation.** Pure functions return `Either[String, A]`; the executor is effectful (`zio.IO`).
- **Sign convention.** A positive shock moves a factor up (rate up, spread up, volatility up, prepayment up, FX spot up).
- **A curve is derived, never stored.** Market data holds *quotes*; a `Curve` is bootstrapped from them.

## 2. Pipeline at a glance

| Stage | Component | Input | Output |
| --- | --- | --- | --- |
| Parse market data | `marketdata.MarketDataJson.parse` | `String` (JSON) | `Either[String, MarketData]` |
| Parse DSL | `dsl.Parser.parseProgram` | `String` | `Either[String, dsl.Program]` |
| Compile | `engine.DslCompiler.build` | `Program`, `MarketData`, `SensitivityConfig` | `engine.Dag` |
| Execute | `engine.Executor.run` | `engine.Dag` | `zio.IO[ExecutionError, Map[String, Double]]` |
| Render | `render.DotRenderer.toDot` | `engine.Dag` | `String` (Graphviz DOT) |

## 3. `com.writhlang.dsl` — AST and parser

Source: `dsl/Ast.scala`, `dsl/Parser.scala`.

### 3.1 AST

```scala
sealed trait InstrumentSpec { def id: String }

case class BondSpec(id, notional, coupon, maturityYears: Int,
                    discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, couponFreq: Int)
case class MortgageSpec(id, notional, noteRate, termMonths: Int,
                        discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, prepayCurve: RiskFactorKey)
case class MbsPoolSpec(id, notional, wac, wamMonths: Int,
                       discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, prepayCurve: RiskFactorKey)
case class SwapSpec(id, notional, fixedRate, maturityYears: Int,
                    discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, freq: Int)
case class CapSpec(id, notional, strike, maturityYears: Int, freq: Int,
                   discountCurve: RiskFactorKey, creditCurve: RiskFactorKey, volSurface: RiskFactorKey)
case class SwaptionSpec(id, notional, strike, expiryYears, swapMaturityYears: Int, freq: Int,
                        isPayer: Boolean, discountCurve: RiskFactorKey,
                        creditCurve: RiskFactorKey, volSurface: RiskFactorKey)
case class FxForwardSpec(id, notional, fxRate, maturityYears,
                         domesticCurve: RiskFactorKey, foreignCurve: RiskFactorKey, fxSpot: RiskFactorKey)
```

`notional`, `coupon`, `noteRate`, `fixedRate`, `strike`, `fxRate` are **trade terms** (contractual). The `RiskFactorKey` fields are **market references** — the instrument says *which* market object to price with, but the object's value comes from `MarketData`.

```scala
sealed trait CurveShift
case object NoCurveShift; case class FlatShift(amount); case class BucketShift(tenorYears, amount)
case class TwistShift(shortAmount, longAmount, pivotYears); case class SineShift(amplitude, omega, phase)

sealed trait ShockMove
case class PointMove(key: RiskFactorKey, shiftType: ShiftType, amount: Double) extends ShockMove
case class ShapeMove(key: RiskFactorKey, shape: CurveShift) extends ShockMove

case class Shock(name: String, moves: List[ShockMove])

sealed trait PortfolioNode
case class InstrumentLeaf(spec: InstrumentSpec) extends PortfolioNode
case class Portfolio(name: String, children: List[PortfolioNode]) extends PortfolioNode

case class Program(shocks: List[Shock], nodes: List[PortfolioNode])
```

### 3.2 Parser

```scala
object Parser {
  def parseProgram(input: String): Either[String, Program]
}
```

`parseProgram` parses the DSL (FastParse) and validates every instrument, aggregating errors into a `; `-separated `Left`.

## 4. `com.writhlang.risk` — taxonomy

Source: `risk/KeyType.scala`, `risk/RiskFactorKey.scala`, `risk/ShiftType.scala`.

```scala
sealed trait KeyType { def name: String }        // DiscountCurve, CreditCurve, FxSpot, SwaptionVolatility, Prepay, …
final case class Tenor(years: Double)            // canonical "5Y"; Tenor.parse("3M") == Tenor(0.25)
final case class RiskFactorKey(keyType: KeyType, name: String,
                               tenor: Option[Tenor] = None, expiry: Option[Tenor] = None,
                               swapTenor: Option[Tenor] = None, strike: Option[Strike] = None,
                               lossLevel: Option[Double] = None) {
  def canonical: String        // "DiscountCurve:EUR:2Y"
  def withoutTenor: RiskFactorKey
}
object RiskFactorKey { def parse(canonical: String): Either[String, RiskFactorKey] }

sealed trait ShiftType { def name: String }       // Absolute | Relative
object ShiftType { def defaultFor(keyType: KeyType): ShiftType }  // rates/prepay -> Absolute; fx/vol -> Relative
```

Two keys are equal iff their `canonical` strings are equal, so they are safe as map keys and in DAG node ids.

## 5. `com.writhlang.marketdata` — market-data objects

### 5.1 `Curve`

```scala
trait Curve {
  def rateAt(tenorYears: Double): Double                 // annual zero rate (decimal)
  def df(tenorYears: Double): Double = math.exp(-rateAt(tenorYears) * tenorYears)
  def shifted(delta: Double => Double): Curve
  def plus(other: Curve): Curve
}
object Curve {
  def flat(rate: Double): Curve                          // a flat curve (e.g. a credit spread)
  def function(f: Double => Double): Curve
}
final case class BootstrappedCurve(pillars: Vector[(Double, Double)]) extends Curve
```

`BootstrappedCurve` is a curve built from quoted instruments, represented by its pillar points `(tenorYears, discountFactor)` and interpolated log-linearly in discount factors (flat forward) by `DiscountCurveInterpolator`.

### 5.2 `MarketData`

```scala
final case class MarketData(
  asOf: LocalDate = LocalDate.of(2024, 1, 1),
  curves: Map[RiskFactorKey, Curve] = Map.empty,
  curveQuotes: Map[RiskFactorKey, QuoteSet] = Map.empty,
  volSurfaces: Map[RiskFactorKey, VolSurface] = Map.empty,
  prepayVectors: Map[RiskFactorKey, PrepayVector] = Map.empty,
  fxSpots: Map[RiskFactorKey, SpotQuote] = Map.empty,
  correlations: Map[RiskFactorKey, CorrelationMatrix] = Map.empty
) {
  def curve(key): Curve; def quotes(key): QuoteSet
  def combinedCurve(discountKey, creditKey): Curve   // discount.plus(credit)
  def withCurveAndQuotes(key, curve, quotes): MarketData
  // withCurve / withVolSurface / withPrepayVector / withFxSpot / withCorrelation
}
```

`curveQuotes` is the *input* (par instruments); `curves` is the *derived* result. A shock transforms the quotes and re-bootstraps.

### 5.3 Other market-data objects

```scala
final case class VolSurface(volAt: (Double, Double) => Double) { def volatility(expiry, strike): Double; def shifted(delta): VolSurface }
object VolSurface { def flat(volatility: Double): VolSurface }

final case class PrepayVector(values: Vector[Double]) { def months: Int; def cpr(monthIndex: Int): Double; def shifted(delta): PrepayVector }
object PrepayVector { def constant(cpr, months): PrepayVector; def fromRamp(start, end, rampMonths, months): PrepayVector }

final case class SpotQuote(value: Double) { def shiftedRelative(amount): SpotQuote; def shiftedAbsolute(amount): SpotQuote }

final case class CorrelationMatrix(labels: Vector[String], values: Vector[Vector[Double]]) { def apply(i, j): Double }
```

### 5.4 `marketdata.bootstrap`

```scala
sealed trait ParInstrument { def rate: Double; def tenorYears: Double; def maturityYears: Double; def withRate(r): ParInstrument }
case class Deposit(tenor: Tenor, rate: Double) extends ParInstrument      // continuous: df(T) = e^(-r·T)
case class Future(start: Tenor, end: Tenor, rate: Double) extends ParInstrument // df(end) = df(start)·e^(-r·(end-start))
case class Swap(tenor: Tenor, rate: Double, freq: Int = 1) extends ParInstrument  // par equation

final case class QuoteSet(instruments: List[ParInstrument]) {
  def sorted: List[ParInstrument]
  def shifted(delta: Double => Double): QuoteSet
  def shiftOne(tenorYears: Double, amount: Double): QuoteSet
}

object Bootstrapper {
  def bootstrap(quotes: QuoteSet): Either[String, BootstrappedCurve]
  def bootstrapOrThrow(quotes: QuoteSet): BootstrappedCurve
}
```

`bootstrap` walks instruments in maturity order, solving one discount factor per instrument; returns `Left` on a non-positive or non-decreasing discount factor.

### 5.5 `MarketDataJson`

```scala
object MarketDataJson { def parse(json: String): Either[String, MarketData] }
```

Loads the JSON schema documented in [dsl-reference.md](dsl-reference.md). A curve entry is either `{ "type": ..., "instruments": [...] }` (bootstrapped) or `{ "type": ..., "flat": rate }` (flat).

### 5.6 `com.writhlang.time` — day-count & business-day conventions

```scala
sealed trait DayCount { def yearFraction(from: LocalDate, to: LocalDate): Double } // Actual365Fixed, Actual360, Thirty360
trait Calendar { def isBusinessDay(date: LocalDate): Boolean; def addBusinessDays(date: LocalDate, days: Int): LocalDate }
object Calendar { val weekend: Calendar }
sealed trait BusinessDayConvention { def adjust(date: LocalDate, calendar: Calendar): LocalDate } // Following, ModifiedFollowing, Preceding, Unadjusted
final case class ThetaPeriod(days: Int) { def elapsedYears(asOf: LocalDate, calendar: Calendar, dayCount: DayCount): Double }
```

`ThetaPeriod` is the theta horizon (ORE's `thetaPeriod_`): a business-day count converted to an `elapsedYears` year fraction, which pricing subtracts from every time-to-cashflow tenor.


## 6. `com.writhlang.scenario` — shocks & sensitivities

### 6.1 `ShiftShape`

```scala
sealed trait ShiftShape { def at(x: Double): Double }
case class Flat(amount); case class Bucket(tenor, amount); case class Twist(short, long, pivot)
case class Sine(amplitude, omega, phase); case class Custom(f: Double => Double)
```

### 6.2 `Scenario`

```scala
sealed trait ScenarioShift
case class AdditiveShift(shape: ShiftShape) extends ScenarioShift
case class ParQuoteShift(tenorYears: Double, amount: Double) extends ScenarioShift
case class RelativeSpotShift(amount: Double) extends ScenarioShift
case class AbsoluteSpotShift(amount: Double) extends ScenarioShift

final case class Scenario(name: String, shifts: List[(RiskFactorKey, ScenarioShift)]) {
  def applyTo(market: MarketData): MarketData
}
```

`applyTo` transforms the snapshot: for a quote-backed curve, `AdditiveShift` applies the shape to each quote and re-bootstraps; `ParQuoteShift` bumps one quote and re-bootstraps. A shock shocks the market *input* (quotes), then re-derives the curve.

### 6.3 `SensitivityConfig` / `ShiftScheme`

```scala
sealed trait ShiftScheme { def name: String }   // Forward | Backward | Central
final case class SensitivityConfig(shiftScheme: ShiftScheme = ShiftScheme.Central,
                                   bumpFor: RiskFactorKey => Double = SensitivityConfig.defaultBump,
                                   thetaPeriod: Option[ThetaPeriod] = Some(ThetaPeriod(days = 1)),
                                   dayCount: DayCount = DayCount.Actual365Fixed,
                                   calendar: Calendar = Calendar.weekend)
```

`defaultBump` is `1e-4` for rates/spreads (1bp), `1e-2` for prepay, `1e-3` for vol/fx. `thetaPeriod` is the Stage 3 theta horizon; `None` disables theta/carry emission.

### 6.4 `ScenarioGenerator`

```scala
trait ScenarioGenerator { def next(): Option[Scenario]; def reset(): Unit }
final class SensitivityScenarioGenerator(factors, bumpFor, shiftFor) extends ScenarioGenerator
final class ListScenarioGenerator(scenarios: List[Scenario]) extends ScenarioGenerator
```

### 6.5 `scenario.par`

```scala
final case class CurveShiftParData(curveKey: RiskFactorKey, parTenorYears: Double)
```

Maps a pillar to the par instrument whose quote is bumped (par-conversion).

## 7. `com.writhlang.engine`

### 7.1 Instruction model

```scala
sealed trait Op
case class Price(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double = 0.0) extends Op
case class Theta(baseId: String, thetaEvalId: String) extends Op
case class Carry(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double) extends Op
case class Const(value: Double) extends Op
case class DeltaCentral(baseId, upId, downId, bump) extends Op
case class DeltaForward(baseId, upId, bump) extends Op
case class DeltaBackward(baseId, downId, bump) extends Op
case class Gamma(baseId, upId, downId, bump) extends Op
case class CrossGamma(baseId, upIId, upJId, upIJId, bumpI, bumpJ) extends Op
case class LinearScenario(baseId, terms: List[(String, String)]) extends Op
case class QuadraticScenario(baseId, linear, gamma, cross) extends Op
case class Instruction(id: String, op: Op, deps: List[String])
```

### 7.2 `Dag`

```scala
case class Dag(nodes: Map[String, Instruction]) { def levels: Either[String, List[List[Instruction]]] }
```

### 7.3 `DslCompiler`

```scala
object DslCompiler {
  def build(program: Program, market: MarketData, config: SensitivityConfig = SensitivityConfig()): Dag
  def flattenInstruments(nodes: List[PortfolioNode]): List[(InstrumentSpec, List[String])]
  def factorsFor(instrument: InstrumentSpec, market: MarketData): List[RiskFactorKey]
  // node-id helpers: basePriceId, deltaId, gammaId, crossGammaId, bumpedPriceId,
  //   crossBumpedPriceId, shockFactorId, linearScenarioId, quadraticScenarioId, fullScenarioId
}
```

`factorsFor` returns one `RiskFactorKey` per par instrument of each referenced curve (per-pillar) plus the direct keys (prepay/vol/fx). `build` emits base price, per-factor up/down bumped prices + delta/gamma, cross-gammas, and per-shock full/linear/quadratic scenario nodes.

### 7.4 `Executor`

```scala
object Executor {
  sealed trait ExecutionError { def message: String }
  case class MissingDependency(node, dep) extends ExecutionError
  case class GraphError(reason) extends ExecutionError
  def run(dag: Dag): zio.IO[ExecutionError, Map[String, Double]]
}
```

### 7.5 `Pricing`

```scala
object Pricing {
  def price(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double = 0.0): Double
  def carry(instrument: InstrumentSpec, market: MarketData, elapsedYears: Double): Double
  def bondPrice(spec: BondSpec, curve: Curve, elapsedYears: Double = 0.0): Double
  def mortgagePrice(spec: MortgageSpec, curve: Curve, prepay: PrepayVector, elapsedYears: Double = 0.0): Double
  def mbsPrice(spec: MbsPoolSpec, curve: Curve, prepay: PrepayVector, elapsedYears: Double = 0.0): Double
  def swapPrice(spec: SwapSpec, curve: Curve, elapsedYears: Double = 0.0): Double
  def capPrice(spec: CapSpec, curve: Curve, volSurface: VolSurface, elapsedYears: Double = 0.0): Double
  def swaptionPrice(spec: SwaptionSpec, curve: Curve, volSurface: VolSurface, elapsedYears: Double = 0.0): Double
  def fxForwardPrice(spec: FxForwardSpec, domestic: Curve, foreign: Curve, fx: SpotQuote, elapsedYears: Double = 0.0): Double
}
```

**Bond** — sum of discounted fixed coupons + principal. **Mortgage/MBS** — level-payment amortization: annuity payment `P = N·(r/12)/(1 − (1+r/12)^−n)`, then each month interest + scheduled principal + prepayment (`SMM = 1 − (1−CPR)^(1/12)` applied to the remaining balance), each discounted. **Swap** — `fixedPV − floatPV` with float leg `N·(1 − df(T))`. **Cap** — strip of Black-76 caplets on the forward rate. **Swaption** — Black-76 on the forward swap rate, scaled by the annuity. **FX forward** — covered interest parity `N·(spot·dfForeign − contracted·dfDomestic)`.

## 8. `com.writhlang.render`

```scala
object DotRenderer { def toDot(dag: Dag): String }
```

Emits a left-to-right `digraph`, colored by `Op` type and clustered per instrument.

## 9. `com.writhlang.app` — the CLI

```scala
object Main extends ZIOAppDefault {
  val defaultMarketJson: String; val defaultDsl: String
  override def run: ZIO[/* … */, Any, Any]
}
```

Flow: read args (`--input`, `--market`, `--dot`, `--png`, `--theta-days`) → load DSL + market JSON → `MarketDataJson.parse` + `Parser.parseProgram` → `DslCompiler.build` → `DotRenderer.toDot` → write DOT/PNG → `Executor.run` → print per-instrument base price, `theta`, `carry`, and per-shock `full`/`linear`/`quadratic`.

## 10. Node-id reference

| Pattern | Meaning |
| --- | --- |
| `shock:<name>:<key>` | shock literal (`Const`) |
| `price:base:<inst>` | base price |
| `price:bump:<inst>:<key>:<dir>` | one-factor bumped price |
| `price:cross:<inst>:<k1>:<k2>` | two-factor bumped price |
| `greek:delta:<inst>:<key>` / `greek:gamma:<inst>:<key>` | delta / gamma |
| `greek:cross:<inst>:<k1>:<k2>` | cross-gamma |
| `price:linear:<inst>:<shock>` / `price:quad:<inst>:<shock>` / `price:full:<inst>:<shock>` | scenario prices |
| `price:thetaeval:<inst>` | aged revaluation (leaf) |
| `price:theta:<inst>` / `price:carry:<inst>` | theta (time decay) / cash carry |

The canonical key may itself contain `:` (e.g. `DiscountCurve:EUR:2Y`), so ids are parsed positionally where needed.



