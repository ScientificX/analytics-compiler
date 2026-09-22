# AGENTS.md

Guidance for humans and AI agents working in this repository.

## What this project is

WrithLang is a Scala 2.13 **DSL compiler and execution engine for financial risk/pricing traceability**. It parses a small language of market shocks and financial instruments, builds a computation DAG, evaluates it in parallel with ZIO, and renders a Graphviz graph. See [README.md](README.md), [docs/architecture.md](docs/architecture.md), [docs/low-level-design.md](docs/low-level-design.md), and [docs/dsl-reference.md](docs/dsl-reference.md).

## Documentation

The docs under `docs/` are part of the codebase and must stay in sync with the code. Update them in the same change that modifies the behaviour they describe — not as a follow-up.

**After each pass — a stage, a feature, or any change that alters behaviour — do a full rewrite of the affected docs, not incremental edits.** Re-read the code and write each doc as a complete, current description end-to-end. A partial edit leaves stale sections that mislead the next reader; if the code changed shape (public API, grammar, package layout, or pricing), the corresponding doc must be rewritten wholesale so every section matches reality.

| Doc | Update it when you… |
| --- | ------------------- |
| `docs/architecture.md` | Change module/package layout, the parse → DAG → execute → render pipeline, DAG construction, pricing models, error handling, or the native-compiler stub. |
| `docs/low-level-design.md` | Change any component's public API, input/output shapes (AST case classes, `Op` variants, `Instruction`/`Dag`, node-id patterns, `RiskFactorKey`), or a pricing formula. |
| `docs/dsl-reference.md` | Change the DSL grammar, instrument/field names, defaults, or validation rules. |

When you add or change a public finance API, explain it in plain language for a software engineer with no finance background, and keep the finance glossary below consistent with any new terms you introduce.

## Build, test, run

Run from the repository root. On Windows use `gradlew.bat` instead of `./gradlew`.

```bash
./gradlew build          # compile + test
./gradlew test           # test only
./gradlew :app:run       # run the CLI (built-in example DSL)
./gradlew :app:run --args="--input path/to.dsl --dot out.dot --png out.png"
```

- Toolchain: JDK 21 (defined in `lib/build.gradle.kts`; foojay resolver can download it).
- Tests: ScalaTest (`AnyFunSuite`) with the JUnit 4 runner (`@RunWith(classOf[JUnitRunner])`).

## Module layout

| Module | Role |
| ------ | ---- |
| `lib`  | Core library: DSL, engine, renderer, and native-compiler stub. |
| `app`  | ZIO CLI entry point (`com.writhlang.app.Main`). Depends on `lib`. |

## Package map (`com.writhlang`)

| Package | Responsibility |
| ------- | -------------- |
| `dsl` | DSL AST (`Ast.scala`) and FastParse parser (`Parser.scala`). |
| `engine` | DAG builder (`DslCompiler`), instruction model (`Instruction`, `Dag`), executor (`Executor`), pricing (`Pricing`). |
| `risk`   | Risk-factor taxonomy, canonical keys, shift types (`KeyType`, `RiskFactorKey`, `ShiftType`). |
| `marketdata` | Market-data objects (`Curve`, `MarketData`, `VolSurface`, `PrepayVector`, `SpotQuote`), curve bootstrapping (`bootstrap`), JSON loader (`MarketDataJson`). |
| `scenario` | Shock shapes, scenarios, sensitivity config (`Scenario`, `ShiftShape`, `ScenarioGenerator`, `SensitivityConfig`, `par`). |
| `render` | `DotRenderer` — Graphviz DOT output. |
| `compiler` | Early-stage native x86-64 GAS backend (`Compiler`, `frontend`, `ast`, `ir`, `backend.gas`). |
| `interpreter` | Thin wrapper around `Compiler` (pure `Either` + ZIO `IO`). |
| `examples` | `VariableAssignment` demo for the native compiler. |

## Important: two separate AST/parser systems

Do **not** conflate these:

