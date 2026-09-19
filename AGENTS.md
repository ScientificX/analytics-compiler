# AGENTS.md

Guidance for humans and AI agents working in this repository.

## What this project is

WrithLang is a Scala 2.13 **DSL compiler and execution engine for financial risk/pricing traceability**. It parses a small language of market shocks and financial instruments, builds a computation DAG, evaluates it in parallel with ZIO, and renders a Graphviz graph. See [README.md](README.md), [docs/architecture.md](docs/architecture.md), and [docs/dsl-reference.md](docs/dsl-reference.md).

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
| `engine` | DAG builder (`DslCompiler`), instruction model (`Instruction`, `Dag`), executor (`Executor`), pricing (`Pricing`, `Curve`), risk factors (`Factors`). |
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

## Testing conventions

- Add engine/parser tests under `lib/src/test/scala/com/writhlang/engine/` following `PricingDslSuite`.
- `org.example.LibrarySuite` is a leftover template placeholder and can be removed.
- To run a single suite: `./gradlew :lib:test --tests com.writhlang.engine.PricingDslSuite`.
