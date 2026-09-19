# WrithLang

A Scala **domain-specific language (DSL) compiler and execution engine** for financial **risk and pricing traceability**.

WrithLang lets you describe market **shocks** (scenarios) and a portfolio of financial **instruments** in a small, human-readable DSL, then it:

1. **parses** the DSL (FastParse),
2. **builds** a dependency graph (DAG) of pricing and sensitivity computations,
3. **executes** the DAG level-parallel (via ZIO),
4. **renders** a Graphviz `.dot` graph of the computation, and
5. **reports** base prices, first- and second-order Greeks, and first-/second-order Taylor scenario prices alongside exact re-priced values.

---

## Table of contents

- [Tech stack](#tech-stack)
- [Features](#features)
- [Architecture overview](#architecture-overview)
- [Modules](#modules)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Usage](#usage)
- [DSL quick reference](#dsl-quick-reference)
- [Output](#output)
- [Project structure](#project-structure)
- [Known limitations and roadmap](#known-limitations-and-roadmap)
- [License](#license)

---

## Tech stack

| Concern          | Technology                                    |
| ---------------- | --------------------------------------------- |
| Language         | Scala 2.13                                    |
| Build            | Gradle 9.2.1 (Kotlin DSL), JDK 21 toolchain   |
| Effects / concurrency | ZIO 2 (`zio_2.13` 2.0.18)                 |
| Parsing          | FastParse 2.3.3                               |
| Statistics       | Apache Commons Math 3 (Black model)           |
| Logging          | Logback Classic 1.5.22 + scala-logging 3.9.5  |
| Testing          | ScalaTest 3.2.19 + JUnit 4                    |
| Visualization    | Graphviz `dot` (optional, PNG output only)    |

---

## Features

- **DSL** for shocks and instruments (no external config files required).
- **7 instrument types**: bond, mortgage, MBS pool, swap, cap, swaption, FX forward.
- **Shocks** as scalar factor moves (`rate`, `spread`, `prepay`, `volatility`, `fx`) and curve shifts (`parallel`, `bucket`, `twist`).
- **Greeks** computed by finite differences: delta, gamma, and cross-gamma.
- **Scenario pricing**: exact full re-price, plus linear and quadratic (Taylor) approximations.
- **DAG execution** with topological ordering and parallel evaluation per level.
- **Graphviz DOT** visualization, clustered by instrument.
- **Early-stage native code path** that compiles a toy `let`/`print` language to x86-64 GAS assembly.

---

## Architecture overview

There are two largely independent code paths under the `com.writhlang` package:

1. **Risk DSL + engine** (active, tested): `dsl` → `engine` → `render`.
2. **Native compiler stub** (early stage, not yet wired into the CLI): `compiler` → `interpreter` → `examples`.

The main pipeline is:

```
DSL source
   │  com.writhlang.dsl.Parser (FastParse)
   ▼
AST (com.writhlang.dsl: Program, Shock, InstrumentSpec)
   │  com.writhlang.engine.DslCompiler
   ▼
DAG (com.writhlang.engine: Dag, Instruction, Op)
   │  com.writhlang.engine.Executor (ZIO, level-parallel)
   ▼
results: Map[String, Double]
   │  com.writhlang.render.DotRenderer
   ▼
Graphviz DOT (and optional PNG via `dot`)
```

See [docs/architecture.md](docs/architecture.md) for the full design, and [docs/dsl-reference.md](docs/dsl-reference.md) for the grammar.

---

## Modules

| Module | Type    | Purpose                                                            |
| ------ | ------- | ------------------------------------------------------------------ |
| `lib`  | library | Core DSL parser, AST, DAG builder, pricing engine, executor, renderer, and the native compiler stub. |
| `app`  | app     | ZIO CLI entry point (`com.writhlang.app.Main`).                     |

The `app` module depends on `lib` and only adds the command-line driver.

---

## Prerequisites

- **JDK 21** (the `lib` module sets a Java 21 toolchain; Gradle's foojay resolver can provision it automatically).
- **Graphviz** (`dot` on `PATH`) — optional, only required to render PNG output.
- A network connection on first build to download Gradle and dependencies.

---

## Getting started

All commands are run from the repository root. On Windows, replace `./gradlew` with `gradlew.bat`.

```bash
# Compile everything and run tests
./gradlew build

# Run tests only
./gradlew test

# Run the CLI (uses the built-in example DSL when no --input is given)
./gradlew :app:run

# Run the CLI with a custom DSL file
./gradlew :app:run --args="--input path/to/portfolio.dsl"
```

Gradle writes build outputs under `build/` (git-ignored).

---

## Usage

The CLI accepts the following arguments (from `com.writhlang.app.Main`):

| Argument          | Meaning                                                        | Default                     |
| ----------------- | -------------------------------------------------------------- | --------------------------- |
| `--input <path>`  | DSL source file to read (UTF-8). A bare positional path also works. | built-in example DSL        |
| `--dot <path>`    | Where to write the Graphviz DOT graph.                         | `build/writhlang_dag.dot`   |
| `--png <path>`    | Where to write the rendered PNG (requires Graphviz `dot`).     | `build/writhlang_dag.png`   |

Example:

```bash
./gradlew :app:run --args="--input portfolio.dsl --dot build/dag.dot --png build/dag.png"
```

If no `--input` is supplied, WrithLang runs the built-in example portfolio (bonds, a mortgage, a swap, a cap, an MBS pool, and an FX forward under several shocks).

---

## DSL quick reference

A program is a `shocks` block followed by zero or more `instrument` blocks.

```text
shocks {
  shock up   { rate 0.0005; spread 0.0001; prepay -0.005; }
  shock down { rate -0.0005; spread -0.0001; prepay 0.005; }
  shock twist { curve twist -0.001 0.001 10; }
  shock bucket { curve bucket 5 0.001; }
  shock vol  { volatility 0.01; }
}

instrument bond BondA {
  notional 1000000
  coupon 0.05
  maturity 7
  rate 0.042
  spread 0.0015
  freq 2
}
```

### Shocks

A `shock` is a named set of **scalar factor moves** and/or **curve shifts**:

- Scalar factors: `rate`, `spread`, `prepay`, `volatility`, `fx`.
- Curve shifts:
  - `parallel <amount>` — flat bump across all tenors.
  - `bucket <tenorYears> <amount>` — localized bump (linear tent, 1-year width).
  - `twist <shortAmount> <longAmount> [pivotYears]` — short→long twist; pivot defaults to `10`.

### Instruments

| Kind       | Fields (required unless noted)                                                                                         |
| ---------- | ---------------------------------------------------------------------------------------------------------------------- |
| `bond`     | `notional`, `coupon`, `maturity`, `rate`, `spread` (default `0`), `freq` (default `1`)                                  |
| `mortgage` | `notional`, `rate`, `spread` (default `0`), `term`, `prepayCurve`                                                        |
| `mbs`      | `notional`, `rate`, `spread` (default `0`), `wac`, `wam`, `prepayCurve`                                                  |
| `swap`     | `notional`, `rate`, `spread` (default `0`), `fixedRate`, `maturity`, `freq` (default `1`)                                |
| `cap`      | `notional`, `rate`, `spread` (default `0`), `strike`, `maturity`, `freq` (default `4`), `volatility`                     |
| `swaption` | `notional`, `rate`, `spread` (default `0`), `strike`, `expiry`, `maturity`, `freq` (default `2`), `volatility`, `call`/`put` (default `call`) |
| `fxforward`| `notional`, `domesticRate`, `fxRate`, `foreignRate`, `maturity`                                                          |

`prepayCurve` values:

- `flat <cpr>`
- `ramp <startCpr> <endCpr> <rampMonths>` (months must be `> 0`)

The full grammar and validation rules are documented in [docs/dsl-reference.md](docs/dsl-reference.md).

## Output

When run successfully, WrithLang prints `--- Scenario Prices ---` followed by, for each instrument:

- `base` — the unshocked present value.
- per shock:
  - `full` — exact re-priced value under the full shock.
  - `linear` — first-order Taylor approximation (base + Σ delta·shock).
  - `quadratic` — second-order Taylor approximation (linear + ½Σ gamma·shock² + Σ cross·shockᵢ·shockⱼ).

Example:

```text
--- Scenario Prices ---
BondA base=1001234.5678
  up: full=999123.4567 linear=999100.1234 quadratic=999122.9876
  down: full=1003345.6789 linear=1003400.0000 quadratic=1003346.1234
```

The `.dot` file (and optional PNG) visualizes the same computation graph as a left-to-right DAG, with nodes clustered by instrument.

---

## Project structure

```text
risk-traceability-compiler/
├── app/                      # ZIO CLI application module
│   └── src/main/scala/com/writhlang/app/Main.scala
├── lib/                      # Core library module
│   ├── build.gradle.kts
│   └── src/
│       ├── main/scala/com/writhlang/
│       │   ├── WrithLang.scala              # (empty stub)
│       │   ├── dsl/                         # DSL AST + FastParse parser
│       │   │   ├── Ast.scala
│       │   │   └── Parser.scala
│       │   ├── engine/                      # DAG builder, executor, pricing
│       │   │   ├── Curve.scala
│       │   │   ├── Dag.scala
│       │   │   ├── DslCompiler.scala
│       │   │   ├── Executor.scala
│       │   │   ├── Factors.scala
│       │   │   ├── Instruction.scala
│       │   │   └── Pricing.scala
│       │   ├── render/DotRenderer.scala     # Graphviz DOT output
│       │   ├── compiler/                    # early-stage native GAS backend
│       │   │   ├── Compiler.scala
│       │   │   ├── ast/AST.scala
│       │   │   ├── frontend/Frontend.scala
│       │   │   ├── ir/IR.scala
│       │   │   └── backend/gas/GasBackend.scala
│       │   ├── interpreter/Interpreter.scala
│       │   └── examples/VariableAssignment.scala
│       └── test/scala/                      # ScalaTest suites
│           ├── com/writhlang/engine/PricingDslSuite.scala
│           └── org/example/LibrarySuite.scala
├── gradle/                   # Gradle wrapper + version catalog
├── docs/                     # Extended documentation
│   ├── architecture.md
│   └── dsl-reference.md
├── settings.gradle.kts       # root project name + module list
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

---

## Known limitations and roadmap

- The **native compiler** (`com.writhlang.compiler`) is an early-stage stub: its `Frontend` parser only handles `let <id> = <int>;` and `print(<id>)`, and register allocation is naive. It is not wired into the CLI and is not covered by tests.
- `WrithLang` and the `Interpreter` class are empty scaffolding; `Interpreter` is functional only through its companion object, which delegates to `Compiler`.
- `org.example.LibrarySuite` is a leftover placeholder test from the Gradle `init` template.
- Risk factor coverage is limited to the five scalar factors and three curve-shift shapes; no stochastic/rate-model simulation is implemented.

---

## License

No `LICENSE` file is present in this repository. Please add one before redistributing.