1. **Risk DSL** — `com.writhlang.dsl.Parser` + `com.writhlang.dsl.Ast`. This is the real, tested path (see `PricingDslSuite`). It powers the CLI and the engine.
2. **Native compiler** — `com.writhlang.compiler.Frontend` + `compiler.ast.AST`. This is a stub for a toy `let`/`print` language that emits GAS assembly. It is not wired into the CLI and is not tested.

`WrithLang.scala` and the `Interpreter` *class* are empty scaffolding; the `Interpreter` companion object is the functional entry point.

## Conventions

- Scala 2.13 syntax. Prefer immutable structures and `Either`/ZIO `IO` for effectful/pure separation (see `Executor`, `Compiler`, `Interpreter`).
- The engine models computation as `Instruction(id, op, deps)` nodes in a `Dag`; keep new pricing logic behind the `Op`/`Pricing` layer rather than ad-hoc code.
- Field/property names in the DSL are validated in `Parser.validateInstrument`; update both `Parser.scala` and `dsl/Ast.scala` when adding an instrument or factor.
- Keep line endings LF (`.gitattributes` enforces LF for `gradlew`, CRLF for `.bat`).
- Do not edit generated/IDE directories (`.idea/`, `build/`, `out/`) — they are git-ignored.

## Code readability: naming & commentary (finance-first)

This is a **finance codebase**. Every piece of code that touches money — pricing, risk, instruments, curves, shocks — must be written so that **a first-time reader of finance code can follow it end to end**. Descriptive variable names and explanatory comments are **mandatory**, not optional. Prefer clarity over brevity. If a reader needs to know what CPR, SMM, WAC, WAM, "discount factor", or "spread" mean to understand a line, the code must say so in place (or point at the [glossary](#finance-glossary)).

These rules bind code in `dsl/`, `engine/`, `render/`, and `app/`.

### Variable naming

- Scala 2.13 camelCase, matching the surrounding code.
- Use full, descriptive names. Avoid unexplained single-letter names (loop indices such as `i` or `t` are fine when their meaning is clear from the comment).
- Spell out finance abbreviations or define them on first use: `cpr`, `smm`, `wac`, `wam`, `pv`, `df`, `dv01`, etc.
- Make **units and time base explicit** in the name:
  - `rate` = annual decimal; `rateMonthly` = per-month; `tenorYears` = years; `termMonths` = months.
  - Rates are stored as decimals, so `0.04` means 4%. Say so where it matters.
- Keep the existing conventions:
  - `*Shift` for shocked deltas (`rateShift`, `spreadShift`, `prepayShift`, `volShift`, `fxShift`).
  - `*Id` for node identifiers (`basePriceId`, `deltaId`, ...).
  - `up` / `down` for bumped directions; `base` for the un-shifted value.
  - `df` for discount factor and `pv` for present value, once defined locally.
- Reuse the exact DSL field names from `dsl/Ast.scala` and `docs/dsl-reference.md` (`notional`, `coupon`, `spread`, `fixedRate`, `strike`, `volatility`, ...) so code and DSL stay in sync.

### Commentary

- Put Scaladoc on public finance APIs (`Pricing`, `Curve`, the `Op` cases, the `Factor`s) explaining the finance meaning, not just the mechanics.
- Beside each finance formula, add an inline comment stating (1) the formula, (2) the intuition / "why", and (3) the recognised name of the formula when one exists (e.g. "annuity payment", "CPR→SMM conversion", "Black-76", "covered interest parity", "central difference").
- Document units and sign conventions (e.g. which direction is a positive shock, and what `+spread` does to a price).
- Explain each concept where it first appears in a file; a short worked numeric example in a comment is welcome when it aids understanding.

### Worked example

Prefer this style (explanatory, finance-aware):

```scala
// CPR (Conditional Prepayment Rate) is the annualised fraction of outstanding
// principal expected to prepay. Convert to SMM (Single Monthly Mortality), the
// monthly equivalent, via SMM = 1 - (1 - CPR)^(1/12).
val cpr = clamp(cprCurve(month - 1) + prepayShift, 0.0, 1.0) // 0..1 decimal, not %
val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
// Prepayment = SMM applied to the principal remaining after scheduled amortisation.
val prepay = (balance - scheduledPrincipal) * smm
```

Avoid this (bare abbreviations, no units or rationale):

```scala
val smm = 1.0 - math.pow(1.0 - cpr, 1.0 / 12.0)
val prepay = (balance - scheduledPrincipal) * smm
```

## Finance glossary

Canonical terms used across `dsl/`, `engine/`, and `docs/dsl-reference.md`. Reference this when naming variables or writing comments.

| Term | Meaning |
| ---- | ------- |
| Notional / principal | Face value / outstanding balance the cashflows are computed on. |
| Coupon / coupon rate | Annual interest paid by a bond, as a decimal of notional (e.g. `0.05` = 5%). |
| Coupon frequency | Number of coupon payments per year (`freq`). |
| Maturity / term / tenor | Time until final cashflow; `maturity` is in years, `term`/`wam` in months. |
| Yield / discount rate | Rate used to discount future cashflows to today (`rate`). |
| Credit spread | Extra yield over the base rate to compensate credit/liquidity risk (`spread`). |
| Discounting / present value (PV) | Value today of a future cashflow: `PV = CF × DF`. |
| Discount factor (DF) | Factor to discount a cashflow: `df(t) = e^(-r·t)` (continuous compounding). |
| Accrual fraction | Fraction of a year between payments (`1 / freq`). |
| Amortization | Paying down a loan over time; each payment is interest + scheduled principal. |
| Annuity payment | Constant periodic payment that exactly repays a loan at its note rate. |
| Prepayment | Paying principal earlier than scheduled (a mortgage/MBS risk). |
| CPR | Conditional Prepayment Rate — annualised prepayment speed (decimal). |
| SMM | Single Monthly Mortality — monthly prepayment speed; `SMM = 1 - (1 - CPR)^(1/12)`. |
| Prepay curve | CPR over time: `flat <cpr>` or `ramp <start> <end> <months>`. |
| WAC / WAM | Weighted-average coupon / weighted-average maturity of an MBS pool. |
| Pass-through MBS pool | A pool of mortgages whose cashflows pass through to investors (`mbs`). |
| Interest-rate swap | Exchange fixed vs floating rate payments; priced as fixed-leg PV minus floating-leg PV. |
| Forward swap rate | Fixed rate making the swap value zero; used to price swaptions. |
| Cap / caplet | Option that pays when a floating rate exceeds a `strike`; a cap is a strip of caplets. |
| Swaption | Option to enter a swap; `call` = payer, `put` = receiver. |
| Black model (Black-76) | Log-normal model used to price caplets and swaptions. |
| Volatility | Annualised standard deviation of rate returns, input to Black pricing. |
| FX forward | Agreement to exchange currencies at a set future rate; priced via covered interest parity. |
| Covered interest parity | Relates forward FX to spot and the domestic/foreign interest rates. |
| Risk factor | A market input whose move changes prices: `rate`, `spread`, `prepay`, `volatility`, `fx`. |
| Shock | A named bundle of factor moves and/or curve shifts. |
| Curve shift | `parallel` (flat), `bucket` (tent, 1yr), `twist` (short→long with pivot). |
| Delta | First-order price sensitivity: central difference `(up - down) / (2·bump)`. |
| Gamma | Second-order sensitivity: `(up - 2·base + down) / bump²`. |
| Cross-gamma | Mixed second-order sensitivity to two factors together. |
| Scenario price | Price under a shock: full re-price, linear Taylor, or quadratic Taylor. |
| Linear Taylor | `base + Σ delta_i·shock_i` (first-order approximation). |
| Quadratic Taylor | linear + `½ Σ gamma_i·shock_i² + ΣΣ cross_ij·shock_i·shock_j`. |
| DAG | Directed acyclic graph of `Instruction` nodes, giving computation traceability. |

## Testing conventions

- Add engine/parser tests under `lib/src/test/scala/com/writhlang/engine/` following `PricingDslSuite`.
- `org.example.LibrarySuite` is a leftover template placeholder and can be removed.
- To run a single suite: `./gradlew :lib:test --tests com.writhlang.engine.PricingDslSuite`.
